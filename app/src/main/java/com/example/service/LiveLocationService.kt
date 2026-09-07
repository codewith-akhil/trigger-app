package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.di.AppServiceContainer
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * LiveLocationShareState — what the UI needs to know about an active share.
 */
data class LiveLocationShareState(
    val conversationId: String,
    val sharerId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val expiresAtMillis: Long,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

/**
 * LiveLocationService (C7) — makes live location REAL.
 *
 * Previously "live location" sent ONE static message labelled "Live •
 * updating" and nothing ever moved. Now a proper foreground service:
 *  1. takes GPS/NETWORK fixes every ~10 s (framework LocationManager — no
 *     Google Play dependency, matching the osmdroid map);
 *  2. upserts them into `public.live_location_shares` (RLS: participants
 *     only) so the PEER receives the coordinates via Supabase Realtime;
 *  3. shows a countdown notification with a Stop action;
 *  4. stops itself when the chosen duration (15 min / 1 h / 8 h) expires.
 *
 * The sharer's own state is exposed via [activeShare] so the chat screen can
 * render a "Sharing live location — Stop" banner.
 */
class LiveLocationService : Service() {

    companion object {
        private const val TAG = "LiveLocationService"
        private const val CHANNEL_ID = "live_location_channel"
        private const val NOTIFICATION_ID = 4711
        const val ACTION_STOP = "com.example.action.STOP_LIVE_LOCATION"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_DURATION_MIN = "duration_min"

        /** Sharer-side state observed by the chat UI (single active share). */
        @Volatile
        var activeShare: LiveLocationShareState? = null
            private set

        /** Aggregates the PEER's latest coordinates per conversation id. */
        val peerShares = ConcurrentHashMap<String, LiveLocationShareState>()

        fun start(context: Context, conversationId: String, durationMin: Int) {
            val intent = Intent(context, LiveLocationService::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_DURATION_MIN, durationMin)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveLocationService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    private var conversationId: String = ""
    private var sharerId: String = ""
    private var expiresAtMillis: Long = 0L
    private var lastFix: Location? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Explicit object (NOT a SAM lambda): on API < 31 the platform interface
    // has abstract onStatusChanged/onProviderEnabled/onProviderDisabled — a
    // SAM lambda compiled against SDK 36 would crash with
    // AbstractMethodError on those devices.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFix = location
            publishFix(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    private val expiryRunnable = Runnable {
        Log.i(TAG, "Live location share expired — stopping")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID) ?: ""
        val durationMin = intent?.getIntExtra(EXTRA_DURATION_MIN, 60) ?: 60
        sharerId = AppServiceContainer.supabaseClient.currentSession?.user?.id ?: ""
        expiresAtMillis = System.currentTimeMillis() + durationMin * 60_000L

        startInForeground(durationMin)

        if (conversationId.isBlank() || sharerId.isBlank()) {
            Log.e(TAG, "Missing conversation/sharer id — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        requestFixes()
        // A second start while a share is live must REPLACE the old expiry —
        // both runnables otherwise fire and the longer share dies early.
        mainHandler.removeCallbacks(expiryRunnable)
        mainHandler.postDelayed(expiryRunnable, durationMin * 60_000L)

        // Seed the sharer-side UI state from the anchor message coordinates.
        activeShare = LiveLocationShareState(
            conversationId = conversationId,
            sharerId = sharerId,
            latitude = 0.0,
            longitude = 0.0,
            expiresAtMillis = expiresAtMillis
        )

        return START_NOT_STICKY
    }

    private fun requestFixes() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 10_000L, 5f, locationListener, Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 15_000L, 10f, locationListener, Looper.getMainLooper()
                )
            }
            // Seed immediately with the last known fix so the first update
            // doesn't wait up to 10 s.
            val seed = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (seed != null) {
                lastFix = seed
                publishFix(seed)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
            stopSelf()
        } catch (e: Exception) {
            Log.w(TAG, "requestLocationUpdates failed: ${e.message}")
        }
    }

    private fun publishFix(location: Location) {
        activeShare = (activeShare ?: LiveLocationShareState(
            conversationId = conversationId, sharerId = sharerId,
            latitude = location.latitude, longitude = location.longitude,
            expiresAtMillis = expiresAtMillis
        )).copy(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            updatedAtMillis = System.currentTimeMillis()
        )
        updateNotification()

        serviceScope.launch {
            try {
                val payload = JSONObject()
                    .put("conversation_id", conversationId)
                    .put("sharer_id", sharerId)
                    .put("latitude", location.latitude)
                    .put("longitude", location.longitude)
                    .put("accuracy", location.accuracy.toDouble())
                    .put(
                        "expires_at",
                        java.time.Instant.ofEpochMilli(expiresAtMillis).toString()
                    )
                // Upsert on (conversation_id, sharer_id) — one row per sharer.
                val result = AppServiceContainer.supabaseClient.upsertRecord(
                    "live_location_shares", payload,
                    onConflict = "conversation_id,sharer_id"
                )
                if (result is SupabaseResult.Error) {
                    Log.w(TAG, "live location upsert failed: ${result.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "live location publish failed: ${e.message}")
            }
        }
    }

    private fun startInForeground(durationMin: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live location", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Sharing live location")
                .setContentText("Updates for the next $durationMin min — tap Stop to end")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setContentTitle("Sharing live location")
                .setContentText("Updates for the next $durationMin min")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val share = activeShare ?: return
        val minsLeft = ((share.expiresAtMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Sharing live location")
                .setContentText("${"%.4f".format(share.latitude)}, ${"%.4f".format(share.longitude)} — $minsLeft min left")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
                .build()
        } else return
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(expiryRunnable)
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        activeShare = null
        serviceScope.cancel()
        Log.i(TAG, "Live location service destroyed")
        super.onDestroy()
    }
}
