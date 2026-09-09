package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Ongoing-call foreground service (microphone|camera types).
 *
 * Android 12+/14+ cut off microphone and camera access for apps that are
 * backgrounded WITHOUT a foreground service — an active Agora call would go
 * silent the moment the user switched apps or locked the screen. This service
 * runs for the lifetime of an active call (AgoraCallService starts/stops it
 * with the call session) so the RTC engine keeps full audio/video access.
 *
 * The service itself holds NO call logic — it is purely an Android process
 * lifeline. Starting it from the background is legal in the accept-from-
 * notification path because that path launches MainActivity first (the
 * activity is visible when AgoraCallService.acceptIncomingCall runs).
 */
class OngoingCallService : Service() {

    companion object {
        const val CHANNEL_ID = "trigger_ongoing_call"
        const val NOTIFICATION_ID = 2002

        fun start(context: Context) {
            val intent = Intent(context, OngoingCallService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OngoingCallService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Ongoing calls", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shown while an audio/video call is active"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle("Trigger call in progress")
            .setContentText("Tap to return to the call")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

        // Typed foreground start: microphone|camera — required on API 29+ for
        // the types to be honoured (and on 14+ for the FGS to start at all).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
