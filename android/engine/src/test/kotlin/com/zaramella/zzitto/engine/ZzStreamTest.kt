package com.zaramella.zzitto.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
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
 * Dettatura in streaming con un nativo finto (API 2, Spec §11, §5.2, §10, §12).
 * Aggiunto il 22/10/2026 alle 16:20.
 */
class ZzStreamTest {

    private val native = FakeEngineNative()
    private val config = EngineConfig("/m/w.bin", "/m/l.gguf", null, null)
    private val engine = ZzEngine(native, EngineConfigProvider { config })

    @AfterEach
    fun tearDown() = engine.close()

    @Test
    fun `begin carica i modelli e apre il flusso sul thread del motore`() = runBlocking {
        val options = DictationOptions(language = "en", mode = CorrectionMode.FAITHFUL)
        val stream = engine.beginStream(options)
        assertTrue(stream.isOpen)
        assertTrue(engine.loaded.value)
        assertEquals(options, native.lastStreamOptions)
        assertEquals(setOf("zz-engine"), native.callingThreads)
        stream.abort()
    }

    @Test
    fun `feed gira sul thread del chiamante e finish restituisce il risultato`() = runBlocking {
        val stream = engine.beginStream(DictationOptions())
        val feeder = Thread({
            val chunk = FloatArray(1600)
            repeat(5) { assertEquals(EngineStatus.OK, stream.feed(chunk)) }
            assertEquals(EngineStatus.OK, stream.feed(chunk, offset = 100, length = 500))
        }, "zz-audio")
        feeder.start()
        feeder.join()
        val stages = mutableListOf<EngineStage>()
        val result = stream.finish { synchronized(stages) { stages += it } }
        assertEquals("Ciao.", result.finalText)
        assertEquals(5 * 1600 + 500, native.fedSamples.get())
        assertEquals(setOf("zz-audio"), native.feedThreads)
        assertEquals(setOf("zz-engine"), native.callingThreads)
        assertEquals(listOf(EngineStage.TRANSCRIBING, EngineStage.CORRECTING, EngineStage.DONE), stages)
        assertFalse(stream.isOpen)
        assertTrue(native.openStreams.isEmpty())
    }

    @Test
    fun `dopo la chiusura feed non arriva al motore`() = runBlocking {
        val stream = engine.beginStream(DictationOptions())
        stream.finish()
        assertEquals(EngineStatus.INVALID_ARG, stream.feed(FloatArray(10)))
        assertEquals(0, native.fedSamples.get())
        assertThrows<EngineException> { runBlocking { stream.finish() } }
        stream.abort() // idempotente, nessun doppio rilascio
        assertEquals(0, native.streamAbortCount.get())
    }

    @Test
    fun `feed con intervallo non valido`() = runBlocking {
        val stream = engine.beginStream(DictationOptions())
        assertThrows<IllegalArgumentException> { stream.feed(FloatArray(10), offset = 5, length = 6) }
        assertEquals(EngineStatus.OK, stream.feed(FloatArray(10), offset = 10, length = 0))
        stream.abort()
    }

    @Test
    fun `errore del motore in feed viene restituito`() = runBlocking {
        val stream = engine.beginStream(DictationOptions())
        native.streamFeedStatus = EngineStatus.OUT_OF_MEMORY
        assertEquals(EngineStatus.OUT_OF_MEMORY, stream.feed(FloatArray(10)))
        stream.abort()
    }

    @Test
    fun `abort libera il flusso sul thread del motore`() = runBlocking {
        val stream = engine.beginStream(DictationOptions())
        stream.abort()
        assertTrue(native.abortLatch.await(5, TimeUnit.SECONDS))
        assertFalse(stream.isOpen)
        assertTrue(native.openStreams.isEmpty())
        assertEquals(setOf("zz-engine"), native.callingThreads)
        // Il motore accetta un nuovo flusso.
        engine.beginStream(DictationOptions()).finish()
        assertEquals(2, native.streamBeginCount.get())
    }

    @Test
    fun `errore all'apertura diventa EngineException`() {
        native.streamBeginStatus = EngineStatus.BUSY
        val e = assertThrows<EngineException> { runBlocking { engine.beginStream(DictationOptions()) } }
        assertEquals(EngineStatus.BUSY, e.status)
        assertTrue(native.openStreams.isEmpty())
    }

    @Test
    fun `un secondo flusso mentre il primo e aperto viene rifiutato`() = runBlocking {
        val first = engine.beginStream(DictationOptions())
        val e = assertThrows<EngineException> { runBlocking { engine.beginStream(DictationOptions()) } }
        assertEquals(EngineStatus.BUSY, e.status)
        first.abort()
    }

    @Test
    fun `errore in finish diventa EngineException e il flusso e comunque chiuso`() = runBlocking {
        native.streamFinishStatus = EngineStatus.NO_SPEECH
        val stream = engine.beginStream(DictationOptions())
        val e = assertThrows<EngineException> { runBlocking { stream.finish() } }
        assertEquals(EngineStatus.NO_SPEECH, e.status)
        assertTrue(native.openStreams.isEmpty())
        // Il flusso non è più attivo: se ne può aprire un altro.
        engine.beginStream(DictationOptions()).abort()
    }

    @Test
    fun `annullare finish in corso chiama zz_cancel`() = runBlocking {
        native.blockFinishUntilCancelled = CountDownLatch(1)
        val stream = engine.beginStream(DictationOptions())
        val job = async(Dispatchers.Default) { runCatching { stream.finish() } }
        assertTrue(native.finishStarted.await(5, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertEquals(1, native.cancelCount.get())
        assertTrue(native.openStreams.isEmpty())
        native.blockFinishUntilCancelled = null
        assertEquals("Ciao.", engine.beginStream(DictationOptions()).finish().finalText)
    }

    @Test
    fun `abort durante finish interrompe con zz_cancel`() = runBlocking {
        native.blockFinishUntilCancelled = CountDownLatch(1)
        val stream = engine.beginStream(DictationOptions())
        val result = async(Dispatchers.Default) { runCatching { stream.finish() } }
        assertTrue(native.finishStarted.await(5, TimeUnit.SECONDS))
        stream.abort()
        val e = result.await().exceptionOrNull() as EngineException
        assertEquals(EngineStatus.CANCELLED, e.status)
        assertEquals(0, native.streamAbortCount.get())
    }

    @Test
    fun `begin annullato mentre il motore apre il flusso non lascia flussi aperti`() = runBlocking {
        engine.ensureLoaded()
        native.blockBegin = CountDownLatch(1)
        val begin = async(Dispatchers.Default) { engine.beginStream(DictationOptions()) }
        assertTrue(native.beginStarted.await(5, TimeUnit.SECONDS))
        begin.cancel()
        native.blockBegin?.countDown()
        val outcome = runCatching { begin.await() }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertTrue(native.abortLatch.await(5, TimeUnit.SECONDS))
        assertTrue(native.openStreams.isEmpty())
    }

    @Test
    fun `close annulla il flusso aperto prima di distruggere l'istanza`() = runBlocking {
        engine.beginStream(DictationOptions())
        engine.close()
        assertTrue(native.abortLatch.await(5, TimeUnit.SECONDS))
        withContext(Dispatchers.Default) { Thread.sleep(50) }
        assertTrue(native.openStreams.isEmpty())
        assertEquals(1, native.destroyCount.get())
    }

    @Test
    fun `toString del flusso non espone dati`() = runBlocking {
        val stream = engine.beginStream(DictationOptions())
        assertEquals("ZzStream(open=true)", stream.toString())
        stream.abort()
    }
}
