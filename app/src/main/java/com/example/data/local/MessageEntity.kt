package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.model.MessageReaction
import com.example.model.MessageStatus
import com.example.model.MessageType
import com.example.model.DomainMessage

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["timestampMillis"]),
        // Declared to match the index created by the 3→4 migration
        // (index_messages_seq). Room validates the expected schema on EVERY
        // open — an actual index the entity doesn't declare crashes
        // checkIdentity ("Migration didn't properly handle messages") for
        // every device that ever migrated 3→4.
        Index(value = ["seq"]),
        // Task 25: composite pagination index — serves the newest-first
        // window/page queries (conversationId equality + timestampMillis/seq
        // ordering) without loading the whole conversation. Created by the
        // 10→11 migration; the name must stay in sync with it.
        Index(value = ["conversationId", "timestampMillis", "seq"], name = "index_messages_conv_ts_seq")
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val type: String = "TEXT", // MessageType
    val text: String = "",
    val mediaUrl: String? = null,
    val mediaThumbnail: String? = null,
    val mediaBucket: String? = null,
    val mediaPath: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val mediaDurationSec: Int = 0,
    val isViewOnce: Boolean = false,
    val isViewed: Boolean = false,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val status: String = "SENT", // MessageStatus
    val timestamp: String,
    val timestampMillis: Long,
    val isOutgoing: Boolean,
    val isStarred: Boolean = false,
    val isPinned: Boolean = false,
    val isDeletedForEveryone: Boolean = false,
    val editedAt: Long? = null,
    val seq: Long = 0L,
    val idempotencyKey: String? = null,
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    val locationAddress: String? = null,
    val locationLiveMinutes: Int? = null, // live-location duration in minutes (15/60/480)
    val locationComment: String? = null,  // user comment attached to a location share
    val contactName: String? = null,
    val contactPhone: String? = null,
    val callType: String? = null,      // CALL_LOG: "audio" | "video"
    val callDurationSec: Int = 0,      // CALL_LOG: duration in seconds
    val reactionsRaw: String = "" // e.g. "❤️:1:false,👍:2:true"
) {
    fun toDomainMessage(
        uploadProgress: Int? = null,
        uploadSpeed: String? = null,
        remainingTimeText: String? = null
    ): DomainMessage {
        val parsedReactions = if (reactionsRaw.isBlank()) {
            emptyList()
        } else {
            reactionsRaw.split(";").mapNotNull { part ->
                val pieces = part.split(":")
                if (pieces.size >= 3) {
                    MessageReaction(
                        emoji = pieces[0],
                        count = pieces[1].toIntOrNull() ?: 1,
                        userReacted = pieces[2].toBooleanStrictOrNull() ?: false
                    )
                } else null
            }
        }

        return DomainMessage(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            senderName = senderName,
            type = try { MessageType.valueOf(type) } catch (e: Exception) { MessageType.TEXT },
            text = text,
            // Re-sign / normalize on the way OUT of Room: an expired signed URL
            // is rebuilt from the persisted bucket+path (public form for the
            // public chat_media bucket, fresh signature otherwise) so media
            // renders forever instead of dying after 7 days.
            mediaUrl = com.example.service.MediaUrlResolver.resolve(
                storedUrl = mediaUrl, bucket = mediaBucket, path = mediaPath
            ),
            mediaThumbnail = mediaThumbnail,
            mediaBucket = mediaBucket,
            mediaPath = mediaPath,
            fileName = fileName,
            fileSize = fileSize,
            mediaDurationSec = mediaDurationSec,
            isViewOnce = isViewOnce,
            isViewed = isViewed,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToSender = replyToSender,
            status = try { MessageStatus.valueOf(status) } catch (e: Exception) { MessageStatus.SENT },
            timestamp = timestamp,
            timestampMillis = timestampMillis,
            isOutgoing = isOutgoing,
            isStarred = isStarred,
            isPinned = isPinned,
            isDeletedForEveryone = isDeletedForEveryone,
            editedAt = editedAt,
            seq = seq,
            idempotencyKey = idempotencyKey,
            locationLatitude = locationLatitude,
            locationLongitude = locationLongitude,
            locationAddress = locationAddress,
            locationLiveMinutes = locationLiveMinutes,
            locationComment = locationComment,
            contactName = contactName,
            contactPhone = contactPhone,
            callType = callType,
            callDurationSec = callDurationSec,
            reactions = parsedReactions,
            uploadProgress = uploadProgress,
            uploadSpeed = uploadSpeed,
            remainingTimeText = remainingTimeText
        )
    }

    companion object {
        fun fromDomain(domain: DomainMessage): MessageEntity {
            val reactionsString = domain.reactions.joinToString(";") {
                "${it.emoji}:${it.count}:${it.userReacted}"
            }
            return MessageEntity(
                id = domain.id,
                conversationId = domain.conversationId,
                senderId = domain.senderId,
                senderName = domain.senderName,
                type = domain.type.name,
                text = domain.text,
                mediaUrl = domain.mediaUrl,
                mediaThumbnail = domain.mediaThumbnail,
                mediaBucket = domain.mediaBucket,
                mediaPath = domain.mediaPath,
                fileName = domain.fileName,
                fileSize = domain.fileSize,
                mediaDurationSec = domain.mediaDurationSec,
                isViewOnce = domain.isViewOnce,
                isViewed = domain.isViewed,
                replyToId = domain.replyToId,
                replyToText = domain.replyToText,
                replyToSender = domain.replyToSender,
                status = domain.status.name,
                timestamp = domain.timestamp,
                timestampMillis = domain.timestampMillis,
                isOutgoing = domain.isOutgoing,
                isStarred = domain.isStarred,
                isPinned = domain.isPinned,
                isDeletedForEveryone = domain.isDeletedForEveryone,
                editedAt = domain.editedAt,
                seq = domain.seq,
                idempotencyKey = domain.idempotencyKey,
                locationLatitude = domain.locationLatitude,
                locationLongitude = domain.locationLongitude,
                locationAddress = domain.locationAddress,
                locationLiveMinutes = domain.locationLiveMinutes,
                locationComment = domain.locationComment,
                contactName = domain.contactName,
                contactPhone = domain.contactPhone,
                callType = domain.callType,
                callDurationSec = domain.callDurationSec,
                reactionsRaw = reactionsString
            )
        }
    }
}
