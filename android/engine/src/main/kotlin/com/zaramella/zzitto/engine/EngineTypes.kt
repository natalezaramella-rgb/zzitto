package com.zaramella.zzitto.engine

/**
 * Tipi Kotlin che rispecchiano il contratto `zz_engine.h` (API versione 1).
 * Spec §4.3, §5.2, §5.3, §5.9, §5.12. Aggiunto il 22/10/2026 alle 11:25.
 */

/** Codici `zz_status`. */
enum class EngineStatus(val code: Int) {
    OK(0),
    INVALID_ARG(1),
    NOT_LOADED(2),
    MODEL_LOAD(3),
    MODEL_INTEGRITY(4),
    OUT_OF_MEMORY(5),
    CANCELLED(6),
    TIMEOUT(7),
    BUSY(8),
    NO_SPEECH(9),
    INTERNAL(99),
    ;

    companion object {
        fun fromCode(code: Int): EngineStatus = entries.firstOrNull { it.code == code } ?: INTERNAL
    }
}

/** Errore restituito dal motore. Il messaggio non contiene mai testo dettato (Spec §10). */
class EngineException(val status: EngineStatus, message: String = status.name) : Exception(message)

/** Fasi notificate dal motore durante l'elaborazione (`zz_stage`, Spec §5.2). */
enum class EngineStage(val code: Int) {
    TRANSCRIBING(1),
    CORRECTING(2),
    DONE(3),
    ;

    companion object {
        fun fromCode(code: Int): EngineStage? = entries.firstOrNull { it.code == code }
    }
}

/** Modalità di correzione (Spec §5.3). */
enum class CorrectionMode(val code: Int) {
    FAITHFUL(0),
    CLEAN(1),
    FORMAL(2),
    ;

    companion object {
        val DEFAULT = CLEAN
        fun fromCode(code: Int): CorrectionMode = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/** Lingua di traduzione (Spec §5.12). */
enum class TranslateTarget(val code: Int) {
    NONE(0),
    EN(1),
    ES(2),
    ;

    companion object {
        fun fromCode(code: Int): TranslateTarget = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** Azioni che la piattaforma esegue dopo l'inserimento (`zz_action`, Spec §5.9). */
enum class EngineAction(val bit: Int) {
    UNDO_LAST_INSERT(1 shl 0),
    DELETE_PREVIOUS_SENTENCE(1 shl 1),
    SEND(1 shl 2),
    ;

    companion object {
        fun fromMask(mask: Int): Set<EngineAction> = entries.filterTo(mutableSetOf()) { mask and it.bit != 0 }
    }
}

/** Opzioni di una dettatura (`zz_dictation_options`). */
data class DictationOptions(
    val language: String = "it",
    val mode: CorrectionMode = CorrectionMode.DEFAULT,
    val translate: TranslateTarget = TranslateTarget.NONE,
    val voiceCommandsEnabled: Boolean = true,
    /** Oltre questo tempo si inserisce il testo grezzo (Spec §5.3). 0 = predefinito del motore (10 s). */
    val correctionTimeoutMs: Int = 0,
) {
    init {
        require(language.isNotBlank()) { "language vuoto" }
        require(correctionTimeoutMs >= 0) { "timeout negativo" }
    }
}

/**
 * Risultato di `zz_dictate`/`zz_rewrite`/`zz_process_text`.
 * Contiene testo dettato: tenerlo solo in memoria e non registrarlo nei log (Spec §10).
 */
data class EngineResult(
    val rawText: String,
    val finalText: String,
    val actions: Set<EngineAction>,
    /** Correzione fallita o in timeout: [finalText] è il testo grezzo (Spec §5.3). */
    val usedFallback: Boolean,
    /** [finalText] viene da una scorciatoia vocale (Spec §5.11). */
    val fromShortcut: Boolean,
    val msTranscribe: Long,
    val msCorrect: Long,
) {
    // Spec §10: niente testo nei log, nemmeno per errore tramite toString().
    override fun toString(): String =
        "EngineResult(rawLen=${rawText.length}, finalLen=${finalText.length}, actions=$actions, " +
            "usedFallback=$usedFallback, fromShortcut=$fromShortcut, msTranscribe=$msTranscribe, msCorrect=$msCorrect)"
}

/** Configurazione di creazione (`zz_engine_config`, Spec §7). */
data class EngineConfig(
    val whisperModelPath: String,
    val llmModelPath: String,
    /** Hash esadecimale minuscolo; null = nessuna verifica (solo test o hash non ancora impostato). */
    val whisperSha256: String?,
    val llmSha256: String?,
    val threads: Int = 0,
    val useGpu: Boolean = true,
    /** Spec §7, §11: dal 22/10/2026 entrambi i modelli restano in RAM (reattività); true solo con poca memoria. */
    val sequentialLoading: Boolean = false,
)

/** Scorciatoia vocale parola chiave → testo (Spec §5.11). */
data class VoiceShortcut(val keyword: String, val expansion: String) {
    override fun toString(): String = "VoiceShortcut(keywordLen=${keyword.length}, expansionLen=${expansion.length})"
}
