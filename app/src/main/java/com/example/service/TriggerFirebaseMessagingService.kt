package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.launch
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * TriggerFirebaseMessagingService
 *
 * Receives push notifications from Firebase Cloud Messaging (FCM) and posts
 * system notifications. Also handles FCM token refresh — when the token
 * changes, it auto-registers the new token with the Supabase
 * register-push-token edge function.
 *
 * Notification channels:
 *   - trigger_chat_messages     — incoming chat messages
 *   - trigger_calls             — incoming call alerts
 *   - trigger_stream_notifications — stream-scheduled / stream-live alerts
 *   - trigger_wallet            — wallet withdrawal / credit alerts
 *   - trigger_default           — fallback for all other notifications
 */
class TriggerFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "TriggerFCM"

        // Notification channel IDs (match the androidChannelId values sent by the
        // send-push-notification + send-chat-notification edge functions).
        const val CHANNEL_CHAT = "trigger_chat_messages"
        const val CHANNEL_CALLS = "trigger_calls"
        const val CHANNEL_STREAMS = "trigger_stream_notifications"
        const val CHANNEL_WALLET = "trigger_wallet"
        const val CHANNEL_DEFAULT = "trigger_default"

        const val NOTIF_ID_BASE = 3000

        /**
         * Create all notification channels. Call this from Application.onCreate
         * or MainActivity.onCreate (idempotent — safe to call multiple times).
         */
        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channels = listOf(
                NotificationChannel(
                    CHANNEL_CHAT,
                    "Chat Messages",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for incoming chat messages"
                    enableLights(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 200, 100, 200)
                    setShowBadge(true)
                },
                NotificationChannel(
                    CHANNEL_CALLS,
                    "Calls",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Incoming call notifications"
                    enableLights(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                    setShowBadge(true)
                },
                NotificationChannel(
                    CHANNEL_STREAMS,
                    "Live Streams & Bookings",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Stream scheduled, stream live, and booking confirmation alerts"
                    enableLights(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 150, 250)
                    setShowBadge(true)
                },
                NotificationChannel(
                    CHANNEL_WALLET,
                    "Wallet",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Wallet withdrawal and credit notifications"
                    setShowBadge(true)
                },
                NotificationChannel(
                    CHANNEL_DEFAULT,
                    "General",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "General app notifications"
                    setShowBadge(true)
                }
            )

            for (ch in channels) {
                nm.createNotificationChannel(ch)
            }
        }

        /**
         * Register (or refresh) the FCM token with the Supabase
         * register-push-token edge function. Call this on app launch + on
         * token refresh.
         */
        fun registerToken(context: Context) {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    // POST the token to the register-push-token edge function
                    // in a background coroutine.
                    val scope = kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
                    )
                    scope.launch {
                        try {
                            val deviceId = android.provider.Settings.Secure.getString(
                                context.contentResolver,
                                android.provider.Settings.Secure.ANDROID_ID
                            ) ?: "unknown"
                            val payload = org.json.JSONObject().apply {
                                put("fcmToken", token)
                                put("deviceId", deviceId)
                                put("platform", "android")
                                put("appVersion", context.packageManager
                                    .getPackageInfo(context.packageName, 0)?.versionName ?: "1.0.0")
                            }
                            com.example.di.AppServiceContainer.supabaseClient
                                .invokeFunction("register-push-token", payload)
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "FCM token registration failed: ${e.message}")
                        }
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.w(TAG, "FCM token retrieval failed: ${e.message}")
                }
        }
    }

    /**
     * Called when a push notification is received (foreground + background).
     * The edge functions send a `notification` payload (title + body) so the
     * system notification is posted automatically in background. In foreground,
     * we post it manually so the user sees it immediately.
     *
     * Special handling for `type=chat_message`:
     *   - Title = sender name (from `senderName` data field)
     *   - Body  = message preview (from `messagePreview` data field)
     *   - Tapping the notification deep-links into the chat conversation
     *     (passed as `notif_conversationId`, `notif_contactName`).
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Ensure channels exist (safe to call repeatedly).
        createNotificationChannels(this)

        val type = remoteMessage.data["type"] ?: "default"

        // REAL incoming-call signaling (send-call-invite edge function →
        // data-only FCM). Fires even when the app is BACKGROUND/KILLED.
        if (type == "incoming_call") {
            handleIncomingCallPush(remoteMessage.data)
            return
        }
        if (type == "call_cancelled") {
            handleCallCancelledPush(remoteMessage.data)
            return
        }

        val isChatMessage = type.contains("chat") || type.contains("message")

        // For chat messages, prefer the structured payload sent by
        // send-chat-notification edge function. Otherwise fall back to the
        // generic title/body fields.
        val title = if (isChatMessage) {
            remoteMessage.data["senderName"] ?: remoteMessage.notification?.title
            ?: remoteMessage.data["title"] ?: "Trigger App"
        } else {
            remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "Trigger App"
        }
        val body = if (isChatMessage) {
            remoteMessage.data["messagePreview"] ?: remoteMessage.notification?.body
            ?: remoteMessage.data["body"] ?: ""
        } else {
            remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""
        }

        val channelId = when {
            isChatMessage -> CHANNEL_CHAT
            type.contains("call") -> CHANNEL_CALLS
            type.contains("stream") -> CHANNEL_STREAMS
            type.contains("wallet") -> CHANNEL_WALLET
            else -> CHANNEL_DEFAULT
        }

        // Unique notification ID (use a hash of the title+body so duplicates don't overwrite).
        val notifId = NOTIF_ID_BASE + (title + body).hashCode().and(0xFFF)

        postNotification(this, channelId, notifId, title, body, remoteMessage.data, isChatMessage)
    }

    /**
     * Real incoming-call ring: post the full-screen/heads-up call notification
     * with Accept + Decline actions. The in-app call overlay is driven by the
     * AgoraCallService monitor (which also hydrates the session from
     * call_sessions); this notification is what wakes a BACKGROUND/KILLED app.
     */
    private fun handleIncomingCallPush(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val callerName = data["callerName"] ?: "Incoming call"
        val isVideo = data["callType"] == "video"
        try {
            com.example.service.IncomingCallNotificationHelper(this).apply {
                showIncomingCallNotification(callId, callerName, isVideo)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to post incoming-call notification: ${e.message}")
        }
    }

    /**
     * The caller gave up / declined / timed out before we answered — remove
     * the ring notification and any ringing session for that call.
     */
    private fun handleCallCancelledPush(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        try {
            com.example.service.IncomingCallNotificationHelper(this).cancelCallNotification()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to cancel call notification: ${e.message}")
        }
        try {
            val callService = com.example.di.AppServiceContainer.callService
            val current = callService.currentCall.value
            if (current?.callId == callId && current.isIncoming &&
                current.state == com.example.model.CallState.RINGING
            ) {
                callService.dismissIncomingRing()
            }
        } catch (e: Exception) {
            // App not initialized (cold start) — nothing to dismiss.
        }
    }

    /**
     * Called when the FCM token is refreshed (app reinstall, app data clear,
     * token rotation). Auto-registers the new token with the backend.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        android.util.Log.d(TAG, "FCM token refreshed, registering with backend...")
        registerToken(this)
    }

    /**
     * Post a system notification. Opens MainActivity on tap.
     *
     * For chat_message notifications, the conversationId + contactName are
     * passed as Intent extras (notif_conversationId, notif_contactName,
     * notif_avatarRes) so MainActivity can deep-link straight into the chat.
     */
    private fun postNotification(
        context: Context,
        channelId: String,
        notifId: Int,
        title: String,
        body: String,
        data: Map<String, String>,
        isChatMessage: Boolean = false
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (isChatMessage) {
                // Mark this as a chat deep-link so MainActivity can route it
                putExtra("deep_link_type", "chat_message")
                putExtra("notif_conversationId", data["conversationId"] ?: "")
                putExtra("notif_contactName", data["senderName"] ?: title)
                putExtra("notif_messageType", data["messageType"] ?: "TEXT")
            }
            // Pass the full data payload too (for other deep-link types)
            for ((k, v) in data) {
                if (k != "conversationId" && k != "senderName" && k != "messageType") {
                    putExtra("notif_$k", v)
                }
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(notifId, builder.build())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to post notification: ${e.message}")
        }
    }
}
