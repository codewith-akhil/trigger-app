package com.example.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.di.AppServiceContainer
import com.example.service.ScheduledStream
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
    val coroutineScope = rememberCoroutineScope()

    var streamName by remember { mutableStateOf("") }

    // Category dropdown options: Education, Business, Meeting, Personal
    val categoryOptions = listOf("Education", "Business", "Meeting", "Personal")
    var selectedCategory by remember { mutableStateOf("Education") }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }

    val calendar = remember { Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) } }
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    var selectedDate by remember { mutableStateOf(dateFormat.format(calendar.time)) }
    var selectedTime by remember { mutableStateOf(timeFormat.format(calendar.time)) }

    // Slot: Limited / Unlimited
    val slotOptions = listOf("Unlimited", "Limited")
    var selectedSlotType by remember { mutableStateOf("Unlimited") }
    var slotDropdownExpanded by remember { mutableStateOf(false) }
    var slotNumberText by remember { mutableStateOf("50") }

    // Type: Free / Paid
    val pricingTypeOptions = listOf("Free", "Paid")
    var selectedPricingType by remember { mutableStateOf("Free") }
    var pricingDropdownExpanded by remember { mutableStateOf(false) }

    // Paid amount & Currency
    var amountText by remember { mutableStateOf("9.99") }
    val currencyOptions = listOf("USD ($)", "INR (₹)", "EUR (€)", "GBP (£)")
    var selectedCurrency by remember { mutableStateOf("USD ($)") }
    var currencyDropdownExpanded by remember { mutableStateOf(false) }

    // Notifications configuration
    var sendPushNotification by remember { mutableStateOf(true) }

    var showSuccessDialog by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    // Populated ONLY on successful scheduling. Carries the real ScheduledStream
    // (with the share link generated inside StreamScheduleService.scheduleStream)
    // into the success dialog — the link is never displayed or shared before
    // the stream actually exists.
    var createdStream by remember { mutableStateOf<ScheduledStream?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("schedule_stream_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = DarkBackground,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Create Streaming",
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

                    // Stream Title Input
                    OutlinedTextField(
                        value = streamName,
                        onValueChange = { streamName = it },
                        label = { Text("Stream Title *") },
                        placeholder = { Text("e.g. Next-Gen WebRTC Architecture Q&A") },
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

                    Spacer(modifier = Modifier.height(14.dp))

                    // Category Dropdown (Education, Business, Meeting, Personal)
                    Text(
                        text = "Category *",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSub
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ExposedDropdownMenuBox(
                        expanded = categoryDropdownExpanded,
                        onExpandedChange = { categoryDropdownExpanded = !categoryDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedCategory,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryDropdownExpanded) },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = HeaderGreen,
                                focusedLabelColor = HeaderGreen
                            ),
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .testTag("category_dropdown_field")
                        )
                        ExposedDropdownMenu(
                            expanded = categoryDropdownExpanded,
                            onDismissRequest = { categoryDropdownExpanded = false }
                        ) {
                            categoryOptions.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat) },
                                    onClick = {
                                        selectedCategory = cat
                                        categoryDropdownExpanded = false
                                    }
                                )
                            }
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

            // Section 3: Slot Limit Dropdown (Unlimited / Limited)
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
                        text = "Choose audience capacity: Unlimited or Limited to a specific number of seats.",
                        fontSize = 12.sp,
                        color = TextSub
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ExposedDropdownMenuBox(
                        expanded = slotDropdownExpanded,
                        onExpandedChange = { slotDropdownExpanded = !slotDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedSlotType,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Slot Type *") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = slotDropdownExpanded) },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = HeaderGreen,
                                focusedLabelColor = HeaderGreen
                            ),
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
                                    text = { Text(opt) },
                                    onClick = {
                                        selectedSlotType = opt
                                        slotDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // If Limited: type box to type slot no
                    AnimatedVisibility(visible = selectedSlotType == "Limited") {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            OutlinedTextField(
                                value = slotNumberText,
                                onValueChange = { slotNumberText = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Slot Number *") },
                                placeholder = { Text("e.g. 50") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = HeaderGreen,
                                    focusedLabelColor = HeaderGreen
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("slot_number_input")
                            )
                        }
                    }
                }
            }

            // Section 4: Type Dropdown (Free / Paid) & Pricing
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

                    // Type Dropdown: Free / Paid
                    ExposedDropdownMenuBox(
                        expanded = pricingDropdownExpanded,
                        onExpandedChange = { pricingDropdownExpanded = !pricingDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = if (selectedPricingType == "Paid") "Paid" else "Free",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Type *") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (selectedPricingType == "Paid") Icons.Filled.Paid else Icons.Filled.LockOpen,
                                    contentDescription = null,
                                    tint = AccentGreen
                                )
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = pricingDropdownExpanded) },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = HeaderGreen,
                                focusedLabelColor = HeaderGreen
                            ),
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .testTag("type_dropdown_field")
                        )
                        ExposedDropdownMenu(
                            expanded = pricingDropdownExpanded,
                            onDismissRequest = { pricingDropdownExpanded = false }
                        ) {
                            pricingTypeOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        selectedPricingType = opt
                                        pricingDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // If Paid: Currency dropdown and Amount type box
                    AnimatedVisibility(visible = selectedPricingType == "Paid") {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Amount Type Box
                                OutlinedTextField(
                                    value = amountText,
                                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                    label = { Text("Amount *") },
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

                                // Currency Selection Dropdown
                                ExposedDropdownMenuBox(
                                    expanded = currencyDropdownExpanded,
                                    onExpandedChange = { currencyDropdownExpanded = !currencyDropdownExpanded },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    OutlinedTextField(
                                        value = selectedCurrency,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Currency *") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyDropdownExpanded) },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = HeaderGreen,
                                            focusedLabelColor = HeaderGreen
                                        ),
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

            // Section 5: Push Notification
            // (Email notification channel removed — creation no longer sends any
            // email; the share link is only revealed after creation, in the
            // success dialog.)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBackground),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "5. Push Notification",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = HeaderGreen
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Shows a high-priority alert on this device when the stream is scheduled.",
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
                                Text("High-priority alert on this device", fontSize = 12.sp, color = TextSub)
                            }
                        }
                        Switch(
                            checked = sendPushNotification,
                            onCheckedChange = { sendPushNotification = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = HeaderGreen, checkedTrackColor = Color(0xFFC8E6C9))
                        )
                    }
                }
            }

            // Action Buttons: Go Live Now & Schedule
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Go Live Now Button
                Button(
                    onClick = {
                        if (streamName.isBlank()) {
                            snackbarMessage = "Please enter a stream title."
                            return@Button
                        }
                        val finalTitle = streamName.trim()
                        val channel = "stream_" + System.currentTimeMillis()
                        AppServiceContainer.agoraService.startLiveStream(finalTitle, channel)
                        onBack()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("go_live_now_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Go Live Now",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Schedule Button
                Button(
                    onClick = {
                        if (streamName.isBlank()) {
                            snackbarMessage = "Please enter a title for your stream."
                            return@Button
                        }
                        val isPaid = selectedPricingType == "Paid"
                        val amount = amountText.toDoubleOrNull() ?: 0.0
                        if (isPaid && amount <= 0.0) {
                            snackbarMessage = "Please enter a valid ticket price greater than 0."
                            return@Button
                        }
                        val finalSlotLimit = if (selectedSlotType == "Limited") {
                            slotNumberText.ifBlank { "50" }
                        } else {
                            "ANY"
                        }
                        val streamType = if (isPaid) StreamPricingType.PAID else StreamPricingType.FREE

                        isSubmitting = true
                        coroutineScope.launch {
                            try {
                                val scheduled = AppServiceContainer.streamScheduleService.scheduleStream(
                                    context = context,
                                    title = streamName.trim(),
                                    category = selectedCategory,
                                    date = selectedDate,
                                    time = selectedTime,
                                    slotLimit = finalSlotLimit,
                                    type = streamType,
                                    amount = if (isPaid) amount else 0.0,
                                    currency = selectedCurrency,
                                    sendPush = sendPushNotification
                                )
                                isSubmitting = false
                                createdStream = scheduled
                                showSuccessDialog = true
                            } catch (e: Exception) {
                                isSubmitting = false
                                snackbarMessage = "Failed to schedule stream: ${e.message ?: "unknown error"}"
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("schedule_stream_submit_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderGreen),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isSubmitting
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Schedule",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
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
                            Text(
                                text = "👥 Slot Limit: ${if (selectedSlotType == "Limited") "$slotNumberText Seats" else "Unlimited"}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = HeaderGreen
                            )
                            Text(
                                text = "💰 Pricing: ${if (selectedPricingType == "Paid") "$selectedCurrency $amountText" else "Free"}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = HeaderGreen
                            )
                        }
                    }

                    // Share link — only exists now that the stream was actually
                    // created. Copy / share the REAL link generated by the
                    // StreamScheduleService (never a pre-creation placeholder).
                    createdStream?.let { created ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Share Link",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMain
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFF1F5F9),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = created.shareLink,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Stream Link", created.shareLink))
                                        Toast.makeText(context, "Stream invite link copied to clipboard!", Toast.LENGTH_SHORT).show()
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
                                                "Join my live stream on Trigger App!\n📌 Title: ${created.title}\n📅 Date: ${created.date} at ${created.time}\n🔗 Join Link: ${created.shareLink}"
                                            )
                                            type = "text/plain"
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Share Stream Invite"))
                                    }
                                ) {
                                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = HeaderGreen)
                                }
                            }
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
