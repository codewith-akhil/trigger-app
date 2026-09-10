package com.example.service

import android.content.Context
import android.util.Log
import com.example.di.AppServiceContainer
import com.example.model.UserRepository
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

    private val client get() = AppServiceContainer.supabaseClient

    /**
     * Schedules a new stream: updates the in-memory list, fires a local push
     * notification on this device, and inserts a row into the
     * `scheduled_streams` Supabase table so the `cron-auto-start-streams`
     * edge function can auto-promote it to live at the scheduled time.
     * (Email notification channel was removed — creation no longer emails
     * anyone. The returned ScheduledStream carries the real share link,
     * which the UI reveals only after creation.)
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
        sendPush: Boolean = true
    ): ScheduledStream {
        val userProfile = UserRepository.profile.value
        val hostName = userProfile.name
        val hostEmail = userProfile.email
        val hostId = userProfile.id

        val streamId = "sch_${System.currentTimeMillis()}"
        // Real, user-owned domain. Deep link: App Links intent-filter in the
        // manifest routes https://www.triggerappltd.cyou/stream/<id> into the
        // app, where the dashboard Stream tab fetches the stream and opens
        // booking. www is canonical (redirect-free 200; the apex host
        // 302-redirects here), so verification is most reliable on www.
        val shareLink = "https://www.triggerappltd.cyou/stream/$streamId"

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

        // 2. Insert into `scheduled_streams` table so cron-auto-start-streams
        // can promote it to a live_streams row at the scheduled time.
        scope.launch {
            val scheduledAtIso = buildScheduledAtIso(date, time)
            if (scheduledAtIso == null) {
                Log.e(TAG, "scheduleStream: unparsable date/time '$date $time' — stream NOT scheduled (a 'now' fallback previously let the cron start it immediately).")
                return@launch
            }
            val row = JSONObject()
                .put("id", streamId)
                .put("host_id", hostId)
                .put("host_email", hostEmail)
                .put("host_name", hostName)
                .put("title", title)
                .put("category", category)
                .put("scheduled_at", scheduledAtIso)
                .put("slot_limit", slotLimit)
                .put("pricing_type", if (type == StreamPricingType.PAID) "PAID" else "FREE")
                .put("amount", amount)
                .put("currency", currency)
                .put("share_link", shareLink)
            when (val res = client.upsertRecord("scheduled_streams", row, onConflict = "id")) {
                is SupabaseResult.Success -> Log.i(TAG, "scheduled_streams row inserted: $streamId")
                is SupabaseResult.Error -> Log.e(TAG, "scheduled_streams insert failed: ${res.message}")
            }
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

        // Email notification to both attendee and host via edge function.
        dispatchBookingConfirmationEmail(
            attendeeEmail = userEmail,
            attendeeName = userName,
            stream = updatedStream
        )

        return Result.success(updatedStream)
    }

    /**
     * Dispatches booking confirmation email via the
     * `send-booking-confirmation-email` edge function (was previously a
     * `Log.i` placeholder).
     */
    private fun dispatchBookingConfirmationEmail(
        attendeeEmail: String,
        attendeeName: String,
        stream: ScheduledStream
    ) {
        scope.launch {
            val payload = JSONObject()
                .put("attendeeEmail", attendeeEmail)
                .put("attendeeName", attendeeName)
                .put("hostEmail", stream.hostEmail)
                .put("hostName", stream.hostName)
                .put("streamTitle", stream.title)
                .put("scheduledDateTime", "${stream.date} at ${stream.time}")
                .put("pricingBadge", stream.priceDisplay)
                .put("shareLink", stream.shareLink)
            when (val res = client.invokeFunction("send-booking-confirmation-email", payload)) {
                is SupabaseResult.Success -> Log.i(TAG, "Booking email dispatched to $attendeeEmail & host ${stream.hostEmail}")
                is SupabaseResult.Error -> Log.e(TAG, "send-booking-confirmation-email failed: ${res.message}")
            }
        }
    }

    /**
     * Hydrates [_streamHistory] from the `live_streams` table (status=ended
     * rows for the current host). Called from StreamHistoryScreen's
     * LaunchedEffect.
     */
    suspend fun refreshStreamHistory() {
        val userId = UserRepository.profile.value.id
        if (userId.isBlank()) {
            // Fall back to no filter — server RLS will scope to caller anyway.
            return
        }
        val query = "select=*&status=eq.ended&host_id=eq.$userId&order=created_at.desc&limit=50"
        when (val res = client.getTable("live_streams", query)) {
            is SupabaseResult.Success -> {
                val list = ArrayList<StreamHistoryItem>(res.data.length())
                for (i in 0 until res.data.length()) {
                    val row = res.data.getJSONObject(i)
                    val type = if (row.optString("pricing_type", "FREE").equals("PAID", true)) "PAID" else "FREE"
                    val revenue = row.optDouble("revenue", row.optDouble("total_revenue", 0.0))
                    val attendeesCount = row.optInt("peak_viewers", row.optInt("attendees_count", 0))
                    val peakViewers = row.optInt("peak_viewers", attendeesCount)
                    list += StreamHistoryItem(
                        id = row.optString("id", "hist_${System.currentTimeMillis()}_$i"),
                        title = row.optString("title", "Untitled Stream"),
                        hostName = row.optString("host_name", row.optString("streamer_name", "")),
                        date = row.optString("started_at", row.optString("created_at", "")),
                        duration = formatDurationSeconds(row.optInt("duration_seconds", row.optInt("duration", 0))),
                        peakViewers = peakViewers,
                        type = type,
                        revenue = revenue,
                        currency = row.optString("currency", "USD (\$)"),
                        attendeesCount = attendeesCount,
                        status = row.optString("status", "ended").replaceFirstChar { it.uppercase() }
                    )
                }
                _streamHistory.value = list
            }
            is SupabaseResult.Error -> Log.w(TAG, "refreshStreamHistory failed: ${res.message}")
        }
    }

    /**
     * Hydrates [_scheduledStreams] from the `scheduled_streams` table for the
     * current host.
     */
    suspend fun refreshScheduledStreams() {
        val userId = UserRepository.profile.value.id
        if (userId.isBlank()) return
        val query = "select=*&host_id=eq.$userId&order=scheduled_at.desc&limit=50"
        when (val res = client.getTable("scheduled_streams", query)) {
            is SupabaseResult.Success -> {
                val list = ArrayList<ScheduledStream>(res.data.length())
                for (i in 0 until res.data.length()) {
                    val row = res.data.getJSONObject(i)
                    val slotLimit = row.optString("slot_limit", "50")
                    val pricingType = if (row.optString("pricing_type", "FREE").equals("PAID", true))
                        StreamPricingType.PAID else StreamPricingType.FREE
                    val scheduledAt = row.optString("scheduled_at", "")
                    list += ScheduledStream(
                        id = row.optString("id", "sch_${System.currentTimeMillis()}_$i"),
                        title = row.optString("title", "Untitled Stream"),
                        category = row.optString("category", "Tech & Talk"),
                        hostName = row.optString("host_name", ""),
                        hostEmail = row.optString("host_email", ""),
                        // Render in the DEVICE zone — the raw UTC substring
                        // showed "14:00" for a 2 PM local stream.
                        date = let {
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = parseIsoToMillis(scheduledAt) }
                            String.format(java.util.Locale.US, "%04d-%02d-%02d",
                                cal.get(java.util.Calendar.YEAR),
                                cal.get(java.util.Calendar.MONTH) + 1,
                                cal.get(java.util.Calendar.DAY_OF_MONTH))
                        },
                        time = let {
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = parseIsoToMillis(scheduledAt) }
                            String.format(java.util.Locale.US, "%02d:%02d",
                                cal.get(java.util.Calendar.HOUR_OF_DAY),
                                cal.get(java.util.Calendar.MINUTE))
                        },
                        timestampMillis = parseIsoToMillis(scheduledAt),
                        slotLimit = slotLimit,
                        slotsBooked = row.optInt("slots_booked", 0),
                        type = pricingType,
                        amount = row.optDouble("amount", 0.0),
                        currency = row.optString("currency", "USD (\$)"),
                        shareLink = row.optString("share_link", ""),
                        isHost = true,
                        isJoined = false
                    )
                }
                _scheduledStreams.value = list
            }
            is SupabaseResult.Error -> Log.w(TAG, "refreshScheduledStreams failed: ${res.message}")
        }
    }

    /**
     * Fetches ONE scheduled stream by id — used by the App Links deep link
     * (https://triggerappltd.cyou/stream/<id>) so ANY signed-in viewer can
     * resolve and book a shared stream, not just the host. `ss_select` RLS
     * allows every authenticated user to read scheduled_streams rows.
     * Null when the id does not exist or the caller is signed out.
     */
    suspend fun fetchStreamById(streamId: String): ScheduledStream? {
        if (streamId.isBlank()) return null
        if (UserRepository.profile.value.id.isBlank()) return null
        return when (val res = client.getTable("scheduled_streams", "select=*&id=eq.$streamId&limit=1")) {
            is SupabaseResult.Success -> {
                if (res.data.length() == 0) null else parseScheduledStreamRow(res.data.getJSONObject(0))
            }
            is SupabaseResult.Error -> {
                Log.w(TAG, "fetchStreamById($streamId) failed: ${res.message}")
                null
            }
        }
    }

    /** Maps one scheduled_streams row (ISO UTC scheduled_at → device zone). */
    private fun parseScheduledStreamRow(row: org.json.JSONObject): ScheduledStream {
        val scheduledAt = row.optString("scheduled_at", "")
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = parseIsoToMillis(scheduledAt) }
        val slotLimit = row.optString("slot_limit", "50")
        val pricingType = if (row.optString("pricing_type", "FREE").equals("PAID", true))
            StreamPricingType.PAID else StreamPricingType.FREE
        return ScheduledStream(
            id = row.optString("id"),
            title = row.optString("title", "Untitled Stream"),
            category = row.optString("category", "Tech & Talk"),
            hostName = row.optString("host_name", ""),
            hostEmail = row.optString("host_email", ""),
            date = String.format(java.util.Locale.US, "%04d-%02d-%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)),
            time = String.format(java.util.Locale.US, "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE)),
            timestampMillis = parseIsoToMillis(scheduledAt),
            slotLimit = slotLimit,
            slotsBooked = row.optInt("slots_booked", 0),
            type = pricingType,
            amount = row.optDouble("amount", 0.0),
            currency = row.optString("currency", "USD ($)"),
            shareLink = row.optString("share_link", ""),
            isHost = row.optString("host_id") == UserRepository.profile.value.id,
            isJoined = false
        )
    }

    /** Build an ISO-8601 timestamp from the user-selected date + time strings. */
    /** Null on parse failure — callers MUST abort instead of scheduling.
     *  The previous "now" fallback made the cron auto-start a stream that was
     *  meant for days later (parse failure → immediate promotion). */
    private fun buildScheduledAtIso(date: String, time: String): String? {
        return try {
            val inFmt = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US)
            inFmt.timeZone = TimeZone.getDefault()
            val outFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            outFmt.timeZone = TimeZone.getDefault()
            val parsed = inFmt.parse("$date $time") ?: return null
            outFmt.format(parsed)
        } catch (_: Exception) {
            null
        }
    }

    /** 0L on parse failure (never fabricate "now"). */
    private fun parseIsoToMillis(iso: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(iso)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatDurationSeconds(seconds: Int): String {
        if (seconds <= 0) return "0 min"
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins > 0) "$mins min $secs sec" else "$secs sec"
    }
}

