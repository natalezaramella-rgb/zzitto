package com.zaramella.zzitto.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Microfono tramite [AudioRecord]: 16 kHz, mono, PCM float (Spec §4.1, §5.2).
 * Aggiunto il 22/10/2026 alle 11:45.
 *
 * L'AudioRecord viene creato in [start] e rilasciato in [close]: tra una dettatura e l'altra
 * il microfono non resta aperto.
 */
class AndroidPcmSource(private val context: Context) : PcmSource {

    private var record: AudioRecord? = null

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
        try {
            created.startRecording()
        } catch (e: IllegalStateException) {
            created.release()
            throw AudioCaptureException(AudioCaptureException.Reason.MICROPHONE_UNAVAILABLE, "avvio non riuscito", e)
        }
        if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            // Microfono occupato da un'altra app (chiamata, registratore).
            created.release()
            throw AudioCaptureException(AudioCaptureException.Reason.MICROPHONE_UNAVAILABLE, "microfono occupato")
        }
        record = created
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
    }
}
