package com.zaramella.zzitto.dictation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zaramella.zzitto.dictation.overlay.IconPlacement
import com.zaramella.zzitto.dictation.settings.DictationSettings
import com.zaramella.zzitto.dictation.settings.SettingsRepository
import com.zaramella.zzitto.engine.CorrectionMode
import com.zaramella.zzitto.engine.TranslateTarget
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Impostazioni (Spec §5.7) e posizione dell'icona (Spec §5.1-bis). Aggiunto il 22/10/2026 alle 11:59. */
class SettingsTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `valori predefiniti della specifica`() {
        val d = DictationSettings()
        assertEquals("it", d.language)
        assertEquals(CorrectionMode.CLEAN, d.mode)
        assertEquals(2_000, d.silenceTimeoutMs)
        assertEquals(5 * 60_000L, d.maxDurationMs)
        assertEquals(TranslateTarget.NONE, d.translate)
    }

    @Test
    fun `valori fuori intervallo vengono corretti`() {
        val s = DictationSettings(
            language = "xx",
            silenceTimeoutMs = 10,
            maxDurationMs = 99_999_999,
            historyRetentionDays = 0,
            unloadAfterMinutes = 1_000,
        ).sanitized()
        assertEquals("it", s.language)
        assertEquals(DictationSettings.MIN_SILENCE_MS, s.silenceTimeoutMs)
        assertEquals(DictationSettings.MAX_MAX_DURATION_MS, s.maxDurationMs)
        assertEquals(1, s.historyRetentionDays)
        assertEquals(60, s.unloadAfterMinutes)
    }

    @Test
    fun `repository salva e rilegge`() = runTest {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }
        val repo = SettingsRepository(store)
        assertEquals(DictationSettings(), repo.settings.first())
        repo.update { it.copy(mode = CorrectionMode.FORMAL, translate = TranslateTarget.EN, iconX = 10, iconY = 20) }
        val read = repo.settings.first()
        assertEquals(CorrectionMode.FORMAL, read.mode)
        assertEquals(TranslateTarget.EN, read.translate)
        assertEquals(10, read.iconX)
        assertEquals(20, read.iconY)
        scope.cancel()
    }

    @Test
    fun `posizione dell'icona sempre sullo schermo`() {
        assertEquals(0 to 0, IconPlacement.clamp(-50, -10, 1080, 2340, 150))
        assertEquals(930 to 2190, IconPlacement.clamp(5000, 9000, 1080, 2340, 150))
        assertEquals(918 to 1404, IconPlacement.default(1080, 2340, 150, 12))
    }
}
