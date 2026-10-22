package com.zaramella.zzitto.dictation

import com.zaramella.zzitto.engine.DictationOptions
import com.zaramella.zzitto.engine.EngineResult
import com.zaramella.zzitto.engine.EngineStage
import com.zaramella.zzitto.engine.EngineStatus
import com.zaramella.zzitto.engine.TranslateTarget
import com.zaramella.zzitto.engine.VoiceShortcut
import com.zaramella.zzitto.engine.ZzEngine
import com.zaramella.zzitto.engine.ZzStream
import kotlinx.coroutines.flow.Flow

/**
 * Interfacce tra il modulo :dictation e il modulo :app, che le implementa con il database
 * cifrato (Spec §5.5, §5.6, §5.11, §10) e le collega con Hilt.
 * Aggiunto il 22/10/2026 alle 11:59.
 */

/** Dizionario personale e scorciatoie vocali, letti dal database cifrato. */
interface PersonalizationSource {
    val dictionary: Flow<List<String>>
    val shortcuts: Flow<List<VoiceShortcut>>
}

/** Una voce della cronologia (Spec §5.6). Contiene testo dettato: solo nel database cifrato. */
data class HistoryRecord(
    val rawText: String,
    val finalText: String,
    val rewrite: Boolean,
    val translate: TranslateTarget,
    val timestampMillis: Long,
) {
    override fun toString(): String =
        "HistoryRecord(rawLen=${rawText.length}, finalLen=${finalText.length}, rewrite=$rewrite, translate=$translate)"
}

/** Destinazione della cronologia; chiamata solo se la cronologia è attiva. */
interface DictationHistorySink {
    suspend fun record(entry: HistoryRecord)
}

/** Motore visto dal ciclo di dettatura; astratto per i test (Spec §12). */
interface DictationEngine {
    /** Carica i modelli; chiamata in parallelo all'ascolto (Spec §11: avvio < 300 ms). */
    suspend fun prepare()

    suspend fun dictate(
        pcm: FloatArray,
        sampleCount: Int,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit,
    ): EngineResult

    suspend fun rewrite(
        selectedText: String,
        pcm: FloatArray,
        sampleCount: Int,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit,
    ): EngineResult

    /**
     * Apre un flusso di dettatura in streaming (Spec §11): il motore trascrive mentre l'utente parla.
     * Lancia un'eccezione se il flusso non si apre; il ciclo ripiega allora su [dictate].
     * Aggiunto il 22/10/2026 alle 16:20.
     */
    suspend fun beginStream(options: DictationOptions): DictationStream

    /** Scarica i modelli dalla RAM dopo l'inattività (Spec §4.2). */
    suspend fun unload()
}

/**
 * Flusso aperto con [DictationEngine.beginStream] (Spec §11). Aggiunto il 22/10/2026 alle 16:20.
 * Si chiude una sola volta, con [finish] oppure [abort].
 */
interface DictationStream {
    /**
     * Inoltra un blocco audio; chiamata dal thread di registrazione, non bloccante. Il chiamante può
     * azzerare il blocco subito dopo (Spec §10). Restituisce false se il motore l'ha rifiutato.
     */
    fun feed(pcm: FloatArray, offset: Int, length: Int): Boolean

    /** Allo stop: trascrive l'ultimo pezzo, corregge e restituisce il risultato come [DictationEngine.dictate]. */
    suspend fun finish(onStage: (EngineStage) -> Unit): EngineResult

    /** Annulla senza risultato (Spec §5.2). Non bloccante e idempotente. */
    fun abort()
}

/** Adattatore sul wrapper reale del motore. */
class ZzDictationEngine(private val engine: ZzEngine) : DictationEngine {
    override suspend fun prepare() = engine.ensureLoaded()

    override suspend fun dictate(
        pcm: FloatArray,
        sampleCount: Int,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit,
    ): EngineResult = engine.dictate(pcm, sampleCount, options, onStage)

    override suspend fun rewrite(
        selectedText: String,
        pcm: FloatArray,
        sampleCount: Int,
        options: DictationOptions,
        onStage: (EngineStage) -> Unit,
    ): EngineResult = engine.rewrite(selectedText, pcm, sampleCount, options, onStage)

    override suspend fun beginStream(options: DictationOptions): DictationStream =
        ZzDictationStream(engine.beginStream(options))

    override suspend fun unload() = engine.unload()
}

/** Adattatore sul flusso reale del motore (Spec §11). Aggiunto il 22/10/2026 alle 16:20. */
class ZzDictationStream(private val stream: ZzStream) : DictationStream {
    override fun feed(pcm: FloatArray, offset: Int, length: Int): Boolean =
        stream.feed(pcm, offset, length) == EngineStatus.OK

    override suspend fun finish(onStage: (EngineStage) -> Unit): EngineResult = stream.finish(onStage)

    override fun abort() = stream.abort()
}

/** Azioni (intent) che il servizio usa per aprire le schermate dell'app. */
object ZzittoIntents {
    /** Schermata di acquisto (Spec §8.1). */
    const val ACTION_OPEN_PURCHASE = "com.zaramella.zzitto.action.OPEN_PURCHASE"

    /** Schermata delle impostazioni (Spec §2.5, §5.7). */
    const val ACTION_OPEN_SETTINGS = "com.zaramella.zzitto.action.OPEN_SETTINGS"
}
