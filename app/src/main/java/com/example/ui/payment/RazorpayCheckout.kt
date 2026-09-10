package com.example.ui.payment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResult
import com.example.R
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import org.json.JSONObject

/**
 * Parameters for opening the Razorpay checkout sheet.
 *
 * `order_id` is authoritative: Razorpay resolves amount + currency from the
 * server-side order, so a tampered client can never change the price.
 */
data class RazorpayCheckoutParams(
    val orderId: String,
    val keyId: String,
    val description: String,
    val prefillName: String? = null,
    val prefillEmail: String? = null,
    val prefillContact: String? = null,
    val themeColorHex: String = "#008069" // Trigger brand green
) {
    fun toCheckoutJson(): JSONObject = JSONObject().apply {
        put("order_id", orderId)
        put("name", "Trigger")
        put("description", description)
        put("theme", JSONObject().put("color", themeColorHex))
        put(
            "prefill",
            JSONObject().apply {
                prefillName?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                prefillEmail?.takeIf { it.isNotBlank() }?.let { put("email", it) }
                prefillContact?.takeIf { it.isNotBlank() }?.let { put("contact", it) }
            }
        )
    }

    companion object {
        internal const val EXTRA_PARAMS = "trigger.razorpay.extra.PARAMS"
        internal const val EXTRA_KEY_ID = "trigger.razorpay.extra.KEY_ID"
        internal const val EXTRA_PAYMENT_ID = "trigger.razorpay.extra.PAYMENT_ID"
        internal const val EXTRA_ORDER_ID = "trigger.razorpay.extra.ORDER_ID"
        internal const val EXTRA_SIGNATURE = "trigger.razorpay.extra.SIGNATURE"
        internal const val EXTRA_ERROR = "trigger.razorpay.extra.ERROR"
        internal const val EXTRA_ERROR_CODE = "trigger.razorpay.extra.ERROR_CODE"

        fun intent(context: Context, params: RazorpayCheckoutParams): Intent =
            Intent(context, RazorpayCheckoutActivity::class.java)
                .putExtra(EXTRA_PARAMS, params.toCheckoutJson().toString())
                .putExtra(EXTRA_KEY_ID, params.keyId)
    }
}

/**
 * Outcome of the checkout sheet, parsed from the bridge activity's result.
 */
sealed class RazorpayCheckoutResult {
    data class Success(
        val paymentId: String,
        val orderId: String,
        val signature: String
    ) : RazorpayCheckoutResult()

    data class Failure(val code: Int, val message: String) : RazorpayCheckoutResult()

    /** User backed out of the sheet — no charge was attempted. */
    object Cancelled : RazorpayCheckoutResult()

    companion object {
        fun fromActivityResult(result: ActivityResult): RazorpayCheckoutResult {
            val data = result.data

            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val paymentId = data.getStringExtra(RazorpayCheckoutParams.EXTRA_PAYMENT_ID).orEmpty()
                val orderId = data.getStringExtra(RazorpayCheckoutParams.EXTRA_ORDER_ID).orEmpty()
                val signature = data.getStringExtra(RazorpayCheckoutParams.EXTRA_SIGNATURE).orEmpty()
                return if (paymentId.isNotBlank() && signature.isNotBlank()) {
                    RazorpayCheckoutResult.Success(paymentId, orderId, signature)
                } else {
                    RazorpayCheckoutResult.Failure(-1, "Payment result was incomplete.")
                }
            }

            if (data != null && data.hasExtra(RazorpayCheckoutParams.EXTRA_ERROR)) {
                val code = data.getIntExtra(RazorpayCheckoutParams.EXTRA_ERROR_CODE, -1)
                val message = data.getStringExtra(RazorpayCheckoutParams.EXTRA_ERROR) ?: "Payment failed."
                return if (code == Checkout.PAYMENT_CANCELED) {
                    RazorpayCheckoutResult.Cancelled
                } else {
                    RazorpayCheckoutResult.Failure(code, message)
                }
            }

            // Activity killed / no result delivered — treat as user cancel.
            return RazorpayCheckoutResult.Cancelled
        }
    }
}

/**
 * Invisible bridge activity between Compose and the Razorpay SDK.
 *
 * Compose dialogs cannot receive `onActivityResult`, and the Razorpay SDK
 * delivers its result to the Activity that called `Checkout.open()`. This
 * translucent activity takes the checkout params via Intent extras, opens
 * the official payment sheet, and relays the outcome back to the caller
 * through `Activity.setResult` — so screens only need a
 * `rememberLauncherForActivityResult(StartActivityForResult())`.
 *
 * Implements `PaymentResultWithDataListener` (NOT the deprecated
 * `PaymentResultListener`) so the callback carries `PaymentData` — which
 * provides the order_id + signature required for server-side verification.
 */
class RazorpayCheckoutActivity : Activity(), PaymentResultWithDataListener {

    private val TAG = "RazorpayCheckout"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val paramsJson = intent.getStringExtra(RazorpayCheckoutParams.EXTRA_PARAMS)
        val keyId = intent.getStringExtra(RazorpayCheckoutParams.EXTRA_KEY_ID)

        if (paramsJson.isNullOrBlank() || keyId.isNullOrBlank()) {
            finishWithFailure(Checkout.INVALID_OPTIONS, "Missing checkout parameters.")
            return
        }

        // Brief placeholder (window is translucent) shown until the Razorpay
        // sheet takes over the whole screen.
        setContentView(loadingView())

        try {
            val checkout = Checkout()
            checkout.setKeyID(keyId)
            checkout.setImage(R.drawable.logo)
            checkout.open(this, JSONObject(paramsJson))
            // Outcome arrives in onPaymentSuccess / onPaymentError.
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Razorpay checkout", e)
            finishWithFailure(Checkout.INVALID_OPTIONS, e.message ?: "Could not open the payment sheet.")
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: PaymentData?) {
        val intent = Intent().apply {
            putExtra(RazorpayCheckoutParams.EXTRA_PAYMENT_ID, razorpayPaymentId ?: "")
            putExtra(RazorpayCheckoutParams.EXTRA_ORDER_ID, paymentData?.orderId ?: "")
            putExtra(RazorpayCheckoutParams.EXTRA_SIGNATURE, paymentData?.signature ?: "")
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        Log.w(TAG, "Razorpay checkout error ($code): $response")
        finishWithFailure(code, humanizeError(code, response))
    }

    private fun humanizeError(code: Int, response: String?): String = when (code) {
        Checkout.PAYMENT_CANCELED -> "Payment cancelled."
        Checkout.NETWORK_ERROR -> "Network error — check your connection and try again."
        else -> response?.takeIf { it.isNotBlank() } ?: "Payment failed."
    }

    private fun finishWithFailure(code: Int, message: String) {
        val intent = Intent().apply {
            putExtra(RazorpayCheckoutParams.EXTRA_ERROR_CODE, code)
            putExtra(RazorpayCheckoutParams.EXTRA_ERROR, message)
        }
        setResult(RESULT_FIRST_USER, intent)
        finish()
    }

    private fun loadingView(): LinearLayout {
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (24 * density).toInt(),
                (20 * density).toInt(),
                (24 * density).toInt(),
                (20 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 20f * density
                setColor(Color.WHITE)
            }
        }
        val label = TextView(this).apply {
            text = "Opening secure checkout…"
            textSize = 14f
            setTextColor(Color.parseColor("#111B21"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        card.addView(label)
        root.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }
}
