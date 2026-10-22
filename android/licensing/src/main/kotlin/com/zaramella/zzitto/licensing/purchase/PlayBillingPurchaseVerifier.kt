package com.zaramella.zzitto.licensing.purchase

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.zaramella.zzitto.licensing.LicensingConfig
import com.zaramella.zzitto.licensing.LicensingScope
import com.zaramella.zzitto.licensing.catchingNonCancellation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Implementazione con Google Play Billing Library 8 (prodotto una tantum NON consumabile
 * [LicensingConfig.PRODUCT_ID]). Spec §8.1. Aggiunto il 22/10/2026 alle 11:28.
 *
 * Note:
 * - La libreria comunica con l'app Play Store via IPC: l'app non apre connessioni di rete proprie.
 * - `enablePendingPurchases(PendingPurchasesParams…enableOneTimeProducts())` è obbligatorio dalla v8
 *   e abilita gli acquisti in sospeso (PENDING).
 * - `enableAutoServiceReconnection()` (v8) fa riconnettere la libreria da sola se il servizio cade.
 * - `queryPurchasesAsync` restituisce gli acquisti attivi anche dalla cache del Play Store, quindi il
 *   ripristino dopo una reinstallazione funziona anche con rete lenta.
 * - Si usano le API a callback della libreria (stabili dalla v5) con un adattatore a coroutine,
 *   per non dipendere dai tipi di ritorno delle estensioni Kotlin cambiati tra le versioni.
 */
@Singleton
class PlayBillingPurchaseVerifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:LicensingScope private val scope: CoroutineScope,
) : PurchaseVerifier, PurchasesUpdatedListener {

    private val updates = MutableSharedFlow<Ownership>(extraBufferCapacity = 8)
    override val purchaseUpdates: Flow<Ownership> = updates.asSharedFlow()

    private val clientLock = Any()
    private var client: BillingClient? = null
    private val connectMutex = Mutex()

    @Volatile
    private var pendingFlow: CompletableDeferred<PurchaseFlowResult>? = null

    override suspend fun queryOwnership(): Ownership {
        val billing = connectedClient() ?: return Ownership.Unavailable
        val (result, purchases) = queryPurchases(billing)
        if (result.responseCode != BillingResponseCode.OK) return Ownership.Unavailable
        return resolve(billing, purchases.map(::snapshotOf))
    }

    override suspend fun launchPurchaseFlow(activity: Activity): PurchaseFlowResult {
        val billing = connectedClient() ?: return PurchaseFlowResult.Failed
        val details = queryProductDetails(billing) ?: return PurchaseFlowResult.Failed

        // Dalla v8 un prodotto una tantum può avere più offerte: si usa la prima disponibile per l'utente.
        val offerToken: String? =
            details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
                ?: details.oneTimePurchaseOfferDetails?.offerToken
        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        if (offerToken != null) productParamsBuilder.setOfferToken(offerToken)
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
            .build()

        val deferred = CompletableDeferred<PurchaseFlowResult>()
        pendingFlow = deferred
        try {
            // launchBillingFlow va chiamato sul main thread.
            val launch = withContext(Dispatchers.Main.immediate) {
                billing.launchBillingFlow(activity, flowParams)
            }
            when (launch.responseCode) {
                BillingResponseCode.OK -> Unit
                BillingResponseCode.USER_CANCELED -> return PurchaseFlowResult.Cancelled
                BillingResponseCode.ITEM_ALREADY_OWNED -> return PurchaseFlowResult.AlreadyOwned
                else -> return PurchaseFlowResult.Failed
            }
            // L'esito arriva in onPurchasesUpdated.
            return deferred.await()
        } finally {
            if (pendingFlow === deferred) pendingFlow = null
        }
    }

    override fun release() {
        synchronized(clientLock) {
            client?.endConnection()
            client = null
        }
    }

    // Chiamato dalla libreria sul main thread, sia per il flusso avviato da noi sia per acquisti
    // completati più tardi (es. PENDING → PURCHASED).
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        val waiting = pendingFlow
        scope.launch {
            val outcome: PurchaseFlowResult = catchingNonCancellation {
                when (billingResult.responseCode) {
                    BillingResponseCode.OK -> PurchaseFlowResult.Completed(
                        resolve(obtainClient(), purchases.orEmpty().map(::snapshotOf)),
                    )
                    BillingResponseCode.USER_CANCELED -> PurchaseFlowResult.Cancelled
                    BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseFlowResult.AlreadyOwned
                    else -> PurchaseFlowResult.Failed
                }
            }.getOrDefault(PurchaseFlowResult.Failed)

            if (waiting != null && waiting.isActive) {
                waiting.complete(outcome)
            } else if (outcome is PurchaseFlowResult.Completed) {
                updates.emit(outcome.ownership)
            }
        }
    }

    private fun obtainClient(): BillingClient = synchronized(clientLock) {
        client ?: BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .enableAutoServiceReconnection()
            .build()
            .also { client = it }
    }

    /** Client connesso, oppure null se Google Play non è disponibile sul dispositivo. */
    private suspend fun connectedClient(): BillingClient? {
        val billing = obtainClient()
        if (billing.isReady) return billing
        return connectMutex.withLock {
            if (billing.isReady) return@withLock billing
            val connected = suspendCancellableCoroutine<Boolean> { cont ->
                billing.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        if (cont.isActive) cont.resume(billingResult.responseCode == BillingResponseCode.OK)
                    }

                    override fun onBillingServiceDisconnected() {
                        // Con la riconnessione automatica la libreria riprova da sola alla prossima chiamata.
                        if (cont.isActive) cont.resume(false)
                    }
                })
            }
            if (connected) billing else null
        }
    }

    private suspend fun queryPurchases(billing: BillingClient): Pair<BillingResult, List<Purchase>> =
        suspendCancellableCoroutine { cont ->
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            billing.queryPurchasesAsync(
                params,
                PurchasesResponseListener { result, list ->
                    if (cont.isActive) cont.resume(result to list)
                },
            )
        }

    private suspend fun queryProductDetails(billing: BillingClient): ProductDetails? =
        suspendCancellableCoroutine { cont ->
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(LicensingConfig.PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()
            billing.queryProductDetailsAsync(
                params,
                ProductDetailsResponseListener { result, detailsResult ->
                    val details = if (result.responseCode == BillingResponseCode.OK) {
                        detailsResult.productDetailsList.firstOrNull {
                            it.productId == LicensingConfig.PRODUCT_ID
                        }
                    } else {
                        null
                    }
                    if (cont.isActive) cont.resume(details)
                },
            )
        }

    private suspend fun resolve(billing: BillingClient, snapshots: List<PurchaseSnapshot>): Ownership =
        when (val evaluation = OwnershipEvaluator.evaluate(snapshots, LicensingConfig.PRODUCT_ID)) {
            Evaluation.Owned -> Ownership.Owned(acknowledged = true)
            is Evaluation.NeedsAcknowledge ->
                Ownership.Owned(acknowledged = acknowledge(billing, evaluation.purchaseToken))
            Evaluation.Pending -> Ownership.Pending
            Evaluation.NotOwned -> Ownership.NotOwned
        }

    /** Riconosce l'acquisto (obbligatorio entro 3 giorni, altrimenti Google lo rimborsa). */
    private suspend fun acknowledge(billing: BillingClient, purchaseToken: String): Boolean {
        val ready = if (billing.isReady) billing else connectedClient() ?: return false
        return suspendCancellableCoroutine { cont ->
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()
            ready.acknowledgePurchase(
                params,
                AcknowledgePurchaseResponseListener { result ->
                    if (cont.isActive) cont.resume(result.responseCode == BillingResponseCode.OK)
                },
            )
        }
    }

    private fun snapshotOf(purchase: Purchase): PurchaseSnapshot = PurchaseSnapshot(
        productIds = purchase.products,
        state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseSnapshot.State.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseSnapshot.State.PENDING
            else -> PurchaseSnapshot.State.UNSPECIFIED
        },
        acknowledged = purchase.isAcknowledged,
        purchaseToken = purchase.purchaseToken,
    )
}
