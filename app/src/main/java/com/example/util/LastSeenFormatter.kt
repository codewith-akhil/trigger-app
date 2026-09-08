package com.example.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Last seen" formatting for the chat header subtitle (user spec: 24-hour
 * clock, compact WhatsApp-style).
 *
 *  - seen today                  → "Last seen 03:02"
 *  - seen yesterday              → "Last seen yesterday 20:15"
 *  - seen within the last 6 days → "Last seen Wednesday 08:00"
 *  - older                       → "Last seen 12 Sep"
 *  - missing/unparseable         → "offline"
 *
 * All times are rendered in the device's current timezone (WhatsApp behavior)
 * on a 24-hour clock. "online" (when is_online) is handled by the caller; a
 * hidden (privacy-restricted) peer is blanked by the caller, not here.
 */
object LastSeenFormatter {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
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
            clampedDate == nowDate -> "Last seen $timeText"
            clampedDate == nowDate.minusDays(1) -> "Last seen yesterday $timeText"
            clampedDate.isAfter(nowDate.minusDays(7)) -> "Last seen ${clampedDate.format(weekdayFormatter)} $timeText"
            else -> "Last seen ${clampedDate.format(dayFormatter)}"
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
