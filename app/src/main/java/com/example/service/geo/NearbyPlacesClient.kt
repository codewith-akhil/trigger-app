package com.example.service.geo

import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A real point of interest resolved from OpenStreetMap data. */
data class PlaceLocationItem(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int = 0
)

/**
 * Real nearby-places provider for the Send Location screen, built entirely on
 * free OpenStreetMap services (no API keys, no billing):
 *
 *  • [nearby]      — Overpass API: named POIs (amenity/shop/tourism/leisure)
 *                    within ~1.2 km of a coordinate, sorted by real distance.
 *  • [search]      — Nominatim forward search biased to a bounding box around
 *                    the user, for the top-bar "search places" mode.
 *
 * Both endpoints are public OSM community infrastructure. Fair-use compliance
 * (1 req/s + identifying User-Agent) is enforced through [OsmServiceThrottle].
 */
object NearbyPlacesClient {

    private const val TAG = "NearbyPlacesClient"
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val NOMINATIM_BASE = "https://nominatim.openstreetmap.org"
    private const val USER_AGENT = "TriggerApp/1.0 (Android; contact: info@triggerapp.com)"

    /**
     * Fetches named POIs around a coordinate. Returns an empty list when the
     * network/service fails — the screen renders its own error state.
     */
    suspend fun nearby(latitude: Double, longitude: Double): List<PlaceLocationItem> =
        withContext(Dispatchers.IO) {
            try {
                // Overpass QL: named nodes with a POI class tag within 1200 m.
                val query = """
                    [out:json][timeout:20];
                    (
                      node(around:1200,$latitude,$longitude)[amenity][name];
                      node(around:1200,$latitude,$longitude)[shop][name];
                      node(around:1200,$latitude,$longitude)[tourism][name];
                      node(around:1200,$latitude,$longitude)[leisure][name];
                    );
                    out center 40;
                """.trimIndent()

                OsmServiceThrottle.awaitTurn()
                val body = "data=${java.net.URLEncoder.encode(query, "UTF-8")}"
                val request = okhttp3.Request.Builder()
                    .url(OVERPASS_URL)
                    .header("User-Agent", USER_AGENT)
                    .post(
                        body.toRequestBody("application/x-www-form-urlencoded".toMediaType())
                    )
                    .build()

                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .readTimeout(java.time.Duration.ofSeconds(25))
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "Overpass returned ${response.code}")
                            return@use emptyList()
                        }
                        val payload = response.body?.string() ?: return@use emptyList()
                        parseOverpass(payload, latitude, longitude)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Overpass nearby failed: ${e.message}")
                emptyList()
            }
        }

    private fun parseOverpass(payload: String, lat: Double, lng: Double): List<PlaceLocationItem> {
        val root = JSONObject(payload)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val results = ArrayList<PlaceLocationItem>(elements.length())
        val seen = HashSet<String>()
        val dist = FloatArray(1)

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val elLat = el.optDouble("lat", Double.NaN)
            val elLon = el.optDouble("lon", Double.NaN)
            if (elLat.isNaN() || elLon.isNaN()) continue
            val tags = el.optJSONObject("tags") ?: continue
            val name = tags.optString("name", "").ifBlank { continue }

            // Deduplicate chains that carry the same brand name (e.g. ATMs).
            if (!seen.add("$name@${"%.4f".format(elLat)},${"%.4f".format(elLon)}")) continue

            val category = listOf("amenity", "shop", "tourism", "leisure")
                .firstNotNullOfOrNull { tags.optString(it, "").ifBlank { null } }
            val street = listOfNotNull(
                tags.optString("addr:housenumber", "").ifBlank { null },
                tags.optString("addr:street", "").ifBlank { null }
            ).joinToString(" ")
            val locality = tags.optString("addr:suburb", "").ifBlank {
                tags.optString("addr:city", "").ifBlank {
                    tags.optString("addr:village", "").ifBlank { tags.optString("addr:town", "") }
                }
            }
            val address = listOf(street.ifBlank { category?.replaceFirstChar { it.uppercase() } ?: "Place" }, locality)
                .filter { it.isNotBlank() }
                .joinToString(", ")

            Location.distanceBetween(lat, lng, elLat, elLon, dist)
            results.add(
                PlaceLocationItem(
                    id = "osm-${el.optString("type", "node")}-${el.optLong("id", i.toLong())}",
                    name = name,
                    address = address,
                    latitude = elLat,
                    longitude = elLon,
                    distanceMeters = dist[0].toInt()
                )
            )
        }
        return results.sortedBy { it.distanceMeters }.take(12)
    }

    /**
     * Nominatim keyword search constrained (not bounded) to a generous box
     * around the user — mirrors WhatsApp's "search places near me".
     */
    suspend fun search(query: String, latitude: Double, longitude: Double): List<PlaceLocationItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            OsmServiceThrottle.awaitTurn()
            try {
                // ~0.35° box (≈35 km) centred on the user, weighted by
                // viewbox bias rather than hard bounding.
                val west = longitude - 0.35
                val east = longitude + 0.35
                val south = latitude - 0.35
                val north = latitude + 0.35
                val url = "$NOMINATIM_BASE/search" +
                    "?q=${java.net.URLEncoder.encode(q, "UTF-8")}" +
                    "&format=jsonv2&limit=10&addressdetails=1" +
                    "&viewbox=$west,$north,$east,$south&bounded=0"
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
                        if (!response.isSuccessful) return@use emptyList()
                        val body = response.body?.string() ?: return@use emptyList()
                        val arr = org.json.JSONArray(body)
                        val results = ArrayList<PlaceLocationItem>(arr.length())
                        val dist = FloatArray(1)
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val pLat = obj.optString("lat").toDoubleOrNull() ?: continue
                            val pLon = obj.optString("lon").toDoubleOrNull() ?: continue
                            val displayName = obj.optString("display_name", "").ifBlank { continue }
                            val name = displayName.substringBefore(",").trim()
                            val address = displayName.substringAfter(',').trim()
                            Location.distanceBetween(latitude, longitude, pLat, pLon, dist)
                            results.add(
                                PlaceLocationItem(
                                    id = "nom-${obj.optString("osm_type", "n")}-${obj.optString("osm_id", i.toString())}",
                                    name = name,
                                    address = address.ifBlank { displayName },
                                    latitude = pLat,
                                    longitude = pLon,
                                    distanceMeters = dist[0].toInt()
                                )
                            )
                        }
                        results
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Nominatim search failed: ${e.message}")
                emptyList()
            }
        }
}
