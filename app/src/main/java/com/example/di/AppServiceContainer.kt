package com.example.di

import android.content.Context
import com.example.data.local.ChatDatabase
import com.example.data.local.DbKeyManager
import com.example.data.local.TriggerDbMigrator
import com.example.data.repository.ChatRepositoryImpl
import com.example.model.CallType
import com.example.model.DomainMessage
import com.example.model.MessageType
import com.example.service.*
import com.example.service.supabase.SupabaseClient
import com.example.storage.TriggerStorageManager
import com.example.service.webrtc.AgoraWebRtcService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

object AppServiceContainer {
    // Exception handler: an uncaught throw on this scope (e.g. a background
    // FGS start rejected on Android 12+) previously killed the whole process.
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            android.util.Log.w("AppServiceContainer", "Uncaught appScope exception", e)
        }
    )

    private var initialized = false

    /** Guard so the ProcessLifecycleOwner observer is added exactly once. */
    @Volatile
    var lifecycleObserverRegistered = false

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
    lateinit var razorpayPaymentService: com.example.service.RazorpayPaymentService
        private set
    lateinit var secretVaultService: com.example.service.SecretVaultService
        private set
    lateinit var feedRepository: com.example.service.FeedRepository
        private set
    lateinit var storageManager: TriggerStorageManager
        private set

    /**
     * Phase 2 DI gate for the encrypted message store. Completes ONLY after
     * the SQLCipher passphrase is available and the one-time rename/encrypt
     * migration has finished; completes exceptionally (fail fast) if that
     * pipeline fails — every database open awaits it via the gated factory
     * in ChatDatabase, so no code path can touch the file before migration.
     */
    val databaseReady = CompletableDeferred<Unit>()

    /**
     * SQLCipher passphrase (raw 32-byte key) published by the startup
     * pipeline right before [databaseReady] completes. NEVER logged.
     */
    @Volatile
    var dbPassphrase: ByteArray? = null
        private set

    /** Once-guard so the migration pipeline is kicked exactly once. */
    private val databasePipelineStarted = AtomicBoolean(false)

    private lateinit var dbKeyManager: DbKeyManager

    val agoraService: AgoraWebRtcService
        get() = agoraWebRtcService

    fun initialize(context: Context) {
        if (initialized) return

        // Application context only — the previous code pinned the first
        // Activity to process-wide singletons for the app's lifetime (leak).
        this.context = context.applicationContext

        // Phase 1 (WhatsApp-grade storage): single source of truth for every
        // on-device path. The directory tree is created OFF the main thread —
        // initialize() must never block or crash the UI. ensureTree() is
        // idempotent and never throws; the appScope handler below is the
        // extra safety net for anything unexpected.
        storageManager = TriggerStorageManager(context)
        appScope.launch(Dispatchers.IO) { storageManager.ensureTree() }

        // Pass context so the Supabase session persists across process death
        supabaseClient = SupabaseClient(context)
        agoraTokenService = com.example.service.agora.SupabaseEdgeFunctionTokenService(supabaseClient)
        agoraRtcEngineManager = com.example.service.agora.AgoraRtcEngineManager(context, agoraTokenService)

        // The Room wrapper is built synchronously (cheap: no file I/O happens
        // here — the actual file open is gated behind [databaseReady]).
        database = ChatDatabase.getInstance(context)
        chatRepository = ChatRepositoryImpl(database, appScope)
        presenceService = PresenceServiceImpl(appScope)
        messageService = MessageServiceImpl(chatRepository, presenceService)

        // Phase 2 (encrypted message store): on Dispatchers.IO, unwrap or
        // create the SQLCipher passphrase, run the one-time legacy-plaintext →
        // "trigger_msgstore.db" rename/encrypt migration (TriggerDbMigrator is
        // the ONLY code aware of the legacy name), then release the gate so
        // Room's gated open-helper factory can finally open the encrypted
        // file. Failure completes the gate exceptionally: every database open
        // fails fast instead of silently re-creating an empty store.
        // initialize() itself never blocks on this.
        dbKeyManager = DbKeyManager(context)
        kickDatabasePipeline()

        storageService = StorageServiceImpl(context.cacheDir)
        notificationService = NotificationServiceImpl(context)

        uploadService = UploadServiceImpl(
            appScope,
            onUploadComplete = { completedTask ->
                // Media messages are now SENT TO THE SERVER ONLY AFTER the
                // upload completes, carrying the recipient-accessible signed
                // URL. The upload's final URL is written back onto the Room
                // row too (previously recipients got a dead content:// URI).
                messageService.completeMediaUpload(completedTask)
            },
            onUploadFailed = { failedTask ->
                // Flip the staged message to FAILED so the retry affordance
                // appears instead of an eternal spinner.
                messageService.markMediaMessageFailed(
                    failedTask.messageId,
                    failedTask.errorMessage ?: "Upload failed"
                )
            }
        )

        callService = com.example.service.agora.AgoraCallService(
            rtcManager = agoraRtcEngineManager,
            supabaseClient = supabaseClient,
            scope = appScope
        ) { contactId, type, durationSec, isMissed, isOutgoing ->
            // Call-history persistence. Only the CALLER writes the CALL_LOG
            // message (the receiver's copy arrives via Realtime), so both
            // participants get exactly ONE record with the duration. contactId
            // here is the peer's USER uuid — the real conversation uuid is
            // resolved through the message service (H4) and the call metadata
            // is stored in messages.call_type / call_duration_sec (DB history).
            if (isOutgoing) {
                appScope.launch(kotlinx.coroutines.CoroutineName("callLogPersist")) {
                    try {
                        val me = supabaseClient.currentSession?.user?.id
                            ?: return@launch
                        val conversationId = messageService.resolveOrCreateConversation(contactId)
                            ?: return@launch

                        val callText = if (isMissed) {
                            "📞 Missed ${if (type == CallType.VIDEO) "video" else "audio"} call"
                        } else {
                            val mins = durationSec / 60
                            val secs = durationSec % 60
                            val durString = if (mins > 0) "$mins min $secs sec" else "$secs sec"
                            "📞 ${if (type == CallType.VIDEO) "Video" else "Audio"} call, $durString"
                        }
                        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                        val callLogMsg = DomainMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            senderId = me,
                            senderName = "You",
                            type = MessageType.CALL_LOG,
                            text = callText,
                            timestamp = time,
                            timestampMillis = System.currentTimeMillis(),
                            isOutgoing = true,
                            status = com.example.model.MessageStatus.SENT,
                            callType = if (type == CallType.VIDEO) "video" else "audio",
                            callDurationSec = durationSec
                        )
                        messageService.sendMessage(callLogMsg, peerId = contactId, peerName = null)
                    } catch (e: Exception) {
                        android.util.Log.w("AppServiceContainer", "call log persist failed: ${e.message}")
                    }
                }
            }
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
        razorpayPaymentService = com.example.service.RazorpayPaymentService()
        secretVaultService = com.example.service.SecretVaultService(context)
        feedRepository = com.example.service.FeedRepository(this.context)

        initialized = true
    }

    /**
     * Passphrase → migration → gate release, once per process, off main.
     * On any failure the gate completes exceptionally with the original
     * error (no fresh-key fallback: that would orphan the encrypted data).
     */
    private fun kickDatabasePipeline() {
        if (!databasePipelineStarted.compareAndSet(false, true)) return
        appScope.launch(Dispatchers.IO) {
            try {
                val passphrase = dbKeyManager.getOrCreatePassphrase()
                TriggerDbMigrator.ensureMigrated(context, passphrase)
                dbPassphrase = passphrase
                databaseReady.complete(Unit)
            } catch (t: Throwable) {
                android.util.Log.e(
                    "AppServiceContainer",
                    "Encrypted chat DB pipeline failed — failing fast",
                    t
                )
                databaseReady.completeExceptionally(t)
            }
        }
    }
}
