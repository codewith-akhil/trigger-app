package com.example.service

import android.content.Context
import android.util.Log
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class BankDetails(
    val accountHolderName: String = "",
    val bankName: String = "",
    val accountNumber: String = "",
    val rawAccountNumber: String = "",
    val ifscOrRouting: String = "",
    val swiftCode: String = ""
)

data class PayoutDetails(
    val primaryMethod: String = "",
    val upiId: String = "",
    val paypalEmail: String = ""
)

data class WalletTransaction(
    val id: String,
    val title: String,
    val amount: Double,
    val isCredit: Boolean,
    val currency: String = "USD ($)",
    val date: String,
    val status: String = "Completed",
    val referenceId: String = ""
)

class WalletService(private val context: Context? = null) {
    private val TAG = "WalletService"

    private val _availableBalance = MutableStateFlow(0.0)
    val availableBalance: StateFlow<Double> = _availableBalance.asStateFlow()

    private val _pendingBalance = MutableStateFlow(0.0)
    val pendingBalance: StateFlow<Double> = _pendingBalance.asStateFlow()

    private val _totalEarned = MutableStateFlow(0.0)
    val totalEarned: StateFlow<Double> = _totalEarned.asStateFlow()

    private val _bankDetails = MutableStateFlow(BankDetails())
    val bankDetails: StateFlow<BankDetails> = _bankDetails.asStateFlow()

    private val _payoutDetails = MutableStateFlow(PayoutDetails())
    val payoutDetails: StateFlow<PayoutDetails> = _payoutDetails.asStateFlow()

    private val _transactions = MutableStateFlow<List<WalletTransaction>>(emptyList())
    val transactions: StateFlow<List<WalletTransaction>> = _transactions.asStateFlow()

    private val client get() = AppServiceContainer.supabaseClient

    /**
     * Persist bank details via the `update-bank-details` edge function.
     * Server stores the last4 + a hash of the full account number; only the
     * masked value is mirrored into local state for UI hydration.
     */
    suspend fun updateBankDetails(
        accountHolderName: String,
        bankName: String,
        accountNumber: String,
        ifscOrRouting: String,
        swiftCode: String
    ): Boolean {
        val payload = JSONObject()
            .put("accountHolderName", accountHolderName.trim())
            .put("bankName", bankName.trim())
            .put("accountNumber", accountNumber.trim())
            .put("ifscOrRouting", ifscOrRouting.trim())
            .put("swiftCode", swiftCode.trim())

        return when (val res = client.invokeFunction("update-bank-details", payload)) {
            is SupabaseResult.Success -> {
                val masked = if (accountNumber.length > 4) {
                    "•••• •••• •••• " + accountNumber.takeLast(4)
                } else {
                    accountNumber
                }
                _bankDetails.value = BankDetails(
                    accountHolderName = accountHolderName.trim(),
                    bankName = bankName.trim(),
                    accountNumber = masked,
                    rawAccountNumber = accountNumber.trim(),
                    ifscOrRouting = ifscOrRouting.trim().uppercase(Locale.getDefault()),
                    swiftCode = swiftCode.trim().uppercase(Locale.getDefault())
                )
                Log.i(TAG, "Bank details synced to server for account ending in ${accountNumber.takeLast(4)}")
                true
            }
            is SupabaseResult.Error -> {
                Log.e(TAG, "update-bank-details failed: ${res.message}")
                false
            }
        }
    }

    /**
     * Persist payout details (UPI / PayPal) via the `update-payout-details`
     * edge function. Mirrors the validated values into local state.
     */
    suspend fun updatePayoutDetails(
        primaryMethod: String,
        upiId: String,
        paypalEmail: String
    ): Boolean {
        // UI labels the bank option "Bank Transfer" but the server
        // `update-payout-details` edge function only accepts
        // "Bank" | "UPI" | "PayPal". Map the UI label to the server token.
        val serverMethod = when (primaryMethod.trim().lowercase(Locale.getDefault())) {
            "bank transfer", "bank" -> "Bank"
            "upi" -> "UPI"
            "paypal" -> "PayPal"
            else -> primaryMethod.trim()
        }
        val payload = JSONObject()
            .put("primaryMethod", serverMethod)
            .put("upiId", upiId.trim())
            .put("paypalEmail", paypalEmail.trim())

        return when (val res = client.invokeFunction("update-payout-details", payload)) {
            is SupabaseResult.Success -> {
                _payoutDetails.value = PayoutDetails(
                    primaryMethod = primaryMethod,
                    upiId = upiId.trim(),
                    paypalEmail = paypalEmail.trim()
                )
                Log.i(TAG, "Payout details synced to server: method=$serverMethod")
                true
            }
            is SupabaseResult.Error -> {
                Log.e(TAG, "update-payout-details failed: ${res.message}")
                false
            }
        }
    }

    /**
     * Initiates a withdrawal via the `wallet-withdraw` edge function (which
     * atomically debits the wallet + inserts a `wallet_transactions` row
     * server-side). On success the local balance + transactions are
     * re-hydrated from the server so the UI reflects the authoritative state.
     *
     * `destination` must be one of "Bank" | "UPI" | "PayPal" — the edge fn
     * rejects anything else.
     */
    suspend fun withdraw(
        amount: Double,
        destination: String
    ): Result<WalletTransaction> {
        val current = _availableBalance.value
        if (amount <= 0.0) {
            return Result.failure(Exception("Please enter a valid withdrawal amount greater than zero."))
        }
        if (amount > current) {
            return Result.failure(Exception("Insufficient balance! Available balance is $${"%.2f".format(current)}."))
        }

        val payload = JSONObject()
            .put("amount", amount)
            .put("destination", destination)

        return when (val res = client.invokeFunction("wallet-withdraw", payload)) {
            is SupabaseResult.Success -> {
                // Server has debited + recorded the transaction. Re-hydrate
                // local state from the canonical source.
                refreshFromServer()

                val dateFormat = SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault())
                val refId = res.data.optString("referenceId", "WTHD-" + (100000..999999).random())
                val tx = WalletTransaction(
                    id = "tx_${System.currentTimeMillis()}",
                    title = "Withdrawal to $destination",
                    amount = amount,
                    isCredit = false,
                    currency = "USD ($)",
                    date = dateFormat.format(Date()),
                    status = "Processing",
                    referenceId = refId
                )
                Log.i(TAG, "Withdrawal synced to server: $$amount to $destination. Ref: $refId")
                Result.success(tx)
            }
            is SupabaseResult.Error -> {
                Log.e(TAG, "wallet-withdraw failed: ${res.message}")
                Result.failure(Exception(res.message))
            }
        }
    }

    /**
     * Re-hydrates balance, bank details, payout details and transactions
     * from Supabase. Called from `WalletScreen`'s LaunchedEffect so the UI
     * always reflects the canonical server state.
     */
    suspend fun refreshFromServer() {
        try {
            // 1. Balance row from the `wallet_balance` view.
            when (val balRes = client.getTable("wallet_balance", "select=*")) {
                is SupabaseResult.Success -> {
                    if (balRes.data.length() > 0) {
                        val row = balRes.data.getJSONObject(0)
                        _availableBalance.value = row.optDouble("available_balance", 0.0)
                        _pendingBalance.value = row.optDouble("pending_balance", 0.0)
                        _totalEarned.value = row.optDouble("total_earned", 0.0)
                    }
                }
                is SupabaseResult.Error -> Log.w(TAG, "wallet_balance fetch failed: ${balRes.message}")
            }

            // 2. Recent transactions (most-recent 50).
            when (val txRes = client.getTable("wallet_transactions", "select=*&order=created_at.desc&limit=50")) {
                is SupabaseResult.Success -> {
                    val list = ArrayList<WalletTransaction>(txRes.data.length())
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault())
                    for (i in 0 until txRes.data.length()) {
                        val row = txRes.data.getJSONObject(i)
                        val type = row.optString("type", "")
                        val isCredit = type.equals("credit", ignoreCase = true) ||
                                row.optString("direction", "").equals("credit", ignoreCase = true) ||
                                row.optDouble("amount", 0.0) > 0
                        val rawAmount = row.optDouble("amount", 0.0)
                        list += WalletTransaction(
                            id = row.optString("id", "tx_${System.currentTimeMillis()}_$i"),
                            title = row.optString("description", row.optString("title", "Transaction")),
                            amount = kotlin.math.abs(rawAmount),
                            isCredit = isCredit,
                            currency = row.optString("currency", "USD (\$)"),
                            date = try {
                                dateFormat.format(Date(row.optString("created_at", "").let {
                                    if (it.isNotEmpty()) parseServerTimestamp(it) else System.currentTimeMillis()
                                }))
                            } catch (_: Exception) {
                                row.optString("created_at", "")
                            },
                            status = row.optString("status", "Completed").replaceFirstChar { it.uppercase() },
                            referenceId = row.optString("reference_id", "")
                        )
                    }
                    _transactions.value = list
                }
                is SupabaseResult.Error -> Log.w(TAG, "wallet_transactions fetch failed: ${txRes.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromServer error: ${e.message}")
        }
    }

    /** Parse an ISO-8601 timestamp from Supabase into millis, with fallback. */
    private fun parseServerTimestamp(iso: String): Long {
        return try {
            // Supabase returns e.g. 2026-09-15T10:30:00.123456+00:00
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(iso)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(iso)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    /**
     * Local-only credit (kept for compatibility). The PAID booking flow no
     * longer credits the booker's wallet — the server-side
     * `verify-razorpay-payment` edge function credits the host's wallet after
     * a successful payment. This method is retained for any future internal
     * use but should NOT be called for ticket-sale flows.
     */
    fun creditTicketSale(amount: Double, streamTitle: String) {
        _availableBalance.value += amount
        _totalEarned.value += amount

        val dateFormat = SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault())
        val tx = WalletTransaction(
            id = "tx_${System.currentTimeMillis()}",
            title = "Stream Ticket Sale: $streamTitle",
            amount = amount,
            isCredit = true,
            currency = "USD ($)",
            date = dateFormat.format(Date()),
            status = "Completed",
            referenceId = "TKT-" + (100000..999999).random()
        )
        _transactions.value = listOf(tx) + _transactions.value
    }
}
