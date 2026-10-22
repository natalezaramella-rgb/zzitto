package com.zaramella.zzitto.licensing.purchase

/**
 * Esito della verifica di possesso dello sblocco presso Google Play. Spec §8.1.
 * Aggiunto il 22/10/2026 alle 11:28.
 */
sealed interface Ownership {
    /**
     * Acquisto completato (stato PURCHASED).
     * @property acknowledged true se riconosciuto presso Google: solo allora lo sblocco si salva in locale.
     *   Un acquisto non riconosciuto entro 3 giorni viene rimborsato automaticamente da Google.
     */
    data class Owned(val acknowledged: Boolean) : Ownership

    /** Acquisto in sospeso (es. pagamento in contanti in negozio): nessuno sblocco finché non diventa PURCHASED. */
    data object Pending : Ownership

    /** Nessun acquisto. */
    data object NotOwned : Ownership

    /** Verifica non possibile: niente rete, Play Store assente o non raggiungibile, timeout. La prova continua. */
    data object Unavailable : Ownership
}

/** Esito del flusso di acquisto mostrato da Google Play. Spec §8.1. */
sealed interface PurchaseFlowResult {
    data class Completed(val ownership: Ownership) : PurchaseFlowResult
    data object AlreadyOwned : PurchaseFlowResult
    data object Cancelled : PurchaseFlowResult
    data object Failed : PurchaseFlowResult
}
