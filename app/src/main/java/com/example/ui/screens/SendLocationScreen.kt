package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

data class PlaceLocationItem(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int = 120
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendLocationScreen(
    onBack: () -> Unit,
    onSendLocation: (latitude: Double, longitude: Double, name: String, address: String) -> Unit,
    onSendLiveLocation: (durationText: String) -> Unit
) {
    val context = LocalContext.current
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        hasLocationPermission = fineGranted || coarseGranted
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Default coordinates (e.g. Taliparamba, Kerala coordinates matching user's image)
    var currentLat by remember { mutableStateOf(12.0436) }
    var currentLng by remember { mutableStateOf(75.3588) }
    var accuracyMeters by remember { mutableStateOf(20) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Fetch live system location if available
    DisposableEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            try {
                val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        currentLat = location.latitude
                        currentLng = location.longitude
                        accuracyMeters = location.accuracy.toInt().coerceAtLeast(10)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                val lastKnown = locManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (lastKnown != null) {
                    currentLat = lastKnown.latitude
                    currentLng = lastKnown.longitude
                    accuracyMeters = lastKnown.accuracy.toInt().coerceAtLeast(15)
                }

                locManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    10f,
                    listener
                )

                onDispose {
                    locManager?.removeUpdates(listener)
                }
            } catch (e: SecurityException) {
                onDispose {}
            } catch (e: Exception) {
                onDispose {}
            }
        } else {
            onDispose {}
        }
    }

    var isSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isMapExpanded by remember { mutableStateOf(false) }
    var showLiveLocationSheet by remember { mutableStateOf(false) }

    val defaultPlaces = emptyList<PlaceLocationItem>()

    val filteredPlaces = remember(searchQuery, defaultPlaces) {
        if (searchQuery.isBlank()) defaultPlaces
        else defaultPlaces.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.address.contains(searchQuery, ignoreCase = true)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("send_location_screen"),
        color = Color(0xFF0F171D) // Authentic WhatsApp Dark Mode background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            Surface(
                color = Color(0xFF0F171D),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    if (isSearchMode) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                            cursorBrush = SolidColor(Color(0xFF00A884)),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search places...",
                                            color = Color(0xFF8696A0),
                                            fontSize = 17.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        IconButton(onClick = {
                            if (searchQuery.isNotEmpty()) searchQuery = ""
                            else isSearchMode = false
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = Color.White
                            )
                        }
                    } else {
                        Text(
                            text = "Send location",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(onClick = { isSearchMode = true }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search",
                                tint = Color.White
                            )
                        }

                        IconButton(onClick = {
                            isRefreshing = true
                            // brief simulation of location re-fix
                            accuracyMeters = (15..25).random()
                            isRefreshing = false
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // Dark Map View Canvas
            val mapHeight = if (isMapExpanded) 380.dp else 220.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mapHeight)
                    .background(Color(0xFF141F28))
            ) {
                // Custom stylized vector map rendering dark Google Maps theme
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Background land
                    drawRect(color = Color(0xFF121B22))

                    // River / Water body
                    val riverPath = Path().apply {
                        moveTo(0f, h * 0.35f)
                        cubicTo(w * 0.35f, h * 0.40f, w * 0.55f, h * 0.18f, w, h * 0.30f)
                        lineTo(w, h * 0.52f)
                        cubicTo(w * 0.60f, h * 0.35f, w * 0.35f, h * 0.62f, 0f, h * 0.50f)
                        close()
                    }
                    drawPath(riverPath, color = Color(0xFF192A38))

                    // Road Network lines
                    val roadColorMajor = Color(0xFF2C3E4C)
                    val roadColorMinor = Color(0xFF1F2E3A)

                    // Roads
                    drawLine(roadColorMajor, Offset(0f, h * 0.70f), Offset(w, h * 0.62f), strokeWidth = 5f)
                    drawLine(roadColorMajor, Offset(w * 0.15f, 0f), Offset(w * 0.45f, h), strokeWidth = 4.5f)
                    drawLine(roadColorMinor, Offset(w * 0.45f, h * 0.2f), Offset(w * 0.85f, h * 0.9f), strokeWidth = 3f)
                    drawLine(roadColorMinor, Offset(w * 0.2f, h * 0.3f), Offset(w, h * 0.15f), strokeWidth = 3.5f)
                    drawLine(roadColorMinor, Offset(0f, h * 0.20f), Offset(w * 0.5f, h * 0.45f), strokeWidth = 2.5f)

                    // Scattered place markers (green circular dots with white outline)
                    val markerPoints = listOf(
                        Offset(w * 0.12f, h * 0.22f),
                        Offset(w * 0.27f, h * 0.20f),
                        Offset(w * 0.33f, h * 0.19f),
                        Offset(w * 0.28f, h * 0.24f),
                        Offset(w * 0.31f, h * 0.23f),
                        Offset(w * 0.37f, h * 0.26f),
                        Offset(w * 0.42f, h * 0.20f),
                        Offset(w * 0.51f, h * 0.17f),
                        Offset(w * 0.55f, h * 0.25f),
                        Offset(w * 0.65f, h * 0.18f),
                        Offset(w * 0.69f, h * 0.19f),
                        Offset(w * 0.78f, h * 0.20f),
                        Offset(w * 0.88f, h * 0.20f),
                        Offset(w * 0.77f, h * 0.28f),
                        Offset(w * 0.82f, h * 0.35f),
                        Offset(w * 0.40f, h * 0.29f),
                        Offset(w * 0.49f, h * 0.39f)
                    )

                    markerPoints.forEach { pt ->
                        drawCircle(color = Color(0xFF00A884), radius = 6.5f, center = pt)
                        drawCircle(color = Color.White, radius = 6.5f, center = pt, style = Stroke(width = 1.5f))
                    }

                    // Current Location (Blue Dot with Pulsing Halo)
                    val centerPt = Offset(w * 0.57f, h * 0.28f)
                    // Outer Accuracy Halo
                    drawCircle(color = Color(0x332196F3), radius = 24f, center = centerPt)
                    // Inner Blue Core
                    drawCircle(color = Color(0xFF2196F3), radius = 9f, center = centerPt)
                    drawCircle(color = Color.White, radius = 9f, center = centerPt, style = Stroke(width = 2.5f))
                }

                // Top-left View Toggle Icon
                Box(
                    modifier = Modifier
                        .padding(14.dp)
                        .align(Alignment.TopStart)
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC182229))
                        .clickable { isMapExpanded = !isMapExpanded },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isMapExpanded) Icons.Filled.FullscreenExit else Icons.Filled.CropFree,
                        contentDescription = "Toggle Map Size",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Top-right My Location FAB
                Box(
                    modifier = Modifier
                        .padding(14.dp)
                        .align(Alignment.TopEnd)
                        .size(44.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable {
                            accuracyMeters = (15..20).random()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.MyLocation,
                        contentDescription = "My Location",
                        tint = Color(0xFF121B22),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Bottom-left Google Watermark
                Text(
                    text = "Google",
                    color = Color(0x99FFFFFF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, bottom = 8.dp)
                )
            }

            // Places & Actions List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0F171D))
            ) {
                // Share Live Location
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLiveLocationSheet = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GpsFixed,
                                contentDescription = null,
                                tint = Color(0xFF0F171D),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Text(
                            text = "Share live location",
                            color = Color.White,
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    HorizontalDivider(color = Color(0xFF1E2A32), thickness = 0.8.dp)
                }

                // Nearby places header
                item {
                    Text(
                        text = "Nearby places",
                        color = Color(0xFF8696A0),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp)
                    )
                }

                // Send your current location
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSendLocation(
                                    currentLat,
                                    currentLng,
                                    "Current Location",
                                    "Accurate to $accuracyMeters meters"
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF182E28)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Adjust,
                                contentDescription = null,
                                tint = Color(0xFF00A884),
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = "Send your current location",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Accurate to $accuracyMeters meters",
                                color = Color(0xFF8696A0),
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }

                // Nearby places list items
                items(filteredPlaces, key = { it.id }) { place ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSendLocation(
                                    place.latitude,
                                    place.longitude,
                                    place.name,
                                    place.address
                                )
                            }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1F2C34)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Place,
                                contentDescription = null,
                                tint = Color(0xFFE9EDEF),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = place.name,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = place.address,
                                color = Color(0xFF8696A0),
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // Share Live Location Bottom Sheet
    if (showLiveLocationSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLiveLocationSheet = false },
            containerColor = Color(0xFF1F2C34)
        ) {
            var selectedDuration by remember { mutableStateOf("1 hour") }
            var commentText by remember { mutableStateOf("") }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Share Live Location",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Participants in this chat will see your real-time location. This feature shares your location for the selected duration even if you're not using the app.",
                    color = Color(0xFF8696A0),
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("15 minutes", "1 hour", "8 hours").forEach { dur ->
                        val isSelected = selectedDuration == dur
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) Color(0xFF00A884) else Color(0xFF2A3942),
                            modifier = Modifier.clickable { selectedDuration = dur }
                        ) {
                            Text(
                                text = dur,
                                color = if (isSelected) Color.White else Color(0xFFD1D7DB),
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    placeholder = { Text("Add comment", color = Color(0xFF8696A0)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00A884),
                        unfocusedBorderColor = Color(0xFF2A3942),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                showLiveLocationSheet = false
                                onSendLiveLocation(selectedDuration)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = "Send",
                                tint = Color(0xFF00A884)
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
