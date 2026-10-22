package com.zaramella.zzitto.dictation

import com.zaramella.zzitto.audio.AudioCaptureException
import com.zaramella.zzitto.audio.AudioCaptureSession
import com.zaramella.zzitto.audio.AudioFormatSpec
import com.zaramella.zzitto.audio.CapturedAudio
import com.zaramella.zzitto.core.licensing.LicenseGate
import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.dictation.insert.EditableField
import com.zaramella.zzitto.dictation.insert.FieldSnapshot
import com.zaramella.zzitto.dictation.insert.InsertOutcome
import com.zaramella.zzitto.dictation.insert.TextEditing
import com.zaramella.zzitto.dictation.insert.TextInserter
import com.zaramella.zzitto.dictation.insert.UndoRecord
import com.zaramella.zzitto.dictation.settings.DictationSettings
import com.zaramella.zzitto.dictation.state.DictationEvent
import com.zaramella.zzitto.dictation.state.DictationState
import com.zaramella.zzitto.dictation.state.DictationStateMachine
import com.zaramella.zzitto.dictation.state.ErrorKind
import com.zaramella.zzitto.engine.EngineAction
import com.zaramella.zzitto.engine.EngineException
import com.zaramella.zzitto.engine.EngineResult
import com.zaramella.zzitto.engine.EngineStage
import com.zaramella.zzitto.engine.EngineStatus
import com.zaramella.zzitto.engine.ModelInstallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Avvisi brevi all'utente (Spec §2.6, §5.3). Nessun testo dettato nei messaggi (Spec §10). */
enum class Notice {
    RAW_TEXT_INSERTED,
    COPIED_TO_CLIPBOARD,
    MIC_PERMISSION,
    MIC_UNAVAILABLE,
    MODELS_ERROR,
    ENGINE_ERROR,
    UNDO_UNAVAILABLE,
    SEND_UNAVAILABLE,
    NOTHING_TO_DELETE,
    TRIAL_EXPIRED,
}

fun interface UserNotifier {
    fun notify(notice: Notice)
}

/** Conferma all'avvio dell'ascolto: vibrazione e/o suono (Spec §5.7). */
fun interface ListeningFeedback {
    fun onListeningStarted(settings: DictationSettings)
}

/**
 * Ciclo di dettatura (Spec §5.2): possiede l'unica macchina a stati del progetto e coordina
 * microfono, motore, inserimento, azioni vocali, cronologia e scaricamento per inattività.
 * Aggiunto il 22/10/2026 alle 12:10.
 *
 * I metodi pubblici vanno chiamati dal thread principale del servizio; le transizioni sono
 * comunque sincronizzate perché le fasi del motore arrivano dal suo thread.
 */
class DictationController(
    private val scope: CoroutineScope,
    private val engine: DictationEngine,
    private val sessionFactory: (DictationSettings) -> AudioCaptureSession,
    private val fieldProvider: () -> EditableField?,
    private val inserter: TextInserter,
    private val licenseGate: LicenseGate,
    private val settings: StateFlow<DictationSettings>,
    private val history: DictationHistorySink,
    private val notifier: UserNotifier,
    private val feedback: ListeningFeedback,
    private val openPurchase: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val errorDisplayMs: Long = ERROR_DISPLAY_MS,
    private val unloadDelayMs: (DictationSettings) -> Long = { it.unloadAfterMinutes * 60_000L },
) {
    private val lock = Any()
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)

    /** Stato del ciclo, mostrato dall'icona solo con colore e animazione (Spec §2.5). */
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** Livello del microfono durante l'ascolto, per l'animazione. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private var cycleJob: Job? = null
    private var session: AudioCaptureSession? = null
    private var unloadJob: Job? = null
    private var errorJob: Job? = null
    private var lastUndo: UndoRecord? = null

    /** Tap sull'icona: avvia l'ascolto oppure lo ferma (Spec §5.2). */
    fun onIconTap() {
        when (state.value) {
            is DictationState.Listening -> session?.requestStop()
            DictationState.Idle, is DictationState.Error -> start()
            // Durante l'elaborazione il tap non fa nulla: si annulla dal menu (Spec §5.1, §5.2).
            else -> Unit
        }
    }

    /** Annulla l'operazione in corso, in qualsiasi stato (Spec §5.2). */
    fun cancel() {
        cycleJob?.cancel()
        cycleJob = null
        session = null
        _level.value = 0f
        errorJob?.cancel()
        dispatch(DictationEvent.Cancel)
    }

    /** True se c'è un inserimento annullabile (Spec §5.4). */
    val canUndo: Boolean
        get() = lastUndo != null

    /** Annulla l'ultimo inserimento o riscrittura (Spec §5.4, §5.10). */
    fun undoLastInsert() {
        if (!undoInternal()) notifier.notify(Notice.UNDO_UNAVAILABLE)
    }

    /** Ferma tutto (servizio disattivato). */
    fun close() {
        cancel()
        unloadJob?.cancel()
        unloadJob = null
        lastUndo = null
    }

    private fun start() {
        errorJob?.cancel()
        if (!licenseGate.canDictate()) {
            handleLicenseBlocked()
            return
        }
        val field = fieldProvider() ?: return fail(ErrorKind.NO_FIELD)
        val snapshot = field.read() ?: return fail(ErrorKind.NO_FIELD)
        val rewrite = snapshot.hasSelection
        val cfg = settings.value
        if (!dispatch(DictationEvent.StartListening(rewrite))) return
        unloadJob?.cancel()
        feedback.onListeningStarted(cfg)
        val s = sessionFactory(cfg)
        session = s
        cycleJob = scope.launch { runCycle(field, snapshot, rewrite, cfg, s) }
    }

    /** Spec §8.1: prova scaduta → dettatura bloccata e l'icona porta alla schermata di acquisto. */
    private fun handleLicenseBlocked() {
        when (licenseGate.state.value) {
            LicenseState.Unknown -> scope.launch {
                licenseGate.refresh()
                if (licenseGate.canDictate()) {
                    start()
                } else {
                    fail(ErrorKind.LICENSE)
                    if (licenseGate.state.value == LicenseState.TrialExpired) openPurchase()
                }
            }
            else -> {
                fail(ErrorKind.LICENSE)
                notifier.notify(Notice.TRIAL_EXPIRED)
                openPurchase()
            }
        }
    }

    private suspend fun runCycle(
        field: EditableField,
        startSnapshot: FieldSnapshot,
        rewrite: Boolean,
        cfg: DictationSettings,
        s: AudioCaptureSession,
    ) {
        var audio: CapturedAudio? = null
        val levelJob = scope.launch { s.level.collect { _level.value = it } }
        try {
            coroutineScope {
                // Spec §11: l'ascolto parte subito, i modelli si caricano in parallelo.
                val prepared = async { runCatching { engine.prepare() } }
                val captured = s.record()
                audio = captured
                levelJob.cancel()
                _level.value = 0f
                session = null
                dispatch(DictationEvent.StopListening)
                prepared.await().getOrThrow()

                if (!captured.hadSpeech || captured.sampleCount < MIN_SAMPLES) {
                    throw EngineException(EngineStatus.NO_SPEECH)
                }
                val options = cfg.toDictationOptions()
                val onStage: (EngineStage) -> Unit = { stage ->
                    if (stage == EngineStage.CORRECTING) dispatch(DictationEvent.CorrectionStarted)
                }
                val result = try {
                    if (rewrite) {
                        engine.rewrite(startSnapshot.selectedText, captured.samples, captured.sampleCount, options, onStage)
                    } else {
                        engine.dictate(captured.samples, captured.sampleCount, options, onStage)
                    }
                } finally {
                    captured.wipe() // Spec §10: audio distrutto subito dopo la trascrizione.
                }
                dispatch(DictationEvent.ResultReady)
                if (applyResult(field, startSnapshot, rewrite, result, cfg)) {
                    dispatch(DictationEvent.InsertionDone)
                } else {
                    fail(ErrorKind.INSERT_FAILED)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            when (e.status) {
                EngineStatus.CANCELLED -> dispatch(DictationEvent.Cancel)
                EngineStatus.NO_SPEECH -> fail(ErrorKind.NO_SPEECH)
                EngineStatus.MODEL_LOAD, EngineStatus.MODEL_INTEGRITY -> {
                    notifier.notify(Notice.MODELS_ERROR)
                    fail(ErrorKind.MODELS)
                }
                else -> {
                    notifier.notify(Notice.ENGINE_ERROR)
                    fail(ErrorKind.ENGINE)
                }
            }
        } catch (e: ModelInstallException) {
            notifier.notify(Notice.MODELS_ERROR)
            fail(ErrorKind.MODELS)
        } catch (e: AudioCaptureException) {
            if (e.reason == AudioCaptureException.Reason.PERMISSION_DENIED) {
                notifier.notify(Notice.MIC_PERMISSION)
                fail(ErrorKind.MIC_PERMISSION)
            } else {
                notifier.notify(Notice.MIC_UNAVAILABLE)
                fail(ErrorKind.MIC_UNAVAILABLE)
            }
        } finally {
            audio?.wipe()
            levelJob.cancel()
            _level.value = 0f
            if (session === s) session = null
            scheduleUnload(cfg)
        }
    }

    /**
     * Inserisce il testo e poi esegue le azioni, nell'ordine del contratto (Spec §5.9).
     * Restituisce false se il testo è finito negli appunti.
     */
    private suspend fun applyResult(
        field: EditableField,
        startSnapshot: FieldSnapshot,
        rewrite: Boolean,
        result: EngineResult,
        cfg: DictationSettings,
    ): Boolean {
        var inserted = true
        if (result.finalText.isNotEmpty()) {
            val outcome = inserter.insert(
                field = field,
                text = result.finalText,
                replaceSnapshot = if (rewrite) startSnapshot else null,
                smartSpacing = !rewrite,
            )
            when (outcome) {
                is InsertOutcome.Inserted -> lastUndo = outcome.undo
                InsertOutcome.CopiedToClipboard -> {
                    inserted = false
                    notifier.notify(Notice.COPIED_TO_CLIPBOARD)
                }
            }
        }

        if (EngineAction.UNDO_LAST_INSERT in result.actions && !undoInternal()) {
            notifier.notify(Notice.UNDO_UNAVAILABLE)
        }
        if (EngineAction.DELETE_PREVIOUS_SENTENCE in result.actions) {
            val current = field.read()
            val plan = current?.let(TextEditing::deletePreviousSentence)
            if (current != null && plan != null && inserter.apply(field, plan)) {
                lastUndo = UndoRecord(field.key, current, plan.newText)
            } else {
                notifier.notify(Notice.NOTHING_TO_DELETE)
            }
        }
        if (EngineAction.SEND in result.actions && !field.pressEnter()) {
            notifier.notify(Notice.SEND_UNAVAILABLE)
        }

        // Spec §5.3: testo non corretto, lo si segnala.
        if (result.usedFallback) notifier.notify(Notice.RAW_TEXT_INSERTED)

        // Spec §5.6: cronologia solo se attiva; un errore qui non interrompe la dettatura.
        if (cfg.historyEnabled && result.finalText.isNotEmpty()) {
            runCatching {
                history.record(
                    HistoryRecord(
                        rawText = result.rawText,
                        finalText = result.finalText,
                        rewrite = rewrite,
                        translate = cfg.translate,
                        timestampMillis = clock(),
                    ),
                )
            }
        }
        return inserted
    }

    private fun undoInternal(): Boolean {
        val record = lastUndo ?: return false
        val field = fieldProvider() ?: return false
        if (field.key != record.fieldKey) return false
        val current = field.read() ?: return false
        val plan = record.restorePlan(current) ?: return false
        if (!inserter.apply(field, plan)) return false
        lastUndo = null
        return true
    }

    /** Spec §4.2: dopo l'inattività configurata i modelli escono dalla RAM. */
    private fun scheduleUnload(cfg: DictationSettings) {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(unloadDelayMs(cfg))
            runCatching { engine.unload() }
        }
    }

    private fun fail(kind: ErrorKind) {
        dispatch(DictationEvent.Failed(kind))
        errorJob?.cancel()
        errorJob = scope.launch {
            delay(errorDisplayMs)
            dispatch(DictationEvent.ErrorShown)
        }
    }

    /** Applica un evento; restituisce false se non è ammesso nello stato corrente. */
    private fun dispatch(event: DictationEvent): Boolean = synchronized(lock) {
        val next = DictationStateMachine.next(_state.value, event) ?: return false
        _state.value = next
        true
    }

    companion object {
        const val ERROR_DISPLAY_MS = 1_500L

        /** Meno di 0,3 s di audio non viene inviato al motore. */
        const val MIN_SAMPLES = AudioFormatSpec.SAMPLE_RATE * 3 / 10
    }
}
