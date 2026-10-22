package com.zaramella.zzitto.ui

/** Lettura dell'elenco dei servizi di accessibilità attivi (Spec §5.1-bis). Aggiunto il 22/10/2026 alle 11:59. */
object EnabledServices {
    /** Il valore di sistema è un elenco separato da ':' di componenti (forma lunga o corta). */
    fun contains(list: String, longName: String, shortName: String): Boolean =
        list.split(':').any { it.equals(longName, ignoreCase = true) || it.equals(shortName, ignoreCase = true) }
}
