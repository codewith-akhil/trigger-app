package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.service.RazorpayVerifyResult
import com.example.ui.components.TriggerAlertDialog
import com.example.ui.components.TriggerTwoToneSpinner
import com.example.ui.theme.TriggerFabGreen
import com.example.ui.theme.TriggerHeaderGreen
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

private val BrandGreen = TriggerHeaderGreen
private val AccentGreen = TriggerFabGreen

enum class ValidationStatus {
    VERIFYING,
    SUCCESS,
    FAILED
}

/**
 * Dedicated Payment Validation Page matching Section.png design specification.
 * After payment gateway procedure, displays the exact model confirmation screen
 * ("Your payment is being processed") until server validation resolves the status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentValidationScreen(
    postId: String,
    orderId: String,
    paymentId: String,
    signature: String,
    initialStatus: String,
    errorMessage: String?,
    onNavigateToPostView: (postId: String) -> Unit,
    onNavigateToFeed: () -> Unit,
    onRetryPayment: (postId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val feedRepository = AppServiceContainer.feedRepository
    val posts by feedRepository.posts.collectAsState()
    val post = posts.find { it.id == postId }

    var validationStatus by remember {
        mutableStateOf(
            if (initialStatus.equals("failed", ignoreCase = true)) ValidationStatus.FAILED
            else ValidationStatus.VERIFYING
        )
    }
    var failureReason by remember { mutableStateOf(errorMessage ?: "Payment verification failed.") }
    var showLeaveConfirmDialog by remember { mutableStateOf(false) }

    val payableAmountText = remember(post) {
        val price = post?.priceAmount?.toInt() ?: 0
        if (price > 0) "₹$price" else "₹49"
    }

    val displayPaymentId = remember {
        if (paymentId.isNotBlank()) paymentId else "pay_${UUID.randomUUID().toString().take(14).replace("-", "")}"
    }
    val displayOrderId = remember {
        if (orderId.isNotBlank()) orderId else "order_${UUID.randomUUID().toString().take(12).replace("-", "")}"
    }

    // Intercept hardware back button when verifying to prevent accidental exit
    BackHandler(enabled = validationStatus == ValidationStatus.VERIFYING) {
        showLeaveConfirmDialog = true
    }

    // Launch verification process
    LaunchedEffect(postId, orderId, paymentId, initialStatus) {
        if (validationStatus == ValidationStatus.VERIFYING) {
            // Keep confirmation screen active for realistic verification window
            delay(1600)

            if (orderId.isNotBlank() && paymentId.isNotBlank() && signature.isNotBlank()) {
                val result = AppServiceContainer.razorpayPaymentService.verifyPayment(
                    orderId = orderId,
                    paymentId = paymentId,
                    signature = signature,
                    purpose = "post_unlock",
                    streamId = postId
                )
                when (result) {
                    is RazorpayVerifyResult.Verified -> {
                        feedRepository.unlockPaidPostLocally(postId)
                        feedRepository.getPostLive(postId)
                        validationStatus = ValidationStatus.SUCCESS
                    }
                    is RazorpayVerifyResult.Failed -> {
                        failureReason = result.message
                        validationStatus = ValidationStatus.FAILED
                    }
                }
            } else {
                // If signature missing or test order
                failureReason = "Payment signature was missing — verification is not possible."
                validationStatus = ValidationStatus.FAILED
            }
        }
    }

    if (showLeaveConfirmDialog) {
        TriggerAlertDialog(
            onDismissRequest = { showLeaveConfirmDialog = false },
            title = {
                Text(
                    text = "Payment in Progress",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827)
                )
            },
            text = {
                Text(
                    text = "Your payment is currently being processed. Leaving now will not cancel your order, but your media unlock may take a moment to reflect in your feed. Do you want to leave?",
                    fontSize = 14.sp,
                    color = Color(0xFF4B5563),
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirmDialog = false
                        onNavigateToFeed()
                    }
                ) {
                    Text("Leave", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirmDialog = false }) {
                    Text("Stay", color = Color(0xFF008069), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("payment_validation_screen"),
        containerColor = Color.White,
        topBar = {
            Surface(
                color = Color.White,
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (validationStatus == ValidationStatus.VERIFYING) {
                                showLeaveConfirmDialog = true
                            } else {
                                onNavigateToFeed()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFF111827),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (validationStatus == ValidationStatus.SUCCESS) "Payment Verified" else "Payment",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF111827)
                        )
                        Text(
                            text = "Amount payable: $payableAmountText",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp,
                            color = Color(0xFF111827)
                        )
                    }
                }
            }
        },
        bottomBar = {
            // Model Bottom Navigation Bar matching Section.png
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(color = Color(0xFFE5E7EB), thickness = 1.dp)
                Surface(
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Tab 1: Chats (Selected with light green pill)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    if (validationStatus == ValidationStatus.VERIFYING) showLeaveConfirmDialog = true
                                    else onNavigateToFeed()
                                }
                                .padding(horizontal = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFFD1FADF))
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Chat,
                                    contentDescription = "Chats",
                                    tint = Color(0xFF008069),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Chats",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF008069)
                            )
                        }

                        // Tab 2: Updates
                        ValidationBottomNavItem(
                            icon = Icons.Outlined.History,
                            label = "Updates",
                            onClick = {
                                if (validationStatus == ValidationStatus.VERIFYING) showLeaveConfirmDialog = true
                                else onNavigateToFeed()
                            }
                        )

                        // Tab 3: Stream
                        ValidationBottomNavItem(
                            icon = Icons.Outlined.LiveTv,
                            label = "Stream",
                            onClick = {
                                if (validationStatus == ValidationStatus.VERIFYING) showLeaveConfirmDialog = true
                                else onNavigateToFeed()
                            }
                        )

                        // Tab 4: Calls
                        ValidationBottomNavItem(
                            icon = Icons.Outlined.Call,
                            label = "Calls",
                            onClick = {
                                if (validationStatus == ValidationStatus.VERIFYING) showLeaveConfirmDialog = true
                                else onNavigateToFeed()
                            }
                        )

                        // Tab 5: Profile
                        ValidationBottomNavItem(
                            icon = Icons.Outlined.Person,
                            label = "Profile",
                            onClick = {
                                if (validationStatus == ValidationStatus.VERIFYING) showLeaveConfirmDialog = true
                                else onNavigateToFeed()
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.White)
        ) {
            when (validationStatus) {
                ValidationStatus.VERIFYING -> {
                    // ----------------------------------------------------
                    // EXACT ATTACHED MODEL UI SCREEN FROM Section.png
                    // ----------------------------------------------------
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.weight(1f))

                        // Custom two-tone royal blue + light grey circular spinner
                        TriggerTwoToneSpinner(
                            size = 84.dp,
                            strokeWidth = 6.dp,
                            primaryColor = Color(0xFF0052CC),
                            trackColor = Color(0xFFE5E7EB)
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        Text(
                            text = "Your payment is being\nprocessed.",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF111827),
                            textAlign = TextAlign.Center,
                            lineHeight = 28.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Please hold on as it may take upto 30 mins in\nsome cases.",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFF4B5563),
                            textAlign = TextAlign.Center,
                            lineHeight = 19.sp
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = "Note: Do not hit back button or close this screen\nuntil the transaction is complete",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF6B7280),
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                }

                ValidationStatus.SUCCESS -> {
                    // ----------------------------------------------------
                    // Payment Verified & Content Unlocked State
                    // ----------------------------------------------------
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(24.dp))

                        Box(
                            modifier = Modifier
                                .size(84.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE8F5E9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Success",
                                tint = AccentGreen,
                                modifier = Modifier.size(56.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Payment Verified!",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111B21)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Exclusive post has been permanently unlocked. You can now view full-resolution media with zero blur.",
                            fontSize = 13.5.sp,
                            color = Color(0xFF54656F),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Digital Transaction Receipt Card
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAFB)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E7EB)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Transaction Receipt",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color(0xFF111B21)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFFE8F5E9)
                                    ) {
                                        Text(
                                            text = "VERIFIED",
                                            color = AccentGreen,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = Color(0xFFE5E7EB), thickness = 1.dp)
                                Spacer(modifier = Modifier.height(10.dp))

                                ReceiptRow(label = "Payment ID", value = displayPaymentId)
                                ReceiptRow(label = "Order ID", value = displayOrderId)
                                ReceiptRow(
                                    label = "Amount Paid",
                                    value = "${post?.currency?.take(3) ?: "INR"} ${"%.2f".format(post?.priceAmount ?: 49.0)}"
                                )
                                ReceiptRow(label = "Item", value = "Post by ${post?.authorName ?: "Creator"}")
                                ReceiptRow(
                                    label = "Date & Time",
                                    value = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
                                )
                                ReceiptRow(label = "Payment Method", value = "Razorpay Gateway (UPI/Card)")
                            }
                        }

                        Spacer(modifier = Modifier.height(22.dp))

                        // Primary Action: Watch Post Now -> PostViewScreen
                        Button(
                            onClick = { onNavigateToPostView(postId) },
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("watch_unlocked_post_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Watch Post Now",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Secondary Action: Return to Feed
                        OutlinedButton(
                            onClick = onNavigateToFeed,
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF111B21)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Text(
                                text = "Back to Feed",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                ValidationStatus.FAILED -> {
                    // ----------------------------------------------------
                    // Failure / Cancelled State
                    // ----------------------------------------------------
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(84.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFEBEE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ErrorOutline,
                                contentDescription = "Failed",
                                tint = Color(0xFFD32F2F),
                                modifier = Modifier.size(52.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "Payment Incomplete",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111B21)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = failureReason,
                            fontSize = 13.5.sp,
                            color = Color(0xFF667781),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { onRetryPayment(postId) },
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Try Again", color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = onNavigateToFeed,
                            shape = RoundedCornerShape(26.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Text("Back to Feed", color = Color(0xFF111B21))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ValidationBottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color(0xFF6B7280),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            color = Color(0xFF6B7280),
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun ReceiptRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            color = Color(0xFF667781)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF111B21)
        )
    }
}
