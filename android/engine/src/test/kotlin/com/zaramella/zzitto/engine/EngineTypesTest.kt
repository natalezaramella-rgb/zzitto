package com.zaramella.zzitto.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Corrispondenza con le costanti di zz_engine.h (Spec §4.3). Aggiunto il 22/10/2026 alle 11:35. */
class EngineTypesTest {

    @Test
    fun `codici di stato come nel contratto`() {
        assertEquals(EngineStatus.OK, EngineStatus.fromCode(0))
        assertEquals(EngineStatus.MODEL_INTEGRITY, EngineStatus.fromCode(4))
        assertEquals(EngineStatus.NO_SPEECH, EngineStatus.fromCode(9))
        assertEquals(EngineStatus.INTERNAL, EngineStatus.fromCode(99))
        assertEquals(EngineStatus.INTERNAL, EngineStatus.fromCode(1234))
    }

    @Test
    fun `maschera delle azioni`() {
        assertEquals(emptySet<EngineAction>(), EngineAction.fromMask(0))
        assertEquals(setOf(EngineAction.UNDO_LAST_INSERT), EngineAction.fromMask(1))
        assertEquals(setOf(EngineAction.DELETE_PREVIOUS_SENTENCE, EngineAction.SEND), EngineAction.fromMask(6))
    }

    @Test
    fun `modalita e traduzione`() {
        assertEquals(CorrectionMode.CLEAN, CorrectionMode.DEFAULT)
        assertEquals(2, CorrectionMode.FORMAL.code)
        assertEquals(CorrectionMode.CLEAN, CorrectionMode.fromCode(7))
        assertEquals(TranslateTarget.ES, TranslateTarget.fromCode(2))
        assertEquals(EngineStage.CORRECTING, EngineStage.fromCode(2))
        assertNull(EngineStage.fromCode(0))
    }

    @Test
    fun `opzioni non valide`() {
        assertThrows<IllegalArgumentException> { DictationOptions(language = " ") }
        assertThrows<IllegalArgumentException> { DictationOptions(correctionTimeoutMs = -1) }
    }

    @Test
    fun `hash segnaposto e hash valido`() {
        val placeholder = ModelFile("a.bin", ModelManifest.HASH_PLACEHOLDER, 1)
        assertFalse(placeholder.hasExpectedHash)
        assertNull(placeholder.sha256OrNull)
        val real = ModelFile("a.bin", "AB".repeat(32), 1)
        assertTrue(real.hasExpectedHash)
        assertEquals("ab".repeat(32), real.sha256OrNull)
        assertEquals("models/a.bin", real.assetPath)
    }

    @Test
    fun `nomi dei modelli come da Spec 7`() {
        assertEquals("whisper-medium-q5_0.bin", ModelManifest.whisper.fileName)
        assertEquals("phi-4-mini-instruct-q4_k_m.gguf", ModelManifest.llm.fileName)
    }
}
