package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.model.PostMediaType
import com.example.model.UserRepository
import com.example.service.RazorpayOrderResult
import com.example.ui.payment.RazorpayCheckoutParams
import com.example.ui.payment.RazorpayCheckoutResult
import com.example.ui.theme.TriggerFabGreen
import com.example.ui.theme.TriggerHeaderGreen
import kotlinx.coroutines.launch

private val BrandGreen = TriggerHeaderGreen
private val AccentGreen = TriggerFabGreen

/**
 * Dedicated Payment Overview Page displayed when tapping "Pay and Watch".
 * Outlines the post content summary, itemized price breakdown, secure unlock guarantees,
 * and launches the Razorpay Payment Gateway, then routes to Payment Validation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentOverviewScreen(
    postId: String,
    onBack: () -> Unit,
    onNavigateToValidation: (orderId: String, paymentId: String, signature: String, status: String, error: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val feedRepository = AppServiceContainer.feedRepository
    val posts by feedRepository.posts.collectAsState()
    val post = posts.find { it.id == postId }
    val userProfile by UserRepository.profile.collectAsState()

    var isCreatingOrder by remember { mutableStateOf(false) }
    var activeOrderId by remember { mutableStateOf<String?>(null) }

    // ------------------------------------------------------------------------
    // Razorpay Checkout Activity Launcher
    // ------------------------------------------------------------------------
    val checkoutLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (val checkout = RazorpayCheckoutResult.fromActivityResult(result)) {
            is RazorpayCheckoutResult.Success -> {
                isCreatingOrder = false
                onNavigateToValidation(
                    checkout.orderId,
                    checkout.paymentId,
                    checkout.signature,
                    "success",
                    null
                )
            }
            is RazorpayCheckoutResult.Failure -> {
                isCreatingOrder = false
                onNavigateToValidation(
                    activeOrderId.orEmpty(),
                    "",
                    "",
                    "failed",
                    checkout.message
                )
            }
            RazorpayCheckoutResult.Cancelled -> {
                isCreatingOrder = false
                Toast.makeText(context, "Payment cancelled by user", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startRazorpayGateway() {
        if (post == null) return
        isCreatingOrder = true
        coroutineScope.launch {
            val currencyCode = post.currency.trim().substringBefore(' ').uppercase().ifBlank { "INR" }
            when (val order = AppServiceContainer.razorpayPaymentService.createOrder(
                amountMajor = post.priceAmount,
                currencyCode = currencyCode,
                purpose = "post_unlock",
                streamId = post.id
            )) {
                is RazorpayOrderResult.Ready -> {
                    activeOrderId = order.orderId
                    checkoutLauncher.launch(
                        RazorpayCheckoutParams.intent(
                            context,
                            RazorpayCheckoutParams(
                                orderId = order.orderId,
                                keyId = order.keyId,
                                description = "Unlock Post: ${post.authorName}",
                                prefillName = userProfile.name,
                                prefillEmail = userProfile.email
                            )
                        )
                    )
                }
                is RazorpayOrderResult.Failed -> {
                    isCreatingOrder = false
                    Toast.makeText(context, "Failed to initialize gateway: ${order.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    if (post == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF7F8FA)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Post details could not be loaded.", color = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Return") }
            }
        }
        return
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("payment_overview_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Payment Overview",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = Color(0xFFA7E8D8),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "256-Bit SSL Encrypted Checkout",
                                fontSize = 11.sp,
                                color = Color(0xFFA7E8D8)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandGreen)
            )
        },
        containerColor = Color(0xFFF6F8FA)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ----------------------------------------------------------------
            // 1. Post Preview & Creator Summary Card
            // ----------------------------------------------------------------
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE5E9EC)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!post.authorAvatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = post.authorAvatarUrl,
                                    contentDescription = post.authorName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    text = post.authorName.take(1).uppercase(),
                                    color = BrandGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = post.authorName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color(0xFF111B21)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Verified Creator",
                                    tint = AccentGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Text(
                                text = "@${post.authorUsername.ifBlank { "creator" }} • Content Creator",
                                fontSize = 12.sp,
                                color = Color(0xFF667781)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFFF3E0)
                        ) {
                            Text(
                                text = "EXCLUSIVE",
                                color = Color(0xFFE65100),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Post Media Preview Snippet (with security blur representation)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF141E24))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            // Locked paid posts only carry the tiny preview — never
                            // the full media URL.
                            val thumbUrl = post.lockedPreviewUrls.firstOrNull()
                                ?: post.mediaUrls.firstOrNull() ?: ""
                            if (thumbUrl.isNotBlank()) {
                                AsyncImage(
                                    model = thumbUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .blur(14.dp)
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (post.mediaType == PostMediaType.VIDEO) "HD Video Stream Access" else "Photo Gallery (${post.mediaUrls.size} images)",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = post.description.ifBlank { "Exclusive premium media post." },
                                color = Color(0xFFB0BEC5),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------------------
            // 2. Itemized Cost Breakdown Card
            // ----------------------------------------------------------------
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Cost Breakdown",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color(0xFF111B21)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Creator Post Access", color = Color(0xFF54656F), fontSize = 14.sp)
                        Text(
                            "${post.currency.take(3)} ${"%.2f".format(post.priceAmount)}",
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF111B21),
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Platform Convenience Fee", color = Color(0xFF54656F), fontSize = 14.sp)
                        Text("FREE", color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Taxes & GST", color = Color(0xFF54656F), fontSize = 14.sp)
                        Text("Included", color = Color(0xFF54656F), fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = Color(0xFFE5E9EC), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Total Payable Amount",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF111B21)
                        )
                        Text(
                            text = "${post.currency.take(3)} ${"%.2f".format(post.priceAmount)}",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            color = AccentGreen
                        )
                    }
                }
            }

            // ----------------------------------------------------------------
            // 3. Guaranteed Benefits & Protection
            // ----------------------------------------------------------------
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Unlock Guarantees",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFF111B21)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val benefits = listOf(
                        Pair(Icons.Filled.Bolt, "Instant Activation — Media unblurs instantly upon payment verification"),
                        Pair(Icons.Filled.AllInclusive, "Lifetime Access — Return and re-watch anytime on your account"),
                        Pair(Icons.Filled.Shield, "100% Secure Gateway — Processed by Razorpay with end-to-end encryption"),
                        Pair(Icons.Filled.VolunteerActivism, "Direct Support — Funds are routed directly to the creator's wallet")
                    )

                    benefits.forEach { (icon, text) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(AccentGreen.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = AccentGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = text,
                                color = Color(0xFF3B4A54),
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------------------
            // 4. User Billing Info
            // ----------------------------------------------------------------
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFE8F5E9).copy(alpha = 0.6f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC8E6C9)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Billing to: ${userProfile.name.ifBlank { "Current User" }}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color(0xFF1B5E20)
                        )
                        Text(
                            text = userProfile.email.ifBlank { "user@trigger.app" },
                            fontSize = 11.5.sp,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ----------------------------------------------------------------
            // 5. Proceed to Razorpay Payment Button
            // ----------------------------------------------------------------
            Button(
                onClick = { startRazorpayGateway() },
                enabled = !isCreatingOrder,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("proceed_to_razorpay_button")
            ) {
                if (isCreatingOrder) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Securing Payment Gateway...",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Pay ${post.currency.take(3)} ${"%.2f".format(post.priceAmount)} via Razorpay",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Official Razorpay Partner Gateway • 100% Safe",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
