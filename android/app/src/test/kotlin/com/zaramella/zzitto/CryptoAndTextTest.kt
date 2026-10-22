package com.zaramella.zzitto

import com.zaramella.zzitto.data.crypto.AesGcmKeyWrapper
import com.zaramella.zzitto.data.crypto.DatabasePassphraseStore
import com.zaramella.zzitto.ui.EnabledServices
import com.zaramella.zzitto.ui.licenses.LicenseBlock
import com.zaramella.zzitto.ui.licenses.LicensesText
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Cifratura della chiave del database (Spec §10), testo delle licenze (Spec §2.2) e
 * riconoscimento del servizio attivo (Spec §5.1-bis). Aggiunto il 22/10/2026 alle 11:59.
 */
class CryptoAndTextTest {

    @TempDir
    lateinit var dir: File

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `wrap e unwrap AES-GCM`() {
        val key = newKey()
        val wrapper = AesGcmKeyWrapper { key }
        val plain = ByteArray(32) { it.toByte() }
        val wrapped = wrapper.wrap(plain)
        assertFalse(wrapped.ciphertext.copyOf(32).contentEquals(plain))
        assertArrayEquals(plain, wrapper.unwrap(wrapped))
        // IV casuale: due cifrature dello stesso testo sono diverse.
        assertFalse(wrapper.wrap(plain).iv.contentEquals(wrapped.iv))
    }

    @Test
    fun `la passphrase viene creata una volta e poi riletta`() {
        val key = newKey()
        val file = File(dir, "db.key")
        val first = DatabasePassphraseStore(file, AesGcmKeyWrapper { key }).getOrCreate()
        assertTrue(first.created)
        assertEquals(DatabasePassphraseStore.PASSPHRASE_BYTES, first.passphrase.size)
        val second = DatabasePassphraseStore(file, AesGcmKeyWrapper { key }).getOrCreate()
        assertFalse(second.created)
        assertArrayEquals(first.passphrase, second.passphrase)
        // Il file non contiene la passphrase in chiaro.
        val raw = file.readBytes()
        assertFalse(raw.toList().windowed(32).any { it.toByteArray().contentEquals(first.passphrase) })
    }

    @Test
    fun `chiave del Keystore persa o file corrotto rigenerano la passphrase`() {
        val file = File(dir, "db.key")
        val first = DatabasePassphraseStore(file, AesGcmKeyWrapper { newKey() }).getOrCreate()
        val other = DatabasePassphraseStore(file, AesGcmKeyWrapper { newKey() }).getOrCreate()
        assertTrue(other.created)
        assertFalse(first.passphrase.contentEquals(other.passphrase))
        file.writeBytes(byteArrayOf(9, 9, 9))
        assertTrue(DatabasePassphraseStore(file, AesGcmKeyWrapper { newKey() }).getOrCreate().created)
    }

    @Test
    fun `parser delle licenze`() {
        val md = """
            # Licenze di terze parti

            Testo introduttivo
            su due righe con **enfasi** e [link](https://example.org).

            ## whisper.cpp
            - Licenza: MIT
            * Autori: ggml

            | Componente | Licenza |
            |---|---|
            | Room | Apache 2.0 |
        """.trimIndent()
        val blocks = LicensesText.parse(md)
        assertEquals(LicenseBlock.Heading(1, "Licenze di terze parti"), blocks[0])
        assertEquals(LicenseBlock.Paragraph("Testo introduttivo su due righe con enfasi e link (https://example.org)."), blocks[1])
        assertEquals(LicenseBlock.Heading(2, "whisper.cpp"), blocks[2])
        assertEquals(LicenseBlock.Bullet("Licenza: MIT"), blocks[3])
        assertEquals(LicenseBlock.Bullet("Autori: ggml"), blocks[4])
        assertEquals(LicenseBlock.Bullet("Componente · Licenza"), blocks[5])
        assertEquals(LicenseBlock.Bullet("Room · Apache 2.0"), blocks[6])
        assertEquals(7, blocks.size)
    }

    @Test
    fun `servizio di accessibilita riconosciuto nell'elenco di sistema`() {
        val long = "com.zaramella.zzitto/com.zaramella.zzitto.dictation.service.ZzittoAccessibilityService"
        val short = "com.zaramella.zzitto/.dictation.service.ZzittoAccessibilityService"
        assertTrue(EnabledServices.contains("com.other/.X:$long", long, short))
        assertTrue(EnabledServices.contains(short, long, short))
        assertFalse(EnabledServices.contains("com.other/.X", long, short))
        assertFalse(EnabledServices.contains("", long, short))
    }
}
