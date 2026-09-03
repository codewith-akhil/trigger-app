package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.Country
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PhoneAuthScreen(
    selectedCountry: Country,
    onNavigateToCountryPicker: () -> Unit,
    onVerificationSuccess: (phoneNumber: String, countryCode: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var rawPhoneNumber by remember { mutableStateOf("9656839298") }
    var showMenu by remember { mutableStateOf(false) }
    var showHelpSheet by remember { mutableStateOf(false) }
    var showNotificationDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var isSendingCode by remember { mutableStateOf(false) }
    var showWhatsMyNumberDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Clean display formatted phone number (e.g. "96568 39298")
    val formattedPhoneNumber = remember(rawPhoneNumber) {
        if (rawPhoneNumber.length > 5) {
            "${rawPhoneNumber.take(5)} ${rawPhoneNumber.drop(5)}"
        } else {
            rawPhoneNumber
        }
    }

    val fullFormattedWithCode = remember(selectedCountry, rawPhoneNumber) {
        val formatted = if (rawPhoneNumber.length > 5) {
            "${rawPhoneNumber.take(5)} ${rawPhoneNumber.drop(5)}"
        } else {
            rawPhoneNumber
        }
        "${selectedCountry.dialCode} $formatted"
    }

    val isNumberValid = rawPhoneNumber.trim().length >= 8

    // Helper to add digit
    fun addDigit(digit: String) {
        if (rawPhoneNumber.length < 12) {
            rawPhoneNumber += digit
        }
    }

    // Helper to remove digit
    fun removeDigit() {
        if (rawPhoneNumber.isNotEmpty()) {
            rawPhoneNumber = rawPhoneNumber.dropLast(1)
        }
    }

    fun submitNumber() {
        if (isNumberValid) {
            showConfirmDialog = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GeometricCanvasBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("phone_auth_screen")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar with border-b border-gray-100 and 3-dot Menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .drawBehind {
                        drawLine(
                            color = GeometricBorderLight,
                            start = androidx.compose.ui.geometry.Offset(0f, size.height),
                            end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.enter_phone_title),
                    color = GeometricTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.testTag("auth_more_button")
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
                            .testTag("auth_dropdown_menu")
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
                            modifier = Modifier.testTag("auth_menu_help_item")
                        )
                    }
                }
            }

            // Main Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                // Description with "What's my number?" link
                val descriptionText = buildAnnotatedString {
                    append(stringResource(R.string.enter_phone_desc))
                    append(" ")
                    withStyle(
                        style = SpanStyle(
                            color = GeometricGreenDark,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(stringResource(R.string.whats_my_number))
                    }
                }

                Text(
                    text = descriptionText,
                    color = GeometricTextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp,
                    modifier = Modifier
                        .clickable { showWhatsMyNumberDialog = true }
                        .padding(horizontal = 4.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Country Selector Dropdown Row
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onNavigateToCountryPicker)
                        .padding(vertical = 4.dp)
                        .testTag("country_picker_trigger"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(modifier = Modifier.width(20.dp))
                            Text(
                                text = selectedCountry.name,
                                color = GeometricTextDark,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                textAlign = TextAlign.Center
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = "Select country",
                                tint = GeometricGreenPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        HorizontalDivider(
                            color = GeometricGreenPrimary,
                            thickness = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Phone Input Row: Country Code + Phone Number
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 280.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Country Code
                    Column(
                        modifier = Modifier
                            .width(68.dp)
                            .padding(end = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = "+",
                                color = GeometricTextMuted,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                text = selectedCountry.dialCode.replace("+", ""),
                                color = GeometricTextDark,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                        HorizontalDivider(
                            color = GeometricGreenPrimary,
                            thickness = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Phone Number Input Field
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            if (rawPhoneNumber.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.phone_number_placeholder),
                                    color = GeometricTextMuted,
                                    fontSize = 16.sp
                                )
                            }
                            // Real interactive BasicTextField for direct typing or on-screen numpad
                            BasicTextField(
                                value = formattedPhoneNumber,
                                onValueChange = { input ->
                                    val digitsOnly = input.filter { it.isDigit() }
                                    if (digitsOnly.length <= 12) {
                                        rawPhoneNumber = digitsOnly
                                    }
                                },
                                textStyle = TextStyle(
                                    color = GeometricTextDark,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal,
                                    letterSpacing = 0.5.sp
                                ),
                                cursorBrush = SolidColor(GeometricGreenPrimary),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                keyboardActions = KeyboardActions(onDone = { submitNumber() }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("phone_number_input")
                            )
                        }

                        HorizontalDivider(
                            color = GeometricGreenPrimary,
                            thickness = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Next Button (Geometric pill shape with shadow and uppercase tracking)
                Button(
                    onClick = { submitNumber() },
                    enabled = isNumberValid,
                    modifier = Modifier
                        .widthIn(min = 120.dp)
                        .height(42.dp)
                        .testTag("phone_next_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GeometricGreenPrimary,
                        disabledContainerColor = GeometricGreenDisabled,
                        contentColor = Color.White,
                        disabledContentColor = Color.White.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.next).uppercase(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Gboard style Custom Numpad at bottom
            CustomGboardNumpad(
                onNumberClick = { digit -> addDigit(digit) },
                onDeleteClick = { removeDigit() },
                onSubmitClick = { submitNumber() },
                modifier = Modifier.testTag("custom_numpad")
            )
        }

        // Notification Permission Dialog
        if (showNotificationDialog) {
            NotificationPermissionDialog(
                onAllow = { showNotificationDialog = false },
                onDontAllow = { showNotificationDialog = false }
            )
        }

        // Confirm Number Dialog
        if (showConfirmDialog) {
            NumberConfirmDialog(
                fullPhoneNumber = fullFormattedWithCode,
                onEdit = { showConfirmDialog = false },
                onConfirm = {
                    showConfirmDialog = false
                    isSendingCode = true
                    coroutineScope.launch {
                        delay(1300)
                        isSendingCode = false
                        onVerificationSuccess(rawPhoneNumber, selectedCountry.dialCode)
                    }
                }
            )
        }

        // Sending Code Loading Dialog
        if (isSendingCode) {
            SendingCodeDialog()
        }

        // What's My Number Dialog
        if (showWhatsMyNumberDialog) {
            AlertDialog(
                onDismissRequest = { showWhatsMyNumberDialog = false },
                containerColor = Color.White,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = "Phone Number Info",
                        color = GeometricTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Text(
                        text = "Trigger App uses your phone number to uniquely identify your account and let you message your contacts securely. Your number will never be shared without your permission.",
                        color = GeometricTextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showWhatsMyNumberDialog = false }) {
                        Text(text = "OK", color = GeometricGreenPrimary, fontWeight = FontWeight.SemiBold)
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
