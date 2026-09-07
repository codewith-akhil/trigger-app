package com.example.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * WhatsApp-style "last seen" formatting for the chat header subtitle.
 *
 *  - seen today                  → "last seen today at 3:45 PM"
 *  - seen yesterday              → "last seen yesterday at 9:12 AM"
 *  - seen within the last 6 days → "last seen Wednesday at 8:00 AM"
 *  - older                       → "last seen 12 Sep at 8:00 AM"
 *  - missing/unparseable         → "offline"
 *
 * All times are rendered in the device's current timezone (WhatsApp behavior).
 * "online" (when is_online) is handled by the caller.
 */
object LastSeenFormatter {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val weekdayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())

    fun format(isoTimestamp: String, nowMillis: Long = System.currentTimeMillis()): String {
        if (isoTimestamp.isBlank() || isoTimestamp == "null") return "offline"
        val seenMillis = parseMillis(isoTimestamp) ?: return "offline"

        val zone = ZoneId.systemDefault()
        val seenDateTime = Instant.ofEpochMilli(seenMillis).atZone(zone)
        val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val seenDate = seenDateTime.toLocalDate()

        // Clock skew: a "future" last-seen still renders as today.
        val clampedDate = if (seenDate.isAfter(nowDate)) nowDate else seenDate

        val timeText = seenDateTime.format(timeFormatter)
        return when {
            clampedDate == nowDate -> "last seen today at $timeText"
            clampedDate == nowDate.minusDays(1) -> "last seen yesterday at $timeText"
            clampedDate.isAfter(nowDate.minusDays(7)) -> "last seen ${clampedDate.format(weekdayFormatter)} at $timeText"
            else -> "last seen ${clampedDate.format(dayFormatter)} at $timeText"
        }
    }

    private fun parseMillis(iso: String): Long? = try {
        Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) {
        try {
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e2: Exception) {
            try {
                // Fallback: treat a bare timestamp as UTC ("2026-09-07T03:16:42.123")
                java.time.LocalDateTime.parse(iso.replace(" ", "T"))
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            } catch (e3: Exception) {
                null
            }
        }
    }
}
