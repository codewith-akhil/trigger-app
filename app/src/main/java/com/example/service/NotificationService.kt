package com.example.service

import android.content.Context
import android.util.Log

interface NotificationService {
    fun showMessageNotification(sender: String, messageSnippet: String, conversationId: String)
    fun showIncomingCallNotification(callerName: String, isVideo: Boolean)
    fun cancelCallNotification()
}

class NotificationServiceImpl(private val context: Context) : NotificationService {
    override fun showMessageNotification(sender: String, messageSnippet: String, conversationId: String) {
        // Production hook for system notification channels & push notifications
        Log.d("NotificationService", "Message from $sender in $conversationId: $messageSnippet")
    }

    override fun showIncomingCallNotification(callerName: String, isVideo: Boolean) {
        Log.d("NotificationService", "Incoming ${if (isVideo) "Video" else "Audio"} call from $callerName")
    }

    override fun cancelCallNotification() {
        Log.d("NotificationService", "Cancel incoming call notification")
    }
}
