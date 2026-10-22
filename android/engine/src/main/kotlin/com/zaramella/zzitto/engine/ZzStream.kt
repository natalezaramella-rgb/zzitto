package com.zaramella.zzitto.engine

/**
 * Flusso di dettatura aperto con [ZzEngine.beginStream] (`zz_stream_*`, API 2, Spec §11).
 * Aggiunto il 22/10/2026 alle 16:20.
 *
 * Il motore trascrive l'audio mentre l'utente parla; allo stop resta solo l'ultimo pezzo, quindi il
 * risultato arriva prima che con `zz_dictate` sullo stesso audio (risultato equivalente).
 *
 *  - [feed] è non bloccante e si chiama dal thread di registrazione;
 *  - [finish] (bloccante, eseguita sul thread del motore) o [abort] chiudono il flusso: si usa una sola
 *    delle due, una volta. Dopo la chiusura [feed] restituisce [EngineStatus.INVALID_ARG].
 *
 * Un lock interno impedisce che `zz_stream_feed` e `zz_stream_finish`/`zz_stream_abort` si sovrappongano
 * sullo stesso handle (il contratto libera il flusso alla chiusura).
 */
class ZzStream internal constructor(private val engine: ZzEngine, handle: Long) {

    private val lock = Any()

    /** Handle nativo; 0 dopo [finish] o [abort]. Protetto da [lock]. */
    private var handle: Long = handle

    @Volatile
    private var finishing = false

    /** True finché il flusso accetta audio. */
    val isOpen: Boolean
        get() = synchronized(lock) { handle != 0L }

    /**
     * Aggiunge audio PCM float32 mono a 16 kHz (Spec §4.1). Non bloccante: il motore copia i campioni e il
     * chiamante può riusare e azzerare subito il proprio buffer (Spec §10).
     */
    fun feed(pcm: FloatArray, offset: Int = 0, length: Int = pcm.size - offset): EngineStatus {
        require(offset >= 0 && length >= 0 && offset <= pcm.size - length) { "intervallo non valido" }
        synchronized(lock) {
            val h = handle
            if (h == 0L) return EngineStatus.INVALID_ARG
            if (length == 0) return EngineStatus.OK
            return engine.feedStream(h, pcm, offset, length)
        }
    }

    /**
     * Chiude il flusso e restituisce il risultato come [ZzEngine.dictate] (Spec §5.2–§5.12).
     * [onStage] viene chiamata sul thread del motore. Annullare la coroutine chiama `zz_cancel`.
     * Lancia [EngineException] (es. [EngineStatus.NO_SPEECH], [EngineStatus.CANCELLED]).
     */
    suspend fun finish(onStage: (EngineStage) -> Unit = {}): EngineResult {
        val h = take() ?: throw EngineException(EngineStatus.INVALID_ARG, "flusso già chiuso")
        finishing = true
        try {
            return engine.finishStream(this, h, onStage)
        } finally {
            finishing = false
        }
    }

    /**
     * Annulla il flusso senza risultato (Spec §5.2). Non bloccante e idempotente; durante [finish]
     * interrompe l'elaborazione con `zz_cancel`.
     */
    fun abort() {
        val h = take()
        if (h != null) {
            engine.abortStream(this, h)
        } else if (finishing) {
            engine.cancelRunning()
        }
    }

    private fun take(): Long? = synchronized(lock) {
        val h = handle
        handle = 0L
        if (h == 0L) null else h
    }

    override fun toString(): String = "ZzStream(open=$isOpen)"
}
