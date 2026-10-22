package com.zaramella.zzitto.licensing.unlock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM con chiave generata e conservata nell'Android Keystore (non esportabile).
 * Spec §8.1 ("sblocco salvato in locale in modo cifrato"), §10. Aggiunto il 22/10/2026 alle 11:28.
 *
 * Formato del blob: IV (12 byte) || testo cifrato con tag GCM (16 byte).
 * Il tag GCM rende rilevabile qualsiasi manomissione; un file copiato da un altro dispositivo
 * (o ripristinato da backup) non si decifra perché la chiave non lascia il Keystore.
 */
@Singleton
class KeystoreCipher @Inject constructor() : LocalCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        check(iv.size == IV_SIZE) { "IV inatteso" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_SIZE + TAG_BITS / 8) { "Blob troppo corto" }
        val key = existingKey() ?: throw IllegalStateException("Chiave assente nel Keystore")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE))
        cipher.updateAAD(AAD)
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    @Synchronized
    private fun obtainKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zzitto_licensing_unlock_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        val AAD: ByteArray = "zzitto-licensing-unlock".toByteArray(Charsets.UTF_8)
    }
}
