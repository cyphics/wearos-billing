/** ========================================================================
Creation: 12/11/2021
Revision: $
Creator: Thierry Raeber (thierry@sooon.ch)
Notice: (C) Copyright 2021 by Sooon SA. All Rights Reserved.
============================================================================ */

package ch.sooon.billing

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.OnLifecycleEvent
import com.android.billingclient.api.*
import kotlinx.coroutines.*

class BillingManager private constructor (
    private val application: Application,
    private val defaultScope: CoroutineScope,
    skuList: List<String>
) : PurchasesUpdatedListener, BillingClientStateListener {

    companion object {
        private val TAG = BillingManager::class.java.simpleName

        @Volatile
        private var sInstance: BillingManager? = null
        @JvmStatic
        fun getInstance(
            application: Application,
            defaultScope: CoroutineScope,
            skuList: List<String>,
        ) = sInstance ?: synchronized(this) {
            sInstance ?: BillingManager(
                application,
                defaultScope,
                skuList,
            )
                .also { sInstance = it }
        }
    }

    private val mSkuList: List<String> = skuList
    private var skuDetails: SkuDetails? = null

    private val purchaseConsumptionInProcess: MutableSet<Purchase> = HashSet()

    private val bell =  Bell(TAG)

    private val billingClient: BillingClient = BillingClient.newBuilder(application)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    init {
        billingClient.startConnection(this)
    }


    /**
     * It's recommended to requery purchases during onResume.
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun resume() {
        Log.d(TAG, "ON_RESUME")
        // this just avoids an extra purchase refresh after we finish a billing flow
            if (billingClient.isReady) {
                defaultScope.launch {
                    refreshPurchases()
                }
        }
    }
    override fun onBillingSetupFinished(p0: BillingResult) {
        if (p0.responseCode ==  BillingClient.BillingResponseCode.OK) {
            defaultScope.launch {
                querySkuDetails()
                refreshPurchases()
            }
        }
    }

    suspend fun refreshPurchases() {
        Log.d(TAG, "Refreshing purchases.")
        val purchasesResult = billingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP)
        val billingResult = purchasesResult.billingResult
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Problem getting purchases: " + billingResult.debugMessage)
        } else {
            if (purchasesResult.purchasesList.isEmpty()) {
                updatePremiumStatus(false)
                Log.d(TAG, "No purchase found to process")
            } else
                processPurchaseList(purchasesResult.purchasesList)
        }
        Log.d(TAG, "Refreshing purchases finished.")
    }

    override fun onBillingServiceDisconnected() { }

    private fun processPurchaseList(purchases: List<Purchase>?) {
        val updatedSkus = HashSet<String>()
        if (null != purchases) {
            for (purchase in purchases) {
                Log.d(TAG, "Processing purchase $purchase")
                val sku = purchase.skus[0]
                updatedSkus.add(sku)
//                // Global check to make sure all purchases are signed correctly.
//                // This check is best performed on your server.
                val purchaseState = purchase.purchaseState
                if (purchaseState == Purchase.PurchaseState.PURCHASED) {
                    Log.d(TAG, "Purchase valid: $purchase")
                    updatePremiumStatus(true)
                    if (!isSignatureValid(purchase)) {
                        Log.e(
                            TAG,
                            "Invalid signature on SKU $sku. Check to make sure your " +
                                    "public key is correct."
                        )
                        continue
                    }
//                    // only set the purchased state after we've validated the signature.
                    CoroutineScope(Dispatchers.Main).launch {
                        if (!purchase.isAcknowledged) {
                            // acknowledge everything --- new purchases are ones not yet acknowledged
                            val billingResult = billingClient.acknowledgePurchase(
                                AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(purchase.purchaseToken)
                                    .build()
                            )
                            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                                Log.e(TAG, "Error acknowledging purchase: $sku")
                            } else {
                                Log.e(TAG, "Purchase acknowledged: $sku")
                            }
                        }
                    }
                } else {
                    updatePremiumStatus(false)
                }
            }
        } else {
            Log.d(TAG, "Empty purchase list.")
        }
    }

    private fun isSignatureValid(purchase: Purchase): Boolean {
        return true
    }

    suspend fun querySkuDetails() {
        val params = SkuDetailsParams.newBuilder()
        params.setSkusList(mSkuList)
            .setType(BillingClient.SkuType.INAPP)

        val skuDetailsResult = withContext(Dispatchers.IO) {
            billingClient.querySkuDetails(params.build())
        }
        onSkuDetailsResponse(skuDetailsResult.billingResult, skuDetailsResult.skuDetailsList)
    }

    private fun onSkuDetailsResponse(billingResult: BillingResult, skuDetailsList: List<SkuDetails>?) {
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                Log.i(TAG, "onSkuDetailsResponse: $responseCode $debugMessage")
                if (skuDetailsList == null || skuDetailsList.isEmpty()) {
                    Log.e( TAG,
                        "onSkuDetailsResponse: " +
                                "Found null or empty SkuDetails. " +
                                "Check to see if the SKUs you requested are correctly published " +
                                "in the Google Play Console."
                    )
                } else {
                    for (skuDetails in skuDetailsList) {
                        if (skuDetails.sku == mSkuList[0])
                            this.skuDetails = skuDetails
                    }
                }
            }
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
            BillingClient.BillingResponseCode.ERROR ->
                Log.e(TAG, "onSkuDetailsResponse: $responseCode $debugMessage")
            BillingClient.BillingResponseCode.USER_CANCELED ->
                Log.i(TAG, "onSkuDetailsResponse: $responseCode $debugMessage")
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED ->
                Log.wtf(TAG, "onSkuDetailsResponse: $responseCode $debugMessage")
            else -> Log.wtf(TAG, "onSkuDetailsResponse: $responseCode $debugMessage")
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        Log.d(TAG, "onPurchasesUpdated() called with result ${billingResult.responseCode}")
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if(purchases != null){
                        processPurchaseList(purchases)
                }
            }
//            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {}
            else -> {
                Log.e(TAG, "onPurchasedUpdated error ${billingResult.responseCode} : ${billingResult.debugMessage}")
            }
        }
        resume()
    }

    suspend fun consumeInAppPurchase(sku: String) {
        val pr = billingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP)
        val br = pr.billingResult
        val purchasesList = pr.purchasesList
        if (br.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Problem getting purchases: " + br.debugMessage)
        } else {
            for (purchase in purchasesList) {
                // for right now any bundle of SKUs must all be consumable
                for (purchaseSku in purchase.skus) {
                    if (purchaseSku == sku) {
                        consumePurchase(purchase)
                        return
                    }
                }
            }
        }
        Log.e(TAG, "Unable to consume SKU: $sku Sku not found.")
    }

    private suspend fun consumePurchase(purchase: Purchase) {
        // weak check to make sure we're not already consuming the sku
        if (purchaseConsumptionInProcess.contains(purchase)) {
            // already consuming
            return
        }
        purchaseConsumptionInProcess.add(purchase)
        val consumePurchaseResult = billingClient.consumePurchase(
            ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        )

        purchaseConsumptionInProcess.remove(purchase)
        if (consumePurchaseResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "Consumption successful. Emitting sku.")
        } else {
            Log.e(TAG, "Error while consuming: Code ${consumePurchaseResult.billingResult.responseCode} - ${consumePurchaseResult.billingResult.debugMessage}")
        }
        refreshPurchases()
    }

    fun launchPurchaseFlow(activity: Activity) {
        this.skuDetails?.let {
            val flowParams = BillingFlowParams.newBuilder()
                .setSkuDetails(it)
                .build()
            val launch = billingClient.launchBillingFlow(activity, flowParams)
            Log.d(TAG, "launchPurchaseFlow result: ${launch.responseCode}: ${launch.debugMessage}")
        }
    }

    private fun updatePremiumStatus(newStatus: Boolean) {
        // Save premium status in the shared preferences
        bell.ring()
    }

    fun subscribe(function: () -> Unit) = bell.listen(function)
}