package com.zaramella.zzitto.licensing

/**
 * Orologio iniettabile, così i test possono simulare il passare dei giorni e l'orologio spostato
 * all'indietro. Spec §8.1, §12. Aggiunto il 22/10/2026 alle 11:28.
 */
fun interface WallClock {
    /** Millisecondi dall'epoca Unix (UTC), come [System.currentTimeMillis]. */
    fun nowMillis(): Long
}

/** Orologio di sistema. */
object SystemWallClock : WallClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
