package com.zaramella.zzitto.licensing.trial

/**
 * Dati persistiti della prova. Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 *
 * @property startMillis istante di inizio della prova (epoca Unix, UTC).
 * @property highWaterMillis istante più avanzato mai osservato dall'orologio: serve a impedire che
 *   spostare l'orologio all'indietro restituisca giorni di prova.
 *
 * Non contiene dati personali: solo due istanti (Spec §10, minimizzazione dei dati).
 */
data class TrialRecord(val startMillis: Long, val highWaterMillis: Long) {

    /** Formato testuale compatto, uguale per la copia locale e per Block Store (massimo 4 KB). */
    fun encode(): String = "$FORMAT_V1;$startMillis;$highWaterMillis"

    companion object {
        private const val FORMAT_V1 = "v1"

        /** Restituisce null se il testo è assente, illeggibile o incoerente. */
        fun decode(text: String?): TrialRecord? {
            if (text == null) return null
            val parts = text.split(';')
            if (parts.size != 3 || parts[0] != FORMAT_V1) return null
            val start = parts[1].toLongOrNull() ?: return null
            val highWater = parts[2].toLongOrNull() ?: return null
            if (start <= 0L || highWater <= 0L) return null
            return TrialRecord(start, maxOf(start, highWater))
        }
    }
}
