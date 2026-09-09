package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.ui.navigation.TriggerAppNavHost
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TriggerDarkBackground
import com.example.ui.theme.TriggerHeaderGreen

class MainActivity : androidx.fragment.app.FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.di.AppServiceContainer.initialize(this)

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
        com.example.service.TriggerFirebaseMessagingService.createNotificationChannels(this)
        com.example.service.TriggerFirebaseMessagingService.registerToken(this)

        // Enable edge-to-edge so content draws behind the system bars.
        enableEdgeToEdge()

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
}
