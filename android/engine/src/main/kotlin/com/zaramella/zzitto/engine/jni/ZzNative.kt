package com.zaramella.zzitto.engine.jni

/**
 * Dichiarazioni JNI verso `libzz_engine_jni.so` (src/main/cpp/zz_engine_jni.cpp).
 * Spec §4.3. Aggiunto il 22/10/2026 alle 11:20.
 *
 * Convenzioni:
 *  - le stringhe passano come byte UTF-8;
 *  - i valori di ritorno `Int` sono codici `zz_status`;
 *  - i risultati delle operazioni vengono scritti in `outTexts` ([TEXT_RAW], [TEXT_FINAL])
 *    e `outNumbers` ([NUM_ACTIONS] … [NUM_MS_CORRECT]).
 *
 * Non usare direttamente: passare da [com.zaramella.zzitto.engine.ZzEngine].
 */
internal object ZzNative {
    const val TEXT_RAW = 0
    const val TEXT_FINAL = 1
    const val TEXT_COUNT = 2

    const val NUM_ACTIONS = 0
    const val NUM_USED_FALLBACK = 1
    const val NUM_FROM_SHORTCUT = 2
    const val NUM_MS_TRANSCRIBE = 3
    const val NUM_MS_CORRECT = 4
    const val NUM_COUNT = 5

    @Volatile
    private var libraryLoaded = false

    /** Carica la libreria nativa (e, come dipendenze, libzz_engine.so e libc++_shared.so). */
    fun ensureLoaded() {
        if (libraryLoaded) return
        synchronized(this) {
            if (!libraryLoaded) {
                System.loadLibrary("zz_engine_jni")
                libraryLoaded = true
            }
        }
    }

    @JvmStatic external fun apiVersion(): Int
    @JvmStatic external fun versionString(): ByteArray?
    @JvmStatic external fun statusString(status: Int): ByteArray?

    @JvmStatic external fun engineCreate(
        whisperPath: ByteArray,
        llmPath: ByteArray,
        whisperSha256: ByteArray?,
        llmSha256: ByteArray?,
        nThreads: Int,
        useGpu: Boolean,
        sequentialLoading: Boolean,
        outHandle: LongArray,
    ): Int

    @JvmStatic external fun engineDestroy(handle: Long)
    @JvmStatic external fun engineLoad(handle: Long): Int
    @JvmStatic external fun engineUnload(handle: Long): Int
    @JvmStatic external fun engineIsLoaded(handle: Long): Boolean

    @JvmStatic external fun setDictionary(handle: Long, words: Array<ByteArray>): Int
    @JvmStatic external fun setShortcuts(handle: Long, keywords: Array<ByteArray>, expansions: Array<ByteArray>): Int

    @JvmStatic external fun dictate(
        handle: Long,
        pcm: FloatArray,
        nSamples: Int,
        language: ByteArray,
        mode: Int,
        translate: Int,
        voiceCommands: Boolean,
        correctionTimeoutMs: Int,
        listener: NativeStageListener?,
        outTexts: Array<ByteArray?>,
        outNumbers: LongArray,
    ): Int

    @JvmStatic external fun rewrite(
        handle: Long,
        selectedText: ByteArray,
        pcm: FloatArray,
        nSamples: Int,
        language: ByteArray,
        mode: Int,
        translate: Int,
        voiceCommands: Boolean,
        correctionTimeoutMs: Int,
        listener: NativeStageListener?,
        outTexts: Array<ByteArray?>,
        outNumbers: LongArray,
    ): Int

    @JvmStatic external fun processText(
        handle: Long,
        rawText: ByteArray,
        language: ByteArray,
        mode: Int,
        translate: Int,
        voiceCommands: Boolean,
        correctionTimeoutMs: Int,
        outTexts: Array<ByteArray?>,
        outNumbers: LongArray,
    ): Int

    @JvmStatic external fun cancel(handle: Long)

    @JvmStatic external fun vadCreate(silenceMs: Int): Long
    @JvmStatic external fun vadDestroy(vad: Long)
    @JvmStatic external fun vadReset(vad: Long)
    @JvmStatic external fun vadProcess(vad: Long, pcm: FloatArray, offset: Int, length: Int): Int
    @JvmStatic external fun vadHasSpeech(vad: Long): Boolean

    @JvmStatic external fun sha256File(path: ByteArray, outHex: Array<ByteArray?>): Int
}

/** Callback chiamata dal C++ sul thread del motore (Spec §5.2). Nome e firma fissi: vedi consumer-rules.pro. */
fun interface NativeStageListener {
    fun onStage(stage: Int)
}
