package com.example.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.service.VaultMediaItem
import java.io.File

private val HeaderGreen = Color(0xFF008069)
private val DarkBackground = Color(0xFF0F172A)
private val CardDark = Color(0xFF1E293B)
private val AccentGreen = Color(0xFF00A884)
private val ErrorRed = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretVaultScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vaultService = AppServiceContainer.secretVaultService
    val isPinSet by vaultService.isPinSet.collectAsState()
    val isUnlocked by vaultService.isUnlocked.collectAsState()
    val vaultItems by vaultService.vaultItems.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            // Auto-lock vault when exiting screen for security
            vaultService.lock()
        }
    }

    if (!isUnlocked) {
        VaultPinLockScreen(
            isPinSet = isPinSet,
            onPinVerified = { pin ->
                if (!isPinSet) {
                    vaultService.setPin(pin)
                } else {
                    vaultService.verifyPin(pin)
                }
            },
            onBack = onBack
        )
    } else {
        VaultGalleryScreen(
            items = vaultItems,
            onLock = { vaultService.lock() },
            onBack = onBack
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPinLockScreen(
    isPinSet: Boolean,
    onPinVerified: (String) -> Boolean,
    onBack: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isConfirmingSetup by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val promptTitle = remember(isPinSet, isConfirmingSetup) {
        when {
            !isPinSet && !isConfirmingSetup -> "Create 6-Digit Vault PIN"
            !isPinSet && isConfirmingSetup -> "Confirm 6-Digit Vault PIN"
            else -> "Enter 6-Digit Vault PIN"
        }
    }

    val promptSubtitle = remember(isPinSet, isConfirmingSetup) {
        when {
            !isPinSet && !isConfirmingSetup -> "Set a secure 6-digit code to protect your private media."
            !isPinSet && isConfirmingSetup -> "Re-enter your 6-digit PIN to confirm setup."
            else -> "This vault is protected with end-to-end device encryption."
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
                    .background(Color(0xFF1E293B)),
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
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

            // Error Message
            errorMessage?.let { err ->
                Spacer(modifier = Modifier.height(14.dp))
                Text(text = err, color = ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Custom Keypad
            CustomPinKeypad(
                onDigitClick = { digit ->
                    if (!isPinSet) {
                        if (!isConfirmingSetup) {
                            if (enteredPin.length < 6) {
                                enteredPin += digit
                                if (enteredPin.length == 6) {
                                    isConfirmingSetup = true
                                    errorMessage = null
                                }
                            }
                        } else {
                            if (confirmPin.length < 6) {
                                confirmPin += digit
                                if (confirmPin.length == 6) {
                                    if (confirmPin == enteredPin) {
                                        onPinVerified(confirmPin)
                                    } else {
                                        errorMessage = "PINs do not match. Please try again."
                                        confirmPin = ""
                                        enteredPin = ""
                                        isConfirmingSetup = false
                                    }
                                }
                            }
                        }
                    } else {
                        if (enteredPin.length < 6) {
                            enteredPin += digit
                            if (enteredPin.length == 6) {
                                val success = onPinVerified(enteredPin)
                                if (!success) {
                                    errorMessage = "Incorrect 6-digit PIN. Please try again."
                                    enteredPin = ""
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
                    errorMessage = null
                },
                onClear = {
                    enteredPin = ""
                    confirmPin = ""
                    isConfirmingSetup = false
                    errorMessage = null
                }
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultGalleryScreen(
    items: List<VaultMediaItem>,
    onLock: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val vaultService = AppServiceContainer.secretVaultService

    var selectedTab by remember { mutableStateOf("All") }
    var selectedMediaForPreview by remember { mutableStateOf<VaultMediaItem?>(null) }
    var showImportSuccessToast by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        uris.forEach { uri ->
            val type = context.contentResolver.getType(uri) ?: ""
            val isVideo = type.contains("video", ignoreCase = true)
            vaultService.importMedia(uri, isVideo) { success ->
                if (success) showImportSuccessToast = true
            }
        }
    }

    val filteredItems = remember(items, selectedTab) {
        when (selectedTab) {
            "Photos" -> items.filter { !it.isVideo }
            "Videos" -> items.filter { it.isVideo }
            else -> items
        }
    }

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
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                containerColor = AccentGreen,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("vault_add_media_fab")
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Add Secret Photo/Video")
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
                            text = "Tap the + button below to import private photos or videos from your device into the encrypted vault.",
                            fontSize = 13.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                            onClick = { selectedMediaForPreview = item }
                        )
                    }
                }
            }
        }
    }

    // Media Preview Dialog
    selectedMediaForPreview?.let { item ->
        VaultMediaPreviewDialog(
            item = item,
            onDismiss = { selectedMediaForPreview = null },
            onDelete = {
                vaultService.deleteMedia(item)
                selectedMediaForPreview = null
            }
        )
    }
}

@Composable
fun VaultThumbnailItem(
    item: VaultMediaItem,
    onClick: () -> Unit
) {
    val bitmap = remember(item.filePath) {
        try {
            if (!item.isVideo) {
                BitmapFactory.decodeFile(item.filePath)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(CardDark)
            .clickable { onClick() }
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
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
    val bitmap = remember(item.filePath) {
        try {
            if (!item.isVideo) BitmapFactory.decodeFile(item.filePath) else null
        } catch (e: Exception) {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        shape = RoundedCornerShape(16.dp),
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
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
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
