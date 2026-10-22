package com.zaramella.zzitto.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Buffer audio in memoria (Spec §10). Aggiunto il 22/10/2026 alle 11:50. */
class PcmBufferTest {

    @Test
    fun `cresce e conserva l'ordine dei campioni`() {
        val buffer = PcmBuffer(initialCapacity = 4)
        val src = FloatArray(10) { it.toFloat() }
        assertEquals(10, buffer.append(src, 0, 10))
        assertEquals(3, buffer.append(src, 2, 3))
        val out = buffer.toArray()
        assertEquals(13, out.size)
        assertEquals(9f, out[9])
        assertEquals(2f, out[10])
        assertEquals(4f, out[12])
    }

    @Test
    fun `rispetta il limite massimo`() {
        val buffer = PcmBuffer(initialCapacity = 2, maxSamples = 5)
        assertEquals(5, buffer.append(FloatArray(8) { 1f }, 0, 8))
        assertTrue(buffer.isFull)
        assertEquals(0, buffer.append(FloatArray(3), 0, 3))
    }

    @Test
    fun `wipe svuota`() {
        val buffer = PcmBuffer(initialCapacity = 4)
        buffer.append(FloatArray(4) { 0.3f }, 0, 4)
        buffer.wipe()
        assertEquals(0, buffer.size)
        assertEquals(0, buffer.toArray().size)
    }
}
