package com.zaramella.zzitto.audio

/**
 * Buffer di campioni float che cresce a blocchi e si azzera dopo l'uso.
 * Spec §10: l'audio resta solo in memoria e viene distrutto subito dopo l'uso.
 * Aggiunto il 22/10/2026 alle 11:45.
 *
 * Non thread-safe.
 */
class PcmBuffer(initialCapacity: Int = AudioFormatSpec.SAMPLE_RATE * 10, private val maxSamples: Int = Int.MAX_VALUE) {

    private var data = FloatArray(initialCapacity.coerceIn(1, maxSamples))

    var size: Int = 0
        private set

    val isFull: Boolean
        get() = size >= maxSamples

    /** Aggiunge al massimo i campioni che restano fino a [maxSamples]; restituisce quanti ne ha aggiunti. */
    fun append(src: FloatArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= src.size) { "intervallo non valido" }
        val n = minOf(length, maxSamples - size)
        if (n <= 0) return 0
        ensureCapacity(size + n)
        System.arraycopy(src, offset, data, size, n)
        size += n
        return n
    }

    /** Copia esatta dei campioni (il chiamante la azzera dopo l'uso). */
    fun toArray(): FloatArray = data.copyOf(size)

    /** Azzera e svuota il buffer. */
    fun wipe() {
        data.fill(0f)
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var newCapacity = data.size.toLong() * 2
        while (newCapacity < required) newCapacity *= 2
        val capped = newCapacity.coerceAtMost(maxSamples.toLong()).toInt()
        val bigger = data.copyOf(capped)
        data.fill(0f) // Spec §10: non lasciare copie dell'audio nel vecchio array.
        data = bigger
    }
}
