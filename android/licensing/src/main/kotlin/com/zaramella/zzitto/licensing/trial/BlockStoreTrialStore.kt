package com.zaramella.zzitto.licensing.trial

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.BlockstoreClient
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.zaramella.zzitto.licensing.catchingNonCancellation
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data di inizio prova salvata con la Block Store API di Google. Spec §8.1.
 * Aggiunto il 22/10/2026 alle 11:28.
 *
 * Block Store conserva fino a 16 voci da 4 KB per app, legate all'account Google, e le mantiene dopo
 * disinstallazione e reinstallazione (se il backup Google è attivo) e nel ripristino su un nuovo
 * dispositivo. Nessun server proprio. Rischio residuo accettato: reset di fabbrica senza ripristino,
 * cambio di account Google, backup Google disattivato.
 *
 * Il backup su cloud si attiva SOLO se è disponibile la cifratura end-to-end ([CloudBackupPolicy]);
 * altrimenti il dato resta solo sul dispositivo (Block Store locale + copia locale). Spec §8.1, §10.
 * Le eccezioni (Play Services assente o non aggiornato) vengono propagate: [TrialRepository] passa
 * allora alla sola copia locale.
 */
@Singleton
class BlockStoreTrialStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TrialStore {

    private val client: BlockstoreClient by lazy { Blockstore.getClient(context) }

    override suspend fun read(): TrialRecord? {
        val request = RetrieveBytesRequest.Builder()
            .setKeys(listOf(KEY))
            .build()
        val response = client.retrieveBytes(request).awaitTask()
        val data = response.getBlockstoreDataMap()[KEY] ?: return null
        return TrialRecord.decode(String(data.bytes, Charsets.UTF_8))
    }

    override suspend fun write(record: TrialRecord) {
        val endToEnd = catchingNonCancellation {
            client.isEndToEndEncryptionAvailable().awaitTask()
        }
        val data = StoreBytesData.Builder()
            .setBytes(record.encode().toByteArray(Charsets.UTF_8))
            .setKey(KEY)
            .setShouldBackupToCloud(CloudBackupPolicy.shouldBackupToCloud(endToEnd))
            .build()
        client.storeBytes(data).awaitTask()
    }

    private companion object {
        const val KEY = "com.zaramella.zzitto.trial.v1"
    }
}
