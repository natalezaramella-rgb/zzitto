package com.zaramella.zzitto.licensing.purchase

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/**
 * Accesso a Google Play Billing, astratto per i test. Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 */
interface PurchaseVerifier {
    /**
     * Acquisti che arrivano fuori da [launchPurchaseFlow], ad esempio un acquisto in sospeso
     * completato mentre l'app è aperta.
     */
    val purchaseUpdates: Flow<Ownership>

    /** Interroga gli acquisti dell'utente e riconosce quelli completati non ancora riconosciuti. */
    suspend fun queryOwnership(): Ownership

    /** Mostra il flusso di acquisto di Google Play e ne attende l'esito. */
    suspend fun launchPurchaseFlow(activity: Activity): PurchaseFlowResult

    /** Chiude la connessione con Google Play (dopo lo sblocco non serve più). */
    fun release()
}
