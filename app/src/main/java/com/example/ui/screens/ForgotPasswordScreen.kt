package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(
    onNavigateToOtp: (email: String, generatedOtp: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var email by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    fun handleSubmit() {
        errorMessage = null
        val trimmedEmail = email.trim().lowercase()

        // --- Input validation ---
        if (trimmedEmail.isEmpty()) {
            errorMessage = "Email is required"
            return
        }
        val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        if (!emailRegex.matches(trimmedEmail)) {
            errorMessage = "Please enter a valid email address"
            return
        }

        isSubmitting = true
        coroutineScope.launch {
            // Step 1: Check if the email is registered in the backend
            val checkPayload = org.json.JSONObject().apply { put("email", trimmedEmail) }
            val checkResult = AppServiceContainer.supabaseClient.invokeFunction("check-email", checkPayload)
            if (checkResult is SupabaseResult.Success) {
                val exists = checkResult.data.optBoolean("exists", false)
                if (!exists) {
                    isSubmitting = false
                    errorMessage = "No user account registered with this email"
                    return@launch
                }
            } else {
                isSubmitting = false
                errorMessage = "Failed to verify email. Please check your connection and try again."
                return@launch
            }

            // Step 2: Email exists — send the 6-digit OTP via Resend SMTP
            val otpPayload = org.json.JSONObject().apply {
                put("email", trimmedEmail)
                put("purpose", "recovery")
            }
            val result = AppServiceContainer.supabaseClient.invokeFunction("send-email-otp", otpPayload)
            isSubmitting = false
            if (result is SupabaseResult.Success) {
                onNavigateToOtp(trimmedEmail, "")
            } else if (result is SupabaseResult.Error) {
                val msg = result.message ?: "Failed to send reset code"
                errorMessage = when {
                    msg.contains("rate limit", ignoreCase = true) || msg.contains("Too many", ignoreCase = true) ->
                        "Too many reset attempts. Please wait 60 seconds and try again."
                    msg.contains("not configured", ignoreCase = true) ->
                        "Email service is not configured. Please contact support."
                    else -> msg
                }
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("forgot_password_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.White,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Reset password",
                onBack = onBack
            )
        },
        bottomBar = {
            com.example.ui.components.TriggerBottomNavInset()
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Enter your registered email address. We will send a 6-digit verification code to reset your password.",
                fontSize = 14.sp,
                color = GeometricTextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Email text field
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    errorMessage = null
                },
                label = { Text("Registered email") },
                placeholder = { Text("you@example.com") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Email,
                        contentDescription = null,
                        tint = TriggerHeaderGreen
                    )
                },
                trailingIcon = {
                    if (email.isNotEmpty()) {
                        IconButton(onClick = { email = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Color.Gray)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        handleSubmit()
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TriggerFabGreen,
                    unfocusedBorderColor = GeometricBorderLight,
                    focusedLabelColor = TriggerFabGreen
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("forgot_email_input")
            )

            // Error display
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
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
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFD32F2F),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage!!,
                            color = Color(0xFFD32F2F),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Submit Button
            Button(
                onClick = { handleSubmit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("send_reset_code_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TriggerFabGreen,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp),
                enabled = !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = "Send 6-Digit Code",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

