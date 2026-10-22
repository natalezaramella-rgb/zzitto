package com.zaramella.zzitto.dictation.service

import com.zaramella.zzitto.audio.EndOfSpeechDetector
import com.zaramella.zzitto.engine.ZzVad

/**
 * Stop automatico dopo il silenzio con il VAD del motore (Spec §5.2, contratto `zz_vad_*`).
 * Aggiunto il 22/10/2026 alle 11:59.
 */
class EngineVadDetector(silenceMs: Int) : EndOfSpeechDetector {

    private val vad = ZzVad(silenceMs)

    override fun reset() = vad.reset()

    override fun process(chunk: FloatArray, offset: Int, length: Int): Boolean = vad.process(chunk, offset, length)

    override fun hasSpeech(): Boolean = vad.hasSpeech()

    override fun close() = vad.close()
}
