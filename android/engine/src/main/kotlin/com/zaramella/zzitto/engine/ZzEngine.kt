package com.zaramella.zzitto.engine

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** Fornisce i percorsi dei modelli pronti e verificati (Spec §7). */
fun interface EngineConfigProvider {
    suspend fun engineConfig(): EngineConfig
}

/**
 * Wrapper Kotlin del motore, adatto alle coroutine.
 * Spec §4.2, §4.3. Aggiunto il 22/10/2026 alle 11:25.
 *
 *  - Tutte le chiamate native girano su un solo thread dedicato in background (Spec §4.2),
 *    così il contratto "una sola operazione alla volta" è rispettato per costruzione.
 *  - L'annullamento della coroutine chiama `zz_cancel` sull'operazione in corso (Spec §5.2).
 *  - I modelli si caricano alla prima dettatura ([ensureLoaded]) e si scaricano con [unload]
 *    quando la piattaforma rileva l'inattività (Spec §4.2).
 *  - Il dizionario e le scorciatoie vengono memorizzati e riapplicati a ogni nuova istanza.
 */
class ZzEngine(
    private val native: EngineNative,
    private val configProvider: EngineConfigProvider,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        // Stack ampio: il codice nativo di inferenza gira su questo thread.
        Thread(null, runnable, "zz-engine", ENGINE_THREAD_STACK_BYTES).apply { isDaemon = true }
    },
) : Closeable {

    /** Handle nativo; letto e scritto solo dal thread del motore, tranne [cancelRunning]. */
    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var closed = false

    private var dictionary: List<String> = emptyList()
    private var shortcuts: List<VoiceShortcut> = emptyList()

    private val _loaded = MutableStateFlow(false)

    /** True quando i modelli sono in memoria. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Versione dichiarata dalla libreria nativa (motore e modelli attesi). */
    suspend fun versionString(): String = runOnEngine { native.versionString() }

    /**
     * Crea l'istanza se serve, verifica gli hash e carica i modelli (Spec §7). Idempotente.
     * Può richiedere tempo alla prima chiamata: chiamarla appena l'utente avvia l'ascolto,
     * in parallelo alla registrazione, per rispettare l'obiettivo dei 300 ms (Spec §11).
     */
    suspend fun ensureLoaded() {
        val config = if (handle == 0L) configProvider.engineConfig() else null
        runOnEngine {
            if (handle == 0L) {
                val cfg = config ?: throw EngineException(EngineStatus.INTERNAL, "configurazione mancante")
                val api = native.apiVersion()
                if (api != EXPECTED_API_VERSION) {
                    throw EngineException(EngineStatus.INTERNAL, "API motore $api, attesa $EXPECTED_API_VERSION")
                }
                val created = native.create(cfg)
                handle = created
                applyPersonalizationOnEngineThread(created)
            }
            check(native.load(handle))
            _loaded.value = true
        }
    }

    /** Scarica i modelli dalla RAM (Spec §4.2). L'istanza resta valida. */
    suspend fun unload() {
        runOnEngine {
            if (handle != 0L) check(native.unload(handle))
            _loaded.value = false
        }
    }

    /** Dizionario personale (Spec §5.5). Sostituisce il precedente. */
    suspend fun setDictionary(words: List<String>) {
        val copy = words.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        runOnEngine {
            dictionary = copy
            if (handle != 0L) check(native.setDictionary(handle, copy))
        }
    }

    /** Scorciatoie vocali (Spec §5.11). Sostituisce le precedenti. */
    suspend fun setShortcuts(items: List<VoiceShortcut>) {
        val copy = items.filter { it.keyword.isNotBlank() && it.expansion.isNotEmpty() }
            .map { VoiceShortcut(it.keyword.trim(), it.expansion) }
        runOnEngine {
            shortcuts = copy
            if (handle != 0L) check(native.setShortcuts(handle, copy))
        }
    }

    /**
     * Dettatura completa: trascrizione, comandi, scorciatoie, correzione o traduzione (Spec §5.2–§5.12).
     * [onStage] viene chiamata sul thread del motore.
     * Lancia [EngineException] (es. [EngineStatus.NO_SPEECH], [EngineStatus.CANCELLED]).
     */
    suspend fun dictate(
        pcm: FloatArray,
        sampleCount: Int = pcm.size,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit = {},
    ): EngineResult {
        require(sampleCount in 0..pcm.size) { "sampleCount fuori intervallo" }
        ensureLoaded()
        return runOnEngine {
            unwrap(native.dictate(handle, pcm, sampleCount, options, onStage))
        }
    }

    /** Riscrittura a voce del testo selezionato (Spec §5.10). */
    suspend fun rewrite(
        selectedText: String,
        pcm: FloatArray,
        sampleCount: Int = pcm.size,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit = {},
    ): EngineResult {
        require(sampleCount in 0..pcm.size) { "sampleCount fuori intervallo" }
        require(selectedText.isNotEmpty()) { "selezione vuota" }
        ensureLoaded()
        return runOnEngine {
            unwrap(native.rewrite(handle, selectedText, pcm, sampleCount, options, onStage))
        }
    }

    /** Variante testuale (istruzioni già trascritte, test). */
    suspend fun processText(rawText: String, options: DictationOptions): EngineResult {
        ensureLoaded()
        return runOnEngine { unwrap(native.processText(handle, rawText, options)) }
    }

    /** Annulla l'operazione in corso, se c'è (thread-safe). */
    fun cancelRunning() {
        val h = handle
        if (h != 0L) native.cancel(h)
    }

    /** Distrugge l'istanza nativa e ferma il thread. Non riutilizzabile dopo. */
    override fun close() {
        if (closed) return
        closed = true
        cancelRunning()
        try {
            executor.submit(
                Runnable {
                    val h = handle
                    if (h != 0L) native.destroy(h)
                    handle = 0L
                    _loaded.value = false
                },
            )
        } catch (_: RejectedExecutionException) {
            // già chiuso
        }
        executor.shutdown()
    }

    private fun applyPersonalizationOnEngineThread(h: Long) {
        if (dictionary.isNotEmpty()) check(native.setDictionary(h, dictionary))
        if (shortcuts.isNotEmpty()) check(native.setShortcuts(h, shortcuts))
    }

    private fun check(status: EngineStatus) {
        if (status != EngineStatus.OK) throw EngineException(status)
    }

    private fun unwrap(outcome: NativeOutcome): EngineResult {
        if (outcome.status != EngineStatus.OK) throw EngineException(outcome.status)
        return outcome.result ?: throw EngineException(EngineStatus.INTERNAL, "risultato mancante")
    }

    /**
     * Esegue [block] sul thread del motore. Se la coroutine viene annullata mentre il blocco
     * è in esecuzione si chiama `zz_cancel`; se è ancora in coda il blocco non parte.
     */
    private suspend fun <T> runOnEngine(block: () -> T): T {
        if (closed) throw EngineException(EngineStatus.INTERNAL, "motore chiuso")
        return suspendCancellableCoroutine { cont ->
            val state = AtomicInteger(TASK_QUEUED)
            val future: Future<*> = try {
                executor.submit(
                    Runnable {
                        if (!state.compareAndSet(TASK_QUEUED, TASK_RUNNING)) return@Runnable
                        val result = runCatching(block)
                        state.set(TASK_DONE)
                        cont.resumeWith(result)
                    },
                )
            } catch (e: RejectedExecutionException) {
                cont.resumeWith(Result.failure(EngineException(EngineStatus.INTERNAL, "motore chiuso")))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                if (state.compareAndSet(TASK_QUEUED, TASK_DONE)) {
                    future.cancel(false)
                } else if (state.get() == TASK_RUNNING) {
                    cancelRunning()
                }
            }
        }
    }

    companion object {
        const val EXPECTED_API_VERSION = 1
        private const val ENGINE_THREAD_STACK_BYTES = 8L * 1024 * 1024
        private const val TASK_QUEUED = 0
        private const val TASK_RUNNING = 1
        private const val TASK_DONE = 2
    }
}
