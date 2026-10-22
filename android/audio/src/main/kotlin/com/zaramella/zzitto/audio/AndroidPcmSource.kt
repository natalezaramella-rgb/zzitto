package com.zaramella.zzitto.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Microfono tramite [AudioRecord]: 16 kHz, mono, PCM float (Spec §4.1, §5.2).
 * Aggiunto il 22/10/2026 alle 11:45.
 *
 * L'AudioRecord viene creato in [start] e rilasciato in [close]: tra una dettatura e l'altra
 * il microfono non resta aperto.
 *
 * Spec §5.7 (aggiunto il 22/10/2026 alle 17:44): [preferredMicKey] è la chiave del microfono scelto
 * nelle impostazioni ([MicrophoneCatalog.keyOf]); null = predefinito di sistema. Se il microfono
 * scelto non è collegato si usa il predefinito, senza errori.
 */
class AndroidPcmSource(
    private val context: Context,
    private val preferredMicKey: String? = null,
) : PcmSource {

    private var record: AudioRecord? = null

    /** Spec §5.7: true se questa sorgente ha impostato il dispositivo di comunicazione (Bluetooth). */
    private var communicationRouteSet = false

    // Il permesso viene verificato esplicitamente qui sotto prima di creare AudioRecord.
    @SuppressLint("MissingPermission")
    override fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw AudioCaptureException(AudioCaptureException.Reason.PERMISSION_DENIED, "permesso microfono assente")
        }
        val format = AudioFormat.Builder()
            .setSampleRate(AudioFormatSpec.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(
            AudioFormatSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            throw AudioCaptureException(AudioCaptureException.Reason.MICROPHONE_UNAVAILABLE, "formato non supportato")
        }
        // Almeno mezzo secondo di buffer per non perdere campioni se il thread rallenta.
        val bufferBytes = maxOf(minBytes, AudioFormatSpec.SAMPLE_RATE * Float.SIZE_BYTES / 2)
        val created = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: UnsupportedOperationException) {
            throw AudioCaptureException(AudioCaptureException.Reason.MICROPHONE_UNAVAILABLE, "AudioRecord non creato", e)
        } catch (e: SecurityException) {
            throw AudioCaptureException(AudioCaptureException.Reason.PERMISSION_DENIED, "permesso negato", e)
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            throw AudioCaptureException(AudioCaptureException.Reason.MICROPHONE_UNAVAILABLE, "AudioRecord non inizializzato")
        }
        // Spec §5.7: microfono scelto dall'utente, prima di avviare la registrazione.
        applyPreferredMicrophone(created)
        try {
            created.startRecording()
        } catch (e: IllegalStateException) {
            created.release()
            clearCommunicationRoute()
            throw AudioCaptureException(AudioCaptureException.Reason.MICROPHONE_UNAVAILABLE, "avvio non riuscito", e)
        }
        if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // Microfono occupato da un'altra app (chiamata, registratore).
            created.release()
            clearCommunicationRoute()
            throw AudioCaptureException(AudioCaptureException.Reason.MICROPHONE_UNAVAILABLE, "microfono occupato")
        }
        record = created
    }

    /**
     * Spec §5.7: indirizza [target] verso il microfono scelto, se è collegato. Ogni problema porta
     * silenziosamente al predefinito di sistema: la dettatura non fallisce per la preferenza.
     */
    private fun applyPreferredMicrophone(target: AudioRecord) {
        val key = MicrophoneCatalog.sanitizeKey(preferredMicKey) ?: return
        try {
            val directory = AndroidMicrophoneDirectory(context)
            val mic = MicrophoneCatalog.choose(key, directory.current()) ?: return
            val device = directory.deviceFor(mic) ?: return
            if (mic.kind.needsCommunicationRoute) routeCommunicationTo(device)
            target.setPreferredDevice(device)
        } catch (_: RuntimeException) {
            // predefinito di sistema; nessun log (Spec §10: nulla di superfluo nei log)
        }
    }

    /**
     * Spec §5.7: le cuffie Bluetooth (SCO e LE Audio) registrano solo se sono il dispositivo di
     * comunicazione. Si imposta con [AudioManager.setCommunicationDevice] (API 31+, minSdk 34) e si
     * annulla in [close].
     *
     * Limiti: non si tocca l'instradamento se è in corso una chiamata o una videochiamata (lo gestisce
     * l'altra app) o se il dispositivo è già quello di comunicazione; mentre si detta, l'audio di
     * sistema verso le cuffie passa al profilo "chiamata" (qualità telefonica); il collegamento SCO
     * richiede circa un secondo, per cui si attende al massimo [ROUTE_TIMEOUT_MS].
     */
    private fun routeCommunicationTo(input: AudioDeviceInfo) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_CALL_SCREENING
        ) {
            return
        }
        val candidates = am.availableCommunicationDevices.filter { it.type == input.type }
        val output = candidates.firstOrNull { input.address.isNotEmpty() && it.address == input.address }
            ?: candidates.firstOrNull { it.productName.toString() == input.productName.toString() }
            ?: candidates.singleOrNull()
            ?: return
        if (am.communicationDevice?.id == output.id) return

        val ready = CountDownLatch(1)
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (device?.id == output.id) ready.countDown()
        }
        val direct = Executor { it.run() }
        am.addOnCommunicationDeviceChangedListener(direct, listener)
        try {
            if (!am.setCommunicationDevice(output)) return
            communicationRouteSet = true
            if (am.communicationDevice?.id != output.id) {
                ready.await(ROUTE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            am.removeOnCommunicationDeviceChangedListener(listener)
        }
    }

    /** Spec §5.7: ripristina l'instradamento di sistema se l'aveva cambiato questa sorgente. */
    private fun clearCommunicationRoute() {
        if (!communicationRouteSet) return
        communicationRouteSet = false
        try {
            context.getSystemService(AudioManager::class.java)?.clearCommunicationDevice()
        } catch (_: RuntimeException) {
            // nulla da fare
        }
    }

    override fun read(buffer: FloatArray, offset: Int, length: Int): Int {
        val r = record ?: return -1
        return r.read(buffer, offset, length, AudioRecord.READ_BLOCKING)
    }

    override fun stop() {
        val r = record ?: return
        if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                r.stop()
            } catch (_: IllegalStateException) {
                // già fermo
            }
        }
    }

    override fun close() {
        record?.release()
        record = null
        clearCommunicationRoute() // Spec §5.7
    }

    private companion object {
        /** Attesa massima del collegamento Bluetooth prima di registrare (Spec §5.7). */
        const val ROUTE_TIMEOUT_MS = 1_500L
    }
}
