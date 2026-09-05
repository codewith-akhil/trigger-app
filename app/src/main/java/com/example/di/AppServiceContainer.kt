package com.example.di

import android.content.Context
import com.example.data.local.ChatDatabase
import com.example.data.repository.ChatRepositoryImpl
import com.example.model.CallType
import com.example.model.DomainMessage
import com.example.model.MessageType
import com.example.service.*
import com.example.service.supabase.SupabaseClient
import com.example.service.webrtc.AgoraWebRtcService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

object AppServiceContainer {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var initialized = false

    lateinit var context: android.content.Context
        private set

    lateinit var database: ChatDatabase
        private set
    lateinit var chatRepository: ChatRepositoryImpl
        private set
    lateinit var presenceService: PresenceServiceImpl
        private set
    lateinit var messageService: MessageService
        private set
    lateinit var uploadService: UploadService
        private set
    lateinit var callService: CallService
        private set
    lateinit var storageService: StorageService
        private set
    lateinit var notificationService: NotificationService
        private set
    lateinit var supabaseClient: SupabaseClient
        private set
    lateinit var agoraTokenService: com.example.service.agora.AgoraTokenService
        private set
    lateinit var agoraRtcEngineManager: com.example.service.agora.AgoraRtcEngineManager
        private set
    lateinit var liveStreamService: com.example.service.LiveStreamService
        private set
    lateinit var agoraWebRtcService: AgoraWebRtcService
        private set
    lateinit var streamScheduleService: com.example.service.StreamScheduleService
        private set
    lateinit var walletService: com.example.service.WalletService
        private set
    lateinit var secretVaultService: com.example.service.SecretVaultService
        private set

    val agoraService: AgoraWebRtcService
        get() = agoraWebRtcService

    fun initialize(context: Context) {
        if (initialized) return

        this.context = context

        supabaseClient = SupabaseClient()
        agoraTokenService = com.example.service.agora.SupabaseEdgeFunctionTokenService(supabaseClient)
        agoraRtcEngineManager = com.example.service.agora.AgoraRtcEngineManager(context, agoraTokenService)

        database = ChatDatabase.getInstance(context)
        chatRepository = ChatRepositoryImpl(database, appScope)
        presenceService = PresenceServiceImpl(appScope)
        messageService = MessageServiceImpl(chatRepository, presenceService)

        storageService = StorageServiceImpl(context.cacheDir)
        notificationService = NotificationServiceImpl(context)

        uploadService = UploadServiceImpl(appScope) { completedTask ->
            // Update message status upon upload completion
            messageService.updateMessageStatus(completedTask.messageId, com.example.model.MessageStatus.SENT)
        }

        callService = com.example.service.agora.AgoraCallService(
            rtcManager = agoraRtcEngineManager,
            supabaseClient = supabaseClient,
            scope = appScope
        ) { contactId, type, durationSec, isMissed ->
            // Insert call log message into chat
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            val callText = if (isMissed) {
                "📞 Missed ${if (type == CallType.VIDEO) "video" else "audio"} call"
            } else {
                val mins = durationSec / 60
                val secs = durationSec % 60
                val durString = if (mins > 0) "$mins min $secs sec" else "$secs sec"
                "📞 ${if (type == CallType.VIDEO) "Video" else "Audio"} call, $durString"
            }

            val callLogMsg = DomainMessage(
                id = java.util.UUID.randomUUID().toString(),
                conversationId = contactId,
                senderId = "me",
                senderName = "You",
                type = MessageType.CALL_LOG,
                text = callText,
                timestamp = time,
                timestampMillis = System.currentTimeMillis(),
                isOutgoing = true,
                status = com.example.model.MessageStatus.READ
            )
            chatRepository.sendMessage(callLogMsg, isOnline = true)
        }

        liveStreamService = com.example.service.agora.AgoraLiveStreamService(
            rtcManager = agoraRtcEngineManager,
            supabaseClient = supabaseClient,
            scope = appScope
        )

        agoraWebRtcService = AgoraWebRtcService(
            scope = appScope,
            callService = callService,
            liveStreamService = liveStreamService
        )

        streamScheduleService = com.example.service.StreamScheduleService(appScope)
        walletService = com.example.service.WalletService(context)
        secretVaultService = com.example.service.SecretVaultService(context)

        initialized = true
    }
}
