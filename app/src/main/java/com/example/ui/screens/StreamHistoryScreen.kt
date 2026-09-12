package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.example.service.StreamHistoryItem
import kotlinx.coroutines.launch

private val HeaderGreen = Color(0xFF008069)
private val DarkBackground = Color(0xFFF7F9FA)
private val CardBackground = Color(0xFFFFFFFF)
private val TextMain = Color(0xFF111B21)
private val TextSub = Color(0xFF667781)
private val AccentGreen = Color(0xFF00A884)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamHistoryScreen(
    onBack: () -> Unit,
    onScheduleNew: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val historyItems by AppServiceContainer.streamScheduleService.streamHistory.collectAsState()
    var selectedFilter by remember { mutableStateOf("All") }
    val coroutineScope = rememberCoroutineScope()

    // Loading + error state for refreshStreamHistory() — exposed to the UI
    // so the user sees a spinner while fetching, an empty state when nothing
    // has been broadcast yet, and a retry banner on failure.
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Hydrate from `live_streams` (status=ended, host_id=caller) on entry.
    suspend fun loadHistory() {
        isLoading = true
        errorMessage = null
        try {
            AppServiceContainer.streamScheduleService.refreshStreamHistory()
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load stream history."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadHistory()
    }

    val filteredList = remember(historyItems, selectedFilter) {
        when (selectedFilter) {
            "Paid" -> historyItems.filter { it.type == "PAID" }
            "Free" -> historyItems.filter { it.type == "FREE" }
            else -> historyItems
        }
    }

    val totalRevenue = remember(historyItems) { historyItems.sumOf { it.revenue } }
    val totalViewers = remember(historyItems) { historyItems.sumOf { it.peakViewers } }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("stream_history_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = DarkBackground,
        topBar = {
            com.example.ui.components.TriggerTopHeader(
                title = "Stream History",
                onBack = onBack
            )
        },
        bottomBar = {
            com.example.ui.components.TriggerBottomNavInset()
        }
    ) { innerPadding ->
        // Error state with retry — surfaces any failure to fetch stream history.
        val currentError = errorMessage
        if (currentError != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = TextSub,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Couldn't load stream history",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = currentError,
                    fontSize = 13.sp,
                    color = TextSub,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { coroutineScope.launch { loadHistory() } },
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderGreen),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Retry", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            return@Scaffold
        }

        // Loading state — initial fetch in progress.
        if (isLoading && historyItems.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = HeaderGreen, strokeWidth = 3.dp)
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Loading stream history…",
                    fontSize = 14.sp,
                    color = TextSub
                )
            }
            return@Scaffold
        }

        // Empty state — no broadcasts yet.
        if (historyItems.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = TextSub,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No broadcasts yet",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onScheduleNew,
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderGreen),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Schedule a Stream", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Analytics Overview Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBackground),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Broadcast Summary",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = HeaderGreen
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Total Broadcasts", fontSize = 12.sp, color = TextSub)
                                Text("${historyItems.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Peak Viewers", fontSize = 12.sp, color = TextSub)
                                Text("$totalViewers", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Total Revenue", fontSize = 12.sp, color = TextSub)
                                Text("$${"%.2f".format(totalRevenue)}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                            }
                        }
                    }
                }
            }

            // Filter Chips
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Paid", "Free").forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE8F5E9),
                                selectedLabelColor = HeaderGreen
                            )
                        )
                    }
                }
            }

            // History Items
            items(filteredList, key = { it.id }) { item ->
                StreamHistoryCard(item)
            }
        }
    }
}

@Composable
fun StreamHistoryCard(item: StreamHistoryItem) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (item.type == "PAID") Color(0xFFE8F5E9) else Color(0xFFF1F5F9)
                ) {
                    Text(
                        text = if (item.type == "PAID") "PAID STREAM" else "FREE STREAM",
                        color = if (item.type == "PAID") HeaderGreen else Color(0xFF475569),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFF8FAFC)
                ) {
                    Text(
                        text = item.status,
                        color = TextSub,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = item.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, contentDescription = null, tint = TextSub, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(item.date, fontSize = 12.sp, color = TextSub)
                Spacer(modifier = Modifier.width(12.dp))
                Icon(Icons.Filled.Timer, contentDescription = null, tint = TextSub, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(item.duration, fontSize = 12.sp, color = TextSub)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFFF1F5F9))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Visibility, contentDescription = null, tint = HeaderGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${item.peakViewers} peak viewers", fontSize = 13.sp, color = TextMain)
                }

                if (item.type == "PAID") {
                    Text(
                        text = "Revenue: $${"%.2f".format(item.revenue)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentGreen
                    )
                } else {
                    Text(
                        text = "${item.attendeesCount} attendees",
                        fontSize = 13.sp,
                        color = TextSub
                    )
                }
            }
        }
    }
}
