package com.example.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun updateBankDetails(
        accountHolderName: String,
        bankName: String,
        accountNumber: String,
        ifscOrRouting: String,
        swiftCode: String
    ) {
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
        Log.i(TAG, "Bank details updated for account ending in ${accountNumber.takeLast(4)}")
    }

    fun updatePayoutDetails(
        primaryMethod: String,
        upiId: String,
        paypalEmail: String
    ) {
        _payoutDetails.value = PayoutDetails(
            primaryMethod = primaryMethod,
            upiId = upiId.trim(),
            paypalEmail = paypalEmail.trim()
        )
        Log.i(TAG, "Payout details updated: method=$primaryMethod")
    }

    fun withdraw(
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

        _availableBalance.value = current - amount

        val dateFormat = SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault())
        val refId = "WTHD-" + (100000..999999).random()
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

        _transactions.value = listOf(tx) + _transactions.value
        Log.i(TAG, "Withdrawal initiated: $$amount to $destination. Ref: $refId")
        return Result.success(tx)
    }

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
