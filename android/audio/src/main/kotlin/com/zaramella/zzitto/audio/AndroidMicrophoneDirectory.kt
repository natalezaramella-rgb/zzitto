package com.zaramella.zzitto.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Elenco dei microfoni disponibili (Spec §5.7). Interfaccia per poter sostituire il sistema nei test.
 * Aggiunto il 22/10/2026 alle 17:44.
 */
interface MicrophoneDirectory {
    /** Microfoni collegati adesso, già filtrati e ordinati ([MicrophoneCatalog.normalize]). */
    fun current(): List<Microphone>

    /** Elenco aggiornato a ogni collegamento o scollegamento di un dispositivo audio. */
    fun observe(): Flow<List<Microphone>>
}

/**
 * Implementazione con [AudioManager.getDevices] e [AudioDeviceCallback] (Spec §5.7).
 * Aggiunto il 22/10/2026 alle 17:44.
 *
 * L'elenco dei dispositivi non richiede il permesso del microfono e non apre l'audio (Spec §10).
 */
class AndroidMicrophoneDirectory(context: Context) : MicrophoneDirectory {

    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    override fun current(): List<Microphone> = MicrophoneCatalog.normalize(
        inputDevices().mapNotNull { it.toMicrophone() },
    )

    /** Dispositivo di sistema corrispondente a [mic], se ancora collegato. Spec §5.7. */
    fun deviceFor(mic: Microphone): AudioDeviceInfo? = inputDevices().firstOrNull { it.id == mic.systemId }

    override fun observe(): Flow<List<Microphone>> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                trySend(current())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                trySend(current())
            }
        }
        // Spec §5.7: il sistema chiama subito onAudioDevicesAdded con i dispositivi presenti,
        // ma si invia comunque l'elenco iniziale per non dipendere da quel comportamento.
        trySend(current())
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { audioManager.unregisterAudioDeviceCallback(callback) }
    }.conflate().distinctUntilChanged()

    private fun inputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    private fun AudioDeviceInfo.toMicrophone(): Microphone? =
        MicrophoneCatalog.fromDevice(type, isSource, productName, address, id)
}
