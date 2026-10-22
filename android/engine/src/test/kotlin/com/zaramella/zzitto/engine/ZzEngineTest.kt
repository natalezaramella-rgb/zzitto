package com.zaramella.zzitto.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Test del wrapper del motore con un nativo finto (Spec §4.2, §4.3, §12).
 * Aggiunto il 22/10/2026 alle 11:35.
 */
class ZzEngineTest {

    private val native = FakeEngineNative()
    private val configCalls = AtomicInteger()
    private val config = EngineConfig("/m/w.bin", "/m/l.gguf", null, null)
    private val engine = ZzEngine(native, EngineConfigProvider { configCalls.incrementAndGet(); config })

    @AfterEach
    fun tearDown() = engine.close()

    @Test
    fun `ensureLoaded crea una sola istanza ed e idempotente`() = runBlocking {
        engine.ensureLoaded()
        engine.ensureLoaded()
        assertEquals(1, native.createCount.get())
        assertEquals(2, native.loadCount.get())
        assertEquals(1, configCalls.get())
        assertTrue(engine.loaded.value)
        assertEquals(config, native.lastConfig)
    }

    @Test
    fun `le chiamate native girano sul thread dedicato del motore`() = runBlocking {
        engine.dictate(FloatArray(100), options = DictationOptions())
        assertEquals(setOf("zz-engine"), native.callingThreads)
    }

    @Test
    fun `dictate restituisce il risultato e notifica le fasi`() = runBlocking {
        val stages = mutableListOf<EngineStage>()
        val result = engine.dictate(
            FloatArray(320),
            sampleCount = 160,
            options = DictationOptions(mode = CorrectionMode.FORMAL, translate = TranslateTarget.ES),
            onStage = { synchronized(stages) { stages += it } },
        )
        assertEquals("Ciao.", result.finalText)
        assertEquals(listOf(EngineStage.TRANSCRIBING, EngineStage.CORRECTING, EngineStage.DONE), stages)
        assertEquals(160, native.lastSampleCount)
        assertEquals(CorrectionMode.FORMAL, native.lastOptions?.mode)
        assertEquals(TranslateTarget.ES, native.lastOptions?.translate)
    }

    @Test
    fun `gli errori del motore diventano EngineException`() = runBlocking {
        native.dictateStatus = EngineStatus.NO_SPEECH
        val e = assertThrows<EngineException> {
            runBlocking { engine.dictate(FloatArray(10), options = DictationOptions()) }
        }
        assertEquals(EngineStatus.NO_SPEECH, e.status)
    }

    @Test
    fun `versione API diversa blocca la creazione`() {
        native.api = 1
        val e = assertThrows<EngineException> { runBlocking { engine.ensureLoaded() } }
        assertEquals(EngineStatus.INTERNAL, e.status)
        assertEquals(0, native.createCount.get())
    }

    @Test
    fun `errore di integrita al caricamento viene propagato`() {
        native.loadStatus = EngineStatus.MODEL_INTEGRITY
        val e = assertThrows<EngineException> { runBlocking { engine.ensureLoaded() } }
        assertEquals(EngineStatus.MODEL_INTEGRITY, e.status)
        assertFalse(engine.loaded.value)
    }

    @Test
    fun `annullare la coroutine chiama zz_cancel sull'operazione in corso`() = runBlocking {
        native.blockUntilCancelled = CountDownLatch(1)
        val job = async(Dispatchers.Default) {
            runCatching { engine.dictate(FloatArray(100), options = DictationOptions()) }
        }
        assertTrue(native.dictateStarted.await(5, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertEquals(1, native.cancelCount.get())
        // Il thread del motore resta utilizzabile dopo l'annullamento.
        native.blockUntilCancelled = null
        val result = engine.dictate(FloatArray(100), options = DictationOptions())
        assertEquals("Ciao.", result.finalText)
    }

    @Test
    fun `dizionario e scorciatoie vengono applicati alla creazione e normalizzati`() = runBlocking {
        engine.setDictionary(listOf(" Zaramella ", "", "Zaramella", "Zzitto"))
        engine.setShortcuts(listOf(VoiceShortcut(" firma ", "Natale"), VoiceShortcut(" ", "x")))
        assertEquals(0, native.dictionaryCalls) // nessuna istanza ancora
        engine.ensureLoaded()
        assertEquals(listOf("Zaramella", "Zzitto"), native.dictionary)
        assertEquals(listOf(VoiceShortcut("firma", "Natale")), native.shortcuts)
        engine.setDictionary(listOf("Altro"))
        assertEquals(listOf("Altro"), native.dictionary)
    }

    @Test
    fun `unload scarica i modelli e la dettatura successiva li ricarica`() = runBlocking {
        engine.ensureLoaded()
        engine.unload()
        assertFalse(engine.loaded.value)
        assertEquals(1, native.unloadCount.get())
        engine.dictate(FloatArray(10), options = DictationOptions())
        assertTrue(engine.loaded.value)
        assertEquals(1, native.createCount.get())
    }

    @Test
    fun `rewrite passa il testo selezionato`() = runBlocking {
        engine.rewrite("testo", FloatArray(10), options = DictationOptions())
        assertEquals("testo", native.lastSelected)
    }

    @Test
    fun `rewrite rifiuta una selezione vuota`() {
        assertThrows<IllegalArgumentException> {
            runBlocking { engine.rewrite("", FloatArray(10), options = DictationOptions()) }
        }
    }

    @Test
    fun `dopo close le operazioni falliscono e l'istanza viene distrutta`() = runBlocking {
        engine.ensureLoaded()
        engine.close()
        withContext(Dispatchers.Default) { Thread.sleep(100) }
        assertEquals(1, native.destroyCount.get())
        assertThrows<EngineException> { runBlocking { engine.ensureLoaded() } }
        Unit
    }

    @Test
    fun `toString del risultato non espone il testo`() {
        val text = native.result.toString()
        assertFalse(text.contains("Ciao"))
        assertFalse(text.contains("ehm"))
    }
}
