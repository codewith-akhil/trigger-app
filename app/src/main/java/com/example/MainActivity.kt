package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ui.navigation.TriggerAppNavHost
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TriggerDarkBackground

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.di.AppServiceContainer.initialize(this)

        // Create FCM notification channels + auto-register the FCM token with
        // the Supabase register-push-token edge function on every app launch.
        // The token refresh is also handled by TriggerFirebaseMessagingService.onNewToken.
        com.example.service.TriggerFirebaseMessagingService.createNotificationChannels(this)
        com.example.service.TriggerFirebaseMessagingService.registerToken(this)

        enableEdgeToEdge()
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
