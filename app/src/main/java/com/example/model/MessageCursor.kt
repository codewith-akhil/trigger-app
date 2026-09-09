package com.example.model

/**
 * Task 25 — canonical pagination cursor for chat messages.
 *
 * A stable TOTAL order over a conversation's messages:
 * (timestampMillis ASC, seq ASC, id ASC). All window/pagination queries
 * (Room DAO + the sync-messages history endpoint) sort and compare by
 * exactly these three fields, so page boundaries can never skip or
 * duplicate rows that share a timestamp (same-millisecond bursts) or a
 * seq (legacy/local rows default to 0).
 *
 * Pure Kotlin — deliberately free of any Android/Room imports so the
 * pagination state machine can be unit-tested on the JVM.
 */
data class MessageCursor(
    val timestampMillis: Long,
    val seq: Long,
    val messageId: String
) : Comparable<MessageCursor> {

    override fun compareTo(other: MessageCursor): Int =
        compareValuesBy(this, other, { it.timestampMillis }, { it.seq }, { it.messageId })

    companion object {
        fun of(message: DomainMessage): MessageCursor =
            MessageCursor(
                timestampMillis = message.timestampMillis,
                seq = message.seq,
                messageId = message.id
            )
    }
}
