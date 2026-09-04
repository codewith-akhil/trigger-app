package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.CustomGboardNumpad
import com.example.ui.components.HelpModalBottomSheet
import com.example.ui.components.SendingCodeDialog
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OtpVerificationScreen(
    phoneNumber: String,
    countryDialCode: String,
    onWrongNumberClick: () -> Unit,
    onVerificationSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    var otpCode by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showHelpSheet by remember { mutableStateOf(false) }
    var isSendingCode by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }
    var showResendDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val formattedNumber = remember(phoneNumber, countryDialCode) {
        val cleanNumber = phoneNumber.filter { it.isDigit() }
        val splitNumber = if (cleanNumber.length > 5) {
            "${cleanNumber.take(5)} ${cleanNumber.drop(5)}"
        } else {
            cleanNumber
        }
        "$countryDialCode $splitNumber"
    }

    // Auto-verify when 6 digits are entered
    LaunchedEffect(otpCode) {
        if (otpCode.length == 6) {
            isVerifying = true
            delay(1200)
            isVerifying = false
            isSuccess = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GeometricCanvasBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("otp_verification_screen")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.testTag("otp_more_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More options",
                            tint = Color(0xFF44474E)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier
                            .background(Color.White)
                            .testTag("otp_dropdown_menu")
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.help),
                                    color = GeometricTextPrimary,
                                    fontSize = 15.sp
                                )
                            },
                            onClick = {
                                showMenu = false
                                showHelpSheet = true
                            },
                            modifier = Modifier.testTag("otp_menu_help_item")
                        )
                    }
                }
            }

            // Main Verification View
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(10.dp))

                // Title
                Text(
                    text = stringResource(R.string.verifying_title),
                    color = GeometricTextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Description with Wrong number?
                val descriptionText = buildAnnotatedString {
                    append(stringResource(R.string.verifying_desc, formattedNumber))
                    append(" ")
                    withStyle(
                        style = SpanStyle(
                            color = GeometricGreenDark,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(stringResource(R.string.wrong_number))
                    }
                }

                Text(
                    text = descriptionText,
                    color = GeometricTextSecondary,
                    fontSize = 13.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .clickable { onWrongNumberClick() }
                        .padding(horizontal = 6.dp)
                        .testTag("wrong_number_button")
                )

                Spacer(modifier = Modifier.height(36.dp))

                Spacer(modifier = Modifier.height(28.dp))

                // 6 Dash OTP Boxes (Grouped 3 and 3)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("otp_code_container"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // First group of 3
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        OtpDash(char = otpCode.getOrNull(0), isFocused = otpCode.length == 0)
                        OtpDash(char = otpCode.getOrNull(1), isFocused = otpCode.length == 1)
                        OtpDash(char = otpCode.getOrNull(2), isFocused = otpCode.length == 2)
                    }

                    // Separation gap between two sets of 3
                    Spacer(modifier = Modifier.width(28.dp))

                    // Second group of 3
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        OtpDash(char = otpCode.getOrNull(3), isFocused = otpCode.length == 3)
                        OtpDash(char = otpCode.getOrNull(4), isFocused = otpCode.length == 4)
                        OtpDash(char = otpCode.getOrNull(5), isFocused = otpCode.length == 5)
                    }
                }

                Spacer(modifier = Modifier.height(42.dp))

                // Didn't receive code? button
                Text(
                    text = stringResource(R.string.didnt_receive_code),
                    color = GeometricGreenDark,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { showResendDialog = true }
                        .padding(8.dp)
                        .testTag("didnt_receive_code_button")
                )

                Spacer(modifier = Modifier.weight(1f))
            }

            // Custom Gboard Numpad at bottom
            CustomGboardNumpad(
                onNumberClick = { digit ->
                    if (otpCode.length < 6) {
                        otpCode += digit
                    }
                },
                onDeleteClick = {
                    if (otpCode.isNotEmpty()) {
                        otpCode = otpCode.dropLast(1)
                    }
                },
                onSubmitClick = {
                    if (otpCode.length == 6) {
                        isVerifying = true
                        coroutineScope.launch {
                            delay(1000)
                            isVerifying = false
                            isSuccess = true
                        }
                    }
                },
                modifier = Modifier.testTag("otp_numpad")
            )
        }

        // Sending code loading overlay
        if (isSendingCode) {
            SendingCodeDialog(message = stringResource(R.string.sending_code))
        }

        // Verifying spinner
        if (isVerifying) {
            SendingCodeDialog(message = "Verifying…")
        }

        // Verification Success Dialog
        if (isSuccess) {
            AlertDialog(
                onDismissRequest = { /* stay on completed state */ },
                containerColor = Color.White,
                shape = RoundedCornerShape(24.dp),
                icon = {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Success",
                        tint = GeometricGreenPrimary,
                        modifier = Modifier.size(48.dp)
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.verification_complete),
                        color = GeometricTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Text(
                        text = "Your phone number $formattedNumber has been verified successfully on Trigger App.",
                        color = GeometricTextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                },
                confirmButton = {
                    Button(
                        onClick = onVerificationSuccess,
                        colors = ButtonDefaults.buttonColors(containerColor = GeometricGreenPrimary),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("continue_to_trigger_app_button")
                    ) {
                        Text(
                            text = stringResource(R.string.continue_to_app).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            )
        }

        // Resend options dialog
        if (showResendDialog) {
            AlertDialog(
                onDismissRequest = { showResendDialog = false },
                containerColor = Color.White,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = "Verification Options",
                        color = GeometricTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Please choose how you'd like to receive your 6-digit code:",
                            color = GeometricTextSecondary,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    showResendDialog = false
                                    isSendingCode = true
                                    coroutineScope.launch {
                                        delay(1200)
                                        isSendingCode = false
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Resend SMS",
                                color = GeometricGreenPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    showResendDialog = false
                                    isSendingCode = true
                                    coroutineScope.launch {
                                        delay(1200)
                                        isSendingCode = false
                                        otpCode = "773612"
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Call Me",
                                color = GeometricGreenPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showResendDialog = false }) {
                        Text(text = "Cancel", color = GeometricTextMuted)
                    }
                }
            )
        }

        // Help Modal Bottom Sheet
        if (showHelpSheet) {
            HelpModalBottomSheet(onDismiss = { showHelpSheet = false })
        }
    }
}

@Composable
private fun OtpDash(
    char: Char?,
    isFocused: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(22.dp)
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (char != null) {
                Text(
                    text = char.toString(),
                    color = GeometricTextDark,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        // Underline dash
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    if (isFocused) GeometricGreenPrimary else GeometricBorder
                )
        )
    }
}
