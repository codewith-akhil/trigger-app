package com.example.service

import android.util.Log
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Result of the `create-razorpay-order` edge-function invocation.
 */
sealed class RazorpayOrderResult {
    /** Everything the checkout sheet needs. The key secret is NEVER here. */
    data class Ready(
        val orderId: String,
        val keyId: String,
        val amountMinor: Long,
        val currency: String
    ) : RazorpayOrderResult()

    data class Failed(val message: String) : RazorpayOrderResult()
}

/**
 * Result of the `verify-razorpay-payment` edge-function invocation.
 */
sealed class RazorpayVerifyResult {
    /** Signature valid AND the server recorded the booking / wallet credit. */
    object Verified : RazorpayVerifyResult()

    data class Failed(val message: String) : RazorpayVerifyResult()
}

/**
 * Client-side gateway for the Razorpay flow. ALL sensitive work happens in
 * the Supabase edge functions — this class only shuttles order ids and
 * signatures; `RAZORPAY_KEY_SECRET` never leaves the server (and is in the
 * secrets-plugin ignoreList, so it is never baked into BuildConfig either).
 *
 * Flow:
 *   1. [createOrder]    → POST create-razorpay-order (server-side order)
 *   2. checkout sheet   → see ui/payment/RazorpayCheckout.kt
 *   3. [verifyPayment]  → POST verify-razorpay-payment (HMAC-SHA256 check;
 *                         for stream_booking the server inserts the
 *                         stream_bookings row and credits the host's wallet;
 *                         for wallet_topup it credits the payer's wallet)
 */
class RazorpayPaymentService {

    private val TAG = "RazorpayPaymentService"
    private val client get() = AppServiceContainer.supabaseClient

    /**
     * @param amountMajor amount in MAJOR currency units (99.0 = ₹99.00) —
     *   the edge function converts to paise/cents server-side.
     * @param currencyCode ISO-4217 code: INR | USD | EUR | GBP (validated
     *   server-side).
     * @param purpose "stream_booking" | "wallet_topup".
     */
    suspend fun createOrder(
        amountMajor: Double,
        currencyCode: String,
        purpose: String,
        streamId: String? = null
    ): RazorpayOrderResult {
        if (!amountMajor.isFinite() || amountMajor <= 0.0) {
            return RazorpayOrderResult.Failed("Amount must be greater than zero.")
        }

        val payload = JSONObject()
            .put("amount", amountMajor)
            .put("currency", currencyCode.uppercase())
            .put("receipt", "rcpt_${System.currentTimeMillis()}")
            .put("purpose", purpose)
        if (!streamId.isNullOrBlank()) payload.put("streamId", streamId)

        return when (val res = client.invokeFunction("create-razorpay-order", payload)) {
            is SupabaseResult.Success -> {
                val orderId = res.data.optString("orderId")
                val keyId = res.data.optString("keyId")
                if (orderId.isBlank() || keyId.isBlank()) {
                    Log.e(TAG, "create-razorpay-order: malformed response ${res.data}")
                    RazorpayOrderResult.Failed("Payment gateway returned an invalid order.")
                } else {
                    RazorpayOrderResult.Ready(
                        orderId = orderId,
                        keyId = keyId,
                        amountMinor = res.data.optLong("amount", (amountMajor * 100).roundToLong()),
                        currency = res.data.optString("currency", currencyCode)
                    )
                }
            }
            is SupabaseResult.Error -> {
                Log.e(TAG, "create-razorpay-order failed: ${res.message}")
                RazorpayOrderResult.Failed(res.message)
            }
        }
    }

    /**
     * Verifies the checkout result server-side. The edge function recomputes
     * HMAC-SHA256(order_id|payment_id) with the secret and cross-checks the
     * payment against Razorpay's API before persisting anything — the client
     * is never trusted.
     */
    suspend fun verifyPayment(
        orderId: String,
        paymentId: String,
        signature: String,
        purpose: String,
        streamId: String? = null
    ): RazorpayVerifyResult {
        val payload = JSONObject()
            .put("razorpayOrderId", orderId)
            .put("razorpayPaymentId", paymentId)
            .put("razorpaySignature", signature)
            .put("purpose", purpose)
        if (!streamId.isNullOrBlank()) payload.put("streamId", streamId)

        return when (val res = client.invokeFunction("verify-razorpay-payment", payload)) {
            is SupabaseResult.Success ->
                if (res.data.optBoolean("verified", false)) {
                    RazorpayVerifyResult.Verified
                } else {
                    RazorpayVerifyResult.Failed(
                        res.data.optString("error", "Payment verification failed.")
                    )
                }
            is SupabaseResult.Error -> {
                // The edge function answers HTTP 400 {verified:false, error:…}
                // on signature mismatch — surfaced here as a normal failure.
                Log.e(TAG, "verify-razorpay-payment failed: ${res.message}")
                RazorpayVerifyResult.Failed(res.message)
            }
        }
    }
}
