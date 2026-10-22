package com.zaramella.zzitto.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Ciclo di cattura (Spec §5.2, §10). Aggiunto il 22/10/2026 alle 11:50. */
class AudioCaptureSessionTest {

    /** Sorgente finta: restituisce blocchi pieni di [value]; dopo [chunksBeforeEnd] blocchi termina. */
    private class FakeSource(
        val value: Float = 0.5f,
        val chunksBeforeEnd: Int = Int.MAX_VALUE,
        val failAfter: Int = Int.MAX_VALUE,
        val startError: AudioCaptureException? = null,
    ) : PcmSource {
        var reads = 0
        var started = false
        var stopped = false
        var closed = false
        val firstRead = CountDownLatch(1)
        var lastBuffer: FloatArray? = null

        override fun start() {
            startError?.let { throw it }
            started = true
        }

        override fun read(buffer: FloatArray, offset: Int, length: Int): Int {
            lastBuffer = buffer
            firstRead.countDown()
            if (reads >= failAfter) return -3
            if (reads >= chunksBeforeEnd) return 0
            reads++
            buffer.fill(value, offset, offset + length)
            return length
        }

        override fun stop() {
            stopped = true
        }

        override fun close() {
            closed = true
        }
    }

    /** Rilevatore finto: segnala la fine del parlato al blocco numero [endAt]. */
    private class FakeDetector(val endAt: Int, val speech: Boolean = true) : EndOfSpeechDetector {
        var count = 0
        var resets = 0
        override fun reset() {
            resets++
            count = 0
        }
        override fun process(chunk: FloatArray, offset: Int, length: Int): Boolean = ++count >= endAt
        override fun hasSpeech(): Boolean = speech
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private fun session(source: PcmSource, detector: EndOfSpeechDetector?, params: CaptureParams = CaptureParams()) =
        AudioCaptureSession({ source }, detector, params, Dispatchers.Default)

    @Test
    fun `stop automatico quando il VAD rileva la fine del parlato`() = runBlocking {
        val source = FakeSource()
        val detector = FakeDetector(endAt = 5)
        val audio = session(source, detector).record()
        assertEquals(StopReason.END_OF_SPEECH, audio.stopReason)
        assertEquals(5 * AudioFormatSpec.CHUNK_SAMPLES, audio.sampleCount)
        assertEquals(500L, audio.durationMs)
        assertTrue(audio.hadSpeech)
        assertEquals(1, detector.resets)
        assertTrue(source.stopped && source.closed)
        assertTrue(detector.closed)
    }

    @Test
    fun `durata massima`() = runBlocking {
        val audio = session(FakeSource(), FakeDetector(endAt = Int.MAX_VALUE), CaptureParams(maxDurationMs = 1_000)).record()
        assertEquals(StopReason.MAX_DURATION, audio.stopReason)
        assertEquals(AudioFormatSpec.SAMPLE_RATE, audio.sampleCount)
    }

    @Test
    fun `senza stop automatico il VAD viene ignorato`() = runBlocking {
        val audio = session(
            FakeSource(chunksBeforeEnd = 8),
            FakeDetector(endAt = 2),
            CaptureParams(autoStopOnSilence = false),
        ).record()
        assertEquals(StopReason.SOURCE_ENDED, audio.stopReason)
        assertEquals(8 * AudioFormatSpec.CHUNK_SAMPLES, audio.sampleCount)
    }

    @Test
    fun `secondo tap ferma la registrazione`() = runBlocking {
        val source = FakeSource()
        val s = session(source, null)
        val result = async(Dispatchers.Default) { s.record() }
        assertTrue(source.firstRead.await(5, TimeUnit.SECONDS))
        s.requestStop()
        val audio = result.await()
        assertEquals(StopReason.MANUAL, audio.stopReason)
        assertTrue(audio.sampleCount > 0)
    }

    @Test
    fun `annullamento azzera il blocco di lettura e chiude il microfono`() = runBlocking {
        val source = FakeSource()
        val s = session(source, null)
        val job = async(Dispatchers.Default) { runCatching { s.record() } }
        assertTrue(source.firstRead.await(5, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue(source.closed)
        assertTrue(source.lastBuffer!!.all { it == 0f })
    }

    @Test
    fun `errore di lettura`() {
        val source = FakeSource(failAfter = 2)
        val e = assertThrows<AudioCaptureException> { runBlocking { session(source, null).record() } }
        assertEquals(AudioCaptureException.Reason.READ_ERROR, e.reason)
        assertTrue(source.closed)
    }

    @Test
    fun `permesso negato all'avvio`() {
        val source = FakeSource(startError = AudioCaptureException(AudioCaptureException.Reason.PERMISSION_DENIED, "x"))
        val e = assertThrows<AudioCaptureException> { runBlocking { session(source, null).record() } }
        assertEquals(AudioCaptureException.Reason.PERMISSION_DENIED, e.reason)
    }

    @Test
    fun `wipe distrugge l'audio`() = runBlocking {
        val audio = session(FakeSource(chunksBeforeEnd = 2), null).record()
        val samples = audio.samples
        audio.wipe()
        assertTrue(samples.all { it == 0f })
        assertEquals(0, audio.sampleCount)
        assertThrows<IllegalStateException> { audio.samples }
        Unit
    }

    @Test
    fun `assenza di parlato riportata dal VAD`() = runBlocking {
        val audio = session(FakeSource(chunksBeforeEnd = 3), FakeDetector(endAt = 99, speech = false)).record()
        assertFalse(audio.hadSpeech)
    }

    @Test
    fun `parametri non validi`() {
        assertThrows<IllegalArgumentException> { CaptureParams(maxDurationMs = 10) }
        assertEquals(5 * 60 * AudioFormatSpec.SAMPLE_RATE, CaptureParams().maxSamples)
    }
}
