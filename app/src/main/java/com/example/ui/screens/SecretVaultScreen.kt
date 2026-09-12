package com.example.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.example.ui.components.TriggerAlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.service.VaultMediaItem
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val HeaderGreen = Color(0xFF008069)
private val DarkBackground = Color(0xFF0F172A)
private val CardDark = Color(0xFF1E293B)
private val AccentGreen = Color(0xFF00A884)
private val ErrorRed = Color(0xFFEF4444)

// Compact-dialog convention colors (white AlertDialog, 16dp corners, 16sp bold title).
private val CompactGreen = Color(0xFF008069)
private val CompactRed = Color(0xFFEA4335)
private val DialogTitleDark = Color(0xFF111B21)
private val DialogBodyGray = Color(0xFF5F6368)
private val DialogFieldBg = Color(0xFFF1F3F4)

@Composable
fun SecretVaultScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vaultService = AppServiceContainer.secretVaultService
    val serverPinExists by vaultService.serverPinExists.collectAsState()
    val serverStateError by vaultService.serverStateError.collectAsState()
    val isUnlocked by vaultService.isUnlocked.collectAsState()
    val vaultItems by vaultService.vaultItems.collectAsState()
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            // Auto-lock vault when exiting screen for security
            vaultService.lock()
        }
    }

    // Server-authoritative PIN probe on entry (null = loading).
    LaunchedEffect(Unit) {
        vaultService.ensureServerState()
    }

    when {
        serverPinExists == null -> {
            VaultServerLoadingScreen(
                showError = serverStateError,
                onBack = onBack,
                onRetry = { scope.launch { vaultService.ensureServerState() } }
            )
        }
        serverPinExists == false -> {
            VaultPinSetupScreen(onBack = onBack)
        }
        !isUnlocked -> {
            VaultUnlockScreen(onBack = onBack)
        }
        else -> {
            VaultGalleryScreen(
                items = vaultItems,
                onLock = { vaultService.lock() },
                onBack = onBack
            )
        }
    }
}

// --------------------------------------------------------------------------
// Loading (server state unknown)
// --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultServerLoadingScreen(
    showError: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = DarkBackground,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Trigger Secret Vault",
                onBack = onBack
            )
        },
        bottomBar = {
            com.example.ui.components.TriggerBottomNavInset()
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AccentGreen)
                if (showError) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Can't reach the server.",
                        fontSize = 14.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = onRetry) {
                        Text("Retry", color = AccentGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------
// First-time setup: create → confirm (server-confirmed setPin)
// --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPinSetupScreen(
    onBack: () -> Unit
) {
    val vaultService = AppServiceContainer.secretVaultService
    val pinError by vaultService.pinError.collectAsState()
    val settingPin by vaultService.settingPin.collectAsState()
    val scope = rememberCoroutineScope()

    var enteredPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isConfirmingSetup by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val promptTitle = if (isConfirmingSetup) "Confirm 6-Digit Vault PIN" else "Create 6-Digit Vault PIN"
    val promptSubtitle = if (isConfirmingSetup) {
        "Re-enter your 6-digit PIN to confirm setup."
    } else {
        "Set a secure 6-digit code to protect your private media."
    }

    fun submitSetup(pin: String) {
        if (settingPin) return
        scope.launch {
            val ok = vaultService.setPin(pin)
            if (!ok) {
                confirmPin = ""
                enteredPin = ""
                isConfirmingSetup = false
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = DarkBackground,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Trigger Secret Vault",
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Shield Icon
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(CardDark),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = AccentGreen,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = promptTitle,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = promptSubtitle,
                fontSize = 13.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 6-digit PIN Dots
            val currentCode = if (isConfirmingSetup) confirmPin else enteredPin
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0 until 6) {
                    val isFilled = i < currentCode.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                if (isFilled) AccentGreen else Color(0xFF334155)
                            )
                    )
                }
            }

            if (settingPin) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = AccentGreen,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Setting your PIN…", fontSize = 13.sp, color = Color(0xFF94A3B8))
                }
            }

            // Error Message (local first, then server/surface errors)
            (localError ?: pinError)?.let { err ->
                Spacer(modifier = Modifier.height(14.dp))
                Text(text = err, color = ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Custom Keypad
            CustomPinKeypad(
                onDigitClick = { digit ->
                    if (!isConfirmingSetup) {
                        if (enteredPin.length < 6) {
                            enteredPin += digit
                            if (enteredPin.length == 6) {
                                isConfirmingSetup = true
                                localError = null
                            }
                        }
                    } else {
                        if (confirmPin.length < 6) {
                            confirmPin += digit
                            if (confirmPin.length == 6) {
                                if (confirmPin == enteredPin) {
                                    submitSetup(confirmPin)
                                } else {
                                    localError = "PINs do not match. Please try again."
                                    confirmPin = ""
                                    enteredPin = ""
                                    isConfirmingSetup = false
                                }
                            }
                        }
                    }
                },
                onBackspace = {
                    if (isConfirmingSetup) {
                        if (confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1)
                    } else {
                        if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                    }
                    localError = null
                },
                onClear = {
                    enteredPin = ""
                    confirmPin = ""
                    isConfirmingSetup = false
                    localError = null
                }
            )
        }
    }
}

// --------------------------------------------------------------------------
// Unlock: server-authoritative verify + 3-strikes/24h lockout + OTP recovery
// --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultUnlockScreen(
    onBack: () -> Unit
) {
    val vaultService = AppServiceContainer.secretVaultService
    val attemptsLeft by vaultService.attemptsLeft.collectAsState()
    val lockedUntil by vaultService.lockedUntil.collectAsState()
    val verifyError by vaultService.verifyError.collectAsState()
    val verifying by vaultService.verifying.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enteredPin by remember { mutableStateOf("") }
    var showOtpDialog by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = DarkBackground,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Trigger Secret Vault",
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
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (lockedUntil != null) {
                // ---------------------------- LOCKED (3 wrong PINs in 24h) --
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(CardDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Vault Locked",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Too many attempts. Locked until ${formatLockedUntil(context, lockedUntil)}.",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "You can unlock with a 6-digit code sent to your email.",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = { showOtpDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Filled.MarkEmailRead, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlock via email", color = Color.White)
                }
            } else {
                // --------------------------------------------- PIN keypad  --
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(CardDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Enter 6-Digit Vault PIN",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "3 wrong attempts in a day will lock this vault for 24 hours.",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until 6) {
                        val isFilled = i < enteredPin.length
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(if (isFilled) AccentGreen else Color(0xFF334155))
                        )
                    }
                }

                if (verifying) {
                    Spacer(modifier = Modifier.height(14.dp))
                    CircularProgressIndicator(
                        color = AccentGreen,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }

                // Error: network first, then attempts-left.
                val inlineError = when {
                    verifyError != null -> verifyError
                    attemptsLeft != null -> {
                        val n = attemptsLeft ?: 0
                        "Wrong PIN. $n ${if (n == 1) "attempt" else "attempts"} left today."
                    }
                    else -> null
                }
                inlineError?.let { err ->
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(text = err, color = ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(32.dp))

                CustomPinKeypad(
                    onDigitClick = { digit ->
                        if (!verifying && enteredPin.length < 6) {
                            enteredPin += digit
                            if (enteredPin.length == 6) {
                                val pin = enteredPin
                                scope.launch {
                                    vaultService.verifyPin(pin)
                                    enteredPin = ""
                                }
                            }
                        }
                    },
                    onBackspace = {
                        if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                    },
                    onClear = { enteredPin = "" }
                )
            }
        }
    }

    if (showOtpDialog) {
        VaultOtpUnlockDialog(onDismiss = { showOtpDialog = false })
    }
}

/**
 * Compact white dialog (16dp corners, 16sp bold title): send code → 6 OTP
 * boxes + new PIN → verify & unlock. Errors inline, red.
 */
@Composable
fun VaultOtpUnlockDialog(
    onDismiss: () -> Unit
) {
    val vaultService = AppServiceContainer.secretVaultService
    val unlockBusy by vaultService.unlockBusy.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val email = remember {
        AppServiceContainer.supabaseClient.currentSession?.user?.email
            ?: com.example.model.UserRepository.profile.value.email
    }
    val maskedEmail = maskEmail(email)

    var stage by remember { mutableStateOf(0) } // 0 = ask to send, 1 = code entry
    var sending by remember { mutableStateOf(false) }
    var verifyingOtp by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var otp by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }

    val emailLine = maskedEmail ?: "your registered email"

    TriggerAlertDialog(
        onDismissRequest = { if (!sending && !verifyingOtp) onDismiss() },
        containerColor = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Unlock via Email",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = DialogTitleDark
            )
        },
        text = {
            Column {
                if (stage == 0) {
                    Text(
                        text = "We'll send a 6-digit code to $emailLine to reset your vault PIN.",
                        fontSize = 14.sp,
                        color = DialogBodyGray
                    )
                } else {
                    Text(
                        text = "We sent a 6-digit code to $emailLine.",
                        fontSize = 14.sp,
                        color = DialogBodyGray
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OtpDigitBoxes(
                        otp = otp,
                        enabled = !verifyingOtp,
                        onOtpChange = { otp = it }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "New 6-digit PIN",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DialogTitleDark
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { value ->
                            if (value.length <= 6 && value.all { it.isDigit() }) newPin = value
                        },
                        enabled = !verifyingOtp,
                        singleLine = true,
                        placeholder = { Text("••••••", color = Color(0xFF9AA0A6)) },
                        visualTransformation = PasswordVisualTransformation('•'),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CompactGreen,
                            cursorColor = CompactGreen
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                errorMsg?.let { err ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = err, color = CompactRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = {
            if (stage == 0) {
                Button(
                    onClick = {
                        sending = true
                        errorMsg = null
                        scope.launch {
                            val ok = vaultService.requestUnlockOtp()
                            sending = false
                            if (ok) {
                                stage = 1
                            } else {
                                errorMsg = vaultService.unlockError.value
                                    ?: "Couldn't send the code. Try again."
                            }
                        }
                    },
                    enabled = !sending,
                    colors = ButtonDefaults.buttonColors(containerColor = CompactGreen),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Send code", color = Color.White)
                    }
                }
            } else {
                Button(
                    onClick = {
                        verifyingOtp = true
                        errorMsg = null
                        scope.launch {
                            val ok = vaultService.unlockWithOtp(otp, newPin)
                            verifyingOtp = false
                            if (ok) {
                                Toast.makeText(context, "Vault unlocked", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else {
                                errorMsg = vaultService.unlockError.value
                                    ?: "Couldn't verify the code. Try again."
                            }
                        }
                    },
                    enabled = !verifyingOtp && otp.length == 6 && newPin.length == 6,
                    colors = ButtonDefaults.buttonColors(containerColor = CompactGreen),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (verifyingOtp) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Verify & unlock", color = Color.White)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !sending && !verifyingOtp
            ) {
                Text("Cancel", color = CompactGreen)
            }
        }
    )
}

/** Six single-digit boxes with auto-advance focus. */
@Composable
fun OtpDigitBoxes(
    otp: String,
    enabled: Boolean,
    onOtpChange: (String) -> Unit
) {
    val focusRequesters = remember { List(6) { FocusRequester() } }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until 6) {
            BasicTextField(
                value = otp.getOrNull(i)?.toString() ?: "",
                onValueChange = { v ->
                    val c = v.filter { it.isDigit() }.takeLast(1)
                    val next = buildString {
                        for (j in 0 until 6) append(if (j == i) c else (otp.getOrNull(j) ?: ""))
                    }
                    onOtpChange(next)
                    if (c.isNotEmpty() && i < 5) focusRequesters[i + 1].requestFocus()
                },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = DialogTitleDark,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .focusRequester(focusRequesters[i])
                    .size(44.dp)
                    .background(DialogFieldBg, RoundedCornerShape(12.dp))
                    .wrapContentSize(Alignment.Center),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) { inner() }
                }
            )
        }
    }

    LaunchedEffect(enabled) {
        if (enabled) {
            kotlinx.coroutines.delay(200)
            runCatching { focusRequesters[0].requestFocus() }
        }
    }
}

@Composable
fun CustomPinKeypad(
    onDigitClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("C", "0", "⌫")
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (btn in row) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .clickable {
                                when (btn) {
                                    "⌫" -> onBackspace()
                                    "C" -> onClear()
                                    else -> onDigitClick(btn)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = btn,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------
// Unlocked gallery: add (photo/video) + upload overlay + delete
// --------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultGalleryScreen(
    items: List<VaultMediaItem>,
    onLock: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val vaultService = AppServiceContainer.secretVaultService
    val scope = rememberCoroutineScope()

    val isUploading by vaultService.isUploading.collectAsState()
    val uploadProgress by vaultService.uploadProgress.collectAsState()
    val mediaError by vaultService.mediaError.collectAsState()

    var selectedTab by remember { mutableStateOf("All") }
    var selectedMediaForPreview by remember { mutableStateOf<VaultMediaItem?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<VaultMediaItem?>(null) }

    // Refresh the merged (cloud + local cache) listing on entry.
    LaunchedEffect(Unit) {
        vaultService.loadVaultItems()
    }

    // Surface import/upload failures (local-only saves, index errors, …).
    LaunchedEffect(mediaError) {
        mediaError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vaultService.clearMediaError()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            vaultService.importMedia(it, isVideo = false, onComplete = { success ->
                if (!success) { /* error already surfaced via mediaError */ }
            })
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            vaultService.importMedia(it, isVideo = true, onComplete = { success ->
                if (!success) { /* error already surfaced via mediaError */ }
            })
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = DarkBackground,
            topBar = {
                com.example.ui.components.TriggerTopHeader(
                    title = "Secret Vault",
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = onLock) {
                            Icon(Icons.Filled.Lock, contentDescription = "Lock Vault", tint = Color.White)
                        }
                    }
                )
            },
            bottomBar = {
                com.example.ui.components.TriggerBottomNavInset()
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { if (!isUploading) showAddSheet = true },
                    containerColor = AccentGreen,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("vault_add_media_fab")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Secret Photo/Video")
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Tabs: All, Photos, Videos
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("All", "Photos", "Videos").forEach { tab ->
                        FilterChip(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            label = { Text(tab) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentGreen,
                                selectedLabelColor = Color.White,
                                containerColor = CardDark,
                                labelColor = Color(0xFF94A3B8)
                            )
                        )
                    }
                }

                val filteredItems = remember(items, selectedTab) {
                    when (selectedTab) {
                        "Photos" -> items.filter { !it.isVideo }
                        "Videos" -> items.filter { it.isVideo }
                        else -> items
                    }
                }

                if (filteredItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = null,
                                tint = Color(0xFF475569),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Secret Vault is Empty",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Tap the + button to add private photos or videos. Media is visible only to you, on all your devices.",
                                fontSize = 13.sp,
                                color = Color(0xFF94A3B8),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            VaultThumbnailItem(
                                item = item,
                                onClick = { selectedMediaForPreview = item },
                                onLongClick = { if (!isUploading) pendingDelete = item }
                            )
                        }
                    }
                }
            }
        }

        // WhatsApp-style upload overlay (compact card, no close button).
        if (isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Uploading…",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = DialogTitleDark
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (uploadProgress in 0f..1f) {
                            LinearProgressIndicator(
                                progress = { uploadProgress },
                                color = AccentGreen,
                                trackColor = Color(0xFFE9EDEF),
                                modifier = Modifier.width(180.dp)
                            )
                        } else {
                            LinearProgressIndicator(
                                color = AccentGreen,
                                trackColor = Color(0xFFE9EDEF),
                                modifier = Modifier.width(180.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Add sheet: Photo / Video (content picker → importMedia)
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            containerColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "Add to Vault",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DialogTitleDark,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAddSheet = false
                            photoPickerLauncher.launch("image/*")
                        }
                        .padding(vertical = 14.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Photo,
                        contentDescription = null,
                        tint = CompactGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Photo", fontSize = 15.sp, color = DialogTitleDark)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAddSheet = false
                            videoPickerLauncher.launch("video/*")
                        }
                        .padding(vertical = 14.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = CompactGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Video", fontSize = 15.sp, color = DialogTitleDark)
                }
            }
        }
    }

    // Media Preview Dialog (tap) — Delete routes through the confirm dialog.
    selectedMediaForPreview?.let { item ->
        VaultMediaPreviewDialog(
            item = item,
            onDismiss = { selectedMediaForPreview = null },
            onDelete = {
                selectedMediaForPreview = null
                pendingDelete = item
            }
        )
    }

    // Long-press (or preview) delete → compact confirm dialog.
    pendingDelete?.let { item ->
        TriggerAlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Delete from vault?",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = DialogTitleDark
                )
            },
            text = {
                Text(
                    text = "This will remove it from your vault on every device. This can't be undone.",
                    fontSize = 14.sp,
                    color = DialogBodyGray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = item
                        pendingDelete = null
                        scope.launch {
                            val ok = vaultService.deleteMedia(target)
                            if (!ok) {
                                Toast.makeText(
                                    context,
                                    "Couldn't delete. Check your connection.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CompactRed),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = CompactGreen)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultThumbnailItem(
    item: VaultMediaItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val vaultService = AppServiceContainer.secretVaultService

    val bitmap = remember(item.filePath) {
        try {
            if (!item.isVideo && item.filePath.isNotBlank()) {
                BitmapFactory.decodeFile(item.filePath)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // Cloud-only items (no local cache) load a signed URL for the private bucket.
    var remoteUrl by remember(item.id) { mutableStateOf<String?>(null) }
    val hasLocal = item.filePath.isNotBlank()
    LaunchedEffect(item.id, hasLocal) {
        if (!hasLocal && !item.isVideo) {
            remoteUrl = vaultService.displayUrlFor(item)
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(CardDark)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            remoteUrl != null -> {
                AsyncImage(
                    model = remoteUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (item.isVideo) Icons.Filled.PlayCircle else Icons.Filled.Image,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Overlay for video badge or size
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (item.isVideo) "VIDEO" else item.formattedSize,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun VaultMediaPreviewDialog(
    item: VaultMediaItem,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val vaultService = AppServiceContainer.secretVaultService

    val bitmap = remember(item.filePath) {
        try {
            if (!item.isVideo && item.filePath.isNotBlank()) {
                BitmapFactory.decodeFile(item.filePath)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    var remoteUrl by remember(item.id) { mutableStateOf<String?>(null) }
    val hasLocal = item.filePath.isNotBlank()
    LaunchedEffect(item.id, hasLocal) {
        if (!hasLocal && !item.isVideo) {
            remoteUrl = vaultService.displayUrlFor(item)
        }
    }

    TriggerAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (item.isVideo) "Secret Video" else "Secret Photo",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = AccentGreen,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    bitmap != null -> {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                    remoteUrl != null -> {
                        AsyncImage(
                            model = remoteUrl,
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Videocam, null, tint = AccentGreen, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Protected Encrypted Video", color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Date Stored:", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    Text(item.formattedDate, color = Color.White, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("File Size:", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    Text(item.formattedSize, color = Color.White, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
            ) {
                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Delete", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF94A3B8))
            }
        }
    )
}

// --------------------------------------------------------------------------
// Helpers
// --------------------------------------------------------------------------

/** Masks an email: keep first 2 chars of the local part + full domain ("ak***@gmail.com"). */
private fun maskEmail(email: String?): String? {
    val trimmed = email?.trim().orEmpty()
    if (trimmed.isBlank() || !trimmed.contains("@")) return null
    val local = trimmed.substringBefore("@")
    val domain = trimmed.substringAfter("@")
    val prefix = if (local.length >= 2) local.take(2) else local
    return "$prefix***@$domain"
}

/** "Locked until HH:mm" — parses the ISO lockedUntil into a locale time string. */
private fun formatLockedUntil(context: Context, iso: String?): String {
    if (iso.isNullOrBlank()) return "for 24 hours"
    return try {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        var parsed: Date? = null
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.isLenient = false
                if (pattern == "yyyy-MM-dd'T'HH:mm:ss'Z'") {
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                }
                parsed = fmt.parse(iso)
                if (parsed != null) break
            } catch (e: Exception) {
                // try the next pattern
            }
        }
        val d = parsed ?: return "for 24 hours"
        DateFormat.getTimeFormat(context).format(d)
    } catch (e: Exception) {
        "for 24 hours"
    }
}
