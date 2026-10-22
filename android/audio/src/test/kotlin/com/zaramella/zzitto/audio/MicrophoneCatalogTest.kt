package com.zaramella.zzitto.audio

import android.media.AudioDeviceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Scelta del microfono (Spec §5.7). Aggiunto il 22/10/2026 alle 17:44. */
class MicrophoneCatalogTest {

    private val phoneBottom = Microphone(MicKind.BUILTIN, "Pixel 8", "bottom", systemId = 10)
    private val phoneBack = Microphone(MicKind.BUILTIN, "Pixel 8", "back", systemId = 11)
    private val headset = Microphone(MicKind.BLUETOOTH_SCO, "Jabra Elite", "AA:BB:CC:DD:EE:FF", systemId = 30)
    private val usb = Microphone(MicKind.USB, "Rode NT-USB", "card=1;device=0", systemId = 40)

    @Test
    fun `tipi di dispositivo proposti ed esclusi`() {
        assertEquals(MicKind.BUILTIN, MicKind.fromDeviceType(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(MicKind.WIRED_HEADSET, MicKind.fromDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(MicKind.USB, MicKind.fromDeviceType(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(MicKind.USB, MicKind.fromDeviceType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(MicKind.BLUETOOTH_SCO, MicKind.fromDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(MicKind.BLUETOOTH_LE, MicKind.fromDeviceType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertNull(MicKind.fromDeviceType(AudioDeviceInfo.TYPE_TELEPHONY))
        assertNull(MicKind.fromDeviceType(AudioDeviceInfo.TYPE_FM_TUNER))
        assertNull(MicKind.fromDeviceType(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
    }

    @Test
    fun `solo gli ingressi diventano microfoni`() {
        assertNull(MicrophoneCatalog.fromDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, isSource = false, "Jabra", "x", 1))
        val mic = MicrophoneCatalog.fromDevice(AudioDeviceInfo.TYPE_USB_HEADSET, isSource = true, " Rode ", null, 7)!!
        assertEquals(MicKind.USB, mic.kind)
        assertEquals("Rode", mic.productName)
        assertEquals("", mic.address)
        assertEquals(7, mic.systemId)
    }

    @Test
    fun `chiave persistente indipendente dall'id di sistema`() {
        val again = headset.copy(systemId = 99)
        assertEquals(headset.key, again.key)
        assertEquals("bt_sco|Jabra Elite|AA:BB:CC:DD:EE:FF", headset.key)
        assertEquals(Triple(MicKind.BLUETOOTH_SCO, "Jabra Elite", "AA:BB:CC:DD:EE:FF"), MicrophoneCatalog.parseKey(headset.key))
    }

    @Test
    fun `il separatore nei nomi non rompe la chiave`() {
        val odd = Microphone(MicKind.USB, "Mic|Pro", "a|b", 1)
        assertEquals(3, odd.key.split('|').size)
        assertEquals(MicKind.USB, MicrophoneCatalog.parseKey(odd.key)!!.first)
    }

    @Test
    fun `chiavi non valide`() {
        assertNull(MicrophoneCatalog.parseKey("qualcosa"))
        assertNull(MicrophoneCatalog.parseKey("xx|a|b"))
        assertNull(MicrophoneCatalog.sanitizeKey(""))
        assertNull(MicrophoneCatalog.sanitizeKey("  "))
        assertNull(MicrophoneCatalog.sanitizeKey("x".repeat(MicrophoneCatalog.MAX_KEY_LENGTH + 1)))
        assertEquals(usb.key, MicrophoneCatalog.sanitizeKey(usb.key))
    }

    @Test
    fun `nomi visualizzati in italiano`() {
        assertEquals("Microfono del telefono", MicrophoneCatalog.displayName(phoneBottom))
        assertEquals("Cuffie Bluetooth: Jabra Elite", MicrophoneCatalog.displayName(headset))
        assertEquals("USB: Rode NT-USB", MicrophoneCatalog.displayName(usb))
        assertEquals("Cuffie con filo", MicrophoneCatalog.displayName(Microphone(MicKind.WIRED_HEADSET, "", "", 1)))
        assertEquals("Cuffie Bluetooth", MicrophoneCatalog.displayName(Microphone(MicKind.BLUETOOTH_LE, "unknown", "", 1)))
        assertEquals("Microfono USB", MicrophoneCatalog.displayName(Microphone(MicKind.USB, " ", "", 1)))
    }

    @Test
    fun `testi personalizzati per altre lingue`() {
        val en = MicrophoneLabels(bluetoothNamed = "Bluetooth headset: %1\$s")
        assertEquals("Bluetooth headset: Jabra Elite", MicrophoneCatalog.displayName(headset, en))
    }

    @Test
    fun `nomi uguali vengono distinti`() {
        val names = MicrophoneCatalog.displayNames(listOf(phoneBottom, phoneBack, headset))
        assertEquals("Microfono del telefono (bottom)", names[phoneBottom.key])
        assertEquals("Microfono del telefono (back)", names[phoneBack.key])
        assertEquals("Cuffie Bluetooth: Jabra Elite", names[headset.key])

        val twoUsb = listOf(Microphone(MicKind.USB, "Mic", "card=1", 1), Microphone(MicKind.USB, "Mic", "card=2", 2))
        assertEquals(setOf("USB: Mic (1)", "USB: Mic (2)"), MicrophoneCatalog.displayNames(twoUsb).values.toSet())
    }

    @Test
    fun `elenco senza doppioni e in ordine di famiglia`() {
        val list = MicrophoneCatalog.normalize(listOf(headset, usb, phoneBottom, headset.copy(systemId = 31)))
        assertEquals(listOf(MicKind.BUILTIN, MicKind.USB, MicKind.BLUETOOTH_SCO), list.map { it.kind })
    }

    @Test
    fun `nessuna preferenza = predefinito di sistema`() {
        assertNull(MicrophoneCatalog.choose(null, listOf(phoneBottom, headset)))
        assertNull(MicrophoneCatalog.choose("", listOf(phoneBottom, headset)))
    }

    @Test
    fun `microfono scelto presente`() {
        val reconnected = headset.copy(systemId = 77)
        assertSame(reconnected, MicrophoneCatalog.choose(headset.key, listOf(phoneBottom, reconnected)))
    }

    @Test
    fun `microfono scelto assente = predefinito di sistema`() {
        assertNull(MicrophoneCatalog.choose(headset.key, listOf(phoneBottom, usb)))
        assertNull(MicrophoneCatalog.choose("chiave rovinata", listOf(phoneBottom, usb)))
    }

    @Test
    fun `USB su un'altra porta riconosciuto per nome se unico`() {
        val moved = usb.copy(address = "card=2;device=0", systemId = 41)
        assertSame(moved, MicrophoneCatalog.choose(usb.key, listOf(phoneBottom, moved)))
        val ambiguous = listOf(moved, usb.copy(address = "card=3;device=0", systemId = 42))
        assertNull(MicrophoneCatalog.choose(usb.key, ambiguous))
    }

    @Test
    fun `Bluetooth richiede il collegamento di comunicazione`() {
        assertTrue(MicKind.BLUETOOTH_SCO.needsCommunicationRoute)
        assertTrue(MicKind.BLUETOOTH_LE.needsCommunicationRoute)
        assertFalse(MicKind.BUILTIN.needsCommunicationRoute)
        assertFalse(MicKind.USB.needsCommunicationRoute)
    }
}
