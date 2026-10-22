package com.zaramella.zzitto.licensing.unlock

/**
 * Cifratura locale dello sblocco. Spec §8.1, §10. Aggiunto il 22/10/2026 alle 11:28.
 * Implementazione di produzione: [KeystoreCipher] (chiave nell'Android Keystore).
 */
interface LocalCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** @throws Exception se il blob è manomesso, troncato o cifrato con un'altra chiave. */
    fun decrypt(blob: ByteArray): ByteArray
}
