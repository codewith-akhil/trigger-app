package com.example.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ChatTimeFormatter
 * ----------------------------------------------------------------------------
 * Timestamp rendering for the dashboard chat list (WhatsApp-style).
 *
 *  - same calendar day  → "13:23"   (24-hour clock, locale-independent digits)
 *  - yesterday          → "yesterday"
 *  - older              → "dd/MM/yyyy" (leading zeros)
 *  - future timestamps (clock skew) render as today ("HH:mm")
 *  - unparsable/absent  → ""        (the caller renders an empty right column)
 *
 * All times are rendered in the device's current timezone. The source of truth
 * is the epoch-millis stamp persisted on ConversationEntity (lastActivityMillis,
 * Room v8); the ISO-string fallbacks exist only for rows whose millis stamp was
 * never populated (legacy pulls, cross-device restores).
 */
object ChatTimeFormatter {

    // Locale.US pins ASCII digits and the literal "/" separator so the list
    // never renders Arabic-Indic digits or a locale-swapped day/month order.
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)

    /**
     * Formats an epoch-millis activity stamp: same calendar day → "HH:mm",
     * yesterday → "yesterday", else "dd/MM/yyyy". Returns "" for stamps <= 0.
     */
    fun formatChatTimestamp(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0L) return ""
        val zone: ZoneId = ZoneId.systemDefault()
        val thenDate: LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        val nowDate: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            !thenDate.isBefore(nowDate) ->
                TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
            thenDate == nowDate.minusDays(1) -> "yesterday"
            else -> DATE_FORMAT.format(thenDate)
        }
    }

    /**
     * Chat-list entry point with the robust fallback chain: use the persisted
     * millis stamp when valid; otherwise parse the stored ISO-8601 string
     * ("2026-09-07T16:21:39Z" / offset forms); otherwise return "".
     */
    fun formatForList(
        lastActivityMillis: Long,
        isoTimestamp: String,
        now: Long = System.currentTimeMillis()
    ): String {
        if (lastActivityMillis > 0L) return formatChatTimestamp(lastActivityMillis, now)
        val parsed = parseIsoToMillis(isoTimestamp)
        return if (parsed > 0L) formatChatTimestamp(parsed, now) else ""
    }

    /** Parses ISO-8601 to epoch millis; 0L when absent or unparsable. */
    fun parseIsoToMillis(iso: String): Long {
        if (iso.isBlank() || iso == "null") return 0L
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            try {
                // Offset form ("2026-09-07T16:21:39+00:00")
                OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                try {
                    // Bare timestamp treated as UTC ("2026-09-07 16:21:39.123")
                    LocalDateTime.parse(iso.replace(" ", "T"))
                        .toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (e3: Exception) {
                    0L
                }
            }
        }
    }
}
