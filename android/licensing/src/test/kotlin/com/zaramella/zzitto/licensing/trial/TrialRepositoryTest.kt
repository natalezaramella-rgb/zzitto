package com.zaramella.zzitto.licensing.trial

import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.licensing.DAY
import com.zaramella.zzitto.licensing.FakeClock
import com.zaramella.zzitto.licensing.FakeTrialStore
import com.zaramella.zzitto.licensing.T0
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// Spec §8.1, §12: Block Store, copia locale, fallback, reinstallazione. Aggiunto il 22/10/2026 alle 11:28.
class TrialRepositoryTest {

    private val clock = FakeClock()
    private val local = FakeTrialStore()
    private val account = FakeTrialStore()
    private val repository = TrialRepository(local, account, clock)

    @Test
    fun `primo avvio crea la prova su entrambi i supporti`() = runTest {
        assertEquals(LicenseState.Trial(30), repository.currentState())
        assertEquals(TrialRecord(T0, T0), local.record)
        assertEquals(TrialRecord(T0, T0), account.record)
    }

    @Test
    fun `reinstallazione - la data su Block Store impedisce il riavvio della prova`() = runTest {
        account.record = TrialRecord(T0 - 25 * DAY, T0 - 24 * DAY)
        local.record = null // dati locali cancellati dalla disinstallazione
        assertEquals(LicenseState.Trial(5), repository.currentState())
        assertEquals(T0 - 25 * DAY, local.record?.startMillis)
    }

    @Test
    fun `Block Store non disponibile - si usa la copia locale`() = runTest {
        account.available = false
        local.record = TrialRecord(T0 - 3 * DAY, T0 - 3 * DAY)
        assertEquals(LicenseState.Trial(27), repository.currentState())
        assertEquals(T0, local.record?.highWaterMillis)
    }

    @Test
    fun `Block Store non disponibile al primo avvio - la prova parte in locale`() = runTest {
        account.available = false
        assertEquals(LicenseState.Trial(30), repository.currentState())
        assertEquals(TrialRecord(T0, T0), local.record)
    }

    @Test
    fun `entrambi i supporti non disponibili - prova senza errori`() = runTest {
        account.available = false
        local.available = false
        assertEquals(LicenseState.Trial(30), repository.currentState())
    }

    @Test
    fun `scadenza dopo 30 giorni`() = runTest {
        repository.currentState()
        clock.now = T0 + 30 * DAY
        assertEquals(LicenseState.TrialExpired, repository.currentState())
    }

    @Test
    fun `orologio indietro tra due avvii`() = runTest {
        repository.currentState()
        clock.now = T0 + 12 * DAY
        assertEquals(LicenseState.Trial(18), repository.currentState())
        clock.now = T0 + DAY
        assertEquals(LicenseState.Trial(18), repository.currentState())
    }

    @Test
    fun `l'high-water mark si propaga a Block Store`() = runTest {
        repository.currentState()
        clock.now = T0 + 2 * DAY
        repository.currentState()
        assertEquals(T0 + 2 * DAY, account.record?.highWaterMillis)
    }

    @Test
    fun `nessuna scrittura su Block Store se non serve`() = runTest {
        repository.currentState()
        val writes = account.writes
        clock.now = T0 + 60_000
        repository.currentState()
        assertEquals(writes, account.writes)
    }

    @Test
    fun `orologio implausibile - prova piena senza registrazione`() = runTest {
        clock.now = 946_684_800_000L // 01/01/2000
        assertEquals(LicenseState.Trial(30), repository.currentState())
        assertNull(local.record)
        assertNull(account.record)
    }
}
