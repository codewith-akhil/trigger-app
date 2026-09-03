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
        Index(value = ["timestampMillis"])
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
    val isDeletedForEveryone: Boolean = false,
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    val locationAddress: String? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
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
            mediaUrl = mediaUrl,
            mediaThumbnail = mediaThumbnail,
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
            isDeletedForEveryone = isDeletedForEveryone,
            locationLatitude = locationLatitude,
            locationLongitude = locationLongitude,
            locationAddress = locationAddress,
            contactName = contactName,
            contactPhone = contactPhone,
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
                isDeletedForEveryone = domain.isDeletedForEveryone,
                locationLatitude = domain.locationLatitude,
                locationLongitude = domain.locationLongitude,
                locationAddress = domain.locationAddress,
                contactName = domain.contactName,
                contactPhone = domain.contactPhone,
                reactionsRaw = reactionsString
            )
        }
    }
}
