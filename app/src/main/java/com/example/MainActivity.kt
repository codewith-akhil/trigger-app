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
import com.example.ui.navigation.TriggerAppNavHost
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TriggerDarkBackground
import com.example.ui.theme.TriggerHeaderGreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.di.AppServiceContainer.initialize(this)

        // Create FCM notification channels + auto-register the FCM token with
        // the Supabase register-push-token edge function on every app launch.
        // The token refresh is also handled by TriggerFirebaseMessagingService.onNewToken.
        com.example.service.TriggerFirebaseMessagingService.createNotificationChannels(this)
        com.example.service.TriggerFirebaseMessagingService.registerToken(this)

        // Enable edge-to-edge so content draws behind the system bars.
        enableEdgeToEdge()

        // Set the system status bar background to the app's signature green
        // (#008069) so every page has the green strip at the top — unique to
        // Trigger App. The navigation bar stays transparent/default.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Use the compat controller to set status bar color on all API levels.
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false // white status bar icons on green bg
        // Set the status bar color directly via the window (works on API 21+).
        window.statusBarColor = android.graphics.Color.parseColor("#008069")
        // Navigation bar gets a subtle dark green too for consistency.
        window.navigationBarColor = android.graphics.Color.parseColor("#008069")

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
