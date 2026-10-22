package com.zaramella.zzitto.dictation.service

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Process
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zaramella.zzitto.audio.AndroidPcmSource
import com.zaramella.zzitto.audio.AudioCaptureSession
import com.zaramella.zzitto.audio.CaptureParams
import com.zaramella.zzitto.core.licensing.LicenseGate
import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.dictation.DictationController
import com.zaramella.zzitto.dictation.DictationHistorySink
import com.zaramella.zzitto.dictation.PersonalizationSource
import com.zaramella.zzitto.dictation.R
import com.zaramella.zzitto.dictation.ZzDictationEngine
import com.zaramella.zzitto.dictation.ZzittoIntents
import com.zaramella.zzitto.dictation.feedback.AndroidListeningFeedback
import com.zaramella.zzitto.dictation.feedback.ToastNotifier
import com.zaramella.zzitto.dictation.insert.AccessibilityEditableField
import com.zaramella.zzitto.dictation.insert.AndroidClipboardAccess
import com.zaramella.zzitto.dictation.insert.TextInserter
import com.zaramella.zzitto.dictation.overlay.IconAppearance
import com.zaramella.zzitto.dictation.overlay.OverlayController
import com.zaramella.zzitto.dictation.overlay.OverlayMenuItem
import com.zaramella.zzitto.dictation.settings.DictationSettings
import com.zaramella.zzitto.dictation.settings.SettingsRepository
import com.zaramella.zzitto.dictation.state.DictationState
import com.zaramella.zzitto.dictation.state.DictationStateMachine
import com.zaramella.zzitto.engine.CorrectionMode
import com.zaramella.zzitto.engine.EngineException
import com.zaramella.zzitto.engine.TranslateTarget
import com.zaramella.zzitto.engine.ZzEngine
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Servizio di accessibilità di Zzitto (Spec §5.1-bis): rileva il campo di testo con focus,
 * mostra l'icona flottante e vi inserisce il testo dettato.
 * Aggiunto il 22/10/2026 alle 11:59.
 *
 * Privacy (Spec §10): il servizio legge solo il campo con focus, non registra eventi, non
 * scrive testo nei log. Le password sono escluse.
 */
@AndroidEntryPoint
class ZzittoAccessibilityService : AccessibilityService() {

    @Inject lateinit var zzEngine: ZzEngine

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var personalization: PersonalizationSource

    @Inject lateinit var historySink: DictationHistorySink

    @Inject lateinit var licenseGate: LicenseGate

    private var serviceScope: CoroutineScope? = null
    private var overlay: OverlayController? = null
    private var controller: DictationController? = null
    private var audioExecutor: ExecutorService? = null
    private var audioDispatcher: ExecutorCoroutineDispatcher? = null

    private val settingsState = MutableStateFlow(DictationSettings())
    private var focusedNode: AccessibilityNodeInfo? = null
    private var focusedField: AccessibilityEditableField? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope

        // Thread audio dedicato ad alta priorità (Spec §4.2: mai sul main thread).
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                runnable.run()
            }, "zz-audio")
        }
        audioExecutor = executor
        val dispatcher = executor.asCoroutineDispatcher()
        audioDispatcher = dispatcher

        val overlayController = OverlayController(this, overlayCallbacks)
        overlay = overlayController

        val dictation = DictationController(
            scope = scope,
            engine = ZzDictationEngine(zzEngine),
            sessionFactory = { cfg ->
                AudioCaptureSession(
                    // Spec §5.7: microfono scelto nelle impostazioni (assente → predefinito di sistema).
                    // Aggiunto il 22/10/2026 alle 17:44.
                    sourceFactory = { AndroidPcmSource(this, cfg.microphoneKey) },
                    detector = if (cfg.autoStopOnSilence) EngineVadDetector(cfg.silenceTimeoutMs) else null,
                    params = CaptureParams(maxDurationMs = cfg.maxDurationMs, autoStopOnSilence = cfg.autoStopOnSilence),
                    dispatcher = dispatcher,
                )
            },
            fieldProvider = { focusedField },
            inserter = TextInserter(AndroidClipboardAccess(this)),
            licenseGate = licenseGate,
            settings = settingsState,
            // Spec §11, §10: tempi delle fasi nel registro di sistema, senza testo (22/10/2026 21:59).
            perfLog = { android.util.Log.i("ZzittoPerf", it) },
            history = historySink,
            notifier = ToastNotifier(this),
            feedback = AndroidListeningFeedback(this),
            openPurchase = ::openPurchase,
        )
        controller = dictation

        scope.launch {
            settingsRepository.settings.collect {
                settingsState.value = it
                zzEngine.setWhisperModel(it.whisperModel) // Spec §5.7, §11 (22/10/2026 22:09)
            }
        }
        // Spec §5.5, §5.11: dizionario e scorciatoie passati al motore a ogni modifica.
        scope.launch {
            personalization.dictionary.collect { words ->
                try {
                    zzEngine.setDictionary(words)
                } catch (_: EngineException) {
                    // Il motore riapplica l'elenco alla prossima creazione.
                }
            }
        }
        scope.launch {
            personalization.shortcuts.collect { items ->
                try {
                    zzEngine.setShortcuts(items)
                } catch (_: EngineException) {
                    // come sopra
                }
            }
        }
        // Spec §8.1: verifica della licenza a ogni avvio del servizio.
        scope.launch { licenseGate.refresh() }
        scope.launch {
            combine(dictation.state, dictation.level, licenseGate.state) { state, level, license ->
                Triple(state, level, license)
            }.collect { (state, level, license) ->
                val appearance = if (state == DictationState.Idle && license == LicenseState.TrialExpired) {
                    IconAppearance.Locked
                } else {
                    IconAppearance.Cycle(state)
                }
                overlayController.render(appearance, level)
                refreshIconVisibility()
            }
        }
        updateFocus()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            -> updateFocus()
            else -> Unit
        }
    }

    override fun onInterrupt() {
        controller?.cancel()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** Spec §5.1-bis: l'icona compare quando un campo di testo ha il focus e sparisce quando lo perde. */
    private fun updateFocus() {
        val node = try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: RuntimeException) {
            null
        }
        if (AccessibilityEditableField.isDictationTarget(node)) {
            if (node != focusedNode) {
                focusedNode = node
                focusedField = node?.let(::AccessibilityEditableField)
            }
        } else {
            focusedNode = null
            focusedField = null
        }
        refreshIconVisibility()
    }

    private fun refreshIconVisibility() {
        val overlayController = overlay ?: return
        val state = controller?.state?.value ?: DictationState.Idle
        // Durante un'operazione l'icona resta visibile anche se il campo perde il focus.
        val visible = focusedField != null || DictationStateMachine.isBusy(state) || state is DictationState.Error
        if (visible && !overlayController.isIconVisible) {
            val cfg = settingsState.value
            overlayController.showIcon(cfg.iconX, cfg.iconY)
            controller?.prewarm() // Spec §11: modelli pronti prima del tap (22/10/2026 21:48)
        } else if (!visible && overlayController.isIconVisible) {
            overlayController.hideIcon()
        }
    }

    private val overlayCallbacks = object : OverlayController.Callbacks {
        override fun onIconTap() {
            controller?.onIconTap()
        }

        override fun onIconLongPress() {
            overlay?.showMenu(buildMenu())
        }

        override fun onIconMoved(x: Int, y: Int) {
            serviceScope?.launch { settingsRepository.update { it.copy(iconX = x, iconY = y) } }
        }
    }

    /** Menu a pressione lunga: annulla, lingua, traduzione EN/ES, modalità (Spec §5.1, §5.3, §5.12). */
    private fun buildMenu(): List<OverlayMenuItem> {
        val dictation = controller ?: return emptyList()
        val cfg = settingsState.value
        val items = mutableListOf<OverlayMenuItem>()
        if (DictationStateMachine.isBusy(dictation.state.value)) {
            items += OverlayMenuItem(getString(R.string.zz_menu_cancel)) { dictation.cancel() }
        } else if (dictation.canUndo) {
            items += OverlayMenuItem(getString(R.string.zz_menu_undo)) { dictation.undoLastInsert() }
        }
        items += OverlayMenuItem(getString(R.string.zz_menu_language, languageName(cfg.language))) {
            updateSettings { s ->
                val all = DictationSettings.SUPPORTED_LANGUAGES
                s.copy(language = all[(all.indexOf(s.language) + 1).mod(all.size)])
            }
        }
        items += OverlayMenuItem(getString(R.string.zz_menu_translate, translateName(cfg.translate))) {
            updateSettings { s ->
                val all = TranslateTarget.entries
                s.copy(translate = all[(s.translate.ordinal + 1) % all.size])
            }
        }
        items += OverlayMenuItem(getString(R.string.zz_menu_mode, modeName(cfg.mode))) {
            updateSettings { s ->
                val all = CorrectionMode.entries
                s.copy(mode = all[(s.mode.ordinal + 1) % all.size])
            }
        }
        items += OverlayMenuItem(getString(R.string.zz_menu_settings)) { openApp(ZzittoIntents.ACTION_OPEN_SETTINGS) }
        return items
    }

    private fun updateSettings(transform: (DictationSettings) -> DictationSettings) {
        serviceScope?.launch { settingsRepository.update(transform) }
    }

    private fun languageName(code: String): String =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

    private fun translateName(target: TranslateTarget): String = getString(
        when (target) {
            TranslateTarget.NONE -> R.string.zz_translate_none
            TranslateTarget.EN -> R.string.zz_translate_en
            TranslateTarget.ES -> R.string.zz_translate_es
        },
    )

    private fun modeName(mode: CorrectionMode): String = getString(
        when (mode) {
            CorrectionMode.FAITHFUL -> R.string.zz_mode_faithful
            CorrectionMode.CLEAN -> R.string.zz_mode_clean
            CorrectionMode.FORMAL -> R.string.zz_mode_formal
        },
    )

    /** Spec §8.1: con la prova scaduta l'icona porta alla schermata di acquisto. */
    private fun openPurchase() = openApp(ZzittoIntents.ACTION_OPEN_PURCHASE)

    private fun openApp(action: String) {
        val intent = Intent(action)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // L'app principale non espone l'azione: niente da fare.
        }
    }

    private fun shutdown() {
        controller?.close()
        controller = null
        overlay?.hideIcon()
        overlay = null
        serviceScope?.cancel()
        serviceScope = null
        audioDispatcher?.close()
        audioDispatcher = null
        audioExecutor = null
        focusedNode = null
        focusedField = null
    }
}
