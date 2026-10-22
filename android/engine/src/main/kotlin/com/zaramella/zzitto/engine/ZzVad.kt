package com.zaramella.zzitto.engine

import com.zaramella.zzitto.engine.jni.ZzNative
import java.io.Closeable

/**
 * Rilevamento della voce del motore (`zz_vad_*`) per lo stop automatico dopo il silenzio.
 * Spec §5.2. Aggiunto il 22/10/2026 alle 11:30.
 *
 * Non thread-safe: usarlo dal solo thread di cattura audio.
 */
class ZzVad(silenceMs: Int) : Closeable {

    private var ptr: Long

    init {
        require(silenceMs > 0) { "silenceMs deve essere positivo" }
        ZzNative.ensureLoaded()
        ptr = ZzNative.vadCreate(silenceMs)
        if (ptr == 0L) throw EngineException(EngineStatus.OUT_OF_MEMORY, "VAD non creato")
    }

    fun reset() {
        if (ptr != 0L) ZzNative.vadReset(ptr)
    }

    /** True quando c'è stato parlato seguito dal silenzio configurato. */
    fun process(pcm: FloatArray, offset: Int = 0, length: Int = pcm.size - offset): Boolean {
        require(offset >= 0 && length >= 0 && offset + length <= pcm.size) { "intervallo non valido" }
        if (ptr == 0L) return false
        return ZzNative.vadProcess(ptr, pcm, offset, length) == 1
    }

    fun hasSpeech(): Boolean = ptr != 0L && ZzNative.vadHasSpeech(ptr)

    override fun close() {
        val p = ptr
        ptr = 0L
        if (p != 0L) ZzNative.vadDestroy(p)
    }
}
