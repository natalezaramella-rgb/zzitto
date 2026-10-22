package com.zaramella.zzitto.licensing.unlock

import com.zaramella.zzitto.licensing.catchingNonCancellation

/**
 * Sblocco definitivo salvato in locale. Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 *
 * Una volta salvato, l'app non si collega più a internet per la licenza.
 */
interface UnlockStore {
    /** True se uno sblocco valido è presente. Non lancia eccezioni; con dati illeggibili restituisce false. */
    fun isUnlocked(): Boolean

    /** Salva lo sblocco. @return true se salvato e riletto correttamente. Non lancia eccezioni. */
    fun saveUnlocked(): Boolean
}

/**
 * Lo sblocco è un contenuto fisso ([payload]) cifrato con [LocalCipher]. Non si salvano il token
 * d'acquisto né altri dati dell'utente (Spec §10, minimizzazione). Chi non possiede la chiave del
 * Keystore non può creare un blob valido; un blob manomesso non supera la verifica GCM.
 */
class EncryptedUnlockStore(
    private val storage: BlobStorage,
    private val cipher: LocalCipher,
    private val payload: ByteArray,
) : UnlockStore {

    override fun isUnlocked(): Boolean {
        val blob = catchingNonCancellation { storage.read() }.getOrNull() ?: return false
        val plain = catchingNonCancellation { cipher.decrypt(blob) }.getOrNull() ?: return false
        return plain.contentEquals(payload)
    }

    override fun saveUnlocked(): Boolean {
        val written = catchingNonCancellation { storage.write(cipher.encrypt(payload)) }.isSuccess
        return written && isUnlocked()
    }

    companion object {
        /** Contenuto legato al pacchetto e al prodotto: un blob di un'altra app o prodotto non vale. */
        fun payloadFor(packageName: String, productId: String): ByteArray =
            "zzitto-unlock-v1|$packageName|$productId".toByteArray(Charsets.UTF_8)
    }
}
