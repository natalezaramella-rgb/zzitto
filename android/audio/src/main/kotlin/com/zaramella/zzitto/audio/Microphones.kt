package com.zaramella.zzitto.audio

import android.media.AudioDeviceInfo

/**
 * Scelta del microfono nelle impostazioni (Spec §5.7, §4.1): modello puro, senza dipendenze dal
 * sistema, così chiave, nome visualizzato e scelta del dispositivo si verificano con i test JVM.
 * Aggiunto il 22/10/2026 alle 17:44.
 */

/** Famiglie di microfono proposte all'utente (Spec §5.7). Le altre sorgenti di ingresso sono escluse. */
enum class MicKind(val code: String) {
    BUILTIN("builtin"),
    WIRED_HEADSET("wired"),
    USB("usb"),
    BLUETOOTH_SCO("bt_sco"),
    BLUETOOTH_LE("bt_le"),
    ;

    /**
     * Le cuffie Bluetooth registrano solo con il collegamento "comunicazione" attivo
     * (AudioManager.setCommunicationDevice, vedi [AndroidPcmSource]). Spec §5.7.
     */
    val needsCommunicationRoute: Boolean
        get() = this == BLUETOOTH_SCO || this == BLUETOOTH_LE

    companion object {
        /**
         * Tipo di [AudioDeviceInfo] → famiglia; null per gli ingressi da non proporre (telefonia,
         * radio FM, remote submix, riferimento d'eco…). Spec §5.7.
         */
        fun fromDeviceType(type: Int): MicKind? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> BUILTIN
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> WIRED_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> USB
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> BLUETOOTH_SCO
            AudioDeviceInfo.TYPE_BLE_HEADSET -> BLUETOOTH_LE
            else -> null
        }

        fun fromCode(code: String): MicKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Un microfono disponibile in questo momento (Spec §5.7).
 *
 * [key] è la chiave persistente salvata nelle impostazioni (famiglia + nome prodotto + indirizzo):
 * non usa `AudioDeviceInfo.id`, che cambia a ogni collegamento. [systemId] è invece l'id volatile,
 * valido solo per trovare il dispositivo durante la sessione corrente.
 */
data class Microphone(
    val kind: MicKind,
    val productName: String,
    val address: String,
    val systemId: Int,
) {
    val key: String = MicrophoneCatalog.keyOf(kind, productName, address)
}

/**
 * Testi dei nomi visualizzati (Spec §5.7). I valori predefiniti sono in italiano; l'app li costruisce
 * dalle risorse stringa per seguire la lingua del sistema. `%1$s` = nome del dispositivo.
 */
data class MicrophoneLabels(
    val builtin: String = "Microfono del telefono",
    val wiredHeadset: String = "Cuffie con filo",
    val wiredHeadsetNamed: String = "Cuffie con filo: %1\$s",
    val usb: String = "Microfono USB",
    val usbNamed: String = "USB: %1\$s",
    val bluetooth: String = "Cuffie Bluetooth",
    val bluetoothNamed: String = "Cuffie Bluetooth: %1\$s",
) {
    companion object {
        val ITALIANO = MicrophoneLabels()
    }
}

/** Logica pura della scelta del microfono (Spec §5.7). */
object MicrophoneCatalog {

    private const val SEPARATOR = '|'

    /** Lunghezza massima di una chiave salvata, per non accettare valori anomali da DataStore. */
    const val MAX_KEY_LENGTH = 512

    /** Chiave persistente: `famiglia|nome prodotto|indirizzo`, con il separatore tolto dai campi. Spec §5.7. */
    fun keyOf(kind: MicKind, productName: String, address: String): String =
        listOf(kind.code, clean(productName), clean(address)).joinToString(SEPARATOR.toString())

    /** Famiglia e nome prodotto letti da una chiave salvata; null se la chiave non è valida. Spec §5.7. */
    fun parseKey(key: String): Triple<MicKind, String, String>? {
        val parts = key.split(SEPARATOR)
        if (parts.size != 3) return null
        val kind = MicKind.fromCode(parts[0]) ?: return null
        return Triple(kind, parts[1], parts[2])
    }

    /**
     * Converte i dati grezzi di un dispositivo in [Microphone]; null se il tipo non è un microfono da
     * proporre o se è un'uscita. Spec §5.7.
     */
    fun fromDevice(type: Int, isSource: Boolean, productName: CharSequence?, address: String?, systemId: Int): Microphone? {
        if (!isSource) return null
        val kind = MicKind.fromDeviceType(type) ?: return null
        return Microphone(kind, productName?.toString()?.trim().orEmpty(), address?.trim().orEmpty(), systemId)
    }

    /**
     * Elenco da mostrare: senza doppioni (stessa chiave) e ordinato per famiglia (telefono, cavo, USB,
     * Bluetooth), poi per nome. Spec §5.7.
     */
    fun normalize(mics: List<Microphone>): List<Microphone> =
        mics.distinctBy { it.key }.sortedWith(compareBy<Microphone>({ it.kind.ordinal }, { it.productName }, { it.address }))

    /** Nome visualizzato di un microfono, senza disambiguazione. Spec §5.7. */
    fun displayName(mic: Microphone, labels: MicrophoneLabels = MicrophoneLabels.ITALIANO): String {
        val name = mic.productName.takeIf { it.isNotBlank() && !isGenericName(it) }
        return when (mic.kind) {
            // Il nome prodotto del microfono integrato è il modello del telefono: non aggiunge nulla.
            MicKind.BUILTIN -> labels.builtin
            MicKind.WIRED_HEADSET -> name?.let { labels.wiredHeadsetNamed.format(it) } ?: labels.wiredHeadset
            MicKind.USB -> name?.let { labels.usbNamed.format(it) } ?: labels.usb
            MicKind.BLUETOOTH_SCO, MicKind.BLUETOOTH_LE ->
                name?.let { labels.bluetoothNamed.format(it) } ?: labels.bluetooth
        }
    }

    /**
     * Nomi visualizzati per chiave. Se due microfoni hanno lo stesso nome (per esempio i due microfoni
     * integrati "bottom" e "back") si aggiunge l'indirizzo, o un numero progressivo se manca. Spec §5.7.
     */
    fun displayNames(mics: List<Microphone>, labels: MicrophoneLabels = MicrophoneLabels.ITALIANO): Map<String, String> {
        val base = mics.associate { it.key to displayName(it, labels) }
        val counts = base.values.groupingBy { it }.eachCount()
        val seen = mutableMapOf<String, Int>()
        return mics.associate { mic ->
            val name = base.getValue(mic.key)
            if ((counts[name] ?: 0) <= 1) {
                mic.key to name
            } else {
                val n = (seen[name] ?: 0) + 1
                seen[name] = n
                val suffix = mic.address.takeIf { it.isNotBlank() && mic.kind == MicKind.BUILTIN } ?: n.toString()
                mic.key to "$name ($suffix)"
            }
        }
    }

    /**
     * Microfono da usare per la registrazione (Spec §5.7):
     * - nessuna preferenza → null (predefinito di sistema);
     * - chiave presente fra i disponibili → quel microfono;
     * - altrimenti, stessa famiglia e stesso nome prodotto se il riscontro è unico (l'indirizzo dei
     *   dispositivi USB cambia cambiando porta);
     * - altrimenti null: si usa il predefinito di sistema senza errori.
     */
    fun choose(preferredKey: String?, available: List<Microphone>): Microphone? {
        if (preferredKey.isNullOrBlank()) return null
        available.firstOrNull { it.key == preferredKey }?.let { return it }
        val (kind, product, _) = parseKey(preferredKey) ?: return null
        if (product.isBlank()) return null
        return available.filter { it.kind == kind && clean(it.productName) == product }.singleOrNull()
    }

    /** Chiave salvata ripulita: vuota o troppo lunga → null (predefinito di sistema). Spec §5.7. */
    fun sanitizeKey(key: String?): String? = key?.takeIf { it.isNotBlank() && it.length <= MAX_KEY_LENGTH }

    private fun clean(s: String): String = s.trim().replace(SEPARATOR, ' ')

    // Alcuni sistemi riportano come nome prodotto stringhe generiche senza valore per l'utente.
    private fun isGenericName(name: String): Boolean = name.equals("unknown", ignoreCase = true)
}
