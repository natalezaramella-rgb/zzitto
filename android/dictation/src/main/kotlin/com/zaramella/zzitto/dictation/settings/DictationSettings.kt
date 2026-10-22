package com.zaramella.zzitto.dictation.settings

import com.zaramella.zzitto.engine.CorrectionMode
import com.zaramella.zzitto.engine.DictationOptions
import com.zaramella.zzitto.engine.TranslateTarget

/**
 * Impostazioni dell'app (Spec §5.7), salvate con DataStore. Nessun testo dettato qui (Spec §10):
 * dizionario e scorciatoie stanno nel database cifrato del modulo :app.
 * Aggiunto il 22/10/2026 alle 12:00.
 */
data class DictationSettings(
    /** Lingua di dettatura (codice ISO per Whisper). */
    val language: String = DEFAULT_LANGUAGE,
    /** Spec §5.3. */
    val mode: CorrectionMode = CorrectionMode.DEFAULT,
    /** Spec §5.12. */
    val translate: TranslateTarget = TranslateTarget.NONE,
    /** Stop automatico dopo il silenzio (Spec §5.2). */
    val autoStopOnSilence: Boolean = true,
    /** Silenzio che fa scattare lo stop, predefinito 2 s (Spec §5.2). */
    val silenceTimeoutMs: Int = DEFAULT_SILENCE_MS,
    /** Durata massima, predefinita 5 minuti (Spec §5.2). */
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    /** Comandi vocali (Spec §5.9). */
    val voiceCommandsEnabled: Boolean = true,
    /** Cronologia sì/no (Spec §5.6). */
    val historyEnabled: Boolean = true,
    /** Conservazione della cronologia in giorni (Spec §5.6). */
    val historyRetentionDays: Int = DEFAULT_RETENTION_DAYS,
    /** Vibrazione all'avvio dell'ascolto (Spec §5.7). */
    val vibrateOnStart: Boolean = true,
    /** Suono all'avvio dell'ascolto (Spec §5.7). */
    val soundOnStart: Boolean = false,
    /** Minuti di inattività dopo cui i modelli escono dalla RAM (Spec §4.2). */
    val unloadAfterMinutes: Int = DEFAULT_UNLOAD_MINUTES,
    /** Tour completato (Spec §5.8). */
    val tourCompleted: Boolean = false,
    /** Ultima posizione dell'icona flottante, -1 = posizione predefinita (Spec §5.1-bis). */
    val iconX: Int = -1,
    val iconY: Int = -1,
) {
    fun toDictationOptions(): DictationOptions = DictationOptions(
        language = language,
        mode = mode,
        translate = translate,
        voiceCommandsEnabled = voiceCommandsEnabled,
    )

    /** Riporta i valori negli intervalli ammessi. */
    fun sanitized(): DictationSettings = copy(
        language = language.takeIf { it in SUPPORTED_LANGUAGES } ?: DEFAULT_LANGUAGE,
        silenceTimeoutMs = silenceTimeoutMs.coerceIn(MIN_SILENCE_MS, MAX_SILENCE_MS),
        maxDurationMs = maxDurationMs.coerceIn(MIN_MAX_DURATION_MS, MAX_MAX_DURATION_MS),
        historyRetentionDays = historyRetentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS),
        unloadAfterMinutes = unloadAfterMinutes.coerceIn(MIN_UNLOAD_MINUTES, MAX_UNLOAD_MINUTES),
    )

    companion object {
        const val DEFAULT_LANGUAGE = "it"

        /** Lingue proposte; il motore Whisper ne supporta altre (Spec §2.1: principale italiano). */
        val SUPPORTED_LANGUAGES = listOf("it", "en", "es", "fr", "de")

        const val DEFAULT_SILENCE_MS = 2_000
        const val MIN_SILENCE_MS = 500
        const val MAX_SILENCE_MS = 10_000
        const val DEFAULT_MAX_DURATION_MS = 5 * 60_000L
        const val MIN_MAX_DURATION_MS = 30_000L
        const val MAX_MAX_DURATION_MS = 15 * 60_000L
        const val DEFAULT_RETENTION_DAYS = 30
        const val MIN_RETENTION_DAYS = 1
        const val MAX_RETENTION_DAYS = 365
        const val DEFAULT_UNLOAD_MINUTES = 5
        const val MIN_UNLOAD_MINUTES = 1
        const val MAX_UNLOAD_MINUTES = 60
    }
}
