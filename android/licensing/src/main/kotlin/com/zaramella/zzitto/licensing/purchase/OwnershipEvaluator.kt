package com.zaramella.zzitto.licensing.purchase

/**
 * Vista minima di un acquisto Google Play, indipendente dalla libreria Billing (testabile sulla JVM).
 * Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 */
data class PurchaseSnapshot(
    val productIds: List<String>,
    val state: State,
    val acknowledged: Boolean,
    val purchaseToken: String,
) {
    enum class State { PURCHASED, PENDING, UNSPECIFIED }
}

/** Cosa fare con gli acquisti restituiti da Google Play. */
sealed interface Evaluation {
    data object Owned : Evaluation
    data class NeedsAcknowledge(val purchaseToken: String) : Evaluation
    data object Pending : Evaluation
    data object NotOwned : Evaluation
}

/**
 * Regole pure di valutazione degli acquisti. Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 *
 * Precedenza: acquisto riconosciuto > acquisto da riconoscere > in sospeso > nessuno.
 * Gli acquisti in sospeso NON sbloccano e NON si riconoscono (regola di Google Play Billing).
 */
object OwnershipEvaluator {
    fun evaluate(purchases: List<PurchaseSnapshot>, productId: String): Evaluation {
        val relevant = purchases.filter { productId in it.productIds }
        if (relevant.any { it.state == PurchaseSnapshot.State.PURCHASED && it.acknowledged }) {
            return Evaluation.Owned
        }
        relevant.firstOrNull { it.state == PurchaseSnapshot.State.PURCHASED }?.let {
            return Evaluation.NeedsAcknowledge(it.purchaseToken)
        }
        if (relevant.any { it.state == PurchaseSnapshot.State.PENDING }) return Evaluation.Pending
        return Evaluation.NotOwned
    }
}
