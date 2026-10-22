package com.zaramella.zzitto.licensing.trial

/**
 * Supporto di persistenza della prova. Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 *
 * Due implementazioni: [SharedPrefsTrialStore] (locale) e [BlockStoreTrialStore] (account Google).
 */
interface TrialStore {
    /**
     * @return il record salvato, oppure null se non c'è nulla.
     * @throws Exception se il supporto non è disponibile (es. Google Play Services assente).
     */
    suspend fun read(): TrialRecord?

    /** @throws Exception se la scrittura fallisce. */
    suspend fun write(record: TrialRecord)
}
