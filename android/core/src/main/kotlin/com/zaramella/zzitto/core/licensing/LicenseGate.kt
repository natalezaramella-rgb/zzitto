package com.zaramella.zzitto.core.licensing

import kotlinx.coroutines.flow.StateFlow

/**
 * Contratto tra l'app (agente B) e il modulo :licensing (agente D).
 * Spec §8.1. Aggiunto il 22/10/2026 alle 11:15.
 *
 * Regole:
 *  - Durante la prova, [refresh] si collega a internet SOLO per verificare l'acquisto.
 *  - Dopo l'acquisto confermato lo stato [LicenseState.Purchased] è salvato cifrato
 *    e non si fanno più accessi a internet.
 *  - Senza connessione la prova continua normalmente fino alla scadenza.
 */
interface LicenseGate {
    /** Stato corrente, aggiornato da [refresh] e dagli acquisti. */
    val state: StateFlow<LicenseState>

    /** Da chiamare a ogni avvio dell'app/servizio. Non lancia eccezioni. */
    suspend fun refresh()

    /** True se la dettatura è consentita (prova attiva o app acquistata). */
    fun canDictate(): Boolean = when (state.value) {
        is LicenseState.Trial, LicenseState.Purchased -> true
        else -> false
    }

    /** Avvia il flusso di acquisto una tantum; richiede un'Activity in primo piano. */
    suspend fun launchPurchase(activity: android.app.Activity): PurchaseOutcome

    /** Ripristina un acquisto già fatto (reinstallazione). */
    suspend fun restorePurchases(): LicenseState
}

sealed interface LicenseState {
    /** Stato non ancora determinato (primo avvio, lettura in corso). */
    data object Unknown : LicenseState
    data class Trial(val daysLeft: Int) : LicenseState
    data object TrialExpired : LicenseState
    data object Purchased : LicenseState
}

enum class PurchaseOutcome { Purchased, Pending, Cancelled, AlreadyOwned, Error }
