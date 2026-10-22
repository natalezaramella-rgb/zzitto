package com.zaramella.zzitto.licensing

import android.app.Activity
import com.zaramella.zzitto.core.licensing.LicenseGate
import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.core.licensing.PurchaseOutcome
import com.zaramella.zzitto.licensing.purchase.Ownership
import com.zaramella.zzitto.licensing.purchase.PurchaseFlowResult
import com.zaramella.zzitto.licensing.purchase.PurchaseVerifier
import com.zaramella.zzitto.licensing.trial.TrialRepository
import com.zaramella.zzitto.licensing.unlock.UnlockStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementazione Android di [LicenseGate]: prova di 30 giorni + sblocco una tantum con Google Play.
 * Spec §2.3, §8.1. Aggiunto il 22/10/2026 alle 11:28.
 *
 * Flusso di [refresh] (a ogni avvio):
 * 1. Sblocco cifrato presente → [LicenseState.Purchased], nessun accesso a Google Play (Spec §8.1).
 * 2. Altrimenti stato della prova da Block Store + copia locale ([TrialRepository]).
 * 3. Verifica online dell'acquisto ([PurchaseVerifier.queryOwnership]) con timeout: se l'acquisto è
 *    confermato e riconosciuto lo sblocco si salva; se la verifica non riesce la prova continua.
 *
 * Gli acquisti in sospeso non sbloccano; quando Google li completa arrivano da
 * [PurchaseVerifier.purchaseUpdates] (app aperta) o dalla verifica al prossimo avvio.
 */
@Singleton
class PlayLicenseGate @Inject constructor(
    private val unlockStore: UnlockStore,
    private val trialRepository: TrialRepository,
    private val verifier: PurchaseVerifier,
    @LicensingScope scope: CoroutineScope,
    @param:LicensingIo private val io: CoroutineDispatcher,
) : LicenseGate {

    private val mutableState = MutableStateFlow<LicenseState>(LicenseState.Unknown)
    override val state: StateFlow<LicenseState> = mutableState.asStateFlow()

    private val mutex = Mutex()

    init {
        scope.launch {
            verifier.purchaseUpdates.collect { ownership ->
                catchingNonCancellation { mutex.withLock { applyOwnership(ownership) } }
            }
        }
    }

    override suspend fun refresh() {
        catchingNonCancellation { mutex.withLock { refreshLocked() } }
    }

    override suspend fun launchPurchase(activity: Activity): PurchaseOutcome {
        if (mutableState.value == LicenseState.Purchased || isPersistedUnlock()) {
            mutableState.value = LicenseState.Purchased
            return PurchaseOutcome.AlreadyOwned
        }
        val flow = catchingNonCancellation { verifier.launchPurchaseFlow(activity) }
            .getOrDefault(PurchaseFlowResult.Failed)

        return when (flow) {
            is PurchaseFlowResult.Completed -> when (val ownership = flow.ownership) {
                is Ownership.Owned -> {
                    mutex.withLock { applyOwnership(ownership) }
                    PurchaseOutcome.Purchased
                }
                // Spec §8.1: in sospeso → nessuno sblocco finché Google non conferma il pagamento.
                Ownership.Pending -> PurchaseOutcome.Pending
                Ownership.NotOwned, Ownership.Unavailable -> PurchaseOutcome.Error
            }
            // Già acquistato (es. su un altro dispositivo con lo stesso account): si ripristina.
            PurchaseFlowResult.AlreadyOwned -> {
                val ownership = queryOnline()
                mutex.withLock { applyOwnership(ownership) }
                when (ownership) {
                    is Ownership.Owned -> PurchaseOutcome.AlreadyOwned
                    Ownership.Pending -> PurchaseOutcome.Pending
                    Ownership.NotOwned, Ownership.Unavailable -> PurchaseOutcome.Error
                }
            }
            PurchaseFlowResult.Cancelled -> PurchaseOutcome.Cancelled
            PurchaseFlowResult.Failed -> PurchaseOutcome.Error
        }
    }

    /** Spec §8.1: ripristino dalla cronologia acquisti di Google Play dopo una reinstallazione. */
    override suspend fun restorePurchases(): LicenseState {
        catchingNonCancellation {
            mutex.withLock {
                if (isPersistedUnlock()) {
                    mutableState.value = LicenseState.Purchased
                } else {
                    if (mutableState.value == LicenseState.Unknown) {
                        mutableState.value = withContext(io) { trialRepository.currentState() }
                    }
                    applyOwnership(queryOnline())
                }
            }
        }
        return mutableState.value
    }

    private suspend fun refreshLocked() {
        // Spec §8.1: dopo l'acquisto confermato non ci si collega più a internet.
        if (isPersistedUnlock()) {
            mutableState.value = LicenseState.Purchased
            verifier.release()
            return
        }
        val trial = withContext(io) { trialRepository.currentState() }
        // Un acquisto confermato in questa sessione ma non ancora salvato resta valido.
        if (mutableState.value != LicenseState.Purchased) mutableState.value = trial

        // Spec §8.1: durante la prova si verifica online; senza connessione la prova continua.
        applyOwnership(queryOnline())
    }

    private suspend fun queryOnline(): Ownership =
        catchingNonCancellation {
            withTimeoutOrNull(LicensingConfig.ONLINE_CHECK_TIMEOUT_MILLIS) { verifier.queryOwnership() }
        }.getOrNull() ?: Ownership.Unavailable

    /** Da chiamare con [mutex] acquisito. */
    private suspend fun applyOwnership(ownership: Ownership) {
        if (ownership !is Ownership.Owned) return
        mutableState.value = LicenseState.Purchased
        // Spec §8.1: lo sblocco si salva solo dopo il riconoscimento (acknowledge). Se il
        // riconoscimento non è riuscito, l'utente usa l'app in questa sessione e al prossimo avvio
        // la verifica riprova a riconoscerlo.
        if (ownership.acknowledged) {
            val saved = withContext(io) { unlockStore.saveUnlocked() }
            if (saved) verifier.release()
        }
    }

    private suspend fun isPersistedUnlock(): Boolean = withContext(io) { unlockStore.isUnlocked() }
}
