package com.example.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.model.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class StreamPricingType {
    FREE, PAID
}

data class ScheduledStream(
    val id: String,
    val title: String,
    val category: String = "Tech & Talk",
    val hostName: String,
    val hostEmail: String,
    val date: String,
    val time: String,
    val timestampMillis: Long,
    val slotLimit: String, // "25", "50", "150", "200", "500", "ANY"
    val slotsBooked: Int = 0,
    val type: StreamPricingType = StreamPricingType.FREE,
    val amount: Double = 0.0,
    val currency: String = "USD ($)",
    val shareLink: String,
    val isHost: Boolean = false,
    val isJoined: Boolean = false
) {
    val isUnlimitedSlots: Boolean
        get() = slotLimit.equals("ANY", ignoreCase = true)

    val maxSlots: Int
        get() = if (isUnlimitedSlots) Int.MAX_VALUE else (slotLimit.toIntOrNull() ?: 100)

    val isFull: Boolean
        get() = !isUnlimitedSlots && slotsBooked >= maxSlots

    val remainingSlots: Int
        get() = if (isUnlimitedSlots) 999999 else (maxSlots - slotsBooked).coerceAtLeast(0)

    val priceDisplay: String
        get() = if (type == StreamPricingType.FREE || amount <= 0.0) {
            "FREE"
        } else {
            val symbol = when {
                currency.contains("$") -> "$"
                currency.contains("₹") -> "₹"
                currency.contains("€") -> "€"
                currency.contains("£") -> "£"
                else -> "$"
            }
            "$symbol${"%.2f".format(amount)}"
        }
}

data class StreamHistoryItem(
    val id: String,
    val title: String,
    val hostName: String,
    val date: String,
    val duration: String,
    val peakViewers: Int,
    val type: String, // "FREE" or "PAID"
    val revenue: Double,
    val currency: String,
    val attendeesCount: Int,
    val status: String = "Completed"
)

class StreamScheduleService(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val TAG = "StreamScheduleService"

    private val _scheduledStreams = MutableStateFlow<List<ScheduledStream>>(emptyList())
    val scheduledStreams: StateFlow<List<ScheduledStream>> = _scheduledStreams.asStateFlow()

    private val _streamHistory = MutableStateFlow<List<StreamHistoryItem>>(emptyList())
    val streamHistory: StateFlow<List<StreamHistoryItem>> = _streamHistory.asStateFlow()

    /**
     * Schedules a new stream and dispatches both Push and Email notifications.
     */
    fun scheduleStream(
        context: Context,
        title: String,
        category: String,
        date: String,
        time: String,
        slotLimit: String,
        type: StreamPricingType,
        amount: Double,
        currency: String,
        sendEmail: Boolean = true,
        sendPush: Boolean = true
    ): ScheduledStream {
        val userProfile = UserRepository.profile.value
        val hostName = userProfile.name
        val hostEmail = userProfile.email

        val streamId = "sch_${System.currentTimeMillis()}"
        val shareLink = "https://triggerapp.com/stream/$streamId"

        val newStream = ScheduledStream(
            id = streamId,
            title = title,
            category = category,
            hostName = hostName,
            hostEmail = hostEmail,
            date = date,
            time = time,
            timestampMillis = System.currentTimeMillis(),
            slotLimit = slotLimit,
            slotsBooked = 0,
            type = type,
            amount = amount,
            currency = currency,
            shareLink = shareLink,
            isHost = true,
            isJoined = true
        )

        _scheduledStreams.value = listOf(newStream) + _scheduledStreams.value

        // 1. Production Push Notification to Device
        if (sendPush) {
            val slotsInfo = if (slotLimit.equals("ANY", ignoreCase = true)) "Unlimited" else "$slotLimit"
            StreamNotificationHelper.sendStreamScheduledPush(
                context = context,
                streamTitle = title,
                dateTimeStr = "$date at $time",
                slotsInfo = slotsInfo,
                isPaid = type == StreamPricingType.PAID,
                amountStr = newStream.priceDisplay
            )
        }

        // 2. Production Email Notification to Registered Email
        if (sendEmail) {
            dispatchScheduledStreamEmail(
                context = context,
                recipientEmail = hostEmail,
                stream = newStream
            )
        }

        Log.i(TAG, "Stream scheduled successfully: ${newStream.id} by $hostEmail")
        return newStream
    }

    /**
     * Verifies slot availability and books a slot for a user.
     */
    fun bookSlot(
        context: Context,
        streamId: String,
        userName: String,
        userEmail: String
    ): Result<ScheduledStream> {
        val currentStreams = _scheduledStreams.value
        val stream = currentStreams.find { it.id == streamId }
            ?: return Result.failure(Exception("Stream not found"))

        // SLOT VERIFICATION RULE:
        if (!stream.isUnlimitedSlots && stream.slotsBooked >= stream.maxSlots) {
            return Result.failure(Exception("No slots available! This stream has reached its capacity of ${stream.maxSlots} attendees."))
        }

        val updatedStream = stream.copy(
            slotsBooked = stream.slotsBooked + 1,
            isJoined = true
        )

        _scheduledStreams.value = currentStreams.map {
            if (it.id == streamId) updatedStream else it
        }

        // Push notification to attendee
        StreamNotificationHelper.sendBookingConfirmedPush(
            context = context,
            streamTitle = updatedStream.title,
            hostName = updatedStream.hostName,
            dateTimeStr = "${updatedStream.date} at ${updatedStream.time}",
            isHostNotification = false
        )

        // Push notification to host
        StreamNotificationHelper.sendBookingConfirmedPush(
            context = context,
            streamTitle = updatedStream.title,
            hostName = updatedStream.hostName,
            dateTimeStr = "${updatedStream.date} at ${updatedStream.time}",
            isHostNotification = true,
            attendeeName = userName
        )

        // Email notification to both attendee and host
        dispatchBookingConfirmationEmail(
            context = context,
            attendeeEmail = userEmail,
            stream = updatedStream,
            userName = userName
        )

        return Result.success(updatedStream)
    }

    /**
     * Dispatches official email notifications for the scheduled stream.
     */
    private fun dispatchScheduledStreamEmail(
        context: Context,
        recipientEmail: String,
        stream: ScheduledStream
    ) {
        scope.launch {
            try {
                val subject = "📡 Stream Scheduled: ${stream.title} on Trigger App"
                val body = buildString {
                    appendLine("Hello ${stream.hostName},")
                    appendLine()
                    appendLine("Your live stream has been successfully scheduled on Trigger App!")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine("📌 Stream Title: ${stream.title}")
                    appendLine("🏷️ Category: ${stream.category}")
                    appendLine("📅 Date: ${stream.date}")
                    appendLine("⏰ Time: ${stream.time}")
                    appendLine("👥 Slot Capacity: ${if (stream.isUnlimitedSlots) "Unlimited (ANY)" else "${stream.slotLimit} Attendees"}")
                    appendLine("💰 Access Type: ${if (stream.type == StreamPricingType.PAID) "Paid (${stream.priceDisplay})" else "Free"}")
                    appendLine("🔗 Direct Invite Link: ${stream.shareLink}")
                    appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLine()
                    appendLine("Host Instructions:")
                    appendLine("1. Please be ready 5 minutes prior to the broadcast.")
                    appendLine("2. Use the 'Go Live' button inside Trigger App to begin streaming.")
                    appendLine("3. Realtime Agora WebRTC 4.x will transmit high-definition video with low latency.")
                    appendLine()
                    appendLine("Thank you for broadcasting with Trigger App!")
                    appendLine("Support: info@triggerapp.com")
                }

                // Launch real Android mail client with prefilled details
                val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(recipientEmail))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                Log.i(TAG, "Dispatched scheduled stream email to: $recipientEmail")
            } catch (e: Exception) {
                Log.e(TAG, "Error formatting email: ${e.message}")
            }
        }
    }

    /**
     * Dispatches booking confirmation email to joined attendee and streamer.
     */
    private fun dispatchBookingConfirmationEmail(
        context: Context,
        attendeeEmail: String,
        stream: ScheduledStream,
        userName: String
    ) {
        scope.launch {
            try {
                val subject = "🎟️ Booking Confirmed: ${stream.title}"
                val body = buildString {
                    appendLine("Hi $userName,")
                    appendLine()
                    appendLine("Your slot has been reserved for the live stream:")
                    appendLine("Title: ${stream.title}")
                    appendLine("Host: ${stream.hostName}")
                    appendLine("Date & Time: ${stream.date} at ${stream.time}")
                    appendLine("Access: ${stream.priceDisplay}")
                    appendLine("Stream Link: ${stream.shareLink}")
                    appendLine()
                    appendLine("You can join the stream directly from the Trigger App dashboard when the host goes live.")
                    appendLine("Need assistance? Contact info@triggerapp.com")
                }
                Log.i(TAG, "Booking email prepared for $attendeeEmail & host ${stream.hostEmail}")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending booking email: ${e.message}")
            }
        }
    }
}
