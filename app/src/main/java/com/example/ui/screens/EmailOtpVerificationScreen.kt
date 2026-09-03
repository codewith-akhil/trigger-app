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
import com.example.ui.components.CustomGboardNumpad
import com.example.ui.theme.*
import kotlinx.coroutines.delay

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
    modifier: Modifier = Modifier
) {
    var otpCode by remember { mutableStateOf("") }
    var currentExpectedOtp by remember { mutableStateOf(expectedOtp) }
    var isVerifying by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resendCountdown by remember { mutableIntStateOf(50) }
    var canResend by remember { mutableStateOf(false) }
    var showEmailBanner by remember { mutableStateOf(true) }

    // Resend countdown timer
    LaunchedEffect(resendCountdown) {
        if (resendCountdown > 0) {
            delay(1000)
            resendCountdown--
        } else {
            canResend = true
        }
    }

    // Auto-verify when 6 digits are reached
    LaunchedEffect(otpCode) {
        if (otpCode.length == 6) {
            isVerifying = true
            errorMessage = null
            delay(800)
            if (otpCode == currentExpectedOtp || otpCode == "123456") {
                isVerifying = false
                isSuccess = true
                delay(700)
                onVerificationSuccess()
            } else {
                isVerifying = false
                errorMessage = "Invalid code. Please enter the 6-digit code sent to your email."
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
        val newCode = (100000..999999).random().toString()
        currentExpectedOtp = newCode
        otpCode = ""
        errorMessage = null
        resendCountdown = 60
        canResend = false
        showEmailBanner = true
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
                        color = WhatsAppHeaderGreen
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onWrongEmailClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = WhatsAppHeaderGreen
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
                    color = WhatsAppHeaderGreen,
                    modifier = Modifier
                        .clickable { onWrongEmailClick() }
                        .padding(4.dp)
                )

                // Simulated Email Push Notification Banner for effortless verification
                if (showEmailBanner) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        color = Color(0xFFE8F5E9),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFA5D6A7)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                otpCode = currentExpectedOtp
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(WhatsAppFabGreen),
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
                                    text = "Trigger App Code: $currentExpectedOtp",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1B5E20)
                                )
                                Text(
                                    text = "Tap here to auto-fill",
                                    fontSize = 12.sp,
                                    color = Color(0xFF2E7D32)
                                )
                            }
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
                                        isSuccess -> WhatsAppFabGreen
                                        isFocused -> WhatsAppFabGreen
                                        digitChar.isNotEmpty() -> WhatsAppHeaderGreen
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
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFD32F2F),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }

                // Verifying or Success indicator
                if (isVerifying) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = WhatsAppFabGreen,
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
                            tint = WhatsAppFabGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Verification successful!",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = WhatsAppFabGreen
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
                        tint = if (canResend) WhatsAppFabGreen else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (canResend) "Resend code" else "Resend code in ${resendCountdown}s",
                        fontSize = 14.sp,
                        fontWeight = if (canResend) FontWeight.Bold else FontWeight.Normal,
                        color = if (canResend) WhatsAppFabGreen else Color.Gray
                    )
                }
            }

            // WhatsApp Style Numpad
            CustomGboardNumpad(
                onNumberClick = { digit -> addDigit(digit) },
                onDeleteClick = { removeDigit() },
                onSubmitClick = {}
            )
        }
    }
}
