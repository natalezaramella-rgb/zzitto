package com.zaramella.zzitto.dictation

import com.zaramella.zzitto.engine.DictationOptions
import com.zaramella.zzitto.engine.EngineResult
import com.zaramella.zzitto.engine.EngineStage
import com.zaramella.zzitto.engine.EngineStatus
import com.zaramella.zzitto.engine.ZzEngine
import com.zaramella.zzitto.engine.ZzStream
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Adattatori dello streaming sul motore reale, con MockK (Spec §11, §12).
 * Aggiunto il 22/10/2026 alle 16:20.
 */
class ZzDictationStreamTest {

    private val result = EngineResult("ehm ciao", "Ciao.", emptySet(), false, false, 1, 1)

    @Test
    fun `beginStream apre il flusso del motore con le opzioni`() = runBlocking {
        val engine = mockk<ZzEngine>()
        val stream = mockk<ZzStream>()
        val options = DictationOptions(language = "en")
        coEvery { engine.beginStream(options) } returns stream
        every { stream.abort() } returns Unit

        ZzDictationEngine(engine).beginStream(options).abort()

        coVerify(exactly = 1) { engine.beginStream(options) }
        verify(exactly = 1) { stream.abort() }
    }

    @Test
    fun `feed traduce lo stato del motore in booleano`() {
        val stream = mockk<ZzStream>()
        val pcm = FloatArray(1600)
        every { stream.feed(pcm, 0, 1600) } returns EngineStatus.OK
        every { stream.feed(pcm, 0, 800) } returns EngineStatus.OUT_OF_MEMORY

        val adapter = ZzDictationStream(stream)

        assertTrue(adapter.feed(pcm, 0, 1600))
        assertFalse(adapter.feed(pcm, 0, 800))
        verify(exactly = 1) { stream.feed(pcm, 0, 1600) }
    }

    @Test
    fun `finish restituisce il risultato del motore`() = runBlocking {
        val stream = mockk<ZzStream>()
        val onStage: (EngineStage) -> Unit = {}
        coEvery { stream.finish(onStage) } returns result

        assertEquals(result, ZzDictationStream(stream).finish(onStage))
        coVerify(exactly = 1) { stream.finish(onStage) }
    }
}
