package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.ui.navigation.TriggerAppNavHost
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TriggerDarkBackground
import com.example.ui.theme.TriggerHeaderGreen

class MainActivity : androidx.fragment.app.FragmentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.di.AppServiceContainer.initialize(this)

        handleCallIntent(intent)
        StreamDeepLink.setFromIntent(intent)
        // Lock-screen call UX: a ringing call can present over the keyguard
        // (paired with USE_FULL_SCREEN_INTENT + setFullScreenIntent).
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // osmdroid configuration — MUST run before the first MapView is created.
        // A per-app User-Agent is REQUIRED by the OpenStreetMap tile usage policy,
        // and the tile cache lives in the app-private cacheDir (no storage permission).
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        }

        // Create FCM notification channels + auto-register the FCM token with
        // the Supabase register-push-token edge function on every app launch.
        try {
            com.example.service.TriggerFirebaseMessagingService.createNotificationChannels(this)
            com.example.service.TriggerFirebaseMessagingService.registerToken(this)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Push notification setup caught exception: ${t.message}")
        }

        // Enable edge-to-edge so content draws behind the system bars.
        enableEdgeToEdge()

        // Pre-seed sample photos to the Android device gallery so the system Photo Picker
        // is populated even on fresh emulators.
        lifecycleScope.launch(Dispatchers.IO) {
            com.example.util.SampleMediaSeeder.seedToDeviceGallery(this@MainActivity)
        }

        // Set the system status bar and navigation bar to transparent for clean edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // ProcessLifecycleOwner observer — detects real app foreground/background
        // transitions (not just login/logout). Calls presence onAppForeground/
        // onAppBackground so the heartbeat starts/stops correctly.
        // Registered ONCE per process: onCreate re-runs on every Activity
        // recreation and previously stacked duplicate observers (N duplicate
        // presence writes per transition).
        if (!com.example.di.AppServiceContainer.lifecycleObserverRegistered) {
            com.example.di.AppServiceContainer.lifecycleObserverRegistered = true
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // App came to foreground
                    (com.example.di.AppServiceContainer.presenceService as? com.example.service.PresenceServiceImpl)?.onAppForeground()
                }

                override fun onStop(owner: LifecycleOwner) {
                    // App went to background
                    (com.example.di.AppServiceContainer.presenceService as? com.example.service.PresenceServiceImpl)?.onAppBackground()
                }
            })
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = TriggerDarkBackground
                ) {
                    TriggerAppNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: notification Accept/Decline taps while the activity is
        // already running arrive here (previously NOTHING inspected intents —
        // the notification buttons were dead).
        setIntent(intent)
        handleCallIntent(intent)
        StreamDeepLink.setFromIntent(intent)
    }

    /**
     * Routes the incoming-call notification actions to the AgoraCallService.
     * Actions: ACTION_ACCEPT_CALL / ACTION_DECLINE_CALL + a "callId" extra;
     * the full-screen intent (no action) just opens the app — the global call
     * overlay renders itself because currentCall != null.
     */
    private fun handleCallIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val callId = intent.getStringExtra("callId") ?: return
        val callService = try {
            com.example.di.AppServiceContainer.callService
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Call service not ready for intent $action")
            return
        }
        when (action) {
            com.example.service.IncomingCallNotificationHelper.ACTION_ACCEPT_CALL -> {
                android.util.Log.i(TAG, "Notification ACCEPT for call $callId")
                callService.handleNotificationAccept(callId)
            }
            com.example.service.IncomingCallNotificationHelper.ACTION_DECLINE_CALL -> {
                android.util.Log.i(TAG, "Notification DECLINE for call $callId")
                callService.handleNotificationDecline(callId)
            }
        }
    }
}

/**
 * Holds a pending https://www.triggerappltd.cyou/stream/<id> App Link
 * (also accepts the apex host triggerappltd.cyou, which the website
 * redirects to www). MainActivity writes it on cold start (onCreate) and
 * warm delivery (onNewIntent); the dashboard reads it to preselect the
 * Stream tab and StreamTabContent consumes it once to fetch + open the
 * booking dialog. Survives until the dashboard is reachable (e.g. link
 * tapped before login).
 */
object StreamDeepLink {
    var pendingStreamId by mutableStateOf<String?>(null)
        private set

    /** Hosts whose /stream/<id> links we accept (canonical www + apex). */
    private val STREAM_LINK_HOSTS = setOf("www.triggerappltd.cyou", "triggerappltd.cyou")

    fun setFromIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "https" && data.host in STREAM_LINK_HOSTS) {
            val segments = data.pathSegments
            if (segments.size >= 2 && segments[0] == "stream" && segments[1].isNotBlank()) {
                pendingStreamId = segments[1]
                android.util.Log.i("StreamDeepLink", "Captured stream deep link id=${segments[1]}")
            }
        }
    }

    /** Reads and clears the pending id (consume-once). */
    fun consume(): String? {
        val id = pendingStreamId
        pendingStreamId = null
        return id
    }
}
