package com.zaramella.zzitto.engine

import com.zaramella.zzitto.engine.jni.NativeStageListener
import com.zaramella.zzitto.engine.jni.ZzNative

/**
 * Astrazione sottile sopra le funzioni native, a livello di handle.
 * Permette di provare [ZzEngine] con un'implementazione finta nei test JVM (Spec §12),
 * dove la libreria nativa arm64 non è caricabile.
 * Aggiunto il 22/10/2026 alle 11:25.
 */
interface EngineNative {
    fun apiVersion(): Int
    fun versionString(): String
    fun statusString(status: EngineStatus): String

    /** Crea un'istanza. Lancia [EngineException] in caso di errore. */
    fun create(config: EngineConfig): Long
    fun destroy(handle: Long)
    fun load(handle: Long): EngineStatus
    fun unload(handle: Long): EngineStatus
    fun isLoaded(handle: Long): Boolean
    fun setDictionary(handle: Long, words: List<String>): EngineStatus
    fun setShortcuts(handle: Long, shortcuts: List<VoiceShortcut>): EngineStatus

    fun dictate(
        handle: Long,
        pcm: FloatArray,
        sampleCount: Int,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit,
    ): NativeOutcome

    fun rewrite(
        handle: Long,
        selectedText: String,
        pcm: FloatArray,
        sampleCount: Int,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit,
    ): NativeOutcome

    fun processText(handle: Long, rawText: String, options: DictationOptions): NativeOutcome

    /** Thread-safe (`zz_cancel`). */
    fun cancel(handle: Long)
}

/** Esito di un'operazione: [result] è valorizzato solo con [EngineStatus.OK]. */
data class NativeOutcome(val status: EngineStatus, val result: EngineResult?)

/** Implementazione reale tramite JNI (Spec §4.3). */
internal class JniEngineNative : EngineNative {

    init {
        ZzNative.ensureLoaded()
    }

    override fun apiVersion(): Int = ZzNative.apiVersion()

    override fun versionString(): String = ZzNative.versionString().utf8()

    override fun statusString(status: EngineStatus): String = ZzNative.statusString(status.code).utf8()

    override fun create(config: EngineConfig): Long {
        val out = LongArray(1)
        val status = EngineStatus.fromCode(
            ZzNative.engineCreate(
                whisperPath = config.whisperModelPath.toByteArray(Charsets.UTF_8),
                llmPath = config.llmModelPath.toByteArray(Charsets.UTF_8),
                whisperSha256 = config.whisperSha256?.toByteArray(Charsets.UTF_8),
                llmSha256 = config.llmSha256?.toByteArray(Charsets.UTF_8),
                nThreads = config.threads,
                useGpu = config.useGpu,
                sequentialLoading = config.sequentialLoading,
                outHandle = out,
            ),
        )
        if (status != EngineStatus.OK || out[0] == 0L) {
            throw EngineException(if (status == EngineStatus.OK) EngineStatus.INTERNAL else status)
        }
        return out[0]
    }

    override fun destroy(handle: Long) = ZzNative.engineDestroy(handle)

    override fun load(handle: Long): EngineStatus = EngineStatus.fromCode(ZzNative.engineLoad(handle))

    override fun unload(handle: Long): EngineStatus = EngineStatus.fromCode(ZzNative.engineUnload(handle))

    override fun isLoaded(handle: Long): Boolean = ZzNative.engineIsLoaded(handle)

    override fun setDictionary(handle: Long, words: List<String>): EngineStatus {
        val arr = Array(words.size) { words[it].toByteArray(Charsets.UTF_8) }
        try {
            return EngineStatus.fromCode(ZzNative.setDictionary(handle, arr))
        } finally {
            arr.forEach { it.fill(0) }
        }
    }

    override fun setShortcuts(handle: Long, shortcuts: List<VoiceShortcut>): EngineStatus {
        val keys = Array(shortcuts.size) { shortcuts[it].keyword.toByteArray(Charsets.UTF_8) }
        val values = Array(shortcuts.size) { shortcuts[it].expansion.toByteArray(Charsets.UTF_8) }
        try {
            return EngineStatus.fromCode(ZzNative.setShortcuts(handle, keys, values))
        } finally {
            keys.forEach { it.fill(0) }
            values.forEach { it.fill(0) }
        }
    }

    override fun dictate(
        handle: Long,
        pcm: FloatArray,
        sampleCount: Int,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit,
    ): NativeOutcome {
        val texts = arrayOfNulls<ByteArray>(ZzNative.TEXT_COUNT)
        val numbers = LongArray(ZzNative.NUM_COUNT)
        val code = ZzNative.dictate(
            handle = handle,
            pcm = pcm,
            nSamples = sampleCount,
            language = options.language.toByteArray(Charsets.UTF_8),
            mode = options.mode.code,
            translate = options.translate.code,
            voiceCommands = options.voiceCommandsEnabled,
            correctionTimeoutMs = options.correctionTimeoutMs,
            listener = stageListener(onStage),
            outTexts = texts,
            outNumbers = numbers,
        )
        return outcome(code, texts, numbers)
    }

    override fun rewrite(
        handle: Long,
        selectedText: String,
        pcm: FloatArray,
        sampleCount: Int,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit,
    ): NativeOutcome {
        val texts = arrayOfNulls<ByteArray>(ZzNative.TEXT_COUNT)
        val numbers = LongArray(ZzNative.NUM_COUNT)
        val selected = selectedText.toByteArray(Charsets.UTF_8)
        try {
            val code = ZzNative.rewrite(
                handle = handle,
                selectedText = selected,
                pcm = pcm,
                nSamples = sampleCount,
                language = options.language.toByteArray(Charsets.UTF_8),
                mode = options.mode.code,
                translate = options.translate.code,
                voiceCommands = options.voiceCommandsEnabled,
                correctionTimeoutMs = options.correctionTimeoutMs,
                listener = stageListener(onStage),
                outTexts = texts,
                outNumbers = numbers,
            )
            return outcome(code, texts, numbers)
        } finally {
            selected.fill(0)
        }
    }

    override fun processText(handle: Long, rawText: String, options: DictationOptions): NativeOutcome {
        val texts = arrayOfNulls<ByteArray>(ZzNative.TEXT_COUNT)
        val numbers = LongArray(ZzNative.NUM_COUNT)
        val raw = rawText.toByteArray(Charsets.UTF_8)
        try {
            val code = ZzNative.processText(
                handle = handle,
                rawText = raw,
                language = options.language.toByteArray(Charsets.UTF_8),
                mode = options.mode.code,
                translate = options.translate.code,
                voiceCommands = options.voiceCommandsEnabled,
                correctionTimeoutMs = options.correctionTimeoutMs,
                outTexts = texts,
                outNumbers = numbers,
            )
            return outcome(code, texts, numbers)
        } finally {
            raw.fill(0)
        }
    }

    override fun cancel(handle: Long) = ZzNative.cancel(handle)

    private fun stageListener(onStage: (EngineStage) -> Unit) = NativeStageListener { code ->
        EngineStage.fromCode(code)?.let(onStage)
    }

    private fun outcome(code: Int, texts: Array<ByteArray?>, numbers: LongArray): NativeOutcome {
        val status = EngineStatus.fromCode(code)
        if (status != EngineStatus.OK) return NativeOutcome(status, null)
        val result = EngineResult(
            rawText = texts[ZzNative.TEXT_RAW].utf8(),
            finalText = texts[ZzNative.TEXT_FINAL].utf8(),
            actions = EngineAction.fromMask(numbers[ZzNative.NUM_ACTIONS].toInt()),
            usedFallback = numbers[ZzNative.NUM_USED_FALLBACK] != 0L,
            fromShortcut = numbers[ZzNative.NUM_FROM_SHORTCUT] != 0L,
            msTranscribe = numbers[ZzNative.NUM_MS_TRANSCRIBE],
            msCorrect = numbers[ZzNative.NUM_MS_CORRECT],
        )
        // Spec §10: azzera le copie in byte del testo dettato.
        texts.forEach { it?.fill(0) }
        return NativeOutcome(status, result)
    }

    private fun ByteArray?.utf8(): String = this?.toString(Charsets.UTF_8).orEmpty()
}
