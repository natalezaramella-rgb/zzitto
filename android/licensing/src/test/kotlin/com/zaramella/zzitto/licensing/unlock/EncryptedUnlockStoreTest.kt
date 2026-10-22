package com.zaramella.zzitto.licensing.unlock

import com.zaramella.zzitto.licensing.InMemoryBlob
import com.zaramella.zzitto.licensing.SoftwareGcmCipher
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Spec §8.1, §10, §12: persistenza cifrata dello sblocco. Aggiunto il 22/10/2026 alle 11:28.
class EncryptedUnlockStoreTest {

    private val payload = EncryptedUnlockStore.payloadFor("com.zaramella.zzitto", "zzitto_unlock")
    private val blob = InMemoryBlob()
    private val cipher = SoftwareGcmCipher()

    @Test
    fun `senza sblocco salvato`() {
        assertFalse(EncryptedUnlockStore(blob, cipher, payload).isUnlocked())
    }

    @Test
    fun `lo sblocco salvato persiste tra due istanze`() {
        assertTrue(EncryptedUnlockStore(blob, cipher, payload).saveUnlocked())
        assertTrue(EncryptedUnlockStore(blob, cipher, payload).isUnlocked())
    }

    @Test
    fun `il contenuto su disco non e in chiaro`() {
        EncryptedUnlockStore(blob, cipher, payload).saveUnlocked()
        val stored = blob.bytes!!
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("zzitto-unlock"))
        assertArrayEquals(payload, cipher.decrypt(stored))
    }

    @Test
    fun `blob manomesso non vale`() {
        EncryptedUnlockStore(blob, cipher, payload).saveUnlocked()
        val bytes = blob.bytes!!
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        blob.bytes = bytes
        assertFalse(EncryptedUnlockStore(blob, cipher, payload).isUnlocked())
    }

    @Test
    fun `blob troncato o casuale non vale`() {
        blob.bytes = byteArrayOf(1, 2, 3)
        assertFalse(EncryptedUnlockStore(blob, cipher, payload).isUnlocked())
    }

    @Test
    fun `blob cifrato con un'altra chiave non vale (copia da un altro dispositivo)`() {
        EncryptedUnlockStore(blob, SoftwareGcmCipher(), payload).saveUnlocked()
        assertFalse(EncryptedUnlockStore(blob, cipher, payload).isUnlocked())
    }

    @Test
    fun `blob di un altro prodotto non vale`() {
        val other = EncryptedUnlockStore.payloadFor("com.zaramella.zzitto", "altro")
        EncryptedUnlockStore(blob, cipher, other).saveUnlocked()
        assertFalse(EncryptedUnlockStore(blob, cipher, payload).isUnlocked())
    }

    @Test
    fun `errore di scrittura non lancia eccezioni`() {
        val failing = object : BlobStorage {
            override fun read(): ByteArray? = null
            override fun write(bytes: ByteArray) {
                throw java.io.IOException("disco pieno")
            }
        }
        assertFalse(EncryptedUnlockStore(failing, cipher, payload).saveUnlocked())
    }
}
