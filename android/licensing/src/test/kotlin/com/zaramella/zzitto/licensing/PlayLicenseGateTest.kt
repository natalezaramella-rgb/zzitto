package com.zaramella.zzitto.licensing

import android.app.Activity
import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.core.licensing.PurchaseOutcome
import com.zaramella.zzitto.licensing.purchase.Ownership
import com.zaramella.zzitto.licensing.purchase.PurchaseFlowResult
import com.zaramella.zzitto.licensing.trial.TrialRecord
import com.zaramella.zzitto.licensing.trial.TrialRepository
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Spec §8.1, §12: flusso completo di prova, verifica online, acquisto, ripristino.
// Aggiunto il 22/10/2026 alle 11:28.
@OptIn(ExperimentalCoroutinesApi::class)
class PlayLicenseGateTest {

    private val clock = FakeClock()
    private val local = FakeTrialStore()
    private val account = FakeTrialStore()
    private val unlock = FakeUnlockStore()
    private val verifier = FakeVerifier()
    private val activity = mockk<Activity>(relaxed = true)

    private fun TestScope.gate(): PlayLicenseGate = PlayLicenseGate(
        unlockStore = unlock,
        trialRepository = TrialRepository(local, account, clock),
        verifier = verifier,
        scope = backgroundScope,
        io = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `stato iniziale sconosciuto e dettatura bloccata`() = runTest {
        val gate = gate()
        assertEquals(LicenseState.Unknown, gate.state.value)
        assertFalse(gate.canDictate())
    }

    @Test
    fun `primo avvio senza acquisto - prova di 30 giorni`() = runTest {
        val gate = gate()
        gate.refresh()
        assertEquals(LicenseState.Trial(30), gate.state.value)
        assertTrue(gate.canDictate())
        assertEquals(1, verifier.queries)
    }

    @Test
    fun `assenza di rete - la prova continua`() = runTest {
        verifier.ownership = Ownership.Unavailable
        val gate = gate()
        clock.now = T0
        gate.refresh()
        assertEquals(LicenseState.Trial(30), gate.state.value)
        assertEquals(0, unlock.saves)
    }

    @Test
    fun `verifica bloccata oltre il timeout - la prova continua`() = runTest {
        verifier.ownership = null // non risponde mai
        val gate = gate()
        gate.refresh()
        assertEquals(LicenseState.Trial(30), gate.state.value)
    }

    @Test
    fun `errore inatteso nella verifica - nessuna eccezione`() = runTest {
        verifier.throwOnQuery = true
        val gate = gate()
        gate.refresh()
        assertEquals(LicenseState.Trial(30), gate.state.value)
    }

    @Test
    fun `prova scaduta senza rete - dettatura bloccata`() = runTest {
        local.record = TrialRecord(T0 - 31 * DAY, T0 - DAY)
        verifier.ownership = Ownership.Unavailable
        val gate = gate()
        gate.refresh()
        assertEquals(LicenseState.TrialExpired, gate.state.value)
        assertFalse(gate.canDictate())
    }

    @Test
    fun `acquisto confermato all'avvio - sblocco salvato e niente piu verifiche online`() = runTest {
        verifier.ownership = Ownership.Owned(acknowledged = true)
        val gate = gate()
        gate.refresh()
        assertEquals(LicenseState.Purchased, gate.state.value)
        assertTrue(unlock.unlocked)
        assertEquals(1, verifier.queries)

        // Avvio successivo: nuova istanza, stesso archivio cifrato.
        verifier.ownership = Ownership.NotOwned
        val next = gate()
        next.refresh()
        assertEquals(LicenseState.Purchased, next.state.value)
        assertEquals(1, verifier.queries) // nessuna nuova verifica
    }

    @Test
    fun `sblocco persistito anche dopo la scadenza della prova`() = runTest {
        unlock.unlocked = true
        clock.now = T0 + 400 * DAY
        val gate = gate()
        gate.refresh()
        assertEquals(LicenseState.Purchased, gate.state.value)
        assertEquals(0, verifier.queries)
    }

    @Test
    fun `acquisto non riconosciuto - sbloccato in sessione ma non salvato`() = runTest {
        verifier.ownership = Ownership.Owned(acknowledged = false)
        val gate = gate()
        gate.refresh()
        assertEquals(LicenseState.Purchased, gate.state.value)
        assertEquals(0, unlock.saves)
        assertFalse(unlock.unlocked)
    }

    @Test
    fun `salvataggio dello sblocco fallito - sbloccato in sessione, si riprova al prossimo avvio`() = runTest {
        unlock.failSave = true
        verifier.ownership = Ownership.Owned(acknowledged = true)
        val gate = gate()
        gate.refresh()
        assertEquals(LicenseState.Purchased, gate.state.value)
        assertFalse(unlock.unlocked)
        assertEquals(0, verifier.releases)
    }

    @Test
    fun `acquisto in sospeso all'avvio - resta in prova`() = runTest {
        verifier.ownership = Ownership.Pending
        val gate = gate()
        gate.refresh()
        assertEquals(LicenseState.Trial(30), gate.state.value)
    }

    @Test
    fun `acquisto riuscito dal flusso`() = runTest {
        val gate = gate()
        gate.refresh()
        verifier.flowResult = PurchaseFlowResult.Completed(Ownership.Owned(acknowledged = true))
        assertEquals(PurchaseOutcome.Purchased, gate.launchPurchase(activity))
        assertEquals(LicenseState.Purchased, gate.state.value)
        assertTrue(unlock.unlocked)
    }

    @Test
    fun `acquisto in sospeso dal flusso, poi completato mentre l'app e aperta`() = runTest {
        local.record = TrialRecord(T0 - 31 * DAY, T0)
        val gate = gate()
        gate.refresh()
        assertEquals(LicenseState.TrialExpired, gate.state.value)

        verifier.flowResult = PurchaseFlowResult.Completed(Ownership.Pending)
        assertEquals(PurchaseOutcome.Pending, gate.launchPurchase(activity))
        assertEquals(LicenseState.TrialExpired, gate.state.value)

        runCurrent() // il collector degli aggiornamenti è attivo
        verifier.updates.emit(Ownership.Owned(acknowledged = true))
        runCurrent()
        assertEquals(LicenseState.Purchased, gate.state.value)
        assertTrue(unlock.unlocked)
    }

    @Test
    fun `acquisto in sospeso completato prima del prossimo avvio`() = runTest {
        verifier.ownership = Ownership.Pending
        gate().refresh()
        verifier.ownership = Ownership.Owned(acknowledged = true)
        val next = gate()
        next.refresh()
        assertEquals(LicenseState.Purchased, next.state.value)
        assertTrue(unlock.unlocked)
    }

    @Test
    fun `acquisto annullato dall'utente`() = runTest {
        val gate = gate()
        gate.refresh()
        verifier.flowResult = PurchaseFlowResult.Cancelled
        assertEquals(PurchaseOutcome.Cancelled, gate.launchPurchase(activity))
        assertEquals(LicenseState.Trial(30), gate.state.value)
    }

    @Test
    fun `errore nel flusso di acquisto`() = runTest {
        val gate = gate()
        gate.refresh()
        verifier.flowResult = PurchaseFlowResult.Failed
        assertEquals(PurchaseOutcome.Error, gate.launchPurchase(activity))
    }

    @Test
    fun `prodotto gia posseduto - viene ripristinato`() = runTest {
        verifier.ownership = Ownership.Unavailable
        val gate = gate()
        gate.refresh()
        verifier.flowResult = PurchaseFlowResult.AlreadyOwned
        verifier.ownership = Ownership.Owned(acknowledged = true)
        assertEquals(PurchaseOutcome.AlreadyOwned, gate.launchPurchase(activity))
        assertEquals(LicenseState.Purchased, gate.state.value)
        assertTrue(unlock.unlocked)
    }

    @Test
    fun `acquisto gia sbloccato - nessun flusso`() = runTest {
        unlock.unlocked = true
        val gate = gate()
        gate.refresh()
        assertEquals(PurchaseOutcome.AlreadyOwned, gate.launchPurchase(activity))
        assertEquals(0, verifier.launches)
    }

    @Test
    fun `ripristino acquisti dopo reinstallazione`() = runTest {
        // Reinstallazione: copia locale vuota, prova su Block Store, acquisto su Google Play.
        account.record = TrialRecord(T0 - 40 * DAY, T0 - 35 * DAY)
        verifier.ownership = Ownership.Unavailable
        val gate = gate()
        gate.refresh()
        assertEquals(LicenseState.TrialExpired, gate.state.value)

        verifier.ownership = Ownership.Owned(acknowledged = true)
        assertEquals(LicenseState.Purchased, gate.restorePurchases())
        assertTrue(unlock.unlocked)
    }

    @Test
    fun `ripristino senza acquisti - stato invariato`() = runTest {
        val gate = gate()
        assertEquals(LicenseState.Trial(30), gate.restorePurchases())
    }

    @Test
    fun `ripristino senza rete - stato invariato`() = runTest {
        val gate = gate()
        gate.refresh()
        verifier.ownership = Ownership.Unavailable
        assertEquals(LicenseState.Trial(30), gate.restorePurchases())
    }

    @Test
    fun `orologio spostato indietro tra due avvii non restituisce giorni`() = runTest {
        verifier.ownership = Ownership.Unavailable
        clock.now = T0 + 20 * DAY
        local.record = TrialRecord(T0, T0)
        gate().refresh()
        clock.now = T0 + 2 * DAY
        val next = gate()
        next.refresh()
        assertEquals(LicenseState.Trial(10), next.state.value)
    }
}
