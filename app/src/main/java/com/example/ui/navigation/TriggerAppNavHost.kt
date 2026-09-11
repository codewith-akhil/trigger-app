package com.example.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.model.LanguageRepository
import com.example.di.AppServiceContainer
import com.example.ui.components.NotificationPermissionDialog
import com.example.ui.screens.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.viewmodel.DashboardViewModel

object TriggerDestinations {
    const val LANDING = "landing"
    const val LANGUAGE_SELECTION = "language_selection"
    const val EMAIL_AUTH = "email_auth"
    const val SIGN_UP = "sign_up"
    const val FORGOT_PASSWORD = "forgot_password"
    const val RESET_PASSWORD = "reset_password"
    const val EMAIL_OTP = "email_otp"
    const val DASHBOARD = "dashboard"
    const val CHAT = "chat"
    const val PROFILE = "profile"
    const val USER_PROFILE = "user_profile"
    const val DELETE_ACCOUNT = "delete_account"
    const val SELECT_CONTACT = "select_contact"
    const val NEW_MESSAGE = "new_message"
    const val SETTINGS = "settings"
    const val SETTINGS_ACCOUNT = "settings_account"
    const val SETTINGS_PRIVACY = "settings_privacy"
    const val SETTINGS_CHATS = "settings_chats"
    const val SETTINGS_NOTIFICATIONS = "settings_notifications"
    const val SETTINGS_STORAGE = "settings_storage"
    const val SETTINGS_HELP = "settings_help"
    const val SETTINGS_BLOCKED = "settings_blocked"
    const val NOTIFICATIONS = "notifications"
    const val HELP = "help"
    const val SCHEDULE_STREAM = "schedule_stream"
    const val STREAM_HISTORY = "stream_history"
    const val WALLET = "wallet"
    const val SECRET_VAULT = "secret_vault"
    const val POST_UPLOAD = "post_upload/{mediaType}?draftId={draftId}"
    fun postUpload(mediaType: String = "photo", draftId: String? = null) =
        "post_upload/$mediaType" + if (draftId.isNullOrBlank()) "?draftId=" else "?draftId=$draftId"

    const val POST_VIEW = "post_view/{postId}"
    fun postView(postId: String) = "post_view/$postId"

    const val PAYMENT_OVERVIEW = "payment_overview/{postId}"
    fun paymentOverview(postId: String) = "payment_overview/$postId"

    const val PAYMENT_VALIDATION = "payment_validation/{postId}?orderId={orderId}&paymentId={paymentId}&signature={signature}&status={status}&error={error}"
    fun paymentValidation(
        postId: String,
        orderId: String = "",
        paymentId: String = "",
        signature: String = "",
        status: String = "success",
        error: String = ""
    ) = "payment_validation/$postId?orderId=${android.net.Uri.encode(orderId)}&paymentId=${android.net.Uri.encode(paymentId)}&signature=${android.net.Uri.encode(signature)}&status=${android.net.Uri.encode(status)}&error=${android.net.Uri.encode(error)}"
    // (The dead HOME route + TriggerHomeScreen — with its unreachable camera
    // icon and restart-onboarding hook — were removed entirely.)
}

@Composable
fun TriggerAppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    // ---- GLOBAL INCOMING/ACTIVE CALL OVERLAY -------------------------------
    // Hosted at the navigation ROOT so a call is visible and answerable from
    // EVERY screen (previously the ring UI existed only inside ChatScreen — a
    // backgrounded user or a user on any other screen could never see it).
    val activeCall by AppServiceContainer.callService.currentCall.collectAsState()

    Box(modifier = modifier) {
    var selectedLanguage by remember {
        mutableStateOf(LanguageRepository.languages.first())
    }
    var currentEmail by rememberSaveable {
        mutableStateOf("")
    }
    var currentOtp by rememberSaveable {
        mutableStateOf("")
    }
    // Signup password captured for the post-OTP password grant. Deliberately
    // remember (memory-only) instead of rememberSaveable — a plaintext password
    // must never be persisted to disk via SavedStateHandle. If the process dies
    // mid-OTP, the user just signs in with the credentials they just created.
    var pendingSignupPassword by remember {
        mutableStateOf("")
    }
    var currentVerifiedOtpCode by rememberSaveable {
        mutableStateOf("")
    }
    var otpPurpose by rememberSaveable {
        mutableStateOf(OtpPurpose.SIGN_UP)
    }
    var showNotificationDialogOnLandingToAuth by rememberSaveable {
        mutableStateOf(false)
    }
    var activeChatConversationId by rememberSaveable {
        mutableStateOf("")
    }
    // The OTHER user's auth uuid — presence/typing/calls key on it (H5).
    var activeChatPeerId by rememberSaveable {
        mutableStateOf("")
    }
    var activeChatContactName by rememberSaveable {
        mutableStateOf("")
    }
    var activeChatAvatarRes by rememberSaveable {
        mutableStateOf<Int?>(null)
    }
    var activeUserProfileUser by remember {
        mutableStateOf<com.example.ui.screens.UserSearchResult?>(null)
    }

    // Android 13+ requires a RUNTIME request for POST_NOTIFICATIONS. The
    // landing dialog previously just navigated without ever requesting.
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* granted or not — continue to auth either way */ }

    NavHost(
        navController = navController,
        // Auto-login: skip onboarding when a persisted Supabase session exists
        // (session restore happens in SupabaseClient.init from MODE_PRIVATE prefs)
        startDestination = if (AppServiceContainer.supabaseClient.hasActiveSession()) {
            TriggerDestinations.DASHBOARD
        } else {
            TriggerDestinations.LANDING
        },
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(280)) + fadeIn(tween(280))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(280)) + fadeOut(tween(280))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(280)) + fadeIn(tween(280))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(280)) + fadeOut(tween(280))
        }
    ) {
        composable(TriggerDestinations.LANDING) {
            LandingScreen(
                currentLanguageName = selectedLanguage.title,
                onNavigateToLanguage = {
                    navController.navigate(TriggerDestinations.LANGUAGE_SELECTION)
                },
                onAgreeAndContinue = {
                    showNotificationDialogOnLandingToAuth = true
                },
                onNavigateToHelp = {
                    navController.navigate(TriggerDestinations.HELP)
                }
            )

            if (showNotificationDialogOnLandingToAuth) {
                NotificationPermissionDialog(
                    onAllow = {
                        showNotificationDialogOnLandingToAuth = false
                        // Fire the REAL runtime permission request on Android 13+
                        val activity = context as? android.app.Activity
                        if (android.os.Build.VERSION.SDK_INT >= 33 && activity != null) {
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        navController.navigate(TriggerDestinations.EMAIL_AUTH)
                    },
                    onDontAllow = {
                        showNotificationDialogOnLandingToAuth = false
                        navController.navigate(TriggerDestinations.EMAIL_AUTH)
                    }
                )
            }
        }

        composable(TriggerDestinations.LANGUAGE_SELECTION) {
            LanguageSelectionScreen(
                selectedLanguageCode = selectedLanguage.code,
                onLanguageSelected = { lang ->
                    selectedLanguage = lang
                    navController.popBackStack()
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(TriggerDestinations.EMAIL_AUTH) {
            EmailAuthScreen(
                onLoginSuccess = {
                    // Connect to Supabase Realtime on login
                    com.example.di.AppServiceContainer.supabaseClient.connectRealtime(
                        tables = listOf("public.messages", "public.conversations", "public.user_presences")
                    )
                    // Start presence heartbeat
                    (com.example.di.AppServiceContainer.presenceService as? com.example.service.PresenceServiceImpl)?.onAppForeground()
                    navController.navigate(TriggerDestinations.DASHBOARD) {
                        popUpTo(TriggerDestinations.LANDING) { inclusive = true }
                    }
                },
                onNavigateToSignUp = {
                    navController.navigate(TriggerDestinations.SIGN_UP)
                },
                onNavigateToForgotPassword = {
                    navController.navigate(TriggerDestinations.FORGOT_PASSWORD)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(TriggerDestinations.SIGN_UP) {
            SignUpScreen(
                onNavigateToOtp = { _, email, generatedOtp, signupPassword ->
                    currentEmail = email
                    currentOtp = generatedOtp
                    pendingSignupPassword = signupPassword
                    otpPurpose = OtpPurpose.SIGN_UP
                    navController.navigate(TriggerDestinations.EMAIL_OTP)
                },
                onNavigateToLogin = {
                    navController.navigate(TriggerDestinations.EMAIL_AUTH) {
                        popUpTo(TriggerDestinations.EMAIL_AUTH) { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(TriggerDestinations.FORGOT_PASSWORD) {
            ForgotPasswordScreen(
                onNavigateToOtp = { email, generatedOtp ->
                    currentEmail = email
                    currentOtp = generatedOtp
                    otpPurpose = OtpPurpose.FORGOT_PASSWORD
                    navController.navigate(TriggerDestinations.EMAIL_OTP)
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(TriggerDestinations.EMAIL_OTP) {
            EmailOtpVerificationScreen(
                email = currentEmail,
                expectedOtp = currentOtp,
                purpose = otpPurpose,
                signupPassword = if (otpPurpose == OtpPurpose.SIGN_UP) pendingSignupPassword else null,
                onWrongEmailClick = {
                    navController.popBackStack()
                },
                onVerificationSuccess = {
                    if (otpPurpose == OtpPurpose.SIGN_UP) {
                        pendingSignupPassword = ""  // clear credential from memory
                        navController.navigate(TriggerDestinations.DASHBOARD) {
                            popUpTo(TriggerDestinations.LANDING) { inclusive = true }
                        }
                    } else {
                        navController.navigate(TriggerDestinations.RESET_PASSWORD)
                    }
                },
                onOtpVerified = { code ->
                    currentVerifiedOtpCode = code
                }
            )
        }

        composable(TriggerDestinations.RESET_PASSWORD) {
            ResetPasswordScreen(
                email = currentEmail,
                verifiedOtpCode = currentVerifiedOtpCode,
                onResetSuccess = {
                    navController.navigate(TriggerDestinations.EMAIL_AUTH) {
                        popUpTo(TriggerDestinations.EMAIL_AUTH) { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(TriggerDestinations.DASHBOARD) {
            // Safety net: if the authenticated user differs from the last user
            // this device saw (signup without clean logout, restored stale
            // session, etc.), wipe all local per-user state (Room chats, chat
            // prefs, vault prefs + files, wallet/presence caches) BEFORE the
            // dashboard UI reads it.
            LaunchedEffect(Unit) {
                com.example.service.AccountStateManager.onUserSessionChanged(
                    com.example.di.AppServiceContainer.supabaseClient.currentSession?.user?.id
                )
            }
            WhatsAppDashboardScreen(
                onOpenChat = { conversationId, peerId, contactName, avatarRes ->
                    activeChatConversationId = conversationId
                    activeChatPeerId = peerId
                    activeChatContactName = contactName
                    activeChatAvatarRes = avatarRes
                    navController.navigate(TriggerDestinations.CHAT) { launchSingleTop = true }
                },
                onOpenNewMessage = {
                    // The New Message page is the single entry point for
                    // starting chats: message requests, contacts, follows
                    // and username search all live there.
                    navController.navigate(TriggerDestinations.NEW_MESSAGE)
                },
                onOpenProfile = {
                    navController.navigate(TriggerDestinations.PROFILE)
                },
                onOpenSettings = {
                    navController.navigate(TriggerDestinations.SETTINGS)
                },
                onOpenNotifications = {
                    navController.navigate(TriggerDestinations.NOTIFICATIONS)
                },
                onNavigateToScheduleStream = {
                    navController.navigate(TriggerDestinations.SCHEDULE_STREAM)
                },
                onNavigateToStreamHistory = {
                    navController.navigate(TriggerDestinations.STREAM_HISTORY)
                },
                onNavigateToPostUpload = { mediaType ->
                    navController.navigate(TriggerDestinations.postUpload(mediaType))
                },
                onNavigateToEditDraft = { postId ->
                    // Reuse the draft's own media type so "replace media" picks the
                    // right gallery kind.
                    val draft = AppServiceContainer.feedRepository.drafts.value.find { it.id == postId }
                    val mediaType = if (draft?.mediaType == com.example.model.PostMediaType.VIDEO) "video" else "photo"
                    navController.navigate(TriggerDestinations.postUpload(mediaType, postId))
                },
                onNavigateToPostView = { postId ->
                    navController.navigate(TriggerDestinations.postView(postId))
                },
                onNavigateToPaymentOverview = { postId ->
                    navController.navigate(TriggerDestinations.paymentOverview(postId))
                },
                onLogout = {
                    // The dashboard "Restart Onboarding Flow" menu item was
                    // removed; logout keeps the full-cleanup chain (same as the
                    // PROFILE route): presence offline -> realtime disconnect
                    // -> server signOut -> Room/vault/wallet wipe, THEN navigate.
                    com.example.service.AccountStateManager.performLogout {
                        navController.navigate(TriggerDestinations.LANDING) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(TriggerDestinations.NOTIFICATIONS) {
            // Same DashboardViewModel instance the dashboard renders from
            // (scoped to the DASHBOARD back-stack entry) so the bell badge
            // clears the moment rows are marked read. Falls back silently
            // when the dashboard is not on the back stack — the badge
            // re-derives on the next dashboard composition anyway.
            val dashboardEntry = remember(navController) {
                runCatching { navController.getBackStackEntry(TriggerDestinations.DASHBOARD) }.getOrNull()
            }
            val dashboardViewModel = dashboardEntry?.let { entry ->
                viewModel<DashboardViewModel>(viewModelStoreOwner = entry)
            }
            NotificationsScreen(
                onBack = { navController.popBackStack() },
                onNotificationsRead = { dashboardViewModel?.clearNotificationsBadge() },
                onMessageActor = { conversationId, actorId, actorName ->
                    // ChatScreen resolves (or creates) the conversation from
                    // the peer uuid when conversationId is empty (H4).
                    activeChatConversationId = conversationId
                    activeChatPeerId = actorId
                    activeChatContactName = actorName
                    activeChatAvatarRes = null
                    navController.navigate(TriggerDestinations.CHAT) { launchSingleTop = true }
                },
                onOpenUserProfile = { user ->
                    activeUserProfileUser = user
                    navController.navigate(TriggerDestinations.USER_PROFILE)
                }
            )
        }

        composable(TriggerDestinations.SETTINGS_BLOCKED) {
            BlockedUsersScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(TriggerDestinations.PROFILE) {
            ProfileScreen(
                onBack = {
                    navController.popBackStack()
                },
                onLogout = {
                    // Full cleanup on an app-level, non-cancellable scope:
                    // presence offline -> realtime disconnect -> server signOut
                    // -> Room DB + chat prefs + vault prefs/files + wallet/
                    //    presence caches + UserRepository wiped
                    // Navigation happens ONLY after cleanup completes.
                    com.example.service.AccountStateManager.performLogout {
                        navController.navigate(TriggerDestinations.LANDING) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onNavigateToDeleteAccount = {
                    navController.navigate(TriggerDestinations.DELETE_ACCOUNT)
                }
            )
        }

        composable(TriggerDestinations.DELETE_ACCOUNT) {
            DeleteAccountScreen(
                onBack = { navController.popBackStack() },
                onDeleted = {
                    com.example.service.AccountStateManager.performLogout {
                        navController.navigate(TriggerDestinations.LANDING) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(TriggerDestinations.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToProfile = { navController.navigate(TriggerDestinations.PROFILE) },
                onNavigateToAccount = { navController.navigate(TriggerDestinations.SETTINGS_ACCOUNT) },
                onNavigateToPrivacy = { navController.navigate(TriggerDestinations.SETTINGS_PRIVACY) },
                onNavigateToChats = { navController.navigate(TriggerDestinations.SETTINGS_CHATS) },
                onNavigateToNotifications = { navController.navigate(TriggerDestinations.SETTINGS_NOTIFICATIONS) },
                onNavigateToStorage = { navController.navigate(TriggerDestinations.SETTINGS_STORAGE) },
                onNavigateToHelp = { navController.navigate(TriggerDestinations.SETTINGS_HELP) },
                onNavigateToLanguage = { navController.navigate(TriggerDestinations.LANGUAGE_SELECTION) },
                onNavigateToWallet = { navController.navigate(TriggerDestinations.WALLET) },
                onNavigateToSecretVault = { navController.navigate(TriggerDestinations.SECRET_VAULT) }
            )
        }

        composable(TriggerDestinations.SCHEDULE_STREAM) {
            ScheduleStreamScreen(
                onBack = { navController.popBackStack() },
                onStreamScheduled = {
                    navController.popBackStack()
                }
            )
        }

        composable(TriggerDestinations.STREAM_HISTORY) {
            StreamHistoryScreen(
                onBack = { navController.popBackStack() },
                onScheduleNew = {
                    navController.navigate(TriggerDestinations.SCHEDULE_STREAM)
                }
            )
        }

        composable(TriggerDestinations.WALLET) {
            WalletScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(TriggerDestinations.SECRET_VAULT) {
            SecretVaultScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(TriggerDestinations.SETTINGS_ACCOUNT) {
            AccountSettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToDeleteAccount = {
                    navController.navigate(TriggerDestinations.DELETE_ACCOUNT)
                }
            )
        }

        composable(TriggerDestinations.SETTINGS_PRIVACY) {
            PrivacySettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateBlockedUsers = {
                    navController.navigate(TriggerDestinations.SETTINGS_BLOCKED)
                }
            )
        }

        composable(TriggerDestinations.SETTINGS_CHATS) {
            ChatsSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(TriggerDestinations.SETTINGS_NOTIFICATIONS) {
            NotificationsSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(TriggerDestinations.SETTINGS_STORAGE) {
            StorageSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(TriggerDestinations.SETTINGS_HELP) {
            HelpSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(TriggerDestinations.HELP) {
            HelpSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(TriggerDestinations.SELECT_CONTACT) {
            SelectContactScreen(
                onBack = {
                    navController.popBackStack()
                },
                onSelectContact = { peerId, contactName, avatarRes ->
                    // H4: conversationId is resolved inside ChatScreen (local
                    // cache → server get-or-create) from the peer uuid.
                    activeChatConversationId = ""
                    activeChatPeerId = peerId
                    activeChatContactName = contactName
                    activeChatAvatarRes = avatarRes
                    navController.navigate(TriggerDestinations.CHAT) { launchSingleTop = true }
                }
            )
        }

        composable(TriggerDestinations.NEW_MESSAGE) {
            NewMessageScreen(
                onBack = { navController.popBackStack() },
                onChatOpened = { convId, peerId, contactName ->
                    // H4 FIX: previously convId was DISCARDED here and the
                    // peer uuid stored — message-request chats then ran on the
                    // wrong key. Real conversation uuid wins when present.
                    activeChatConversationId = convId
                    activeChatPeerId = peerId
                    activeChatContactName = contactName
                    activeChatAvatarRes = null
                    navController.navigate(TriggerDestinations.CHAT) { launchSingleTop = true }
                },
                onNavigateToUserProfile = { user ->
                    activeUserProfileUser = user
                    navController.navigate(TriggerDestinations.USER_PROFILE)
                }
            )
        }

        composable(TriggerDestinations.USER_PROFILE) {
            val user = activeUserProfileUser
            if (user != null) {
                com.example.ui.screens.UserProfileScreen(
                    user = user,
                    onBack = { navController.popBackStack() },
                    onOpenChat = { convId, peerId, contactName ->
                        activeChatConversationId = convId
                        activeChatPeerId = peerId
                        activeChatContactName = contactName
                        activeChatAvatarRes = null
                        navController.navigate(TriggerDestinations.CHAT) { launchSingleTop = true }
                    }
                )
            } else {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }

        composable(TriggerDestinations.CHAT) {
            ChatScreen(
                conversationId = activeChatConversationId,
                peerId = activeChatPeerId,
                contactName = activeChatContactName,
                contactAvatarRes = activeChatAvatarRes,
                onBack = {
                    navController.popBackStack()
                },
                // Chat header (name/avatar) → the target user's full profile.
                // UserProfileScreen self-hydrates about/username/avatar from the
                // profiles table, so a minimal UserSearchResult keyed on the
                // peer uuid is enough (same contract as NewMessageScreen).
                onOpenProfile = {
                    activeUserProfileUser = UserSearchResult(
                        id = activeChatPeerId,
                        name = activeChatContactName,
                        username = null,
                        avatarUrl = null
                    )
                    navController.navigate(TriggerDestinations.USER_PROFILE)
                }
            )
        }

        composable(
            route = TriggerDestinations.POST_UPLOAD,
            arguments = listOf(
                androidx.navigation.navArgument("mediaType") { type = androidx.navigation.NavType.StringType; defaultValue = "photo" },
                androidx.navigation.navArgument("draftId") { type = androidx.navigation.NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val mediaType = backStackEntry.arguments?.getString("mediaType") ?: "photo"
            val draftId = backStackEntry.arguments?.getString("draftId").orEmpty()
            PostUploadScreen(
                initialMediaType = mediaType,
                draftId = draftId.takeIf { it.isNotBlank() },
                onBack = {
                    navController.popBackStack()
                },
                onPostCreatedSuccessfully = {
                    navController.popBackStack()
                }
            )
        }

        composable(TriggerDestinations.POST_VIEW) { backStackEntry ->
            val postId = backStackEntry.arguments?.getString("postId").orEmpty()
            PostViewScreen(
                postId = postId,
                onBack = {
                    navController.popBackStack()
                },
                onNavigateToPaymentOverview = { id ->
                    navController.navigate(TriggerDestinations.paymentOverview(id))
                }
            )
        }

        composable(TriggerDestinations.PAYMENT_OVERVIEW) { backStackEntry ->
            val postId = backStackEntry.arguments?.getString("postId").orEmpty()
            PaymentOverviewScreen(
                postId = postId,
                onBack = {
                    navController.popBackStack()
                },
                onNavigateToValidation = { orderId, paymentId, signature, status, error ->
                    navController.navigate(
                        TriggerDestinations.paymentValidation(
                            postId = postId,
                            orderId = orderId,
                            paymentId = paymentId,
                            signature = signature,
                            status = status,
                            error = error.orEmpty()
                        )
                    )
                }
            )
        }

        composable(
            route = TriggerDestinations.PAYMENT_VALIDATION,
            arguments = listOf(
                androidx.navigation.navArgument("postId") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("orderId") { type = androidx.navigation.NavType.StringType; defaultValue = "" },
                androidx.navigation.navArgument("paymentId") { type = androidx.navigation.NavType.StringType; defaultValue = "" },
                androidx.navigation.navArgument("signature") { type = androidx.navigation.NavType.StringType; defaultValue = "" },
                androidx.navigation.navArgument("status") { type = androidx.navigation.NavType.StringType; defaultValue = "success" },
                androidx.navigation.navArgument("error") { type = androidx.navigation.NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val postId = backStackEntry.arguments?.getString("postId").orEmpty()
            val orderId = backStackEntry.arguments?.getString("orderId").orEmpty()
            val paymentId = backStackEntry.arguments?.getString("paymentId").orEmpty()
            val signature = backStackEntry.arguments?.getString("signature").orEmpty()
            val status = backStackEntry.arguments?.getString("status") ?: "success"
            val error = backStackEntry.arguments?.getString("error")?.takeIf { it.isNotBlank() }

            PaymentValidationScreen(
                postId = postId,
                orderId = orderId,
                paymentId = paymentId,
                signature = signature,
                initialStatus = status,
                errorMessage = error,
                onNavigateToPostView = { id ->
                    navController.navigate(TriggerDestinations.postView(id)) {
                        popUpTo(TriggerDestinations.DASHBOARD)
                    }
                },
                onNavigateToFeed = {
                    navController.popBackStack(TriggerDestinations.DASHBOARD, inclusive = false)
                },
                onRetryPayment = { id ->
                    navController.popBackStack()
                }
            )
        }
    } // NavHost

    // The call overlay renders ABOVE the entire nav graph.
    // Accept is permission-gated (UI-driven, WhatsApp-style): RECORD_AUDIO —
    // plus CAMERA for video — are requested BEFORE acceptIncomingCall joins
    // the Agora channel. A plain denial does nothing (the next Accept tap
    // re-requests); a permanent denial opens the app's Settings page. The
    // service-side backstop keeps the session RINGING meanwhile, never
    // faking a failed call.
    val callContext = androidx.compose.ui.platform.LocalContext.current
    var pendingCallAccept by remember { mutableStateOf<(() -> Unit)?>(null) }
    val callPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val action = pendingCallAccept
        pendingCallAccept = null
        if (action == null) return@rememberLauncherForActivityResult
        if (result.values.all { it }) {
            action()
        } else {
            val activity = callContext as? android.app.Activity
            val permanentlyDenied = result.filterValues { !it }.keys.all { perm ->
                activity == null || !androidx.core.app.ActivityCompat
                    .shouldShowRequestPermissionRationale(activity, perm)
            }
            if (permanentlyDenied) com.example.util.openAppSettings(callContext)
        }
    }
    activeCall?.let { session ->
        val neededPerms = buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            if (session.type == com.example.model.CallType.VIDEO) {
                add(android.Manifest.permission.CAMERA)
            }
        }
        val missingPerms = neededPerms.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(callContext, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        com.example.ui.screens.ChatCallingOverlay(
            session = session,
            onEndCall = { AppServiceContainer.callService.endCall() },
            onToggleMute = { AppServiceContainer.callService.toggleMute() },
            onToggleSpeaker = { AppServiceContainer.callService.toggleSpeaker() },
            onToggleVideo = { AppServiceContainer.callService.toggleVideo() },
            onSwitchCamera = { AppServiceContainer.callService.switchCamera() },
            onAcceptCall = {
                if (missingPerms.isEmpty()) {
                    AppServiceContainer.callService.acceptIncomingCall()
                } else {
                    pendingCallAccept = { AppServiceContainer.callService.acceptIncomingCall() }
                    callPermissionLauncher.launch(missingPerms.toTypedArray())
                }
            },
            onDeclineCall = { AppServiceContainer.callService.declineCall() }
        )
    }
    } // Box
}

