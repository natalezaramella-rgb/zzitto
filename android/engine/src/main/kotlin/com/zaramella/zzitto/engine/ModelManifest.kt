package com.zaramella.zzitto.engine

/**
 * Modelli inclusi negli asset pack install-time `:models`, `:models_phi_1`, `:models_phi_2` (Spec §7).
 * Aggiunto il 22/10/2026 alle 11:30. Aggiornato il 22/10/2026 alle 11:59 con il formato di
 * `tools/models/README.md` (agente E).
 *
 * Spec §7: gli hash SHA-256 sono salvati nel codice. Le righe precedute dai commenti marcatore
 * (hash, dimensione, versione dei modelli) vengono aggiornate da `tools/models/prepare-models.*`:
 * non cambiarne il formato. Finché un hash vale "DA_IMPOSTARE" il modello è considerato non
 * verificato e non viene caricato, tranne che con il motore finto (build debug).
 */
object ModelManifest {
    const val HASH_PLACEHOLDER = "DA_IMPOSTARE"

    /** Cartella dentro gli asset dei tre pack. */
    const val ASSET_DIR = "models"

    /** Indice scritto dallo script dell'agente E nel pack `models`. */
    const val INDEX_FILE = "models-index.json"

    const val WHISPER_FILE = "whisper-medium-q5_0.bin"
    // zz-model-hash: whisper-medium-q5_0.bin
    const val WHISPER_SHA256 = "DA_IMPOSTARE"
    // zz-model-size: whisper-medium-q5_0.bin
    const val WHISPER_SIZE_BYTES = 0L

    // Spec §11 (22/10/2026 22:06): Whisper small è il modello predefinito su Android (1,8 s contro 3–4 s di
    // medium sul Galaxy S25 Ultra); medium resta come "Massima precisione".
    const val WHISPER_SMALL_FILE = "whisper-small-q5_1.bin"
    // zz-model-hash: whisper-small-q5_1.bin
    const val WHISPER_SMALL_SHA256 = "DA_IMPOSTARE"
    // zz-model-size: whisper-small-q5_1.bin
    const val WHISPER_SMALL_SIZE_BYTES = 0L

    const val LLM_FILE = "phi-4-mini-instruct-q4_k_m.gguf"
    // zz-model-hash: phi-4-mini-instruct-q4_k_m.gguf
    const val LLM_SHA256 = "DA_IMPOSTARE"
    // zz-model-size: phi-4-mini-instruct-q4_k_m.gguf
    const val LLM_SIZE_BYTES = 0L

    // zz-models-version
    const val MODELS_VERSION = "DA_IMPOSTARE"

    /** Stime usate solo se le dimensioni non sono ancora impostate (barra di avanzamento, spazio). */
    private const val WHISPER_APPROX_BYTES = 540L * 1024 * 1024
    private const val WHISPER_SMALL_APPROX_BYTES = 190L * 1024 * 1024
    private const val LLM_APPROX_BYTES = 2_500L * 1024 * 1024

    val whisper = ModelFile(WHISPER_FILE, WHISPER_SHA256, WHISPER_SIZE_BYTES, WHISPER_APPROX_BYTES)
    val whisperSmall = ModelFile(WHISPER_SMALL_FILE, WHISPER_SMALL_SHA256, WHISPER_SMALL_SIZE_BYTES, WHISPER_SMALL_APPROX_BYTES)
    val llm = ModelFile(LLM_FILE, LLM_SHA256, LLM_SIZE_BYTES, LLM_APPROX_BYTES)

    val all: List<ModelFile> = listOf(whisperSmall, whisper, llm)

    /** File del modello Whisper per la scelta dell'utente (Spec §5.7, §11). */
    fun whisperFileFor(model: WhisperModel): String = when (model) {
        WhisperModel.SMALL -> WHISPER_SMALL_FILE
        WhisperModel.MEDIUM -> WHISPER_FILE
    }

    /** True se la versione dei modelli è stata impostata dallo script. */
    val hasModelsVersion: Boolean
        get() = MODELS_VERSION != HASH_PLACEHOLDER
}

/** Un file di modello con hash e dimensione attesi. */
data class ModelFile(
    val fileName: String,
    val sha256: String,
    /** Dimensione esatta in byte; 0 = non ancora impostata. */
    val sizeBytes: Long,
    val approxBytes: Long = sizeBytes,
) {
    /** True se l'hash è stato impostato (64 cifre esadecimali). */
    val hasExpectedHash: Boolean
        get() = sha256 != ModelManifest.HASH_PLACEHOLDER && SHA256_REGEX.matches(sha256)

    /** Hash da passare al motore, oppure null se non ancora impostato. */
    val sha256OrNull: String?
        get() = if (hasExpectedHash) sha256.lowercase() else null

    /** Dimensione per il controllo dello spazio e l'avanzamento. */
    val expectedBytes: Long
        get() = if (sizeBytes > 0) sizeBytes else approxBytes

    val assetPath: String
        get() = "${ModelManifest.ASSET_DIR}/$fileName"

    /** Parte numero [n] (da 1) negli asset pack di Phi-4-mini: `<nome>.<n>.part`. */
    fun partAssetPath(n: Int): String = "${ModelManifest.ASSET_DIR}/$fileName.$n.part"

    private companion object {
        val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }
}
