package com.example.util

import java.time.Instant
import java.time.LocalDate
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
 *
 * Hardening (v1.0.13): the header was seen rendering a bare "Last seen" with
 * no time on some devices. Root causes covered here:
 *  1. some OEM locales render HH:mm with non-Latin digits — the time IS there
 *     but reads as "no time". We now format with Locale.US digits so the
 *     output matches the hardcoded English text;
 *  2. java.time formatting/parsing must NEVER throw through the presence
 *     pipeline (a throw left the subtitle at a stale/partial state) — every
 *     failure mode now resolves to a valid string, never an exception;
 *  3. parse tolerates epoch-millis strings on top of the ISO variants.
 */
object LastSeenFormatter {

    // Locale.US: hardcoded-English prefix + guaranteed Latin digits. The
    // weekday name follows the same choice for consistency ("Last seen
    // Wednesday 08:00" is English text in every build).
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val dayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val weekdayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE", Locale.US)

    fun format(isoTimestamp: String, nowMillis: Long = System.currentTimeMillis()): String {
        if (isoTimestamp.isBlank() || isoTimestamp == "null") return "offline"

        val seenMillis = parseMillis(isoTimestamp) ?: return "offline"
        val zone = try {
            ZoneId.systemDefault()
        } catch (_: Exception) {
            ZoneId.of("UTC")
        }

        // Whole body guarded: any unexpected java.time failure degrades to
        // "offline" instead of throwing into the presence pipeline (which
        // previously left the header stuck at a stale/partial subtitle).
        return try {
            val seenDateTime = Instant.ofEpochMilli(seenMillis).atZone(zone)
            val nowDateTime = Instant.ofEpochMilli(nowMillis).atZone(zone)
            val nowDate: LocalDate = nowDateTime.toLocalDate()
            val seenDate: LocalDate = seenDateTime.toLocalDate()

            // Clock skew: a "future" last-seen still renders as today.
            val clampedDate = if (seenDate.isAfter(nowDate)) nowDate else seenDate

            val timeText = seenDateTime.format(timeFormatter)
            when {
                clampedDate == nowDate -> "Last seen $timeText"
                clampedDate == nowDate.minusDays(1) -> "Last seen yesterday $timeText"
                clampedDate.isAfter(nowDate.minusDays(7)) ->
                    "Last seen ${clampedDate.format(weekdayFormatter)} $timeText"
                else -> "Last seen ${clampedDate.format(dayFormatter)}"
            }
        } catch (_: Exception) {
            "offline"
        }
    }

    private fun parseMillis(iso: String): Long? {
        // epoch-millis shortcut (some rows are stored as raw numbers)
        iso.toLongOrNull()?.let { return it }

        return try {
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
}
