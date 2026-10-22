package com.zaramella.zzitto.licensing.trial

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copia locale della data di inizio prova (fallback quando Block Store non è disponibile).
 * Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 *
 * Non è cifrata perché contiene solo due istanti, nessun dato personale (Spec §10). Una manomissione
 * su un dispositivo con root è un rischio accettato: la copia su Block Store vince comunque se più vecchia.
 */
@Singleton
class SharedPrefsTrialStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TrialStore {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun read(): TrialRecord? = withContext(Dispatchers.IO) {
        TrialRecord.decode(prefs.getString(KEY_RECORD, null))
    }

    override suspend fun write(record: TrialRecord) {
        withContext(Dispatchers.IO) {
            val ok = prefs.edit().putString(KEY_RECORD, record.encode()).commit()
            check(ok) { "Scrittura della prova locale non riuscita" }
        }
    }

    private companion object {
        const val PREFS_NAME = "zzitto_licensing"
        const val KEY_RECORD = "trial_record"
    }
}
