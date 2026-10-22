package com.zaramella.zzitto.licensing.trial

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Spec §8.1, §10, §12: backup su cloud solo con cifratura end-to-end. Aggiunto il 22/10/2026 alle 11:50.
class CloudBackupPolicyTest {

    @Test
    fun `cifratura end-to-end disponibile - backup su cloud`() {
        assertTrue(CloudBackupPolicy.shouldBackupToCloud(Result.success(true)))
    }

    @Test
    fun `cifratura end-to-end non disponibile - solo sul dispositivo`() {
        assertFalse(CloudBackupPolicy.shouldBackupToCloud(Result.success(false)))
    }

    @Test
    fun `verifica fallita - solo sul dispositivo`() {
        assertFalse(CloudBackupPolicy.shouldBackupToCloud(Result.failure(IllegalStateException("Play Services"))))
    }
}
