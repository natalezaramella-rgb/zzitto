package com.zaramella.zzitto.engine

/**
 * Elenco dei modelli inclusi nell'asset pack install-time `:models` (Spec §7).
 * Aggiunto il 22/10/2026 alle 11:30.
 *
 * Gli hash SHA-256 sono costanti nel codice (Spec §7, Spec §10 "controlli di integrità").
 * Il valore [HASH_PLACEHOLDER] viene sostituito dallo script dell'agente E (cartella `tools/`)
 * quando i file dei modelli sono definitivi: finché resta, la verifica viene saltata.
 *
 * Asset pack: ogni pack di Google Play può pesare al massimo 1,5 GB, quindi un modello più grande
 * (Phi-4-mini Q4_K_M, circa 2,5 GB) può essere diviso in parti `<nome>.part00`, `<nome>.part01`, …
 * anche in pack diversi: [ModelInstaller] le riunisce in ordine durante la copia.
 */
object ModelManifest {
    const val HASH_PLACEHOLDER = "DA_IMPOSTARE"

    /** Cartella dentro gli asset (`android/models/src/main/assets/models/`). */
    const val ASSET_DIR = "models"

    const val WHISPER_FILE = "whisper-medium-q5_0.bin"
    const val LLM_FILE = "phi-4-mini-instruct-q4_k_m.gguf"

    // Aggiornati automaticamente dallo script dell'agente E: non cambiare il formato delle due righe.
    const val WHISPER_SHA256 = "DA_IMPOSTARE"
    const val LLM_SHA256 = "DA_IMPOSTARE"

    /** Stime per la barra di avanzamento della copia; non usate per la verifica. */
    const val WHISPER_APPROX_BYTES = 540L * 1024 * 1024
    const val LLM_APPROX_BYTES = 2_500L * 1024 * 1024

    val whisper = ModelFile(WHISPER_FILE, WHISPER_SHA256, WHISPER_APPROX_BYTES)
    val llm = ModelFile(LLM_FILE, LLM_SHA256, LLM_APPROX_BYTES)

    val all: List<ModelFile> = listOf(whisper, llm)
}

/** Un file di modello e il suo hash atteso. */
data class ModelFile(
    val fileName: String,
    val sha256: String,
    val approxBytes: Long,
) {
    /** True se l'hash è stato impostato (64 cifre esadecimali). */
    val hasExpectedHash: Boolean
        get() = sha256 != ModelManifest.HASH_PLACEHOLDER && SHA256_REGEX.matches(sha256)

    /** Hash da passare al motore, oppure null se non ancora impostato. */
    val sha256OrNull: String?
        get() = if (hasExpectedHash) sha256.lowercase() else null

    val assetPath: String
        get() = "${ModelManifest.ASSET_DIR}/$fileName"

    private companion object {
        val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }
}
