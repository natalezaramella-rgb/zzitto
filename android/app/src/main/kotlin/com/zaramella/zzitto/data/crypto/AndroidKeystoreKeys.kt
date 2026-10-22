package com.zaramella.zzitto.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Chiave AES-256 non esportabile nell'Android Keystore. */
object AndroidKeystoreKeys {
    private const val PROVIDER = "AndroidKeyStore"

    fun getOrCreate(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Il servizio di accessibilità scrive la cronologia anche a schermo bloccato:
                // niente autenticazione utente richiesta per l'uso della chiave.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    fun delete(alias: String) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }
}
