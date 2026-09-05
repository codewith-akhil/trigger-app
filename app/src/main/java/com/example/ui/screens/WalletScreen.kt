package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.service.BankDetails
import com.example.service.PayoutDetails
import com.example.service.WalletTransaction

private val HeaderGreen = Color(0xFF008069)
private val DarkBackground = Color(0xFFF7F9FA)
private val CardBackground = Color(0xFFFFFFFF)
private val TextMain = Color(0xFF111B21)
private val TextSub = Color(0xFF667781)
private val AccentGreen = Color(0xFF00A884)
private val RedAlert = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val walletService = AppServiceContainer.walletService

    val balance by walletService.availableBalance.collectAsState()
    val pending by walletService.pendingBalance.collectAsState()
    val totalEarned by walletService.totalEarned.collectAsState()
    val bankDetails by walletService.bankDetails.collectAsState()
    val payoutDetails by walletService.payoutDetails.collectAsState()
    val transactions by walletService.transactions.collectAsState()

    var showWithdrawDialog by remember { mutableStateOf(false) }
    var showEditBankDialog by remember { mutableStateOf(false) }
    var showEditPayoutDialog by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("wallet_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = DarkBackground,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Trigger Wallet & Payouts",
                onBack = onBack
            )
        },
        bottomBar = {
            com.example.ui.components.TriggerBottomNavInset()
        },
        snackbarHost = {
            snackbarMessage?.let { msg ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { snackbarMessage = null }) {
                            Text("DISMISS", color = AccentGreen)
                        }
                    }
                ) {
                    Text(msg)
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Balance Card Hero
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF006853), Color(0xFF008069), Color(0xFF00A884))
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Available Balance",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    imageVector = Icons.Filled.AccountBalanceWallet,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "$${"%.2f".format(balance)}",
                                color = Color.White,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Pending Clearance", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                                    Text("$${"%.2f".format(pending)}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Column {
                                    Text("Total Earned", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                                    Text("$${"%.2f".format(totalEarned)}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Withdraw Button
                            Button(
                                onClick = { showWithdrawDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("wallet_withdraw_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDownward,
                                    contentDescription = null,
                                    tint = HeaderGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Withdraw Funds",
                                    color = HeaderGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            // Bank Details Section
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFE8F5E9)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.AccountBalance, null, tint = HeaderGreen, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Bank Account Details", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                                    Text(bankDetails.bankName, fontSize = 12.sp, color = TextSub)
                                }
                            }
                            TextButton(onClick = { showEditBankDialog = true }) {
                                Text("Update", color = HeaderGreen, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Account Holder:", fontSize = 13.sp, color = TextSub)
                            Text(bankDetails.accountHolderName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Account Number:", fontSize = 13.sp, color = TextSub)
                            Text(bankDetails.accountNumber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("IFSC / Routing Code:", fontSize = 13.sp, color = TextSub)
                            Text(bankDetails.ifscOrRouting, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        }
                    }
                }
            }

            // Payout Methods Section
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFE8F5E9)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Payment, null, tint = HeaderGreen, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Payout Details & Methods", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                                    Text("Preferred: ${payoutDetails.primaryMethod}", fontSize = 12.sp, color = TextSub)
                                }
                            }
                            TextButton(onClick = { showEditPayoutDialog = true }) {
                                Text("Update", color = HeaderGreen, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color(0xFFF1F5F9))
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("UPI ID:", fontSize = 13.sp, color = TextSub)
                            Text(payoutDetails.upiId, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("PayPal Email:", fontSize = 13.sp, color = TextSub)
                            Text(payoutDetails.paypalEmail, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                        }
                    }
                }
            }

            // Transaction History Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Transactions",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain
                    )
                    Text(
                        text = "${transactions.size} records",
                        fontSize = 12.sp,
                        color = TextSub
                    )
                }
            }

            // Transactions List
            items(transactions, key = { it.id }) { tx ->
                TransactionCard(tx)
            }
        }
    }

    // Withdraw Dialog
    if (showWithdrawDialog) {
        WithdrawDialog(
            availableBalance = balance,
            bankDetails = bankDetails,
            payoutDetails = payoutDetails,
            onDismiss = { showWithdrawDialog = false },
            onConfirmWithdraw = { amount, dest ->
                val result = walletService.withdraw(amount, dest)
                showWithdrawDialog = false
                if (result.isSuccess) {
                    snackbarMessage = "Withdrawal of $${"%.2f".format(amount)} initiated to $dest!"
                } else {
                    snackbarMessage = result.exceptionOrNull()?.message ?: "Withdrawal failed"
                }
            }
        )
    }

    // Edit Bank Details Dialog
    if (showEditBankDialog) {
        EditBankDetailsDialog(
            current = bankDetails,
            onDismiss = { showEditBankDialog = false },
            onSave = { name, bank, acct, ifsc, swift ->
                walletService.updateBankDetails(name, bank, acct, ifsc, swift)
                showEditBankDialog = false
                snackbarMessage = "Bank account details updated successfully!"
            }
        )
    }

    // Edit Payout Details Dialog
    if (showEditPayoutDialog) {
        EditPayoutDetailsDialog(
            current = payoutDetails,
            onDismiss = { showEditPayoutDialog = false },
            onSave = { method, upi, paypal ->
                walletService.updatePayoutDetails(method, upi, paypal)
                showEditPayoutDialog = false
                snackbarMessage = "Payout details updated successfully!"
            }
        )
    }
}

@Composable
fun TransactionCard(tx: WalletTransaction) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (tx.isCredit) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (tx.isCredit) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                    contentDescription = null,
                    tint = if (tx.isCredit) AccentGreen else RedAlert,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tx.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextMain
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${tx.date} • ${tx.referenceId}",
                    fontSize = 11.sp,
                    color = TextSub
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${if (tx.isCredit) "+" else "-"}$${"%.2f".format(tx.amount)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (tx.isCredit) AccentGreen else RedAlert
                )
                Text(
                    text = tx.status,
                    fontSize = 11.sp,
                    color = if (tx.status == "Completed") HeaderGreen else Color(0xFFF57C00)
                )
            }
        }
    }
}

@Composable
fun WithdrawDialog(
    availableBalance: Double,
    bankDetails: BankDetails,
    payoutDetails: PayoutDetails,
    onDismiss: () -> Unit,
    onConfirmWithdraw: (Double, String) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var selectedDestination by remember { mutableStateOf("${bankDetails.bankName} (${bankDetails.accountNumber})") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val destinations = listOf(
        "${bankDetails.bankName} (${bankDetails.accountNumber})",
        "UPI: ${payoutDetails.upiId}",
        "PayPal: ${payoutDetails.paypalEmail}"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text("Withdraw Funds", fontWeight = FontWeight.Bold, color = TextMain)
        },
        text = {
            Column {
                Text(
                    text = "Available Balance: $${"%.2f".format(availableBalance)}",
                    fontSize = 13.sp,
                    color = TextSub
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Amount to Withdraw ($)") },
                    placeholder = { Text("e.g. 100.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("Select Destination:", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Spacer(modifier = Modifier.height(4.dp))

                destinations.forEach { dest ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDestination = dest }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = selectedDestination == dest,
                            onClick = { selectedDestination = dest },
                            colors = RadioButtonDefaults.colors(selectedColor = HeaderGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(dest, fontSize = 12.sp, color = TextMain)
                    }
                }

                errorMessage?.let { err ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(err, color = RedAlert, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountText.toDoubleOrNull() ?: 0.0
                    if (amt <= 0.0) {
                        errorMessage = "Please enter an amount greater than $0."
                        return@Button
                    }
                    if (amt > availableBalance) {
                        errorMessage = "Amount exceeds available balance ($${"%.2f".format(availableBalance)})."
                        return@Button
                    }
                    onConfirmWithdraw(amt, selectedDestination)
                },
                colors = ButtonDefaults.buttonColors(containerColor = HeaderGreen)
            ) {
                Text("Confirm Withdrawal", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSub)
            }
        }
    )
}

@Composable
fun EditBankDetailsDialog(
    current: BankDetails,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(current.accountHolderName) }
    var bank by remember { mutableStateOf(current.bankName) }
    var acct by remember { mutableStateOf(current.rawAccountNumber) }
    var ifsc by remember { mutableStateOf(current.ifscOrRouting) }
    var swift by remember { mutableStateOf(current.swiftCode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text("Update Bank Details", fontWeight = FontWeight.Bold, color = TextMain)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account Holder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bank,
                    onValueChange = { bank = it },
                    label = { Text("Bank Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = acct,
                    onValueChange = { acct = it },
                    label = { Text("Account Number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ifsc,
                    onValueChange = { ifsc = it },
                    label = { Text("IFSC / Routing Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = swift,
                    onValueChange = { swift = it },
                    label = { Text("SWIFT / BIC Code (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && acct.isNotBlank()) {
                        onSave(name, bank, acct, ifsc, swift)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = HeaderGreen)
            ) {
                Text("Save Details", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSub)
            }
        }
    )
}

@Composable
fun EditPayoutDetailsDialog(
    current: PayoutDetails,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var primary by remember { mutableStateOf(current.primaryMethod) }
    var upi by remember { mutableStateOf(current.upiId) }
    var paypal by remember { mutableStateOf(current.paypalEmail) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text("Update Payout Details", fontWeight = FontWeight.Bold, color = TextMain)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Primary Payout Method:", fontSize = 13.sp, color = TextSub)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Bank Transfer", "UPI", "PayPal").forEach { m ->
                        FilterChip(
                            selected = primary == m,
                            onClick = { primary = m },
                            label = { Text(m, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE8F5E9),
                                selectedLabelColor = HeaderGreen
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = upi,
                    onValueChange = { upi = it },
                    label = { Text("UPI ID (e.g. name@okhdfcbank)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = paypal,
                    onValueChange = { paypal = it },
                    label = { Text("PayPal Registered Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(primary, upi, paypal) },
                colors = ButtonDefaults.buttonColors(containerColor = HeaderGreen)
            ) {
                Text("Save Payouts", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSub)
            }
        }
    )
}
