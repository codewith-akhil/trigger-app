package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

object StreamNotificationHelper {
    private const val CHANNEL_ID_STREAMS = "trigger_stream_notifications"
    private const val CHANNEL_NAME_STREAMS = "Trigger Live Streams & Bookings"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID_STREAMS,
                CHANNEL_NAME_STREAMS,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for scheduled streams, ticket bookings, and live broadcast alerts"
                enableLights(true)
                lightColor = Color.parseColor("#008069")
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun sendStreamScheduledPush(
        context: Context,
        streamTitle: String,
        dateTimeStr: String,
        slotsInfo: String,
        isPaid: Boolean,
        amountStr: String
    ) {
        ensureChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "stream")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pricingBadge = if (isPaid) "[$amountStr Paid Stream]" else "[Free Stream]"
        val contentText = "$pricingBadge Scheduled for $dateTimeStr ($slotsInfo slots)"

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_STREAMS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("📡 Stream Scheduled: $streamTitle")
            .setContentText(contentText)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Your live stream \"$streamTitle\" has been successfully scheduled on Trigger App!\n\n" +
                            "📅 Date & Time: $dateTimeStr\n" +
                            "👥 Capacity: $slotsInfo\n" +
                            "💰 Access: $pricingBadge\n\n" +
                            "Confirmation and calendar invites have been dispatched to your registered email."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setColor(Color.parseColor("#008069"))
            .build()

        notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
    }

    fun sendBookingConfirmedPush(
        context: Context,
        streamTitle: String,
        hostName: String,
        dateTimeStr: String,
        isHostNotification: Boolean,
        attendeeName: String = ""
    ) {
        ensureChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "stream")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isHostNotification) {
            "🎟️ New Attendee Booked: $streamTitle"
        } else {
            "🎉 Slot Confirmed: $streamTitle"
        }

        val message = if (isHostNotification) {
            "$attendeeName just booked a slot for your stream on $dateTimeStr! Notifications sent."
        } else {
            "You are registered for $streamTitle hosted by $hostName on $dateTimeStr. See you live!"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_STREAMS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setColor(Color.parseColor("#008069"))
            .build()

        notificationManager.notify((System.currentTimeMillis() % 100000).toInt() + 1, notification)
    }
}
