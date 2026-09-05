package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.model.UserRepository
import com.example.service.supabase.SupabaseResult
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignUpScreen(
    onNavigateToOtp: (name: String, email: String, generatedOtp: String) -> Unit,
    onNavigateToLogin: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val imeInsets = WindowInsets.ime
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Smooth scroll into view when keyboard opens
    LaunchedEffect(imeInsets.getBottom(density)) {
        if (imeInsets.getBottom(density) > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    fun handleSignUp() {
        errorMessage = null
        val trimmedName = fullName.trim()
        val trimmedEmail = email.trim().lowercase()

        // --- Input validation ---
        if (trimmedName.isEmpty()) {
            errorMessage = "Please enter your full name"
            return
        }
        if (trimmedName.length < 2) {
            errorMessage = "Name must be at least 2 characters"
            return
        }
        if (trimmedEmail.isEmpty()) {
            errorMessage = "Email is required"
            return
        }
        // Strict email regex
        val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        if (!emailRegex.matches(trimmedEmail)) {
            errorMessage = "Please enter a valid email address"
            return
        }
        if (password.isEmpty()) {
            errorMessage = "Password is required"
            return
        }
        if (password.length < 6) {
            errorMessage = "Password must be at least 6 characters"
            return
        }

        isSubmitting = true
        coroutineScope.launch {
            // Step 0: Check backend if email is already registered BEFORE signup
            val checkPayload = org.json.JSONObject().apply { put("email", trimmedEmail) }
            val checkResult = AppServiceContainer.supabaseClient.invokeFunction("check-email", checkPayload)
            if (checkResult is SupabaseResult.Success) {
                val exists = checkResult.data.optBoolean("exists", false)
                if (exists) {
                    isSubmitting = false
                    errorMessage = "This email is already registered with an account. Try another email or reset your password."
                    return@launch
                }
            }
            // If check fails (network), proceed anyway — Supabase Auth will catch duplicates

            // Step 1: Sign up the user via Supabase Auth.
            // Supabase Auth uses bcrypt for password hashing — no plaintext stored.
            when (val result = AppServiceContainer.supabaseClient.signUp(trimmedEmail, password, trimmedName)) {
                is SupabaseResult.Success -> {
                    // Step 2: Request the server to send a 6-digit OTP email via the
                    // send-email-otp edge function (Resend SMTP). The OTP is generated
                    // + stored server-side as a salted hash — NEVER on the client.
                    val otpPayload = org.json.JSONObject().apply {
                        put("email", trimmedEmail)
                        put("purpose", "signup")
                    }
                    when (val otpResult = AppServiceContainer.supabaseClient.invokeFunction("send-email-otp", otpPayload)) {
                        is SupabaseResult.Success -> {
                            isSubmitting = false
                            UserRepository.setUser(name = trimmedName, email = trimmedEmail, id = result.data.id)
                            onNavigateToOtp(trimmedName, trimmedEmail, "")
                        }
                        is SupabaseResult.Error -> {
                            isSubmitting = false
                            // OTP send failed — but the user was created. Show a friendly
                            // error + still navigate to OTP screen so they can request resend.
                            val msg = otpResult.message ?: ""
                            errorMessage = if (msg.contains("rate limit", ignoreCase = true)) {
                                "Too many OTP requests. Please wait 60 seconds and try again."
                            } else {
                                "Account created, but we couldn't send the verification email. Tap resend on the next screen."
                            }
                            UserRepository.setUser(name = trimmedName, email = trimmedEmail, id = result.data.id)
                            onNavigateToOtp(trimmedName, trimmedEmail, "")
                        }
                    }
                }
                is SupabaseResult.Error -> {
                    isSubmitting = false
                    val msg = result.message ?: "Sign up failed"
                    // Detect duplicate email — Supabase returns "User already registered"
                    errorMessage = when {
                        msg.contains("already registered", ignoreCase = true) ||
                        msg.contains("already been registered", ignoreCase = true) ||
                        msg.contains("user already exists", ignoreCase = true) ->
                            "This email is already registered. Please log in or use a different email."
                        msg.contains("rate limit", ignoreCase = true) ->
                            "Too many sign-up attempts. Please wait a minute and try again."
                        msg.contains("password", ignoreCase = true) && msg.contains("weak", ignoreCase = true) ->
                            "Password is too weak. Use at least 6 characters with a mix of letters and numbers."
                        else -> msg
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("signup_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.White,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Create your account",
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
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Sign up with your details. A 6-digit verification code will be sent to your email.",
                fontSize = 14.sp,
                color = GeometricTextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Full Name input field
            OutlinedTextField(
                value = fullName,
                onValueChange = {
                    fullName = it
                    errorMessage = null
                },
                label = { Text("Full Name") },
                placeholder = { Text("Enter your full name") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = TriggerHeaderGreen
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TriggerFabGreen,
                    unfocusedBorderColor = GeometricBorderLight,
                    focusedLabelColor = TriggerFabGreen
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("signup_name_input")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Email input field (reduced font size for longer emails, smooth scroll friendly)
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    errorMessage = null
                },
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    color = GeometricTextPrimary
                ),
                label = { Text("Email address", fontSize = 13.sp) },
                placeholder = { Text("you@example.com", fontSize = 13.5.sp, color = GeometricTextMuted) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Email,
                        contentDescription = null,
                        tint = TriggerHeaderGreen,
                        modifier = Modifier.size(20.dp)
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TriggerFabGreen,
                    unfocusedBorderColor = GeometricBorderLight,
                    focusedLabelColor = TriggerFabGreen
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("signup_email_input")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Password input field
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = null
                },
                label = { Text("Password") },
                placeholder = { Text("At least 6 characters") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = TriggerHeaderGreen
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                            tint = Color.Gray
                        )
                    }
                },
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        handleSignUp()
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
                    .testTag("signup_password_input")
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

            Spacer(modifier = Modifier.height(28.dp))

            // Sign Up button
            Button(
                onClick = { handleSignUp() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("signup_submit_button"),
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
                        text = "Continue",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Switch to Login
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Already have an account? ",
                    fontSize = 14.sp,
                    color = GeometricTextSecondary
                )
                Text(
                    text = "Log in",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TriggerHeaderGreen,
                    modifier = Modifier

                        .clickable { onNavigateToLogin() }
                        .padding(4.dp)
                        .testTag("navigate_to_login_button")
                )
            }
        }
    }
}
