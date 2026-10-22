package com.zaramella.zzitto.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Motore finto in Kotlin per i test JVM di [ZzEngine] (Spec §12).
 * Aggiunto il 22/10/2026 alle 11:35.
 */
class FakeEngineNative : EngineNative {
    var api = ZzEngine.EXPECTED_API_VERSION
    var createStatus = EngineStatus.OK
    var loadStatus = EngineStatus.OK
    var dictateStatus = EngineStatus.OK
    var result = EngineResult(
        rawText = "ehm ciao",
        finalText = "Ciao.",
        actions = emptySet(),
        usedFallback = false,
        fromShortcut = false,
        msTranscribe = 10,
        msCorrect = 20,
    )

    /** Se impostato, dictate() resta bloccata finché non arriva cancel() o il latch scende. */
    var blockUntilCancelled: CountDownLatch? = null
    val dictateStarted = CountDownLatch(1)

    val createCount = AtomicInteger()
    val loadCount = AtomicInteger()
    val unloadCount = AtomicInteger()
    val destroyCount = AtomicInteger()
    val cancelCount = AtomicInteger()
    val cancelled = AtomicBoolean(false)
    var loaded = false
    var lastConfig: EngineConfig? = null
    var lastOptions: DictationOptions? = null
    var lastSampleCount = -1
    var lastSelected: String? = null
    var dictionary: List<String> = emptyList()
    var shortcuts: List<VoiceShortcut> = emptyList()
    var dictionaryCalls = 0
    var callingThreads = mutableSetOf<String>()

    private fun record() {
        synchronized(callingThreads) { callingThreads += Thread.currentThread().name }
    }

    override fun apiVersion(): Int = api
    override fun versionString(): String = "fake"
    override fun statusString(status: EngineStatus): String = status.name

    override fun create(config: EngineConfig): Long {
        record()
        if (createStatus != EngineStatus.OK) throw EngineException(createStatus)
        createCount.incrementAndGet()
        lastConfig = config
        return 42L
    }

    override fun destroy(handle: Long) {
        destroyCount.incrementAndGet()
    }

    override fun load(handle: Long): EngineStatus {
        record()
        loadCount.incrementAndGet()
        if (loadStatus == EngineStatus.OK) loaded = true
        return loadStatus
    }

    override fun unload(handle: Long): EngineStatus {
        unloadCount.incrementAndGet()
        loaded = false
        return EngineStatus.OK
    }

    override fun isLoaded(handle: Long): Boolean = loaded

    override fun setDictionary(handle: Long, words: List<String>): EngineStatus {
        dictionaryCalls++
        dictionary = words
        return EngineStatus.OK
    }

    override fun setShortcuts(handle: Long, shortcuts: List<VoiceShortcut>): EngineStatus {
        this.shortcuts = shortcuts
        return EngineStatus.OK
    }

    override fun dictate(
        handle: Long,
        pcm: FloatArray,
        sampleCount: Int,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit,
    ): NativeOutcome {
        record()
        lastOptions = options
        lastSampleCount = sampleCount
        if (!loaded) return NativeOutcome(EngineStatus.NOT_LOADED, null)
        onStage(EngineStage.TRANSCRIBING)
        dictateStarted.countDown()
        blockUntilCancelled?.let { latch ->
            latch.await(5, TimeUnit.SECONDS)
            if (cancelled.getAndSet(false)) return NativeOutcome(EngineStatus.CANCELLED, null)
        }
        if (dictateStatus != EngineStatus.OK) return NativeOutcome(dictateStatus, null)
        onStage(EngineStage.CORRECTING)
        onStage(EngineStage.DONE)
        return NativeOutcome(EngineStatus.OK, result)
    }

    override fun rewrite(
        handle: Long,
        selectedText: String,
        pcm: FloatArray,
        sampleCount: Int,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit,
    ): NativeOutcome {
        lastSelected = selectedText
        return dictate(handle, pcm, sampleCount, options, onStage)
    }

    override fun processText(handle: Long, rawText: String, options: DictationOptions): NativeOutcome {
        lastOptions = options
        return NativeOutcome(dictateStatus, if (dictateStatus == EngineStatus.OK) result else null)
    }

    override fun cancel(handle: Long) {
        cancelCount.incrementAndGet()
        cancelled.set(true)
        blockUntilCancelled?.countDown()
        blockFinishUntilCancelled?.countDown()
    }

    // ---- Streaming (API 2, Spec §11). Aggiunto il 22/10/2026 alle 16:20. ----

    var streamBeginStatus = EngineStatus.OK
    var streamFeedStatus = EngineStatus.OK
    var streamFinishStatus = EngineStatus.OK

    /** Se impostato, streamFinish resta bloccata finché non arriva cancel() o il latch scende. */
    var blockFinishUntilCancelled: CountDownLatch? = null
    val finishStarted = CountDownLatch(1)

    /** Se impostato, streamBegin resta bloccata finché il latch non scende (non reagisce a cancel). */
    var blockBegin: CountDownLatch? = null
    val beginStarted = CountDownLatch(1)

    private var nextStream = 1000L

    /** Handle aperti: begin aggiunge, finish/abort tolgono (il contratto libera sempre il flusso). */
    val openStreams: MutableSet<Long> = java.util.Collections.synchronizedSet(mutableSetOf())
    val fedSamples = AtomicInteger()
    val feedThreads: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    val streamBeginCount = AtomicInteger()
    val streamFinishCount = AtomicInteger()
    val streamAbortCount = AtomicInteger()
    val abortLatch = CountDownLatch(1)
    var lastStreamOptions: DictationOptions? = null

    override fun streamBegin(handle: Long, options: DictationOptions): Long {
        record()
        streamBeginCount.incrementAndGet()
        lastStreamOptions = options
        if (!loaded) throw EngineException(EngineStatus.NOT_LOADED)
        beginStarted.countDown()
        blockBegin?.await(5, TimeUnit.SECONDS)
        if (streamBeginStatus != EngineStatus.OK) throw EngineException(streamBeginStatus)
        val h = synchronized(this) { nextStream++ }
        openStreams += h
        return h
    }

    override fun streamFeed(stream: Long, pcm: FloatArray, offset: Int, length: Int): EngineStatus {
        feedThreads += Thread.currentThread().name
        check(stream in openStreams) { "feed su un flusso chiuso" }
        if (streamFeedStatus != EngineStatus.OK) return streamFeedStatus
        fedSamples.addAndGet(length)
        return EngineStatus.OK
    }

    override fun streamFinish(stream: Long, onStage: (EngineStage) -> Unit): NativeOutcome {
        record()
        streamFinishCount.incrementAndGet()
        check(openStreams.remove(stream)) { "finish su un flusso chiuso" }
        onStage(EngineStage.TRANSCRIBING)
        finishStarted.countDown()
        blockFinishUntilCancelled?.let { latch ->
            latch.await(5, TimeUnit.SECONDS)
            if (cancelled.getAndSet(false)) return NativeOutcome(EngineStatus.CANCELLED, null)
        }
        if (streamFinishStatus != EngineStatus.OK) return NativeOutcome(streamFinishStatus, null)
        onStage(EngineStage.CORRECTING)
        onStage(EngineStage.DONE)
        return NativeOutcome(EngineStatus.OK, result)
    }

    override fun streamAbort(stream: Long) {
        record()
        check(openStreams.remove(stream)) { "abort su un flusso chiuso" }
        streamAbortCount.incrementAndGet()
        abortLatch.countDown()
    }
}
