package com.example.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.model.UserRepository
import com.example.service.StreamPricingType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val HeaderGreen = Color(0xFF008069)
private val DarkBackground = Color(0xFFF7F9FA)
private val CardBackground = Color(0xFFFFFFFF)
private val TextMain = Color(0xFF111B21)
private val TextSub = Color(0xFF667781)
private val AccentGreen = Color(0xFF00A884)
private val BorderGrey = Color(0xFFE2E8F0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleStreamScreen(
    onBack: () -> Unit,
    onStreamScheduled: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val userProfile by UserRepository.profile.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var streamName by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Tech & Dev") }

    val calendar = remember { Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) } }
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    var selectedDate by remember { mutableStateOf(dateFormat.format(calendar.time)) }
    var selectedTime by remember { mutableStateOf(timeFormat.format(calendar.time)) }

    // Slot options: 25, 50, 150, 200, 500, ANY
    val slotOptions = listOf("25", "50", "150", "200", "500", "ANY")
    var selectedSlot by remember { mutableStateOf("50") }
    var slotDropdownExpanded by remember { mutableStateOf(false) }

    // Type: FREE vs PAID
    var streamType by remember { mutableStateOf(StreamPricingType.FREE) }
    var typeDropdownExpanded by remember { mutableStateOf(false) }

    // Paid amount & Currency
    var amountText by remember { mutableStateOf("9.99") }
    val currencyOptions = listOf("USD ($)", "INR (₹)", "EUR (€)", "GBP (£)")
    var selectedCurrency by remember { mutableStateOf("USD ($)") }
    var currencyDropdownExpanded by remember { mutableStateOf(false) }

    // Link preview is a placeholder until the stream is actually scheduled —
    // the real stream id (`sch_${System.currentTimeMillis()}`, no modulo) is
    // generated inside StreamScheduleService.scheduleStream on submit. The
    // success dialog already shows the correct shareLink from the returned
    // ScheduledStream, so we just use a static placeholder here.
    val shareLink = "https://triggerapp.com/stream/(generated on submit)"

    // Notifications configuration
    var sendPushNotification by remember { mutableStateOf(true) }
    var sendEmailNotification by remember { mutableStateOf(true) }

    var showSuccessDialog by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    val registeredEmail = userProfile.email

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("schedule_stream_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = DarkBackground,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Schedule Stream",
                onBack = onBack
            )
        },
        bottomBar = {
            com.example.ui.components.TriggerBottomNavInset()
        },
        snackbarHost = {
            snackbarMessage?.let { msg ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { snackbarMessage = null }) {
                            Text("OK", color = AccentGreen)
                        }
                    }
                ) {
                    Text(msg)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Hero Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF008069), Color(0xFF00A884)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Event,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Plan Your Interactive Event",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMain
                        )
                        Text(
                            text = "Audience receives reminders & calendar invites.",
                            fontSize = 13.sp,
                            color = TextSub
                        )
                    }
                }
            }

            // Section 1: Stream Details
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "1. Stream Information",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = HeaderGreen
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Stream Name Input
                    OutlinedTextField(
                        value = streamName,
                        onValueChange = { streamName = it },
                        label = { Text("Stream Name / Title *") },
                        placeholder = { Text("e.g., Next-Gen WebRTC Architecture Q&A") },
                        leadingIcon = {
                            Icon(Icons.Filled.Videocam, contentDescription = null, tint = AccentGreen)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HeaderGreen,
                            focusedLabelColor = HeaderGreen
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("stream_name_input")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Category Chips
                    Text(
                        text = "Category",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSub
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val categories = listOf("Tech & Dev", "Music", "Education", "Gaming", "Talk")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.take(3).forEach { cat ->
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = cat },
                                label = { Text(cat, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFE8F5E9),
                                    selectedLabelColor = HeaderGreen
                                )
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categories.drop(3).forEach { cat ->
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = cat },
                                label = { Text(cat, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFE8F5E9),
                                    selectedLabelColor = HeaderGreen
                                )
                            )
                        }
                    }
                }
            }

            // Section 2: Date & Time Picker
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "2. Schedule Date & Time",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = HeaderGreen
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Date Selector
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, BorderGrey, RoundedCornerShape(12.dp))
                                .clickable {
                                    val now = Calendar.getInstance()
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val c = Calendar.getInstance().apply { set(y, m, d) }
                                            selectedDate = dateFormat.format(c.time)
                                        },
                                        now.get(Calendar.YEAR),
                                        now.get(Calendar.MONTH),
                                        now.get(Calendar.DAY_OF_MONTH)
                                    ).apply {
                                        // Prevent past dates.
                                        datePicker.minDate = System.currentTimeMillis()
                                    }.show()
                                }
                                .padding(12.dp)
                        ) {
                            Column {
                                Text("Date", fontSize = 11.sp, color = TextSub)
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CalendarToday, null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(selectedDate, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }
                            }
                        }

                        // Time Selector
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, BorderGrey, RoundedCornerShape(12.dp))
                                .clickable {
                                    val now = Calendar.getInstance()
                                    TimePickerDialog(
                                        context,
                                        { _, h, m ->
                                            val c = Calendar.getInstance().apply {
                                                set(Calendar.HOUR_OF_DAY, h)
                                                set(Calendar.MINUTE, m)
                                            }
                                            // If the selected date is today,
                                            // reject past times — the time
                                            // picker itself doesn't support
                                            // minTime, so we validate after
                                            // selection.
                                            val today = dateFormat.format(Calendar.getInstance().time)
                                            if (selectedDate == today && c.before(Calendar.getInstance())) {
                                                snackbarMessage = "Please pick a future time for today's stream."
                                                return@TimePickerDialog
                                            }
                                            selectedTime = timeFormat.format(c.time)
                                        },
                                        now.get(Calendar.HOUR_OF_DAY),
                                        now.get(Calendar.MINUTE),
                                        false
                                    ).show()
                                }
                                .padding(12.dp)
                        ) {
                            Column {
                                Text("Time", fontSize = 11.sp, color = TextSub)
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Schedule, null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(selectedTime, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Section 3: Slot Limit Dropdown (25, 50, 150, 200, 500, ANY)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "3. Audience Slot Limit",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = HeaderGreen
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose audience capacity. If ANY is selected, capacity is unlimited.",
                        fontSize = 12.sp,
                        color = TextSub
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ExposedDropdownMenuBox(
                        expanded = slotDropdownExpanded,
                        onExpandedChange = { slotDropdownExpanded = !slotDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = if (selectedSlot.equals("ANY", true)) "ANY (Unlimited Capacity)" else "$selectedSlot Attendees",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = slotDropdownExpanded) },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HeaderGreen),
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .testTag("slot_dropdown_field")
                        )
                        ExposedDropdownMenu(
                            expanded = slotDropdownExpanded,
                            onDismissRequest = { slotDropdownExpanded = false }
                        ) {
                            slotOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (opt == "ANY") "ANY — Unlimited Capacity" else "$opt Attendees"
                                        )
                                    },
                                    onClick = {
                                        selectedSlot = opt
                                        slotDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Section 4: Type Dropdown (PAID / FREE) & Pricing
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "4. Stream Access & Pricing",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = HeaderGreen
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Type Dropdown: PAID / FREE
                    ExposedDropdownMenuBox(
                        expanded = typeDropdownExpanded,
                        onExpandedChange = { typeDropdownExpanded = !typeDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = if (streamType == StreamPricingType.PAID) "PAID STREAM" else "FREE STREAM",
                            onValueChange = {},
                            readOnly = true,
                            leadingIcon = {
                                Icon(
                                    imageVector = if (streamType == StreamPricingType.PAID) Icons.Filled.Paid else Icons.Filled.LockOpen,
                                    contentDescription = null,
                                    tint = AccentGreen
                                )
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownExpanded) },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HeaderGreen),
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .testTag("type_dropdown_field")
                        )
                        ExposedDropdownMenu(
                            expanded = typeDropdownExpanded,
                            onDismissRequest = { typeDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("FREE STREAM (Public & Open)") },
                                onClick = {
                                    streamType = StreamPricingType.FREE
                                    typeDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("PAID STREAM (Ticket / Monetized)") },
                                onClick = {
                                    streamType = StreamPricingType.PAID
                                    typeDropdownExpanded = false
                                }
                            )
                        }
                    }

                    // If PAID: Show Type Box to type Amount and Choose Currency
                    AnimatedVisibility(visible = streamType == StreamPricingType.PAID) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Amount Input
                                OutlinedTextField(
                                    value = amountText,
                                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                    label = { Text("Ticket Price *") },
                                    placeholder = { Text("9.99") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = HeaderGreen,
                                        focusedLabelColor = HeaderGreen
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("price_amount_input")
                                )

                                // Currency Dropdown
                                ExposedDropdownMenuBox(
                                    expanded = currencyDropdownExpanded,
                                    onExpandedChange = { currencyDropdownExpanded = !currencyDropdownExpanded },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    OutlinedTextField(
                                        value = selectedCurrency,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Currency") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyDropdownExpanded) },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HeaderGreen),
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = currencyDropdownExpanded,
                                        onDismissRequest = { currencyDropdownExpanded = false }
                                    ) {
                                        currencyOptions.forEach { curr ->
                                            DropdownMenuItem(
                                                text = { Text(curr) },
                                                onClick = {
                                                    selectedCurrency = curr
                                                    currencyDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Earnings from ticket sales are credited directly to your Trigger App Wallet.",
                                fontSize = 12.sp,
                                color = TextSub
                            )
                        }
                    }
                }
            }

            // Section 5: Link Sharing Option
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Share, contentDescription = null, tint = HeaderGreen, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "5. Link Sharing Option",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = HeaderGreen
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Share with followers or external groups. Users can verify slot availability and book tickets directly.",
                        fontSize = 12.sp,
                        color = TextSub
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF1F5F9),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = shareLink,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF0F172A),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Stream Link", shareLink)
                                    clipboard.setPrimaryClip(clip)
                                    snackbarMessage = "Stream invite link copied to clipboard!"
                                }
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy Link", tint = HeaderGreen)
                            }
                            IconButton(
                                onClick = {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "Join my live stream on Trigger App!\n📌 Title: ${streamName.ifEmpty { "Live Talk" }}\n📅 Date: $selectedDate at $selectedTime\n🔗 Join Link: $shareLink"
                                        )
                                        type = "text/plain"
                                    }
                                    val shareIntent = Intent.createChooser(sendIntent, "Share Stream Invite")
                                    context.startActivity(shareIntent)
                                }
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = "Share", tint = HeaderGreen)
                            }
                        }
                    }
                }
            }

            // Section 6: End-to-End Production Notifications
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "6. Notification Channels",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = HeaderGreen
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Dispatches push alerts and email notifications to registered emails.",
                        fontSize = 12.sp,
                        color = TextSub
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Push Notification Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.NotificationsActive, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Push Notifications", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("High-priority alert to your device and attendees", fontSize = 12.sp, color = TextSub)
                            }
                        }
                        Switch(
                            checked = sendPushNotification,
                            onCheckedChange = { sendPushNotification = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = HeaderGreen, checkedTrackColor = Color(0xFFC8E6C9))
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = BorderGrey)

                    // Email Notification Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Email, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Email Notification", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("To: $registeredEmail", fontSize = 12.sp, color = TextSub)
                            }
                        }
                        Switch(
                            checked = sendEmailNotification,
                            onCheckedChange = { sendEmailNotification = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = HeaderGreen, checkedTrackColor = Color(0xFFC8E6C9))
                        )
                    }
                }
            }

            // Schedule Button
            Button(
                onClick = {
                    if (streamName.isBlank()) {
                        snackbarMessage = "Please enter a title for your stream."
                        return@Button
                    }
                    val amount = amountText.toDoubleOrNull() ?: 0.0
                    if (streamType == StreamPricingType.PAID && amount <= 0.0) {
                        snackbarMessage = "Please enter a valid ticket price greater than 0."
                        return@Button
                    }

                    // Wrap in a coroutine so the isSubmitting spinner actually
                    // renders (scheduleStream fires the server inserts in a
                    // scope.launch internally; we still want the spinner to
                    // show while the synchronous portion runs + briefly so
                    // the user sees feedback).
                    isSubmitting = true
                    coroutineScope.launch {
                        try {
                            AppServiceContainer.streamScheduleService.scheduleStream(
                                context = context,
                                title = streamName.trim(),
                                category = selectedCategory,
                                date = selectedDate,
                                time = selectedTime,
                                slotLimit = selectedSlot,
                                type = streamType,
                                amount = if (streamType == StreamPricingType.PAID) amount else 0.0,
                                currency = selectedCurrency,
                                sendEmail = sendEmailNotification,
                                sendPush = sendPushNotification
                            )
                            isSubmitting = false
                            showSuccessDialog = true
                        } catch (e: Exception) {
                            isSubmitting = false
                            snackbarMessage = "Failed to schedule stream: ${e.message ?: "unknown error"}"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("schedule_stream_submit_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = HeaderGreen),
                shape = RoundedCornerShape(12.dp),
                enabled = !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Schedule Stream Now",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // Success Confirmation Dialog
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = {
                showSuccessDialog = false
                onStreamScheduled(streamName)
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Stream Scheduled!", fontWeight = FontWeight.Bold, color = TextMain)
                }
            },
            text = {
                Column {
                    Text(
                        text = "Your broadcast \"$streamName\" is officially scheduled for $selectedDate at $selectedTime.",
                        fontSize = 14.sp,
                        color = TextSub
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE8F5E9),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("👥 Slot Limit: ${if (selectedSlot.equals("ANY", true)) "Unlimited" else "$selectedSlot Seats"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = HeaderGreen)
                            Text("💰 Pricing: ${if (streamType == StreamPricingType.PAID) "$selectedCurrency $amountText" else "Free"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = HeaderGreen)
                            Text("📧 Sent to: $registeredEmail", fontSize = 12.sp, color = TextSub)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSuccessDialog = false
                        onStreamScheduled(streamName)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderGreen)
                ) {
                    Text("View in Dashboard", color = Color.White)
                }
            }
        )
    }
}
