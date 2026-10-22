package com.zaramella.zzitto.dictation

import com.zaramella.zzitto.audio.AudioCaptureException
import com.zaramella.zzitto.audio.AudioCaptureSession
import com.zaramella.zzitto.audio.AudioFormatSpec
import com.zaramella.zzitto.audio.CaptureParams
import com.zaramella.zzitto.audio.EndOfSpeechDetector
import com.zaramella.zzitto.audio.PcmSource
import com.zaramella.zzitto.core.licensing.LicenseGate
import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.core.licensing.PurchaseOutcome
import com.zaramella.zzitto.dictation.insert.FakeClipboard
import com.zaramella.zzitto.dictation.insert.FakeField
import com.zaramella.zzitto.dictation.insert.TextInserter
import com.zaramella.zzitto.dictation.settings.DictationSettings
import com.zaramella.zzitto.dictation.state.DictationState
import com.zaramella.zzitto.dictation.state.ErrorKind
import com.zaramella.zzitto.engine.CorrectionMode
import com.zaramella.zzitto.engine.DictationOptions
import com.zaramella.zzitto.engine.EngineAction
import com.zaramella.zzitto.engine.EngineException
import com.zaramella.zzitto.engine.EngineResult
import com.zaramella.zzitto.engine.EngineStage
import com.zaramella.zzitto.engine.EngineStatus
import com.zaramella.zzitto.engine.TranslateTarget
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ciclo di dettatura con motore, microfono, campo e licenza finti (Spec §5.2–§5.12, §8.1, §12).
 * Aggiunto il 22/10/2026 alle 11:59.
 */
class DictationControllerTest {

    private class FakeEngine : DictationEngine {
        var result = EngineResult("ehm ciao", "Ciao.", emptySet(), false, false, 1, 1)
        var error: EngineException? = null
        var prepareError: Exception? = null
        var prepared = 0
        var unloaded = 0
        var lastOptions: DictationOptions? = null
        var lastSelected: String? = null
        var lastSamples = 0
        var receivedAudio: FloatArray? = null

        override suspend fun prepare() {
            prepareError?.let { throw it }
            prepared++
        }

        fun dictateImpl(
            pcm: FloatArray,
            sampleCount: Int,
            options: DictationOptions,
            onStage: (EngineStage) -> Unit,
        ): EngineResult {
            receivedAudio = pcm
            lastSamples = sampleCount
            lastOptions = options
            onStage(EngineStage.TRANSCRIBING)
            error?.let { throw it }
            if (!result.fromShortcut) onStage(EngineStage.CORRECTING)
            onStage(EngineStage.DONE)
            return result
        }

        override suspend fun rewrite(
            selectedText: String,
            pcm: FloatArray,
            sampleCount: Int,
            options: DictationOptions,
            onStage: (EngineStage) -> Unit,
        ): EngineResult {
            lastSelected = selectedText
            return dictateImpl(pcm, sampleCount, options, onStage)
        }

        override suspend fun unload() {
            unloaded++
        }

        // ---- Streaming (Spec §11). Aggiunto il 22/10/2026 alle 16:20. ----
        var beginError: Exception? = null
        var beginCount = 0
        var dictateCount = 0
        /** Se impostato, beginStream attende questo via (per provare l'arretrato). */
        var beginGate: CountDownLatch? = null
        var streamFeedResult = true
        var finishError: EngineException? = null
        var finishGate: CountDownLatch? = null
        val finishStarted = CountDownLatch(1)
        val streams: MutableList<FakeStream> = Collections.synchronizedList(mutableListOf())
        val lastStream: FakeStream? get() = synchronized(streams) { streams.lastOrNull() }

        override suspend fun beginStream(options: DictationOptions): DictationStream {
            beginGate?.let { gate -> withContext(Dispatchers.IO) { gate.await(5, TimeUnit.SECONDS) } }
            beginCount++
            beginError?.let { throw it }
            lastOptions = options
            return FakeStream(this).also { streams += it }
        }

        override suspend fun dictate(
            pcm: FloatArray,
            sampleCount: Int,
            options: DictationOptions,
            onStage: (EngineStage) -> Unit,
        ): EngineResult {
            dictateCount++
            return dictateImpl(pcm, sampleCount, options, onStage)
        }
    }

    /** Flusso finto: conta i campioni ricevuti e il thread che li consegna (Spec §11). */
    private class FakeStream(val engine: FakeEngine) : DictationStream {
        @Volatile
        var fedSamples = 0
        @Volatile
        var fedChunks = 0
        val feedThreads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        @Volatile
        var finished = 0
        @Volatile
        var aborted = 0
        var lastBlock: FloatArray? = null

        override fun feed(pcm: FloatArray, offset: Int, length: Int): Boolean {
            check(finished == 0 && aborted == 0) { "feed dopo la chiusura" }
            feedThreads += Thread.currentThread().name
            lastBlock = pcm
            if (!engine.streamFeedResult) return false
            fedSamples += length
            fedChunks++
            return true
        }

        override suspend fun finish(onStage: (EngineStage) -> Unit): EngineResult {
            check(aborted == 0) { "finish dopo abort" }
            finished++
            onStage(EngineStage.TRANSCRIBING)
            engine.finishStarted.countDown()
            engine.finishGate?.let { gate -> withContext(Dispatchers.IO) { gate.await(5, TimeUnit.SECONDS) } }
            engine.finishError?.let { throw it }
            if (!engine.result.fromShortcut) onStage(EngineStage.CORRECTING)
            onStage(EngineStage.DONE)
            return engine.result
        }

        override fun abort() {
            if (finished == 0) aborted++
        }
    }

    private class FakeLicense(initial: LicenseState) : LicenseGate {
        private val flow = MutableStateFlow(initial)
        var afterRefresh: LicenseState? = null
        override val state: StateFlow<LicenseState> = flow
        override suspend fun refresh() {
            afterRefresh?.let { flow.value = it }
        }
        override suspend fun launchPurchase(activity: android.app.Activity): PurchaseOutcome = PurchaseOutcome.Cancelled
        override suspend fun restorePurchases(): LicenseState = flow.value
    }

    /** Sorgente che restituisce [chunks] blocchi di "voce" e poi termina; opzionalmente attende un via. */
    private class FakeSource(
        val chunks: Int,
        val gate: CountDownLatch? = null,
        val startError: AudioCaptureException? = null,
        val sleepMs: Long = 0,
    ) : PcmSource {
        val reading = CountDownLatch(1)
        val readThreads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        @Volatile
        var reads = 0
        override fun start() {
            startError?.let { throw it }
            reads = 0 // ogni sessione riparte da capo
        }
        override fun read(buffer: FloatArray, offset: Int, length: Int): Int {
            reading.countDown()
            readThreads += Thread.currentThread().name
            gate?.await(5, TimeUnit.SECONDS)
            if (sleepMs > 0) Thread.sleep(sleepMs)
            if (reads >= chunks) return 0
            reads++
            buffer.fill(0.4f, offset, offset + length)
            return length
        }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class NoSpeechDetector : EndOfSpeechDetector {
        override fun reset() = Unit
        override fun process(chunk: FloatArray, offset: Int, length: Int) = false
        override fun hasSpeech() = false
        override fun close() = Unit
    }

    private val engine = FakeEngine()
    private val clipboard = FakeClipboard()
    private val field = FakeField("Ciao", 4, 4).also { it.clipboard = clipboard }
    private val notices: MutableList<Notice> = Collections.synchronizedList(mutableListOf())
    private val history: MutableList<HistoryRecord> = Collections.synchronizedList(mutableListOf())
    private val states: MutableList<DictationState> = Collections.synchronizedList(mutableListOf())
    private var purchaseOpened = 0
    private var feedbackCount = 0
    private val settings = MutableStateFlow(DictationSettings())
    private var source = FakeSource(chunks = 10)
    private var detector: EndOfSpeechDetector? = null
    private var currentField: FakeField? = field

    private fun controller(scope: CoroutineScope, license: LicenseGate = FakeLicense(LicenseState.Trial(20))) =
        DictationController(
            scope = scope,
            engine = engine,
            sessionFactory = { cfg ->
                AudioCaptureSession({ source }, detector, CaptureParams(cfg.maxDurationMs, cfg.autoStopOnSilence), Dispatchers.Default)
            },
            fieldProvider = { currentField },
            inserter = TextInserter(clipboard, verifyDelayMs = 1, restoreDelayMs = 1),
            licenseGate = license,
            settings = settings,
            history = object : DictationHistorySink {
                override suspend fun record(entry: HistoryRecord) {
                    history += entry
                }
            },
            notifier = UserNotifier { notices += it },
            feedback = ListeningFeedback { feedbackCount++ },
            openPurchase = { purchaseOpened++ },
            clock = { 1234L },
            errorDisplayMs = 50,
            unloadDelayMs = { 20 },
        )

    private suspend fun DictationController.awaitState(predicate: (DictationState) -> Boolean): DictationState =
        withTimeout(5_000) { state.first(predicate) }

    private suspend fun DictationController.runToIdle() {
        awaitState { it is DictationState.Listening }
        awaitState { it == DictationState.Idle || it is DictationState.Error }
    }

    @Test
    fun `dettatura completa inserisce il testo, registra la cronologia e torna Inattivo`() = runBlocking {
        val c = controller(this)
        val recorder = launchRecorder(c)
        c.onIconTap()
        c.runToIdle()
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals("Ciao Ciao.", field.text)
        assertEquals(1, feedbackCount)
        assertEquals(1, engine.prepared)
        // Spec §11: tutto l'audio arriva al flusso, finish allo stop, nessuna dettatura classica.
        assertEquals(10 * AudioFormatSpec.CHUNK_SAMPLES, engine.lastStream!!.fedSamples)
        assertEquals(1, engine.lastStream!!.finished)
        assertEquals(0, engine.lastStream!!.aborted)
        assertEquals(0, engine.dictateCount)
        assertTrue(engine.lastStream!!.lastBlock!!.all { it == 0f }, "blocco audio azzerato (Spec §10)")
        assertEquals(1, history.size)
        assertEquals("ehm ciao", history[0].rawText)
        assertEquals(1234L, history[0].timestampMillis)
        recorder.stopAndCheck(
            listOf(
                DictationState.Idle,
                DictationState.Listening(false),
                DictationState.Transcribing,
                DictationState.Correcting,
                DictationState.Inserting,
                DictationState.Idle,
            ),
        )
        c.close()
    }

    @Test
    fun `le impostazioni diventano opzioni del motore`() = runBlocking {
        settings.value = DictationSettings(language = "en", mode = CorrectionMode.FORMAL, translate = TranslateTarget.ES, voiceCommandsEnabled = false)
        val c = controller(this)
        c.onIconTap()
        c.runToIdle()
        assertEquals(DictationOptions("en", CorrectionMode.FORMAL, TranslateTarget.ES, false), engine.lastOptions)
        c.close()
    }

    @Test
    fun `cronologia disattivata`() = runBlocking {
        settings.value = DictationSettings(historyEnabled = false)
        val c = controller(this)
        c.onIconTap()
        c.runToIdle()
        assertTrue(history.isEmpty())
        c.close()
    }

    @Test
    fun `secondo tap ferma l'ascolto`() = runBlocking {
        source = FakeSource(chunks = Int.MAX_VALUE, sleepMs = 5)
        val c = controller(this)
        c.onIconTap()
        c.awaitState { it is DictationState.Listening }
        withTimeout(5_000) { while (source.reads < 6) delay(5) }
        c.onIconTap() // stop
        c.awaitState { it == DictationState.Idle }
        assertEquals("Ciao Ciao.", field.text)
        c.close()
    }

    @Test
    fun `annulla durante l'ascolto non inserisce nulla`() = runBlocking {
        val gate = CountDownLatch(1)
        source = FakeSource(chunks = Int.MAX_VALUE, gate = gate)
        val c = controller(this)
        c.onIconTap()
        c.awaitState { it is DictationState.Listening }
        c.cancel()
        gate.countDown()
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals("Ciao", field.text)
        c.close()
    }

    @Test
    fun `selezione presente avvia la riscrittura`() = runBlocking {
        field.text = "Testo vecchio qui"
        field.selStart = 6
        field.selEnd = 13
        engine.result = EngineResult("rendilo formale", "nuovo", emptySet(), false, false, 1, 1)
        val c = controller(this)
        c.onIconTap()
        assertEquals(DictationState.Listening(rewrite = true), c.state.value)
        c.runToIdle()
        assertEquals("vecchio", engine.lastSelected)
        assertEquals(0, engine.beginCount, "Spec §5.10: la riscrittura resta su zz_rewrite")
        assertEquals("Testo nuovo qui", field.text)
        assertTrue(history.single().rewrite)
        c.close()
    }

    @Test
    fun `annulla ultimo inserimento`() = runBlocking {
        val c = controller(this)
        c.onIconTap()
        c.runToIdle()
        assertTrue(c.canUndo)
        c.undoLastInsert()
        assertEquals("Ciao", field.text)
        assertFalse(c.canUndo)
        c.undoLastInsert()
        assertEquals(listOf(Notice.UNDO_UNAVAILABLE), notices)
        c.close()
    }

    @Test
    fun `comando annulla vocale annulla l'inserimento precedente`() = runBlocking {
        val c = controller(this)
        c.onIconTap()
        c.runToIdle()
        engine.result = EngineResult("annulla", "", setOf(EngineAction.UNDO_LAST_INSERT), false, false, 1, 0)
        c.onIconTap()
        c.runToIdle()
        assertEquals("Ciao", field.text)
        assertEquals(1, history.size, "un'azione senza testo non va in cronologia")
        c.close()
    }

    @Test
    fun `cancella l'ultima frase e invia`() = runBlocking {
        field.text = "Prima. Seconda frase."
        field.selStart = 21
        field.selEnd = 21
        engine.result = EngineResult("cancella l'ultima frase invia", "", setOf(EngineAction.DELETE_PREVIOUS_SENTENCE, EngineAction.SEND), false, false, 1, 0)
        val c = controller(this)
        c.onIconTap()
        c.runToIdle()
        assertEquals("Prima.", field.text)
        assertEquals(1, field.enterPressed)
        c.close()
    }

    @Test
    fun `invio non disponibile viene segnalato`() = runBlocking {
        field.enterSupported = false
        engine.result = EngineResult("ok invia", "Ok.", setOf(EngineAction.SEND), false, false, 1, 1)
        val c = controller(this)
        c.onIconTap()
        c.runToIdle()
        assertTrue(Notice.SEND_UNAVAILABLE in notices)
        c.close()
    }

    @Test
    fun `testo grezzo per timeout della correzione viene segnalato`() = runBlocking {
        engine.result = EngineResult("ehm ciao", "ehm ciao", emptySet(), usedFallback = true, fromShortcut = false, msTranscribe = 1, msCorrect = 10_000)
        val c = controller(this)
        c.onIconTap()
        c.runToIdle()
        assertEquals("Ciao ehm ciao", field.text)
        assertTrue(Notice.RAW_TEXT_INSERTED in notices)
        c.close()
    }

    @Test
    fun `scorciatoia vocale passa da Trascrizione a Inserimento`() = runBlocking {
        engine.result = EngineResult("firma", "Natale Zaramella", emptySet(), false, fromShortcut = true, msTranscribe = 1, msCorrect = 0)
        val c = controller(this)
        val recorder = launchRecorder(c)
        c.onIconTap()
        c.runToIdle()
        assertFalse(DictationState.Correcting in recorder.snapshot())
        recorder.stop()
        c.close()
    }

    @Test
    fun `audio senza parlato porta a Errore e poi Inattivo`() = runBlocking {
        detector = NoSpeechDetector()
        val c = controller(this)
        c.onIconTap()
        assertEquals(DictationState.Error(ErrorKind.NO_SPEECH), c.awaitState { it is DictationState.Error })
        c.awaitState { it == DictationState.Idle }
        assertEquals("Ciao", field.text)
        c.close()
    }

    @Test
    fun `errore di integrita dei modelli`() = runBlocking {
        engine.prepareError = EngineException(EngineStatus.MODEL_INTEGRITY)
        val c = controller(this)
        c.onIconTap()
        assertEquals(DictationState.Error(ErrorKind.MODELS), c.awaitState { it is DictationState.Error })
        assertTrue(Notice.MODELS_ERROR in notices)
        c.close()
    }

    @Test
    fun `permesso microfono negato`() = runBlocking {
        source = FakeSource(1, startError = AudioCaptureException(AudioCaptureException.Reason.PERMISSION_DENIED, "x"))
        val c = controller(this)
        c.onIconTap()
        assertEquals(DictationState.Error(ErrorKind.MIC_PERMISSION), c.awaitState { it is DictationState.Error })
        assertTrue(Notice.MIC_PERMISSION in notices)
        c.close()
    }

    @Test
    fun `campo non scrivibile lascia il testo negli appunti`() = runBlocking {
        field.setTextSupported = false
        field.pasteSupported = false
        val c = controller(this)
        c.onIconTap()
        assertEquals(DictationState.Error(ErrorKind.INSERT_FAILED), c.awaitState { it is DictationState.Error })
        assertEquals(" Ciao.", clipboard.current)
        assertTrue(Notice.COPIED_TO_CLIPBOARD in notices)
        c.close()
    }

    @Test
    fun `senza campo con focus`() = runBlocking {
        currentField = null
        val c = controller(this)
        c.onIconTap()
        assertEquals(DictationState.Error(ErrorKind.NO_FIELD), c.state.value)
        c.close()
    }

    @Test
    fun `prova scaduta blocca la dettatura e apre l'acquisto`() = runBlocking {
        val c = controller(this, FakeLicense(LicenseState.TrialExpired))
        c.onIconTap()
        assertEquals(DictationState.Error(ErrorKind.LICENSE), c.state.value)
        assertEquals(1, purchaseOpened)
        assertEquals(0, engine.prepared)
        assertTrue(Notice.TRIAL_EXPIRED in notices)
        c.close()
    }

    @Test
    fun `licenza sconosciuta viene verificata prima di dettare`() = runBlocking {
        val license = FakeLicense(LicenseState.Unknown).also { it.afterRefresh = LicenseState.Purchased }
        val c = controller(this, license)
        c.onIconTap()
        c.runToIdle()
        assertEquals("Ciao Ciao.", field.text)
        c.close()
    }

    // Spec §11, precaricamento all'apparizione dell'icona (22/10/2026 21:48).
    @Test
    fun `prewarm carica i modelli una volta e poi li scarica per inattivita`() = runBlocking {
        val c = controller(this)
        c.prewarm()
        c.prewarm()
        withTimeout(5_000) { while (engine.unloaded == 0) delay(5) }
        assertEquals(1, engine.prepared)
        assertEquals(DictationState.Idle, c.state.value)
        c.close()
    }

    @Test
    fun `prewarm non fa nulla senza licenza valida`() = runBlocking {
        val c = controller(this, FakeLicense(LicenseState.TrialExpired))
        c.prewarm()
        delay(50)
        assertEquals(0, engine.prepared)
        c.close()
    }

    @Test
    fun `i modelli vengono scaricati dopo l'inattivita`() = runBlocking {
        val c = controller(this)
        c.onIconTap()
        c.runToIdle()
        withTimeout(5_000) { while (engine.unloaded == 0) delay(5) }
        assertEquals(1, engine.unloaded)
        c.close()
    }

    // ---- Dettatura in streaming (Spec §5.2, §11). Aggiunto il 22/10/2026 alle 16:20. ----

    @Test
    fun `durante l'ascolto i blocchi vanno al flusso dal thread di registrazione`() = runBlocking {
        val gate = CountDownLatch(1)
        source = FakeSource(chunks = Int.MAX_VALUE, gate = gate, sleepMs = 2)
        val c = controller(this)
        c.onIconTap()
        c.awaitState { it is DictationState.Listening }
        withTimeout(5_000) { while (engine.lastStream == null) delay(5) }
        delay(50) // il flusso è collegato al ricevitore
        gate.countDown()
        val stream = engine.lastStream!!
        withTimeout(5_000) { while (stream.fedChunks < 5) delay(5) }
        assertTrue(c.state.value is DictationState.Listening, "i blocchi arrivano mentre si ascolta")
        assertEquals(0, stream.finished)
        assertTrue(stream.feedThreads.isNotEmpty() && source.readThreads.containsAll(stream.feedThreads))
        c.onIconTap() // stop → finish
        c.awaitState { it == DictationState.Idle }
        assertEquals(1, stream.finished)
        assertEquals(source.reads * AudioFormatSpec.CHUNK_SAMPLES, stream.fedSamples)
        assertEquals(0, engine.dictateCount)
        assertEquals("Ciao Ciao.", field.text)
        c.close()
    }

    @Test
    fun `i blocchi arrivati prima dell'apertura del flusso vengono inoltrati`() = runBlocking {
        val beginGate = CountDownLatch(1)
        engine.beginGate = beginGate
        source = FakeSource(chunks = 10, sleepMs = 2)
        val c = controller(this)
        c.onIconTap()
        withTimeout(5_000) { while (source.reads < 4) delay(2) }
        beginGate.countDown()
        c.runToIdle()
        assertEquals(10 * AudioFormatSpec.CHUNK_SAMPLES, engine.lastStream!!.fedSamples)
        assertEquals(1, engine.lastStream!!.finished)
        assertEquals(0, engine.dictateCount)
        c.close()
    }

    @Test
    fun `annulla durante l'ascolto chiude il flusso con abort`() = runBlocking {
        source = FakeSource(chunks = Int.MAX_VALUE, sleepMs = 2)
        val c = controller(this)
        c.onIconTap()
        withTimeout(5_000) { while ((engine.lastStream?.fedChunks ?: 0) < 2) delay(5) }
        val stream = engine.lastStream!!
        c.cancel()
        withTimeout(5_000) { while (stream.aborted == 0) delay(5) }
        assertEquals(1, stream.aborted)
        assertEquals(0, stream.finished)
        assertEquals(0, engine.dictateCount)
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals("Ciao", field.text)
        c.close()
    }

    @Test
    fun `annulla durante l'elaborazione interrompe finish`() = runBlocking {
        val finishGate = CountDownLatch(1)
        engine.finishGate = finishGate
        val c = controller(this)
        c.onIconTap()
        assertTrue(withContext(Dispatchers.IO) { engine.finishStarted.await(5, TimeUnit.SECONDS) })
        assertEquals(DictationState.Transcribing, c.state.value)
        c.cancel()
        finishGate.countDown()
        delay(50)
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals("Ciao", field.text)
        assertTrue(history.isEmpty())
        c.close()
    }

    @Test
    fun `errore all'apertura del flusso ripiega sulla dettatura classica`() = runBlocking {
        engine.beginError = EngineException(EngineStatus.BUSY)
        val c = controller(this)
        c.onIconTap()
        c.runToIdle()
        assertEquals(1, engine.beginCount)
        assertEquals(1, engine.dictateCount)
        assertEquals(10 * AudioFormatSpec.CHUNK_SAMPLES, engine.lastSamples)
        assertTrue(engine.receivedAudio!!.all { it == 0f }, "audio azzerato dopo la trascrizione (Spec §10)")
        assertEquals("Ciao Ciao.", field.text)
        assertFalse(Notice.ENGINE_ERROR in notices)
        c.close()
    }

    @Test
    fun `blocco rifiutato dal motore annulla il flusso e ripiega sulla dettatura classica`() = runBlocking {
        engine.streamFeedResult = false
        val c = controller(this)
        c.onIconTap()
        c.runToIdle()
        assertEquals(1, engine.lastStream!!.aborted)
        assertEquals(0, engine.lastStream!!.finished)
        assertEquals(1, engine.dictateCount)
        assertEquals(10 * AudioFormatSpec.CHUNK_SAMPLES, engine.lastSamples)
        assertEquals("Ciao Ciao.", field.text)
        c.close()
    }

    @Test
    fun `errore in finish porta allo stato di Errore senza fallback`() = runBlocking {
        engine.finishError = EngineException(EngineStatus.TIMEOUT)
        val c = controller(this)
        c.onIconTap()
        assertEquals(DictationState.Error(ErrorKind.ENGINE), c.awaitState { it is DictationState.Error })
        assertTrue(Notice.ENGINE_ERROR in notices)
        assertEquals(0, engine.dictateCount)
        assertEquals("Ciao", field.text)
        c.close()
    }

    @Test
    fun `finish annullato dal motore torna Inattivo`() = runBlocking {
        engine.finishError = EngineException(EngineStatus.CANCELLED)
        val c = controller(this)
        c.onIconTap()
        c.runToIdle()
        assertEquals(DictationState.Idle, c.state.value)
        assertEquals("Ciao", field.text)
        c.close()
    }

    @Test
    fun `senza parlato il flusso viene annullato`() = runBlocking {
        detector = NoSpeechDetector()
        val c = controller(this)
        c.onIconTap()
        assertEquals(DictationState.Error(ErrorKind.NO_SPEECH), c.awaitState { it is DictationState.Error })
        withTimeout(5_000) { while ((engine.lastStream?.aborted ?: 0) == 0) delay(5) }
        assertEquals(0, engine.lastStream!!.finished)
        c.close()
    }

    @Test
    fun `errore dei modelli non apre il flusso`() = runBlocking {
        engine.prepareError = EngineException(EngineStatus.MODEL_LOAD)
        val c = controller(this)
        c.onIconTap()
        assertEquals(DictationState.Error(ErrorKind.MODELS), c.awaitState { it is DictationState.Error })
        assertEquals(0, engine.beginCount)
        assertEquals(0, engine.dictateCount)
        c.close()
    }

    /** Registra tutti gli stati attraversati. */
    private class StateRecorder(val job: Job, val list: MutableList<DictationState>) {
        fun snapshot(): List<DictationState> = synchronized(list) { list.toList() }
        fun stop() = job.cancel()
        fun stopAndCheck(expected: List<DictationState>) {
            job.cancel()
            assertEquals(expected, snapshot())
        }
    }

    private fun CoroutineScope.launchRecorder(c: DictationController): StateRecorder {
        val job = launch(Dispatchers.Unconfined) { c.state.collect { states += it } }
        return StateRecorder(job, states)
    }
}
