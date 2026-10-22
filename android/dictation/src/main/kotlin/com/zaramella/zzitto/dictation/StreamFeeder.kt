package com.zaramella.zzitto.dictation

import com.zaramella.zzitto.audio.PcmChunkSink

/**
 * Ponte tra la cattura audio e il flusso del motore durante l'Ascolto (Spec §5.2, §11).
 * Aggiunto il 22/10/2026 alle 16:20.
 *
 * Il flusso si apre in parallelo alla registrazione (insieme al caricamento dei modelli): i blocchi che
 * arrivano prima vengono copiati in un arretrato e inoltrati appena il flusso è pronto, così il motore
 * riceve tutto l'audio, nell'ordine. Se il motore rifiuta un blocco il flusso viene scartato e il ciclo
 * ripiega su `zz_dictate` con l'audio registrato.
 *
 * [onChunk] gira sul thread di registrazione; [attach], [takeForFinish] e [abort] sul thread del ciclo.
 * Le copie dell'arretrato vengono azzerate appena inoltrate o scartate (Spec §10).
 */
internal class StreamFeeder : PcmChunkSink {

    private val lock = Any()
    private var stream: DictationStream? = null
    private val backlog = ArrayList<FloatArray>()
    private var closed = false
    private var failed = false

    /** True se un blocco è stato rifiutato dal motore. */
    val hasFailed: Boolean
        get() = synchronized(lock) { failed }

    override fun onChunk(samples: FloatArray, offset: Int, length: Int) {
        synchronized(lock) {
            if (closed || failed || length <= 0) return
            val s = stream
            if (s == null) {
                backlog += samples.copyOfRange(offset, offset + length)
            } else if (!s.feed(samples, offset, length)) {
                failed = true
            }
        }
    }

    /**
     * Collega il flusso appena aperto e gli inoltra l'arretrato. Restituisce false (e annulla il flusso)
     * se nel frattempo l'ascolto è già finito o è stato annullato.
     */
    fun attach(s: DictationStream): Boolean {
        synchronized(lock) {
            if (closed) {
                s.abort()
                return false
            }
            stream = s
            for (chunk in backlog) {
                if (!failed && !s.feed(chunk, 0, chunk.size)) failed = true
                chunk.fill(0f)
            }
            backlog.clear()
            return true
        }
    }

    /**
     * Allo stop: chiude l'inoltro e restituisce il flusso da completare, oppure null se non è stato aperto
     * o se un blocco è stato rifiutato (in quel caso il flusso viene annullato).
     */
    fun takeForFinish(): DictationStream? {
        synchronized(lock) {
            closed = true
            wipeBacklog()
            val s = stream
            stream = null
            if (failed) {
                s?.abort()
                return null
            }
            return s
        }
    }

    /** Annulla il flusso, se aperto, e scarta l'arretrato. Idempotente. */
    fun abort() {
        synchronized(lock) {
            closed = true
            wipeBacklog()
            stream?.abort()
            stream = null
        }
    }

    private fun wipeBacklog() {
        backlog.forEach { it.fill(0f) }
        backlog.clear()
    }
}
