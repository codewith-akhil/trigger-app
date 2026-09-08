package com.example.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SETTINGS_ACCOUNT = "settings_account"
    const val SETTINGS_PRIVACY = "settings_privacy"
    const val SETTINGS_CHATS = "settings_chats"
    const val SETTINGS_NOTIFICATIONS = "settings_notifications"
    const val SETTINGS_STORAGE = "settings_storage"
    const val SETTINGS_HELP = "settings_help"
    const val HELP = "help"
    const val SCHEDULE_STREAM = "schedule_stream"
    const val STREAM_HISTORY = "stream_history"
    const val WALLET = "wallet"
    const val SECRET_VAULT = "secret_vault"
}

@Composable
fun TriggerAppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
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
        modifier = modifier,
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
                onNavigateToScheduleStream = {
                    navController.navigate(TriggerDestinations.SCHEDULE_STREAM)
                },
                onNavigateToStreamHistory = {
                    navController.navigate(TriggerDestinations.STREAM_HISTORY)
                },
                onRestartFlow = {
                    navController.navigate(TriggerDestinations.LANDING) {
                        popUpTo(0) { inclusive = true }
                    }
                }
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
                onBack = { navController.popBackStack() }
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

        composable(TriggerDestinations.HOME) {
            TriggerHomeScreen(
                verifiedPhoneNumber = currentEmail,
                onRestartFlow = {
                    navController.navigate(TriggerDestinations.LANDING) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}

