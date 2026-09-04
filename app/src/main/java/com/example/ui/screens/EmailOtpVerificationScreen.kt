package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
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
import com.example.service.supabase.SupabaseResult
import com.example.ui.components.CustomGboardNumpad
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class OtpPurpose {
    SIGN_UP,
    FORGOT_PASSWORD
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailOtpVerificationScreen(
    email: String,
    expectedOtp: String,
    purpose: OtpPurpose,
    onWrongEmailClick: () -> Unit,
    onVerificationSuccess: () -> Unit,
    onOtpVerified: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var otpCode by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var resendCountdown by remember { mutableIntStateOf(60) }
    var canResend by remember { mutableStateOf(false) }
    var isResending by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Resend countdown timer — 60 seconds per the spec.
    LaunchedEffect(resendCountdown) {
        if (resendCountdown > 0) {
            delay(1000)
            resendCountdown--
        } else {
            canResend = true
        }
    }

    // Auto-verify when 6 digits are reached — calls the verify-email-otp edge
    // function (server-side salted-hash comparison, never client-side).
    // This function is unauthenticated (no JWT needed) so it works during signup.
    LaunchedEffect(otpCode) {
        if (otpCode.length == 6 && !isVerifying && !isSuccess) {
            isVerifying = true
            errorMessage = null
            successMessage = null
            coroutineScope.launch {
                val otpPurpose = if (purpose == OtpPurpose.SIGN_UP) "signup" else "recovery"
                val payload = org.json.JSONObject().apply {
                    put("email", email)
                    put("code", otpCode)
                    put("purpose", otpPurpose)
                }
                val result = AppServiceContainer.supabaseClient.invokeFunction("verify-email-otp", payload)
                if (result is SupabaseResult.Success) {
                    isVerifying = false
                    isSuccess = true
                    successMessage = "Email verified successfully!"
                    // Capture the verified OTP code so the reset-password screen can use it
                    onOtpVerified(otpCode)
                    delay(800)
                    onVerificationSuccess()
                } else {
                    isVerifying = false
                    val err = (result as? SupabaseResult.Error)?.message
                    // Parse the error message from the edge function response
                    errorMessage = when {
                        err == null -> "Verification failed. Please try again."
                        err.contains("expired", ignoreCase = true) ->
                            "This code has expired. Tap resend to get a new one."
                        err.contains("already been used", ignoreCase = true) ->
                            "This code has already been used. Tap resend to get a new one."
                        err.contains("Too many incorrect", ignoreCase = true) ->
                            "Too many wrong attempts. Tap resend to get a new code."
                        err.contains("No verification code", ignoreCase = true) ->
                            "No code was sent to this email. Tap resend to get one."
                        err.contains("Incorrect", ignoreCase = true) ->
                            err // Already has attempt count from the server
                        err.contains("rate limit", ignoreCase = true) ->
                            "Too many attempts. Please wait a moment and try again."
                        else -> err
                    }
                    // Clear the OTP so the user can re-enter
                    otpCode = ""
                }
            }
        }
    }

    fun addDigit(digit: String) {
        if (otpCode.length < 6 && !isVerifying && !isSuccess) {
            errorMessage = null
            otpCode += digit
        }
    }

    fun removeDigit() {
        if (otpCode.isNotEmpty() && !isVerifying && !isSuccess) {
            errorMessage = null
            otpCode = otpCode.dropLast(1)
        }
    }

    fun resendCode() {
        if (!canResend || isResending) return
        isResending = true
        errorMessage = null
        successMessage = null
        otpCode = ""
        coroutineScope.launch {
            val otpPurpose = if (purpose == OtpPurpose.SIGN_UP) "signup" else "recovery"
            val payload = org.json.JSONObject().apply {
                put("email", email)
                put("purpose", otpPurpose)
            }
            val result = AppServiceContainer.supabaseClient.invokeFunction("send-email-otp", payload)
            isResending = false
            if (result is SupabaseResult.Success) {
                resendCountdown = 60
                canResend = false
                successMessage = "A new code has been sent to $email"
            } else {
                val err = (result as? SupabaseResult.Error)?.message ?: "Failed to resend code"
                errorMessage = when {
                    err.contains("rate limit", ignoreCase = true) || err.contains("Too many", ignoreCase = true) ->
                        "Please wait 60 seconds before requesting another code."
                    err.contains("not configured", ignoreCase = true) ->
                        "Email service is not configured. Please contact support."
                    else -> "Failed to resend code: $err"
                }
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("email_otp_screen"),
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Verify your email",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TriggerHeaderGreen
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onWrongEmailClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TriggerHeaderGreen
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Waiting to detect 6-digit code sent by email to $email. ",
                    fontSize = 14.sp,
                    color = GeometricTextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Wrong email?",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TriggerHeaderGreen,
                    modifier = Modifier
                        .clickable { onWrongEmailClick() }
                        .padding(4.dp)
                )

                // Info card — tells the user to check their email (NO OTP displayed,
                // NO auto-fill — the code is only known to the server + the user's inbox).
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFA5D6A7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TriggerFabGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Email,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Check your email",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B5E20)
                            )
                            Text(
                                text = "Enter the 6-digit code we sent to $email",
                                fontSize = 12.sp,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // 6-digit OTP boxes display
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    for (i in 0 until 6) {
                        val digitChar = if (i < otpCode.length) otpCode[i].toString() else ""
                        val isFocused = i == otpCode.length

                        Box(
                            modifier = Modifier
                                .size(46.dp, 54.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF7F8FA))
                                .border(
                                    width = if (isFocused) 2.dp else 1.dp,
                                    color = when {
                                        errorMessage != null -> Color(0xFFD32F2F)
                                        isSuccess -> TriggerFabGreen
                                        isFocused -> TriggerFabGreen
                                        digitChar.isNotEmpty() -> TriggerHeaderGreen
                                        else -> Color(0xFFCFD8DC)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = digitChar,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF111B21)
                            )
                        }
                    }
                }

                // Error message
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = Color(0xFFFFEBEE),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = Color(0xFFD32F2F),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage!!,
                                color = Color(0xFFD32F2F),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Success message (e.g. "A new code has been sent")
                if (successMessage != null && !isVerifying) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = Color(0xFFE8F5E9),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = TriggerFabGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = successMessage!!,
                                color = Color(0xFF1B5E20),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Verifying or Success indicator
                if (isVerifying) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = TriggerFabGreen,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Verifying code...",
                            fontSize = 14.sp,
                            color = GeometricTextSecondary
                        )
                    }
                } else if (isSuccess) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = TriggerFabGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Verification successful!",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TriggerFabGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Resend code row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(enabled = canResend) {
                        if (canResend) resendCode()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = if (canResend) TriggerFabGreen else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (canResend) "Resend code" else "Resend code in ${resendCountdown}s",
                        fontSize = 14.sp,
                        fontWeight = if (canResend) FontWeight.Bold else FontWeight.Normal,
                        color = if (canResend) TriggerFabGreen else Color.Gray
                    )
                }
            }

            // Trigger Numpad
            CustomGboardNumpad(
                onNumberClick = { digit -> addDigit(digit) },
                onDeleteClick = { removeDigit() },
                onSubmitClick = {}
            )
        }
    }
}

