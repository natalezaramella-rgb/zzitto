package com.zaramella.zzitto.audio

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Formato richiesto dal motore (Spec §4.1, contratto zz_engine.h). Aggiunto il 22/10/2026 alle 11:45. */
object AudioFormatSpec {
    const val SAMPLE_RATE = 16_000
    const val CHUNK_MS = 100
    const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000
}

/** Sorgente di campioni PCM float mono a 16 kHz (AudioRecord in produzione, finta nei test). */
interface PcmSource : Closeable {
    fun start()

    /** Lettura bloccante; restituisce i campioni letti, 0 o un valore negativo in caso di errore. */
    fun read(buffer: FloatArray, offset: Int, length: Int): Int

    fun stop()
}

/** Rilevatore della fine del parlato (implementato con `zz_vad_*` del motore, Spec §5.2). */
interface EndOfSpeechDetector : Closeable {
    fun reset()

    /** True quando c'è stato parlato seguito dal silenzio configurato. */
    fun process(chunk: FloatArray, offset: Int, length: Int): Boolean

    fun hasSpeech(): Boolean
}

/** Parametri di una registrazione (Spec §5.2, §5.7). */
data class CaptureParams(
    /** Durata massima (predefinita 5 minuti). */
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    /** Se false lo stop avviene solo con il secondo tap o alla durata massima. */
    val autoStopOnSilence: Boolean = true,
) {
    init {
        require(maxDurationMs in 1_000..MAX_ALLOWED_DURATION_MS) { "durata massima fuori intervallo" }
    }

    val maxSamples: Int
        get() = (maxDurationMs * AudioFormatSpec.SAMPLE_RATE / 1000).toInt()

    companion object {
        const val DEFAULT_MAX_DURATION_MS = 5 * 60_000L
        const val MAX_ALLOWED_DURATION_MS = 15 * 60_000L
    }
}

enum class StopReason { MANUAL, END_OF_SPEECH, MAX_DURATION, SOURCE_ENDED }

/**
 * Audio registrato, solo in memoria. Chiamare [wipe] subito dopo la trascrizione (Spec §10).
 */
class CapturedAudio(
    samples: FloatArray,
    val stopReason: StopReason,
    val hadSpeech: Boolean,
) {
    private var _samples: FloatArray? = samples

    val samples: FloatArray
        get() = _samples ?: error("audio già distrutto")

    val sampleCount: Int
        get() = _samples?.size ?: 0

    val durationMs: Long
        get() = sampleCount * 1000L / AudioFormatSpec.SAMPLE_RATE

    /** Azzera i campioni (Spec §10). Idempotente. */
    fun wipe() {
        _samples?.fill(0f)
        _samples = null
    }

    override fun toString(): String = "CapturedAudio(ms=$durationMs, reason=$stopReason, hadSpeech=$hadSpeech)"
}

class AudioCaptureException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { PERMISSION_DENIED, MICROPHONE_UNAVAILABLE, READ_ERROR }
}

/**
 * Una sessione di registrazione: ciclo di lettura con stop manuale, stop automatico sul
 * silenzio (VAD del motore), durata massima e annullamento (Spec §5.2).
 * Aggiunto il 22/10/2026 alle 11:45.
 */
class AudioCaptureSession(
    private val sourceFactory: () -> PcmSource,
    private val detector: EndOfSpeechDetector?,
    private val params: CaptureParams,
    private val dispatcher: CoroutineDispatcher,
) {
    private val stopRequested = AtomicBoolean(false)
    private val _level = MutableStateFlow(0f)

    /** Livello RMS 0..1 dell'ultimo blocco, per l'animazione dell'icona (Spec §2.5). */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Secondo tap sull'icona: termina la registrazione e restituisce l'audio raccolto. */
    fun requestStop() {
        stopRequested.set(true)
    }

    /**
     * Registra fino allo stop. Se la coroutine viene annullata l'audio viene azzerato e si
     * propaga la CancellationException. Lancia [AudioCaptureException].
     */
    suspend fun record(): CapturedAudio = withContext(dispatcher) {
        val buffer = PcmBuffer(
            initialCapacity = minOf(params.maxSamples, AudioFormatSpec.SAMPLE_RATE * 30),
            maxSamples = params.maxSamples,
        )
        val chunk = FloatArray(AudioFormatSpec.CHUNK_SAMPLES)
        var reason = StopReason.MANUAL
        var success = false
        detector?.reset()
        val source = sourceFactory()
        try {
            source.start()
            while (true) {
                currentCoroutineContext().ensureActive()
                if (stopRequested.get()) {
                    reason = StopReason.MANUAL
                    break
                }
                val n = source.read(chunk, 0, chunk.size)
                if (n < 0) throw AudioCaptureException(AudioCaptureException.Reason.READ_ERROR, "lettura microfono: $n")
                if (n == 0) {
                    reason = StopReason.SOURCE_ENDED
                    break
                }
                buffer.append(chunk, 0, n)
                _level.value = rms(chunk, n)
                if (buffer.isFull) {
                    reason = StopReason.MAX_DURATION
                    break
                }
                if (params.autoStopOnSilence && detector != null && detector.process(chunk, 0, n)) {
                    reason = StopReason.END_OF_SPEECH
                    break
                }
            }
            val hadSpeech = detector?.hasSpeech() ?: true
            success = true
            CapturedAudio(buffer.toArray(), reason, hadSpeech)
        } finally {
            runCatching { source.stop() }
            runCatching { source.close() }
            chunk.fill(0f)
            buffer.wipe() // Spec §10: la copia restituita è l'unica rimasta.
            _level.value = 0f
            if (!success) stopRequested.set(false)
        }
    }

    private fun rms(samples: FloatArray, n: Int): Float {
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = samples[i]
            sum += s * s
        }
        return sqrt(sum / n).toFloat().coerceIn(0f, 1f)
    }
}
