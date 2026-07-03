package com.jeeneet.mocktest.admob

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.jeeneet.mocktest.data.model.IAPProducts
import com.jeeneet.mocktest.data.repository.QuestionSyncManager
import com.jeeneet.mocktest.utils.PrefManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class IAPManager(private val context: Context) : PurchasesUpdatedListener {

    private val TAG = "IAPManager"

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        // billing-ktx 7.x: the no-arg form only covers subscriptions.
        // enableOneTimeProducts() is required so INAPP purchases (packs, remove_ads) work.
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    var onPurchaseSuccess: ((productId: String) -> Unit)? = null
    var onPurchaseFailed: ((errorCode: Int) -> Unit)? = null
    var onRestoreComplete: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Purchase tokens already processed in this session — prevents firing
    // onPurchaseSuccess twice if Play Billing replays a completed purchase.
    private val processedTokens = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    // If purchase() is called while the billing client is still connecting, we store the
    // call here and fire it from onBillingSetupFinished. Calling startConnection() a second
    // time while CONNECTING returns SERVICE_UNAVAILABLE (code 2) immediately — which is the
    // "purchase failed code 2" bug reported when the user taps Buy before billing finishes
    // its initial connect.
    private var pendingPurchase: (() -> Unit)? = null

    // ─── Connect ─────────────────────────────────────────────────────────────

    fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing connected")
                    restorePurchases()
                    pendingPurchase?.let { pending -> pendingPurchase = null; pending() }
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                    pendingPurchase?.let { pendingPurchase = null; onPurchaseFailed?.invoke(result.responseCode) }
                }
            }
            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing disconnected — will retry on next purchase")
            }
        })
    }

    // ─── Launch purchase flow ─────────────────────────────────────────────────

    fun purchase(activity: Activity, productId: String) {
        if (!billingClient.isReady) {
            if (billingClient.connectionState == BillingClient.ConnectionState.CONNECTING) {
                // Already connecting via connect() — queue and let onBillingSetupFinished fire it.
                // Calling startConnection() again while CONNECTING returns SERVICE_UNAVAILABLE (2)
                // immediately, which would surface as a false "purchase failed" error.
                pendingPurchase = { purchase(activity, productId) }
                return
            }
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        activity.runOnUiThread { purchase(activity, productId) }
                    } else {
                        Log.w(TAG, "Billing reconnect failed: ${result.debugMessage}")
                        onPurchaseFailed?.invoke(result.responseCode)
                    }
                }
                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "Billing service disconnected during reconnect attempt")
                    onPurchaseFailed?.invoke(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                }
            })
            return
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(
                        if (productId == IAPProducts.ALL_ACCESS_YEARLY)
                            BillingClient.ProductType.SUBS
                        else
                            BillingClient.ProductType.INAPP
                    )
                    .build()
            ))
            .build()

        billingClient.queryProductDetailsAsync(params) { result, productDetailsList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK || productDetailsList.isEmpty()) {
                Log.w(TAG, "Product not found: $productId (code ${result.responseCode})")
                onPurchaseFailed?.invoke(result.responseCode)
                return@queryProductDetailsAsync
            }
            val productDetails = productDetailsList[0]
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken

            val productDetailsParamsList = if (offerToken != null) {
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offerToken)
                    .build())
            } else {
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .build())
            }

            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()

            activity.runOnUiThread {
                val flowResult = billingClient.launchBillingFlow(activity, billingFlowParams)
                if (flowResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "launchBillingFlow failed: ${flowResult.responseCode} ${flowResult.debugMessage}")
                    onPurchaseFailed?.invoke(flowResult.responseCode)
                }
            }
        }
    }

    // ─── Handle purchase callbacks ────────────────────────────────────────────

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it, fireSuccess = true) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User cancelled purchase")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // User tried to buy something they already own. Querying Play for the real
                // Purchase object lets us acknowledge it if it was left unacknowledged by a
                // previous crashed session — otherwise Google auto-refunds in 3 days.
                Log.d(TAG, "Item already owned — restoring to fix entitlement + acknowledgment")
                restorePurchases()
            }
            else -> {
                Log.w(TAG, "Purchase error: ${result.responseCode} ${result.debugMessage}")
                onPurchaseFailed?.invoke(result.responseCode)
            }
        }
    }

    // Single entry point for handling a Purchase object.
    // fireSuccess=true  → fresh purchase flow (fires onPurchaseSuccess, toasts, analytics)
    // fireSuccess=false → silent restore on app start (no UI, but still acknowledges)
    private fun handlePurchase(purchase: Purchase, fireSuccess: Boolean) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        // Dedupe: a purchase token may replay across callbacks / restore cycles.
        if (!processedTokens.add(purchase.purchaseToken)) {
            Log.d(TAG, "Skipping already-processed purchase token")
            return
        }

        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(params) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    grantEntitlement(purchase, fireSuccess)
                } else {
                    // Critical: acknowledgment failed → Google will auto-refund in 3 days.
                    // Remove from processed set so next restore can retry.
                    Log.e(TAG, "Acknowledgment FAILED: ${result.responseCode} ${result.debugMessage} — user may be auto-refunded")
                    processedTokens.remove(purchase.purchaseToken)
                }
            }
        } else {
            grantEntitlement(purchase, fireSuccess)
        }
    }

    private fun grantEntitlement(purchase: Purchase, fireSuccess: Boolean) {
        val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return

        purchase.products.forEach { productId ->
            when (productId) {
                IAPProducts.REMOVE_ADS -> PrefManager.setAdsRemoved(context)
                IAPProducts.ALL_ACCESS_YEARLY -> PrefManager.setAllAccessUnlocked(context)
                else -> {
                    PrefManager.unlockPack(context, productId)
                    scope.launch {
                        try { QuestionSyncManager(context).syncPack(productId) }
                        catch (e: Exception) { Log.e(TAG, "Sync after purchase failed: ${e.message}") }
                    }
                }
            }
            savePurchaseToFirestore(productId, purchase.purchaseToken)
            Log.d(TAG, "Granted: $productId to $currentUid (fireSuccess=$fireSuccess)")
        }
        // Fire success ONCE per purchase, not once per product (prevents duplicate
        // finish() / toasts / analytics on multi-product purchases).
        if (fireSuccess) {
            onPurchaseSuccess?.invoke(purchase.products.firstOrNull() ?: return)
        }
    }

    // ─── Restore purchases silently ───────────────────────────────────────────
    // Queries Google Play for acknowledgment and refund detection only.
    // INAPP entitlement granting is handled by syncPurchasesFromFirestore() which
    // reads per Firebase UID — preventing cross-account pack leakage when two
    // Firebase accounts share a Google Play account or device.
    // Subscriptions still use Play as the authority since they can cancel/expire.

    fun restorePurchases() {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) return
        if (!billingClient.isReady) return
        val remaining = AtomicInteger(2)

        fun onQueryDone() {
            if (remaining.decrementAndGet() == 0) {
                Log.d(TAG, "Restore complete")
                onRestoreComplete?.invoke()
            }
        }

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val activePurchases = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }

                // Only acknowledge unacknowledged purchases to prevent Google's 3-day auto-refund.
                // Do NOT grant entitlement here — Play purchases are tied to the Google Play account,
                // not the Firebase UID. Granting here would give one Firebase user's packs to any
                // other Firebase user who shares the same device or Google Play account.
                // syncPurchasesFromFirestore() is the authority for INAPP grants (reads per UID).
                activePurchases.filter { !it.isAcknowledged }.forEach { purchase ->
                    val params = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(params) { ackResult ->
                        if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                            Log.e(TAG, "Acknowledgment failed in restore: ${ackResult.debugMessage}")
                        }
                    }
                }

                val activeProductIds = activePurchases.flatMap { it.products }.toSet()
                if (IAPProducts.REMOVE_ADS !in activeProductIds) {
                    PrefManager.revokeAdsRemoved(context)
                    revokeFirestorePurchase(IAPProducts.REMOVE_ADS)
                }
                // Only revoke packs the current user already holds that are no longer in Play
                // (e.g. refunded). Never replace the whole pack list with Play's result — that
                // would copy another Firebase user's purchased packs onto this account.
                val currentPacks = PrefManager.getUnlockedPacks(context)
                val packsToRevoke = currentPacks.filter { it !in activeProductIds }
                if (packsToRevoke.isNotEmpty()) {
                    PrefManager.setUnlockedPacks(context, currentPacks - packsToRevoke.toSet())
                    packsToRevoke.forEach { revokeFirestorePurchase(it) }
                }
            } else {
                Log.w(TAG, "Restore INAPP query failed: ${result.debugMessage}")
            }
            onQueryDone()
        }

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val activeSubs = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                activeSubs.forEach { handlePurchase(it, fireSuccess = false) }
                // Revoke all_access if the subscription is no longer active (cancelled/expired)
                val hasActiveSub = activeSubs.any { it.products.contains(IAPProducts.ALL_ACCESS_YEARLY) }
                if (!hasActiveSub) PrefManager.revokeAllAccess(context)
            } else {
                Log.w(TAG, "Restore SUBS query failed: ${result.debugMessage}")
            }
            onQueryDone()
        }
    }

    // ─── Firebase purchase storage ────────────────────────────────────────────
    // Saves every confirmed purchase to Firestore so it can be restored on
    // a new device even if the Google Play account differs.
    // Path: users/{uid}/purchases/{productId}

    private fun revokeFirestorePurchase(productId: String) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("purchases").document(productId)
            .delete()
            .addOnFailureListener { Log.w(TAG, "Firestore revoke failed for $productId: ${it.message}") }
    }

    private fun savePurchaseToFirestore(productId: String, purchaseToken: String) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("purchases").document(productId)
            .set(mapOf(
                "productId"     to productId,
                "purchasedAt"   to Timestamp.now(),
                "purchaseToken" to purchaseToken
            ))
            .addOnFailureListener { Log.w(TAG, "Firestore save failed for $productId: ${it.message}") }
    }

    // Reads all purchases stored in Firestore for the current user and applies
    // them to PrefManager. Safe to call on every login — reads are cheap and
    // the writes to PrefManager are idempotent.
    fun syncPurchasesFromFirestore(onComplete: () -> Unit = {}) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            ?: run { onComplete(); return }

        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("purchases")
            .get()
            .addOnSuccessListener { snapshot ->
                snapshot.documents.forEach { doc ->
                    val productId = doc.getString("productId") ?: doc.id
                    // Skip subscription products — Play Billing is the authority on active state.
                    // Granting from Firestore would resurrect cancelled subscriptions.
                    if (productId == IAPProducts.ALL_ACCESS_YEARLY) return@forEach
                    when (productId) {
                        IAPProducts.REMOVE_ADS -> PrefManager.setAdsRemoved(context)
                        else                   -> PrefManager.unlockPack(context, productId)
                    }
                    Log.d(TAG, "Synced from Firestore: $productId to $uid")
                }
                onComplete()
            }
            .addOnFailureListener {
                Log.w(TAG, "Firestore purchase sync failed: ${it.message}")
                onComplete()
            }
    }

    fun disconnect() {
        scope.cancel()
        billingClient.endConnection()
    }
}
