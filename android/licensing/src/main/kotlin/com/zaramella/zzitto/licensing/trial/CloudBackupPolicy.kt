package com.zaramella.zzitto.licensing.trial

/**
 * Regola del backup su cloud di Block Store. Spec §8.1, §10. Aggiunto il 22/10/2026 alle 11:50.
 *
 * Per poter dichiarare "nessun dato raccolto" nella sezione Sicurezza dei dati di Google Play, la data
 * di inizio prova va nel cloud solo con cifratura end-to-end disponibile
 * (`BlockstoreClient.isEndToEndEncryptionAvailable()` = true). In ogni altro caso, compreso un errore
 * nella verifica, resta solo sul dispositivo (Block Store locale + copia locale).
 */
object CloudBackupPolicy {
    fun shouldBackupToCloud(endToEndAvailable: Result<Boolean>): Boolean =
        endToEndAvailable.getOrDefault(false)
}
