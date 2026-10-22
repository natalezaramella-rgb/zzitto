package com.zaramella.zzitto.licensing.trial

import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.licensing.LicensingConfig

/**
 * Regole pure (senza I/O) della prova di 30 giorni. Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 *
 * Difesa contro l'orologio spostato all'indietro (Spec §8.1):
 * il tempo "efficace" è il massimo tra l'ora attuale, l'ora più avanzata mai vista
 * ([TrialRecord.highWaterMillis]) e l'inizio della prova. Spostare l'orologio indietro quindi non
 * restituisce giorni: la prova resta ferma finché l'orologio non torna avanti.
 * Rischio accettato: se l'orologio viene portato molto avanti e poi corretto, i giorni consumati
 * restano consumati (caso raro, l'ora di rete è attiva per impostazione predefinita).
 */
object TrialPolicy {
    const val DAY_MILLIS: Long = 86_400_000L

    /** Intervallo minimo tra due scritture dell'high-water mark su Block Store (riduce le chiamate IPC). */
    const val ACCOUNT_WRITE_INTERVAL_MILLIS: Long = 3_600_000L

    /**
     * 01/01/2026 00:00 UTC. Un orologio che segna un istante precedente è sicuramente sbagliato
     * (l'app non esisteva): in quel caso la prova non viene registrata, per non fissare una data
     * di inizio assurda che farebbe scadere subito la prova quando l'orologio viene corretto.
     */
    const val MIN_PLAUSIBLE_EPOCH_MILLIS: Long = 1_767_225_600_000L

    fun isPlausible(nowMillis: Long): Boolean = nowMillis >= MIN_PLAUSIBLE_EPOCH_MILLIS

    /** Nuova prova che inizia adesso. */
    fun start(nowMillis: Long): TrialRecord = TrialRecord(nowMillis, nowMillis)

    /** Aggiorna l'high-water mark; non lo fa mai tornare indietro. */
    fun advance(record: TrialRecord, nowMillis: Long): TrialRecord =
        if (nowMillis > record.highWaterMillis) record.copy(highWaterMillis = nowMillis) else record

    /**
     * Unisce la copia locale e quella di Block Store: vince l'inizio più vecchio (la prova non
     * riparte reinstallando l'app) e l'high-water mark più avanzato.
     */
    fun merge(a: TrialRecord?, b: TrialRecord?): TrialRecord? = when {
        a == null -> b
        b == null -> a
        else -> TrialRecord(
            startMillis = minOf(a.startMillis, b.startMillis),
            highWaterMillis = maxOf(a.highWaterMillis, b.highWaterMillis),
        )
    }

    /** Giorni interi trascorsi dall'inizio, mai negativi. */
    fun elapsedDays(record: TrialRecord, nowMillis: Long): Long {
        val effectiveNow = maxOf(nowMillis, record.highWaterMillis, record.startMillis)
        return (effectiveNow - record.startMillis) / DAY_MILLIS
    }

    /**
     * Giorni rimanenti, da [LicensingConfig.TRIAL_DAYS] (giorno dell'installazione) a 1 (ultimo giorno).
     * Allo scoccare del 30° giorno intero trascorso la prova è scaduta.
     */
    fun daysLeft(record: TrialRecord, nowMillis: Long): Int {
        val left = LicensingConfig.TRIAL_DAYS - elapsedDays(record, nowMillis)
        return left.coerceAtLeast(0L).toInt()
    }

    /** Stato della licenza in base alla sola prova. Spec §8.1. */
    fun evaluate(record: TrialRecord, nowMillis: Long): LicenseState {
        val left = daysLeft(record, nowMillis)
        return if (left > 0) LicenseState.Trial(left) else LicenseState.TrialExpired
    }

    /** True se la copia su Block Store va riscritta. */
    fun needsAccountWrite(stored: TrialRecord?, current: TrialRecord): Boolean =
        stored == null ||
            stored.startMillis != current.startMillis ||
            current.highWaterMillis - stored.highWaterMillis >= ACCOUNT_WRITE_INTERVAL_MILLIS
}
