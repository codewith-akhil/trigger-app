package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import com.example.ui.components.TriggerTopHeader
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val TriggerDanger = Color(0xFFD32F2F)
private val TriggerDangerBg = Color(0xFFFFEBEE)
private val TriggerGreenAccent = Color(0xFF008069)
private val TriggerFabGreen = Color(0xFF00A884)
private val TriggerTextPrimary = Color(0xFF111B21)
private val TriggerTextSecondary = Color(0xFF667781)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteAccountScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var reason by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(DeleteStep.REASON) }
    var otpCode by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resendCountdown by remember { mutableIntStateOf(0) }
    var canResend by remember { mutableStateOf(false) }

    LaunchedEffect(resendCountdown) {
        if (resendCountdown > 0) {
            delay(1000)
            resendCountdown--
        } else if (step == DeleteStep.OTP) {
            canResend = true
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().testTag("delete_account_screen"),
        containerColor = Color.White,
        topBar = { TriggerTopHeader(title = "Delete Account", onBack = onBack) }
    ) { innerPadding ->
        when (step) {
            DeleteStep.REASON -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    // Warning card
                    Surface(
                        color = TriggerDangerBg,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "⚠️ Warning",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TriggerDanger
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Deleting your account will remove all the stored data withus immediately, please save your important data before deleting.",
                                fontSize = 13.sp,
                                color = TriggerDanger,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Tell us why you're leaving (optional)",
                        fontSize = 14.sp,
                        color = TriggerTextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = reason,
                        onValueChange = { if (it.length <= 500) reason = it },
                        placeholder = { Text("Write your reason here...") },
                        singleLine = false,
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TriggerGreenAccent,
                            unfocusedBorderColor = Color(0xFFCFD8DC),
                            cursorColor = TriggerGreenAccent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${reason.length}/500",
                        fontSize = 12.sp,
                        color = TriggerTextSecondary,
                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Delete button
                    Button(
                        onClick = {
                            isProcessing = true
                            errorMessage = null
                            coroutineScope.launch {
                                val payload = JSONObject().put("action", "send_otp")
                                val result = AppServiceContainer.supabaseClient
                                    .invokeFunction("delete-account-otp", payload)
                                isProcessing = false
                                if (result is SupabaseResult.Success) {
                                    step = DeleteStep.OTP
                                    resendCountdown = 60
                                    canResend = false
                                    Toast.makeText(context, "Verification code sent to your email", Toast.LENGTH_SHORT).show()
                                } else {
                                    val err = (result as? SupabaseResult.Error)?.message ?: "Failed to send code"
                                    errorMessage = if (err.contains("rate limit", ignoreCase = true)) {
                                        "Too many attempts. Please wait and try again."
                                    } else {
                                        "Failed to send verification code: $err"
                                    }
                                }
                            }
                        },
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = TriggerDanger),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp), color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sending code...", color = Color.White, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete my account", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TriggerTextSecondary)
                    ) {
                        Text("Back", fontWeight = FontWeight.Medium)
                    }

                    errorMessage?.let {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(it, color = TriggerDanger, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            }

            DeleteStep.OTP -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Enter the 6-digit code sent to your email to confirm account deletion.",
                        fontSize = 14.sp,
                        color = TriggerTextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = otpCode,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) otpCode = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        placeholder = { Text("6-digit code") },
                        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                            textAlign = TextAlign.Center,
                            fontSize = 24.sp,
                            letterSpacing = 8.sp
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TriggerDanger,
                            unfocusedBorderColor = Color(0xFFCFD8DC),
                            cursorColor = TriggerDanger
                        ),
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Resend
                    if (canResend) {
                        TextButton(onClick = {
                            isProcessing = true
                            errorMessage = null
                            coroutineScope.launch {
                                val payload = JSONObject().put("action", "send_otp")
                                val result = AppServiceContainer.supabaseClient
                                    .invokeFunction("delete-account-otp", payload)
                                isProcessing = false
                                if (result is SupabaseResult.Success) {
                                    resendCountdown = 60
                                    canResend = false
                                    Toast.makeText(context, "New code sent", Toast.LENGTH_SHORT).show()
                                } else {
                                    errorMessage = "Failed to resend code. Please try again."
                                }
                            }
                        }) {
                            Text("Resend code", color = TriggerGreenAccent, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Text(
                            text = "Resend available in ${resendCountdown}s",
                            fontSize = 13.sp,
                            color = TriggerTextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Confirm & Delete
                    Button(
                        onClick = {
                            isProcessing = true
                            errorMessage = null
                            coroutineScope.launch {
                                val payload = JSONObject().apply {
                                    put("action", "confirm_delete")
                                    put("code", otpCode)
                                    if (reason.isNotBlank()) put("reason", reason.trim())
                                }
                                val result = AppServiceContainer.supabaseClient
                                    .invokeFunction("delete-account-otp", payload)
                                isProcessing = false
                                if (result is SupabaseResult.Success) {
                                    // Fire navigation IMMEDIATELY so the user is taken to
                                    // the landing screen regardless of whether they back
                                    // out during the visual DELETING / SUCCESS overlays
                                    // below. The overlays are kept purely for transient
                                    // visual feedback during the nav transition.
                                    onDeleted()
                                    step = DeleteStep.DELETING
                                    delay(2500) // show "Deleting your data, Please wait" for 2.5s
                                    step = DeleteStep.SUCCESS
                                    delay(5000) // show success for 5s
                                } else {
                                    val err = (result as? SupabaseResult.Error)?.message ?: "Failed to delete account"
                                    errorMessage = when {
                                        err.contains("Incorrect", ignoreCase = true) -> "Incorrect code. Please try again."
                                        err.contains("expired", ignoreCase = true) -> "Code expired. Please request a new one."
                                        err.contains("already been used", ignoreCase = true) -> "Code already used. Please request a new one."
                                        else -> err
                                    }
                                    otpCode = ""
                                }
                            }
                        },
                        enabled = !isProcessing && otpCode.length == 6,
                        colors = ButtonDefaults.buttonColors(containerColor = TriggerDanger),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp), color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Deleting...", color = Color.White, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Confirm & Delete", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onBack,
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TriggerTextSecondary)
                    ) {
                        Text("Back", fontWeight = FontWeight.Medium)
                    }

                    errorMessage?.let {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(it, color = TriggerDanger, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            }

            DeleteStep.DELETING -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = TriggerDanger,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Deleting your data, Please wait",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = TriggerTextPrimary
                        )
                    }
                }
            }

            DeleteStep.SUCCESS -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = TriggerFabGreen,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Successfully deleted your account",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TriggerTextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Thank you for using Trigger App. Redirecting...",
                            fontSize = 14.sp,
                            color = TriggerTextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

private enum class DeleteStep {
    REASON,
    OTP,
    DELETING,
    SUCCESS
}
