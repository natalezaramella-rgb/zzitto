package com.zaramella.zzitto.dictation.settings

import com.zaramella.zzitto.audio.MicrophoneCatalog
import com.zaramella.zzitto.engine.CorrectionMode
import com.zaramella.zzitto.engine.DictationOptions
import com.zaramella.zzitto.engine.TranslateTarget
import com.zaramella.zzitto.engine.WhisperModel

/**
 * Impostazioni dell'app (Spec §5.7), salvate con DataStore. Nessun testo dettato qui (Spec §10):
 * dizionario e scorciatoie stanno nel database cifrato del modulo :app.
 * Aggiunto il 22/10/2026 alle 11:59.
 */
data class DictationSettings(
    /** Lingua di dettatura (codice ISO per Whisper). */
    val language: String = DEFAULT_LANGUAGE,
    /** Spec §5.3. */
    val mode: CorrectionMode = CorrectionMode.DEFAULT,
    /** Spec §5.12: traduzione rimossa su decisione del titolare del 22/10/2026 alle 22:20 (modello di correzione più leggero): resta sempre NONE. */
    val translate: TranslateTarget = TranslateTarget.NONE,
    /** Stop automatico dopo il silenzio (Spec §5.2). */
    val autoStopOnSilence: Boolean = true,
    /** Silenzio che fa scattare lo stop, predefinito 1 s (Spec §5.2; era 2 s, cambiato il 22/10/2026 alle 22:08). */
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
    /**
     * Microfono scelto (chiave di [com.zaramella.zzitto.audio.MicrophoneCatalog.keyOf]); null =
     * predefinito di sistema. Spec §5.7, aggiunto il 22/10/2026 alle 17:44.
     */
    val microphoneKey: String? = null,
    /**
     * Modello di trascrizione (Spec §5.7, §11; aggiunto il 22/10/2026 alle 22:08): small veloce (predefinito),
     * medium "Massima precisione".
     */
    val whisperModel: WhisperModel = WhisperModel.SMALL,
) {
    fun toDictationOptions(): DictationOptions = DictationOptions(
        language = language,
        mode = mode,
        translate = TranslateTarget.NONE, // Spec §5.12: traduzione rimossa
        voiceCommandsEnabled = voiceCommandsEnabled,
    )

    /** Riporta i valori negli intervalli ammessi. */
    fun sanitized(): DictationSettings = copy(
        language = language.takeIf { it in SUPPORTED_LANGUAGES } ?: DEFAULT_LANGUAGE,
        translate = TranslateTarget.NONE, // Spec §5.12: una traduzione salvata in precedenza si azzera
        silenceTimeoutMs = silenceTimeoutMs.coerceIn(MIN_SILENCE_MS, MAX_SILENCE_MS),
        maxDurationMs = maxDurationMs.coerceIn(MIN_MAX_DURATION_MS, MAX_MAX_DURATION_MS),
        historyRetentionDays = historyRetentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS),
        unloadAfterMinutes = unloadAfterMinutes.coerceIn(MIN_UNLOAD_MINUTES, MAX_UNLOAD_MINUTES),
        microphoneKey = MicrophoneCatalog.sanitizeKey(microphoneKey), // Spec §5.7
    )

    companion object {
        const val DEFAULT_LANGUAGE = "it"

        /** Lingue proposte; il motore Whisper ne supporta altre (Spec §2.1: principale italiano). */
        val SUPPORTED_LANGUAGES = listOf("it", "en", "es", "fr", "de")

        const val DEFAULT_SILENCE_MS = 1_000

        /** Vecchio predefinito: le impostazioni salvate prima della versione 2 lo portano a 1 s. */
        const val LEGACY_DEFAULT_SILENCE_MS = 2_000

        /** Versione dello schema salvato (2: silenzio 1 s e scelta del modello, 22/10/2026). */
        const val SETTINGS_VERSION = 2
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
