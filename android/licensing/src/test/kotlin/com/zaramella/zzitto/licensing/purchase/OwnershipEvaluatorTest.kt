package com.zaramella.zzitto.licensing.purchase

import com.zaramella.zzitto.licensing.LicensingConfig.PRODUCT_ID
import com.zaramella.zzitto.licensing.purchase.PurchaseSnapshot.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Spec §8.1, §12: acquisti completati, da riconoscere, in sospeso. Aggiunto il 22/10/2026 alle 11:28.
class OwnershipEvaluatorTest {

    private fun purchase(state: State, ack: Boolean, product: String = PRODUCT_ID, token: String = "t") =
        PurchaseSnapshot(listOf(product), state, ack, token)

    @Test
    fun `nessun acquisto`() {
        assertEquals(Evaluation.NotOwned, OwnershipEvaluator.evaluate(emptyList(), PRODUCT_ID))
    }

    @Test
    fun `acquisto riconosciuto`() {
        assertEquals(
            Evaluation.Owned,
            OwnershipEvaluator.evaluate(listOf(purchase(State.PURCHASED, ack = true)), PRODUCT_ID),
        )
    }

    @Test
    fun `acquisto da riconoscere`() {
        assertEquals(
            Evaluation.NeedsAcknowledge("abc"),
            OwnershipEvaluator.evaluate(listOf(purchase(State.PURCHASED, ack = false, token = "abc")), PRODUCT_ID),
        )
    }

    @Test
    fun `acquisto in sospeso non sblocca`() {
        assertEquals(
            Evaluation.Pending,
            OwnershipEvaluator.evaluate(listOf(purchase(State.PENDING, ack = false)), PRODUCT_ID),
        )
    }

    @Test
    fun `altri prodotti e stati sconosciuti sono ignorati`() {
        val purchases = listOf(
            purchase(State.PURCHASED, ack = true, product = "altro"),
            purchase(State.UNSPECIFIED, ack = false),
        )
        assertEquals(Evaluation.NotOwned, OwnershipEvaluator.evaluate(purchases, PRODUCT_ID))
    }

    @Test
    fun `un acquisto completato prevale su uno in sospeso`() {
        val purchases = listOf(
            purchase(State.PENDING, ack = false, token = "p"),
            purchase(State.PURCHASED, ack = false, token = "ok"),
        )
        assertEquals(Evaluation.NeedsAcknowledge("ok"), OwnershipEvaluator.evaluate(purchases, PRODUCT_ID))
    }
}
