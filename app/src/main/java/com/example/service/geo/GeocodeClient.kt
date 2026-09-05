package com.example.service.geo

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Production reverse-geocoding client for the Send Location screen.
 *
 * Strategy:
 *  1. Primary: Android framework [Geocoder] — free, locale-aware, no API key,
 *     works on the vast majority of GMS-equipped devices.
 *  2. Fallback: OpenStreetMap Nominatim `/reverse` REST API — used when the
 *     device has no geocoder backend (de-Googled ROMs) or the framework call
 *     throws/returns nothing.
 *
 * Nominatim fair-use policy (https://operations.osmfoundation.org/policies/nominatim):
 * max 1 request/second + an identifying User-Agent. Both are enforced here via
 * [OsmServiceThrottle].
 */
object GeocodeClient {

    private const val TAG = "GeocodeClient"
    private const val NOMINATIM_BASE = "https://nominatim.openstreetmap.org"
    private const val USER_AGENT = "TriggerApp/1.0 (Android; contact: info@triggerapp.com)"

    /** Compact human-readable address, e.g. "Manoor Rd, Taliparamba, Kerala". */
    data class PlaceAddress(
        val displayName: String,
        val shortAddress: String
    )

    /**
     * Reverse-geocode a coordinate into a compact address.
     * Returns null only when both providers fail — callers must handle this
     * (e.g. show the raw "12.0436, 75.3588" coordinates instead).
     */
    suspend fun reverseGeocode(context: Context, latitude: Double, longitude: Double): PlaceAddress? {
        // Try framework Geocoder first.
        systemGeocoder(context, latitude, longitude)?.let { return it }
        // Fallback to Nominatim.
        return nominatimReverse(latitude, longitude)
    }

    // ------------------------------------------------------------------
    // 1) Framework Geocoder
    // ------------------------------------------------------------------
    @SuppressLint("NewApi")
    private suspend fun systemGeocoder(
        context: Context,
        latitude: Double,
        longitude: Double
    ): PlaceAddress? = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) return@withContext null
            val geocoder = Geocoder(context)
            val addresses: List<Address>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : Geocoder.GeocodeListener {
                        override fun onGeocode(results: MutableList<Address>) {
                            if (cont.isActive) cont.resume(results)
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    geocoder.getFromLocation(latitude, longitude, 1, listener)
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1)
            }
            val a = addresses?.firstOrNull() ?: return@withContext null
            val display = a.getAddressLine(0) ?: ""
            if (display.isBlank()) return@withContext null
            // Compact: street + locality + region, like WhatsApp's address line.
            val compact = buildList {
                a.thoroughfare?.let { add(it) }
                a.subLocality?.takeIf { it.isNotBlank() }?.let { add(it) }
                a.locality?.let { add(it) }
                a.adminArea?.let { add(it) }
            }.distinct().joinToString(", ")
            PlaceAddress(
                displayName = display,
                shortAddress = compact.ifBlank { display.substringBefore(",") }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Framework Geocoder failed: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // 2) Nominatim reverse (fallback)
    // ------------------------------------------------------------------
    private suspend fun nominatimReverse(latitude: Double, longitude: Double): PlaceAddress? =
        withContext(Dispatchers.IO) {
            OsmServiceThrottle.awaitTurn()
            try {
                val url = "$NOMINATIM_BASE/reverse" +
                    "?lat=$latitude&lon=$longitude&format=jsonv2&zoom=18&addressdetails=1"
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .readTimeout(java.time.Duration.ofSeconds(15))
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) return@use null
                        val body = response.body?.string() ?: return@use null
                        val obj = org.json.JSONObject(body)
                        val displayName = obj.optString("display_name", "").ifBlank { return@use null }
                        val address = obj.optJSONObject("address")
                        val compact = if (address != null) {
                            listOf(
                                address.optString("road"),
                                address.optString("neighbourhood"),
                                address.optString("suburb"),
                                address.optString("city_district"),
                                address.optString("city"),
                                address.optString("town"),
                                address.optString("village"),
                                address.optString("state")
                            ).filter { it.isNotBlank() }.distinct().take(4).joinToString(", ")
                        } else ""
                        PlaceAddress(
                            displayName = displayName,
                            shortAddress = compact.ifBlank { displayName.substringBefore(",") }
                        )
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Nominatim reverse failed: ${e.message}")
                null
            }
        }
}

/**
 * Serialises outbound requests to OpenStreetMap public services so the app
 * never exceeds the 1 request/second fair-use policy, even when several
 * screens fire concurrently.
 */
internal object OsmServiceThrottle {
    private val lastRequestAt = AtomicLong(0L)

    suspend fun awaitTurn() {
        while (true) {
            val last = lastRequestAt.get()
            val now = System.currentTimeMillis()
            val wait = last + 1100L - now
            if (wait <= 0) {
                if (lastRequestAt.compareAndSet(last, now)) return
            } else {
                delay(wait)
            }
        }
    }
}
