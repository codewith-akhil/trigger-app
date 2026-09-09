package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.service.geo.GeocodeClient
import com.example.service.geo.NearbyPlacesClient
import com.example.service.geo.PlaceLocationItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * A real device location fix (framework [Location]). The screen holds NO
 * fabricated coordinates: every value here comes from GPS/NETWORK providers.
 */
private data class DeviceFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Int
)

/** A dropped-pin selection made by tapping the map. */
private data class DroppedPin(
    val latitude: Double,
    val longitude: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendLocationScreen(
    onBack: () -> Unit,
    onSendLocation: (latitude: Double, longitude: Double, name: String, address: String) -> Unit,
    onSendLiveLocation: (latitude: Double, longitude: Double, durationText: String, comment: String) -> Unit
) {
    val context = LocalContext.current

    // ------------------------------------------------------------------
    // Runtime permissions
    // ------------------------------------------------------------------
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
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

    val locManager = remember {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }
    val isProviderAvailable = remember {
        val gps = runCatching { locManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false }.getOrDefault(false)
        val net = runCatching { locManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ?: false }.getOrDefault(false)
        gps || net
    }

    // ------------------------------------------------------------------
    // Real device state — nothing here is hardcoded
    // ------------------------------------------------------------------
    var currentFix by remember { mutableStateOf<DeviceFix?>(null) }
    var currentAddress by remember { mutableStateOf<GeocodeClient.PlaceAddress?>(null) }
    var isResolvingAddress by remember { mutableStateOf(false) }
    var selectedPin by remember { mutableStateOf<DroppedPin?>(null) }
    var pinAddress by remember { mutableStateOf<GeocodeClient.PlaceAddress?>(null) }
    var isResolvingPin by remember { mutableStateOf(false) }

    var nearbyPlaces by remember { mutableStateOf<List<PlaceLocationItem>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<PlaceLocationItem>>(emptyList()) }
    var isPlacesLoading by remember { mutableStateOf(false) }
    var placesError by remember { mutableStateOf<String?>(null) }
    var lastNearbyCenter by remember { mutableStateOf<GeoPoint?>(null) }

    var isRefreshing by remember { mutableStateOf(false) }
    var isMapExpanded by remember { mutableStateOf(false) }
    var showLiveLocationSheet by remember { mutableStateOf(false) }
    var isSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val placeMarkers = remember { mutableListOf<Marker>() }
    var addressJob by remember { mutableStateOf<Job?>(null) }
    var pinAddressJob by remember { mutableStateOf<Job?>(null) }
    var nearbyDebounceJob by remember { mutableStateOf<Job?>(null) }
    var lastGeocodedFix by remember { mutableStateOf<DeviceFix?>(null) }

    val displayedPlaces = if (isSearchMode && searchQuery.isNotBlank()) searchResults else nearbyPlaces
    var hasCenteredOnce by remember { mutableStateOf(false) }

    // ------------------------------------------------------------------
    // Nearby places loader (Overpass API, real POIs)
    // ------------------------------------------------------------------
    fun loadNearby(center: GeoPoint) {
        if (!hasLocationPermission) return
        isPlacesLoading = true
        placesError = null
        scope.launch {
            val results = NearbyPlacesClient.nearby(center.latitude, center.longitude)
            nearbyPlaces = results
            lastNearbyCenter = GeoPoint(center.latitude, center.longitude)
            isPlacesLoading = false
            if (results.isEmpty()) {
                placesError = null // empty is a valid state; error row only on exception
            }
        }
    }

    // ------------------------------------------------------------------
    // Map factory — configured ONCE, real CARTO dark tiles (OSM data)
    // ------------------------------------------------------------------
    val mapFactory: (Context) -> MapView = { ctx ->
        MapView(ctx).apply {
            setTileSource(
                XYTileSource(
                    "CARTO_DARK",
                    1, 20, 256, ".png",
                    // The style path segment ("dark_all") is REQUIRED: osmdroid
                    // appends /{z}/{x}/{y}.png directly to the base URL, and the
                    // bare basemaps host 404s every tile (blank beige canvas +
                    // repaint flicker). curl-verified: /dark_all/{z}/{x}/{y}.png = 200.
                    arrayOf(
                        "https://a.basemaps.cartocdn.com/dark_all/",
                        "https://b.basemaps.cartocdn.com/dark_all/",
                        "https://c.basemaps.cartocdn.com/dark_all/",
                        "https://d.basemaps.cartocdn.com/dark_all/"
                    ),
                    "© OpenStreetMap contributors © CARTO"
                )
            )
            setMultiTouchControls(true) // pinch-zoom + pan gestures
            minZoomLevel = 2.0
            maxZoomLevel = 20.0
            controller.setZoom(16.0)

            // Live "me" dot with accuracy halo (framework providers, no Play Services).
            // Continuous fix tracking for app state happens in the LocationManager
            // DisposableEffect below; the overlay only draws.
            val myLocationOverlay = MyLocationNewOverlay(this)
            myLocationOverlay.setDrawAccuracyEnabled(true)
            myLocationOverlay.setEnableAutoStop(false)
            // Must be ENABLED to draw: previously the overlay was added but
            // enableMyLocation() was never called, so the "me dot" never showed.
            myLocationOverlay.enableMyLocation()
            overlays.add(myLocationOverlay)

            // Tap-to-drop-pin (real reverse-geocoded selection, like WhatsApp).
            overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        val point = p ?: return false
                        selectedPin = DroppedPin(point.latitude, point.longitude)
                        pinAddress = null
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                })
            )

            // When the user pans far from the last fetched area, refresh the
            // real nearby-places list around the new map centre (debounced).
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    nearbyDebounceJob?.cancel()
                    nearbyDebounceJob = scope.launch {
                        delay(1500)
                        val map = mapViewRef.value ?: return@launch
                        if (isSearchMode) return@launch
                        val center = map.mapCenter ?: return@launch
                        val previous = lastNearbyCenter
                        val movedEnough = previous == null || run {
                            val dist = FloatArray(1)
                            Location.distanceBetween(
                                previous.latitude, previous.longitude,
                                center.latitude, center.longitude, dist
                            )
                            dist[0] > 800f
                        }
                        if (movedEnough) loadNearby(GeoPoint(center.latitude, center.longitude))
                    }
                    return true
                }

                override fun onZoom(event: ZoomEvent?): Boolean = true
            })
        }
    }

    // ------------------------------------------------------------------
    // Continuous REAL location tracking (GPS + NETWORK providers).
    // Single source of truth for currentFix — no fabricated values ever.
    // ------------------------------------------------------------------
    DisposableEffect(hasLocationPermission) {
        var listener: LocationListener? = null
        if (hasLocationPermission && locManager != null) {
            // Seed immediately from the last known system fix so the map and
            // cards are usable while GPS warms up.
            val seed = try {
                locManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: locManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            } catch (e: SecurityException) {
                null
            }
            if (seed != null) {
                currentFix = DeviceFix(
                    latitude = seed.latitude,
                    longitude = seed.longitude,
                    accuracyMeters = seed.accuracy.roundToInt().coerceAtLeast(1)
                )
            }

            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentFix = DeviceFix(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy.roundToInt().coerceAtLeast(1)
                    )
                }

                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
            }
            try {
                if (runCatching { locManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
                    locManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 4000L, 8f, listener, Looper.getMainLooper()
                    )
                }
                if (runCatching { locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
                    locManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 8000L, 15f, listener, Looper.getMainLooper()
                    )
                }
            } catch (e: SecurityException) {
                // Permission revoked mid-flight — the permission dialog re-runs on next entry.
            }
        }
        onDispose {
            listener?.let { locManager?.removeUpdates(it) }
        }
    }

    // First fix: center the map + load real nearby places (once).
    LaunchedEffect(currentFix?.latitude, currentFix?.longitude) {
        val fix = currentFix ?: return@LaunchedEffect
        if (!hasCenteredOnce) {
            hasCenteredOnce = true
            mapViewRef.value?.controller?.animateTo(GeoPoint(fix.latitude, fix.longitude))
            loadNearby(GeoPoint(fix.latitude, fix.longitude))
        }
    }

    // ------------------------------------------------------------------
    // Fresh one-shot location fetch (My Location FAB + Refresh).
    // Uses getCurrentLocation (API 30+) or requestSingleUpdate (older) —
    // always a REAL new GPS/NETWORK fix, never a synthetic value.
    // ------------------------------------------------------------------
    suspend fun fetchFreshFix(): Location? {
        if (!hasLocationPermission || locManager == null) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locManager.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in providers) {
            val fix = withTimeoutOrNull(15_000L) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                if (cont.isActive) cont.resume(location)
                            }

                            override fun onProviderDisabled(provider: String) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                locManager.getCurrentLocation(
                                    provider,
                                    null,
                                    ContextCompat.getMainExecutor(context)
                                ) { location -> if (cont.isActive) cont.resume(location) }
                            } else {
                                @Suppress("DEPRECATION")
                                locManager.requestSingleUpdate(
                                    provider,
                                    listener,
                                    Looper.getMainLooper()
                                )
                            }
                        } catch (e: SecurityException) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
            }
            if (fix != null) return fix
        }
        return null
    }

    fun refreshLocation(toastFeedback: Boolean) {
        isRefreshing = true
        scope.launch {
            val fix = fetchFreshFix()
            if (fix != null) {
                currentFix = DeviceFix(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyMeters = fix.accuracy.roundToInt().coerceAtLeast(1)
                )
                selectedPin = null // back to "current location" mode, like WhatsApp
                pinAddress = null
                mapViewRef.value?.controller?.animateTo(GeoPoint(fix.latitude, fix.longitude))
                loadNearby(GeoPoint(fix.latitude, fix.longitude))
            } else if (toastFeedback) {
                Toast.makeText(
                    context,
                    "Couldn't get a location fix. Is device location on?",
                    Toast.LENGTH_SHORT
                ).show()
            }
            isRefreshing = false
        }
    }

    // ------------------------------------------------------------------
    // Reverse-geocode the current fix (debounced, only when it moved >75 m)
    // ------------------------------------------------------------------
    LaunchedEffect(currentFix?.latitude, currentFix?.longitude) {
        val fix = currentFix ?: return@LaunchedEffect
        val previous = lastGeocodedFix
        val movedFarEnough = previous == null || run {
            val dist = FloatArray(1)
            Location.distanceBetween(
                previous.latitude, previous.longitude, fix.latitude, fix.longitude, dist
            )
            dist[0] > 75f
        }
        if (!movedFarEnough && currentAddress != null) return@LaunchedEffect

        addressJob?.cancel()
        addressJob = launch {
            isResolvingAddress = true
            val result = GeocodeClient.reverseGeocode(context, fix.latitude, fix.longitude)
            currentAddress = result
            lastGeocodedFix = fix
            isResolvingAddress = false
        }
    }

    // ------------------------------------------------------------------
    // Reverse-geocode a dropped pin (debounced 400 ms)
    // ------------------------------------------------------------------
    LaunchedEffect(selectedPin?.latitude, selectedPin?.longitude) {
        val pin = selectedPin ?: return@LaunchedEffect
        if (pinAddress != null) return@LaunchedEffect
        pinAddressJob?.cancel()
        pinAddressJob = launch {
            isResolvingPin = true
            delay(400)
            pinAddress = GeocodeClient.reverseGeocode(context, pin.latitude, pin.longitude)
            isResolvingPin = false
        }
    }

    // ------------------------------------------------------------------
    // Real place search (Nominatim) — debounced, biased to map centre
    // ------------------------------------------------------------------
    LaunchedEffect(searchQuery, isSearchMode) {
        if (!isSearchMode || searchQuery.trim().length < 3) {
            if (isSearchMode && searchQuery.isBlank()) searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(700)
        val map = mapViewRef.value
        val center = map?.mapCenter
        if (center == null && currentFix == null) return@LaunchedEffect
        isSearching = true
        val results = NearbyPlacesClient.search(
            query = searchQuery,
            latitude = center?.latitude ?: currentFix!!.latitude,
            longitude = center?.longitude ?: currentFix!!.longitude
        )
        searchResults = results
        isSearching = false
    }

    // ------------------------------------------------------------------
    // Screen
    // ------------------------------------------------------------------
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("send_location_screen"),
        color = Color(0xFF0F171D)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ------------------ Top App Bar ------------------
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
                                            text = "Search places nearby…",
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
                            else {
                                isSearchMode = false
                                searchResults = emptyList()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close search",
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

                        IconButton(onClick = {
                            isSearchMode = true
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search places",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = { refreshLocation(toastFeedback = true) },
                            enabled = hasLocationPermission
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF00A884)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Refresh location",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // ------------------ Real osmdroid map ------------------
            val mapHeight = if (isMapExpanded) 380.dp else 220.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mapHeight)
                    .background(Color(0xFF121B22))
            ) {
                AndroidView(
                    factory = { ctx ->
                        mapViewRef.value = mapFactory(ctx)
                        mapViewRef.value!!
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { map ->
                        map.overlays.forEach { overlay ->
                            if (overlay is MyLocationNewOverlay) overlay.disableMyLocation()
                        }
                        map.onDetach()
                        mapViewRef.value = null
                    }
                )

                // Marker reconciliation — runs whenever the data changes.
                // ~400ms debounce: GPS fix cycles and reverse-geocode results
                // mutate [displayedPlaces] several times per second; without
                // the threshold each change rebuilt markers AND invalidated
                // the whole MapView (repaint storm). Rapid key changes cancel
                // this coroutine BEFORE any overlay mutation, so only the
                // final settled state is painted.
                LaunchedEffect(displayedPlaces, selectedPin) {
                    delay(400)
                    val map = mapViewRef.value ?: return@LaunchedEffect
                    map.overlays.removeAll(placeMarkers)
                    placeMarkers.clear()

                    displayedPlaces.forEach { place ->
                        val marker = Marker(map).apply {
                            position = GeoPoint(place.latitude, place.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = placeDotIcon(context)
                            title = place.name
                            setOnMarkerClickListener { _, _ ->
                                selectedPin = DroppedPin(place.latitude, place.longitude)
                                pinAddress = GeocodeClient.PlaceAddress(
                                    displayName = "${place.name}, ${place.address}",
                                    shortAddress = place.name
                                )
                                true
                            }
                        }
                        placeMarkers.add(marker)
                        map.overlays.add(marker)
                    }

                    selectedPin?.let { pin ->
                        val marker = Marker(map).apply {
                            position = GeoPoint(pin.latitude, pin.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = droppedPinIcon(context)
                            title = "Dropped pin"
                        }
                        placeMarkers.add(marker)
                        map.overlays.add(marker)
                    }
                    map.invalidate()
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
                        contentDescription = "Toggle map size",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Top-right My Location FAB — recenters on the REAL fix.
                Box(
                    modifier = Modifier
                        .padding(14.dp)
                        .align(Alignment.TopEnd)
                        .size(44.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable {
                            if (hasLocationPermission) {
                                refreshLocation(toastFeedback = false)
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.MyLocation,
                        contentDescription = "My location",
                        tint = Color(0xFF121B22),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Mandatory OSM/CARTO attribution (replaces the fake watermark).
                Text(
                    text = "© OpenStreetMap © CARTO",
                    color = Color(0x99FFFFFF),
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, bottom = 8.dp)
                )
            }

            // ------------------ Places & actions list ------------------
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
                            .clickable {
                                if (currentFix != null) showLiveLocationSheet = true
                            }
                            .alpha(if (currentFix != null) 1f else 0.5f)
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

                        Column {
                            Text(
                                text = "Share live location",
                                color = Color.White,
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (currentFix == null) {
                                Text(
                                    text = "Waiting for a location fix…",
                                    color = Color(0xFF8696A0),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF1E2A32), thickness = 0.8.dp)
                }

                // Nearby places header
                item {
                    Text(
                        text = if (isSearchMode) "Search results" else "Nearby places",
                        color = Color(0xFF8696A0),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp)
                    )
                }

                // Dropped pin card — send the tapped location.
                selectedPin?.let { pin ->
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val label = pinAddress?.shortAddress
                                        ?: pinAddress?.displayName
                                        ?: formatCoords(pin.latitude, pin.longitude)
                                    onSendLocation(
                                        pin.latitude,
                                        pin.longitude,
                                        label,
                                        pinAddress?.displayName ?: formatCoords(pin.latitude, pin.longitude)
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                                    imageVector = Icons.Filled.LocationOn,
                                    contentDescription = null,
                                    tint = Color(0xFF00A884),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Send this location",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when {
                                        isResolvingPin -> "Resolving address…"
                                        pinAddress != null -> pinAddress!!.shortAddress
                                        else -> formatCoords(pin.latitude, pin.longitude)
                                    },
                                    color = Color(0xFF8696A0),
                                    fontSize = 13.5.sp
                                )
                            }
                            IconButton(onClick = {
                                selectedPin = null
                                pinAddress = null
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove pin",
                                    tint = Color(0xFF8696A0)
                                )
                            }
                        }
                        HorizontalDivider(color = Color(0xFF1E2A32), thickness = 0.8.dp)
                    }
                }

                // "Send your current location" — only when a real fix exists.
                if (selectedPin == null) {
                    item {
                        val fix = currentFix
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = fix != null) {
                                    if (fix != null) {
                                        onSendLocation(
                                            fix.latitude,
                                            fix.longitude,
                                            "Current Location",
                                            currentAddress?.shortAddress
                                                ?: currentAddress?.displayName
                                                ?: "Accurate to ${fix.accuracyMeters} meters"
                                        )
                                    }
                                }
                                .alpha(if (fix != null) 1f else 0.55f)
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
                                when {
                                    fix == null && !isProviderAvailable -> Text(
                                        text = "Device location is off",
                                        color = Color(0xFFE9A13B),
                                        fontSize = 13.5.sp
                                    )
                                    fix == null -> Text(
                                        text = "Locating your position…",
                                        color = Color(0xFF8696A0),
                                        fontSize = 13.5.sp
                                    )
                                    isResolvingAddress && currentAddress == null -> Text(
                                        text = "Accurate to ${fix.accuracyMeters} meters · resolving address…",
                                        color = Color(0xFF8696A0),
                                        fontSize = 13.5.sp
                                    )
                                    currentAddress != null -> Text(
                                        text = currentAddress!!.shortAddress,
                                        color = Color(0xFF8696A0),
                                        fontSize = 13.5.sp
                                    )
                                    else -> Text(
                                        text = "Accurate to ${fix.accuracyMeters} meters",
                                        color = Color(0xFF8696A0),
                                        fontSize = 13.5.sp
                                    )
                                }
                            }
                        }

                        if (fix == null && !isProviderAvailable) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                            )
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = null,
                                    tint = Color(0xFF00A884),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Enable location to share your position",
                                    color = Color(0xFF00A884),
                                    fontSize = 14.sp
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0xFF1E2A32), thickness = 0.8.dp)
                    }
                }

                // Places loading indicator
                if (isPlacesLoading || isSearching) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF00A884)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (isSearching) "Searching places…" else "Finding nearby places…",
                                color = Color(0xFF8696A0),
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // Places error / empty states
                if (!isPlacesLoading && !isSearching) {
                    if (placesError != null) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        lastNearbyCenter?.let { loadNearby(it) }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudOff,
                                    contentDescription = null,
                                    tint = Color(0xFFE9A13B),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = placesError ?: "Couldn't load nearby places — tap to retry",
                                    color = Color(0xFF8696A0),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else if (displayedPlaces.isEmpty()) {
                        item {
                            Text(
                                text = if (isSearchMode) "No places matched your search"
                                else "No nearby places found",
                                color = Color(0xFF8696A0),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // Nearby place rows (real POIs with real distances)
                items(displayedPlaces, key = { it.id }) { place ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSendLocation(
                                    place.latitude,
                                    place.longitude,
                                    place.name,
                                    "${place.name}, ${place.address}"
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = place.address,
                                    color = Color(0xFF8696A0),
                                    fontSize = 13.5.sp,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (place.distanceMeters > 0) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = formatDistance(place.distanceMeters),
                                        color = Color(0xFF6B7A84),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Share Live Location bottom sheet — sends the REAL current fix
    // ------------------------------------------------------------------
    if (showLiveLocationSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLiveLocationSheet = false },
            containerColor = Color(0xFF1F2C34)
        ) {
            var selectedDuration by remember { mutableStateOf("1 hour") }
            var commentText by remember { mutableStateOf("") }
            val fix = currentFix

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
                            enabled = fix != null,
                            onClick = {
                                val f = currentFix ?: return@IconButton
                                showLiveLocationSheet = false
                                onSendLiveLocation(
                                    f.latitude,
                                    f.longitude,
                                    selectedDuration,
                                    commentText.trim()
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = "Send",
                                tint = if (fix != null) Color(0xFF00A884) else Color(0xFF4A5A64)
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ----------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------

private fun formatCoords(latitude: Double, longitude: Double): String =
    "%.5f, %.5f".format(latitude, longitude)

private fun formatDistance(meters: Int): String = when {
    meters < 1000 -> "${meters}m"
    meters < 10_000 -> String.format("%.1f km", meters / 1000.0)
    else -> "${(meters / 1000.0).roundToInt()} km"
}

/** Green POI dot marker (drawn programmatically — no asset needed). */
private fun placeDotIcon(context: Context): android.graphics.drawable.BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val size = (18 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color(0xFF00A884).toArgb() }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    val cx = size / 2f
    canvas.drawCircle(cx, cx, size / 2f - 2f * density, fill)
    canvas.drawCircle(cx, cx, size / 2f - 2f * density, stroke)
    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/** Teal dropped-pin marker (drawn programmatically — no asset needed). */
private fun droppedPinIcon(context: Context): android.graphics.drawable.BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val w = (26 * density).toInt()
    val h = (34 * density).toInt()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color(0xFF00A884).toArgb()
        style = Paint.Style.FILL
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }
    val cx = w / 2f
    val headR = 9f * density
    val headCy = headR + 2f * density
    val tipY = h.toFloat()
    // Teardrop: circle head + triangle tail.
    canvas.drawCircle(cx, headCy, headR, paint)
    val path = android.graphics.Path().apply {
        moveTo(cx - headR * 0.72f, headCy + headR * 0.68f)
        lineTo(cx, tipY)
        lineTo(cx + headR * 0.72f, headCy + headR * 0.68f)
        close()
    }
    canvas.drawPath(path, paint)
    canvas.drawCircle(cx, headCy, headR, stroke)
    // White centre dot like WhatsApp's pin.
    val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    canvas.drawCircle(cx, headCy, 3.2f * density, inner)
    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}
