package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.service.RazorpayVerifyResult
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
 * Dedicated Payment Validation Page.
 * Verifies the Razorpay cryptographic signature and payment credentials with the server,
 * permanently unlocks the post, renders a digital transaction receipt,
 * and allows the user to immediately jump into the Post View Page to enjoy the content.
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
    val displayPaymentId = remember {
        if (paymentId.isNotBlank()) paymentId else "pay_${UUID.randomUUID().toString().take(14).replace("-", "")}"
    }
    val displayOrderId = remember {
        if (orderId.isNotBlank()) orderId else "order_${UUID.randomUUID().toString().take(12).replace("-", "")}"
    }

    // Launch verification process
    LaunchedEffect(postId, orderId, paymentId, initialStatus) {
        if (validationStatus == ValidationStatus.VERIFYING) {
            // Give user smooth visual feedback of server validation
            delay(1200)

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
                        // Server recorded the unlock — re-fetch the post (fresh
                        // signed media urls) and mark it locally too.
                        feedRepository.unlockPaidPostLocally(postId)
                        feedRepository.getPostLive(postId)
                        validationStatus = ValidationStatus.SUCCESS
                    }
                    is RazorpayVerifyResult.Failed -> {
                        // SECURITY: a failed server verification must NEVER unlock
                        // the post — unlocking here would let any client-side error
                        // (or tampered signature) grant free access. Show the
                        // failure state with the server's diagnosis instead.
                        failureReason = result.message
                        validationStatus = ValidationStatus.FAILED
                    }
                }
            } else {
                // No signature present — there is nothing to verify. Never unlock
                // on an unverifiable payment.
                failureReason = "Payment signature was missing — verification is not possible."
                validationStatus = ValidationStatus.FAILED
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("payment_validation_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Payment Validation",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateToFeed) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandGreen)
            )
        },
        containerColor = Color(0xFFF5F7F9)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (validationStatus) {
                    ValidationStatus.VERIFYING -> {
                        // ----------------------------------------------------
                        // Verification In Progress State
                        // ----------------------------------------------------
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 0.95f,
                            targetValue = 1.08f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(900, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "scale"
                        )

                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .scale(scale)
                                .clip(CircleShape)
                                .background(AccentGreen.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = AccentGreen,
                                strokeWidth = 3.5.dp,
                                modifier = Modifier.size(72.dp)
                            )
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = AccentGreen,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Validating Payment...",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF111B21)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Verifying cryptographic signatures with Razorpay gateway and unlocking exclusive media access...",
                            fontSize = 13.5.sp,
                            color = Color(0xFF667781),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                            lineHeight = 19.sp
                        )
                    }

                    ValidationStatus.SUCCESS -> {
                        // ----------------------------------------------------
                        // Payment Verified & Content Unlocked State
                        // ----------------------------------------------------
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE8F5E9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Success",
                                tint = AccentGreen,
                                modifier = Modifier.size(60.dp)
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
                            modifier = Modifier.padding(horizontal = 20.dp),
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Digital Transaction Receipt Card
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
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

                                Spacer(modifier = Modifier.height(14.dp))
                                Divider(color = Color(0xFFEEEEEE), thickness = 1.dp)
                                Spacer(modifier = Modifier.height(12.dp))

                                ReceiptRow(label = "Payment ID", value = displayPaymentId)
                                ReceiptRow(label = "Order ID", value = displayOrderId)
                                ReceiptRow(
                                    label = "Amount Paid",
                                    value = "${post?.currency?.take(3) ?: "INR"} ${"%.2f".format(post?.priceAmount ?: 0.0)}"
                                )
                                ReceiptRow(label = "Item", value = "Post by ${post?.authorName ?: "Creator"}")
                                ReceiptRow(
                                    label = "Date & Time",
                                    value = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
                                )
                                ReceiptRow(label = "Payment Method", value = "Razorpay Gateway (UPI/Card)")
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Primary Action: Watch Post Now -> PostViewScreen
                        Button(
                            onClick = { onNavigateToPostView(postId) },
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
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
                                .height(48.dp)
                        ) {
                            Text(
                                text = "Back to Feed",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }

                    ValidationStatus.FAILED -> {
                        // ----------------------------------------------------
                        // Failure / Cancelled State
                        // ----------------------------------------------------
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFFEBEE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ErrorOutline,
                                contentDescription = "Failed",
                                tint = Color(0xFFD32F2F),
                                modifier = Modifier.size(56.dp)
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
                            modifier = Modifier.padding(horizontal = 24.dp),
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(28.dp))

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
                                .height(48.dp)
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
