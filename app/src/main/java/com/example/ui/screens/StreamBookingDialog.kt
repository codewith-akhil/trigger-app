package com.example.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.model.UserRepository
import com.example.service.RazorpayOrderResult
import com.example.service.RazorpayVerifyResult
import com.example.service.ScheduledStream
import com.example.service.StreamPricingType
import com.example.ui.payment.RazorpayCheckoutParams
import com.example.ui.payment.RazorpayCheckoutResult
import kotlinx.coroutines.launch

private val HeaderGreen = Color(0xFF008069)
private val TextDark = Color(0xFF111B21)
private val TextMuted = Color(0xFF667781)
private val AccentGreen = Color(0xFF00A884)
private val RedAlert = Color(0xFFD32F2F)

@Composable
fun StreamBookingDialog(
    stream: ScheduledStream,
    onDismiss: () -> Unit,
    onBookingSuccess: () -> Unit
) {
    val context = LocalContext.current
    val userProfile by UserRepository.profile.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val userName = userProfile.name
    val userEmail = userProfile.email

    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ---------------------------------------------------------------------------
    // Razorpay checkout launcher.
    // Declared in the screen composition (NOT inside the AlertDialog content)
    // so the ActivityResult registry owner from MainActivity is available.
    // ---------------------------------------------------------------------------
    val checkoutLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (val checkout = RazorpayCheckoutResult.fromActivityResult(result)) {
            is RazorpayCheckoutResult.Success -> {
                isProcessing = true
                coroutineScope.launch {
                    // 1. Server verifies the HMAC signature against the key
                    //    secret and — for stream_booking — inserts the
                    //    stream_bookings row and credits the host's wallet.
                    when (val verify = AppServiceContainer.razorpayPaymentService.verifyPayment(
                        orderId = checkout.orderId,
                        paymentId = checkout.paymentId,
                        signature = checkout.signature,
                        purpose = "stream_booking",
                        streamId = stream.id
                    )) {
                        is RazorpayVerifyResult.Verified -> {
                            // 2. Local booking side-effects: in-memory slot
                            //    count, confirmation pushes to attendee and
                            //    host, and the confirmation email dispatch.
                            val booked = AppServiceContainer.streamScheduleService.bookSlot(
                                context = context,
                                streamId = stream.id,
                                userName = userName,
                                userEmail = userEmail
                            )
                            isProcessing = false
                            if (booked.isSuccess) {
                                onBookingSuccess()
                            } else {
                                errorMessage = booked.exceptionOrNull()?.message
                                    ?: "Booking could not be completed after payment."
                            }
                        }
                        is RazorpayVerifyResult.Failed -> {
                            isProcessing = false
                            errorMessage = "Payment verification failed: ${verify.message}"
                        }
                    }
                }
            }
            is RazorpayCheckoutResult.Failure -> {
                isProcessing = false
                errorMessage = checkout.message
            }
            RazorpayCheckoutResult.Cancelled -> {
                isProcessing = false
                // Silent — the user backed out of the sheet; no charge.
            }
        }
    }

    fun startPaidBooking() {
        isProcessing = true
        errorMessage = null
        coroutineScope.launch {
            // Display currency ("USD ($)", "INR (₹)") → ISO code ("USD", "INR").
            // The edge function validates the code server-side.
            val currencyCode = stream.currency.trim().substringBefore(' ')
                .uppercase().ifBlank { "INR" }

            when (val order = AppServiceContainer.razorpayPaymentService.createOrder(
                amountMajor = stream.amount,
                currencyCode = currencyCode,
                purpose = "stream_booking",
                streamId = stream.id
            )) {
                is RazorpayOrderResult.Ready -> {
                    checkoutLauncher.launch(
                        RazorpayCheckoutParams.intent(
                            context,
                            RazorpayCheckoutParams(
                                orderId = order.orderId,
                                keyId = order.keyId,
                                description = stream.title,
                                prefillName = userName,
                                prefillEmail = userEmail
                            )
                        )
                    )
                    // isProcessing stays true until the sheet returns a result.
                }
                is RazorpayOrderResult.Failed -> {
                    isProcessing = false
                    errorMessage = order.message
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8F5E9)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ConfirmationNumber,
                        contentDescription = null,
                        tint = HeaderGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Reserve Your Slot",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark
                    )
                    Text(
                        text = stream.title,
                        fontSize = 13.sp,
                        color = TextMuted,
                        maxLines = 1
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Stream Details Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF8FAFC),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Host:", fontSize = 13.sp, color = TextMuted)
                            Text(stream.hostName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Date & Time:", fontSize = 13.sp, color = TextMuted)
                            Text("${stream.date} • ${stream.time}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Slot Capacity:", fontSize = 13.sp, color = TextMuted)
                            val slotText = if (stream.isUnlimitedSlots) {
                                "Unlimited (ANY)"
                            } else {
                                "${stream.slotsBooked} / ${stream.maxSlots} Booked (${stream.remainingSlots} left)"
                            }
                            Text(
                                text = slotText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (stream.isFull) RedAlert else HeaderGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Ticket Price:", fontSize = 13.sp, color = TextMuted)
                            Text(
                                text = stream.priceDisplay,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (stream.type == StreamPricingType.PAID) HeaderGreen else Color(0xFF2E7D32)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Slot Availability Verification Banner
                if (stream.isFull) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFEBEE),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = RedAlert, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Sold Out! All ${stream.maxSlots} slots have been reserved.",
                                color = RedAlert,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE8F5E9),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (stream.isUnlimitedSlots) "Slots available (Unlimited)" else "Verified: ${stream.remainingSlots} slots currently available",
                                color = HeaderGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // If Paid: secure Razorpay payment notice. Method selection
                // (Cards / UPI / NetBanking / Wallets) happens INSIDE the
                // Razorpay sheet — no local method picker needed.
                if (stream.type == StreamPricingType.PAID && !stream.isFull) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF0FDF4),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = HeaderGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Pay ${stream.priceDisplay} securely",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark
                                )
                                Text(
                                    text = "Cards • UPI • NetBanking • Wallets — powered by Razorpay",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }

                // Error message
                errorMessage?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = err, color = RedAlert, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Push notification & email confirmation will be sent to $userEmail and host upon booking.",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        },
        confirmButton = {
            val isPaid = stream.type == StreamPricingType.PAID && stream.amount > 0
            Button(
                onClick = {
                    if (stream.isFull) {
                        errorMessage = "Cannot book: Slot limit reached."
                        return@Button
                    }
                    if (isPaid) {
                        startPaidBooking()
                        return@Button
                    }
                    isProcessing = true
                    errorMessage = null

                    // FREE booking: just book the slot (no payment). For PAID
                    // bookings the server-side `verify-razorpay-payment` edge
                    // function credits the host's wallet after checkout.
                    coroutineScope.launch {
                        val result = AppServiceContainer.streamScheduleService.bookSlot(
                            context = context,
                            streamId = stream.id,
                            userName = userName,
                            userEmail = userEmail
                        )
                        isProcessing = false
                        if (result.isSuccess) {
                            onBookingSuccess()
                        } else {
                            errorMessage = result.exceptionOrNull()?.message ?: "Booking failed"
                        }
                    }
                },
                enabled = !stream.isFull && !isProcessing,
                colors = ButtonDefaults.buttonColors(containerColor = HeaderGreen),
                modifier = Modifier.testTag("confirm_booking_btn")
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text(
                        text = if (isPaid) "Pay ${stream.priceDisplay} & Reserve" else "Reserve Free Slot",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isProcessing) {
                Text("Cancel", color = TextMuted)
            }
        }
    )
}
