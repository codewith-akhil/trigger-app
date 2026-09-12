package com.example.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide stale-while-revalidate caches for reference/profile data that
 * pages were previously re-fetching from the backend on EVERY open (profile,
 * peer profile, genders, countries). Screens now render the cached values
 * instantly and refresh in the background; the network round-trip no longer
 * gates the first frame.
 *
 * TTLs are generous because the underlying data changes rarely (countries /
 * genders) or is re-validated cheaply on write paths (profile edits, follows).
 */
object RefDataCache {

    private class Entry(val json: String, val fetchedAt: Long)

    @Volatile private var genders: Entry? = null
    @Volatile private var countries: Entry? = null
    @Volatile private var myProfile: Entry? = null
    @Volatile private var myProfileRefreshedAt: Long = 0L
    private val peerProfiles = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    @Volatile private var peerCacheClearedAt: Long = 0L

    private const val REF_DATA_TTL_MS = 24 * 60 * 60 * 1000L   // countries/genders: a day
    private const val MY_PROFILE_TTL_MS = 5 * 60 * 1000L       // own profile: 5 min
    private const val PEER_PROFILE_TTL_MS = 60 * 1000L         // peer profile: 1 min

    // ---- genders ----
    fun getCachedGenders(): List<String>? =
        genders?.takeIf { System.currentTimeMillis() - it.fetchedAt < REF_DATA_TTL_MS }
            ?.let { runCatching { JSONArray(it.json) }.getOrNull() }
            ?.let { arr -> List(arr.length()) { arr.getJSONObject(it).getString("name") } }

    fun putGenders(list: List<String>) {
        genders = Entry(JSONArray(list).toString(), System.currentTimeMillis())
    }

    // ---- countries ----
    fun getCachedCountries(): List<Pair<String, String>>? =
        countries?.takeIf { System.currentTimeMillis() - it.fetchedAt < REF_DATA_TTL_MS }
            ?.let { runCatching { JSONArray(it.json) }.getOrNull() }
            ?.let { arr ->
                List(arr.length()) {
                    val o = arr.getJSONObject(it)
                    Pair(o.getString("currency_code"), o.getString("country_name"))
                }
            }

    fun putCountries(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { (code, name) ->
            arr.put(JSONObject().put("currency_code", code).put("country_name", name))
        }
        countries = Entry(arr.toString(), System.currentTimeMillis())
    }

    // ---- my profile (ProfileScreen hydration) ----
    fun getMyProfileAgeMs(): Long? =
        myProfileRefreshedAt.takeIf { it > 0L }?.let { System.currentTimeMillis() - it }

    fun isMyProfileFresh(): Boolean = (getMyProfileAgeMs() ?: Long.MAX_VALUE) < MY_PROFILE_TTL_MS

    fun markMyProfileRefreshed() {
        myProfileRefreshedAt = System.currentTimeMillis()
    }

    // ---- peer profile (UserProfileScreen) ----
    data class PeerSnapshot(
        val about: String?, val avatarUrl: String?, val username: String?,
        val fullName: String?, val isFollowing: Boolean,
        val followersCount: Int, val followingCount: Int,
    )

    fun getCachedPeer(peerId: String): PeerSnapshot? =
        peerProfiles[peerId]?.takeIf { System.currentTimeMillis() - it.fetchedAt < PEER_PROFILE_TTL_MS }
            ?.let { runCatching { JSONObject(it.json) }.getOrNull() }?.toSnapshot()

    fun putPeer(peerId: String, snapshot: PeerSnapshot) {
        peerProfiles[peerId] = Entry(snapshot.toJson().toString(), System.currentTimeMillis())
    }

    /** Account switch / logout must never leak the previous account's view. */
    fun reset() {
        genders = null
        countries = null
        myProfile = null
        myProfileRefreshedAt = 0L
        peerCacheClearedAt = System.currentTimeMillis()
        peerProfiles.clear()
    }

    // ---- JSON (de)serialization ----
    private fun PeerSnapshot.toJson(): JSONObject = JSONObject()
        .put("about", about ?: "")
        .put("avatar_url", avatarUrl ?: "")
        .put("username", username ?: "")
        .put("full_name", fullName ?: "")
        .put("isFollowing", isFollowing)
        .put("followersCount", followersCount)
        .put("followingCount", followingCount)

    private fun JSONObject.toSnapshot(): PeerSnapshot = PeerSnapshot(
        about = optString("about", "").ifBlank { null },
        avatarUrl = optString("avatar_url", "").ifBlank { null },
        username = optString("username", "").ifBlank { null },
        fullName = optString("full_name", "").ifBlank { null },
        isFollowing = optBoolean("isFollowing", false),
        followersCount = optInt("followersCount", 0),
        followingCount = optInt("followingCount", 0),
    )
}
