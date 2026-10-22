package com.zaramella.zzitto.data.crypto

import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifratura della chiave del database (Spec §10: cronologia e dizionario cifrati con chiave
 * conservata nell'Android Keystore). Aggiunto il 22/10/2026 alle 11:59.
 *
 * Schema: una passphrase casuale di 32 byte apre il database SQLCipher; la passphrase è salvata
 * su file cifrata con AES-256-GCM da una chiave non esportabile dell'Android Keystore.
 */
interface KeyWrapper {
    fun wrap(plain: ByteArray): WrappedKey
    fun unwrap(wrapped: WrappedKey): ByteArray
}

class WrappedKey(val iv: ByteArray, val ciphertext: ByteArray)

/** AES-GCM con una chiave fornita dall'esterno (Keystore in produzione, JCE nei test). */
class AesGcmKeyWrapper(private val keyProvider: () -> SecretKey) : KeyWrapper {

    override fun wrap(plain: ByteArray): WrappedKey {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Il Keystore impone IV casuali generati dal cipher.
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        return WrappedKey(cipher.iv, cipher.doFinal(plain))
    }

    override fun unwrap(wrapped: WrappedKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, wrapped.iv))
        return cipher.doFinal(wrapped.ciphertext)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/**
 * Passphrase del database, salvata cifrata in [file] (in `noBackupFilesDir`, Spec §10).
 * Formato: versione (1 byte), lunghezza IV (1 byte), IV, testo cifrato.
 */
class DatabasePassphraseStore(
    private val file: File,
    private val wrapper: KeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Esito: la passphrase e se è stata appena creata (database da ricreare). */
    class Result(val passphrase: ByteArray, val created: Boolean)

    /**
     * Restituisce la passphrase esistente oppure ne crea una nuova. Se il file è illeggibile
     * (chiave del Keystore persa o file corrotto) ne crea una nuova e segnala [Result.created]:
     * il database precedente non è più apribile e va cancellato.
     */
    @Synchronized
    fun getOrCreate(): Result {
        read()?.let { return Result(it, created = false) }
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(random::nextBytes)
        write(wrapper.wrap(passphrase))
        return Result(passphrase.copyOf(), created = true)
    }

    private fun read(): ByteArray? {
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 2 || bytes[0] != FORMAT_VERSION) return null
            val ivLen = bytes[1].toInt() and 0xFF
            if (ivLen == 0 || bytes.size <= 2 + ivLen) return null
            val iv = bytes.copyOfRange(2, 2 + ivLen)
            val ct = bytes.copyOfRange(2 + ivLen, bytes.size)
            wrapper.unwrap(WrappedKey(iv, ct)).takeIf { it.size == PASSPHRASE_BYTES }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun write(wrapped: WrappedKey) {
        require(wrapped.iv.size in 1..255) { "IV non valido" }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(byteArrayOf(FORMAT_VERSION, wrapped.iv.size.toByte()) + wrapped.iv + wrapped.ciphertext)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "salvataggio della chiave non riuscito" }
        }
    }

    companion object {
        const val PASSPHRASE_BYTES = 32
        private const val FORMAT_VERSION: Byte = 1
    }
}
