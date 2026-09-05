package com.example.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import com.example.model.LanguageRepository
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
    const val DELETE_ACCOUNT = "delete_account"
    const val SELECT_CONTACT = "select_contact"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SETTINGS_ACCOUNT = "settings_account"
    const val SETTINGS_PRIVACY = "settings_privacy"
    const val SETTINGS_CHATS = "settings_chats"
    const val SETTINGS_NOTIFICATIONS = "settings_notifications"
    const val SETTINGS_STORAGE = "settings_storage"
    const val SETTINGS_HELP = "settings_help"
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
    var currentEmail by remember {
        mutableStateOf("")
    }
    var currentOtp by remember {
        mutableStateOf("")
    }
    var currentVerifiedOtpCode by remember {
        mutableStateOf("")
    }
    var otpPurpose by remember {
        mutableStateOf(OtpPurpose.SIGN_UP)
    }
    var showNotificationDialogOnLandingToAuth by remember {
        mutableStateOf(false)
    }
    var activeChatContactId by remember {
        mutableStateOf("")
    }
    var activeChatContactName by remember {
        mutableStateOf("")
    }
    var activeChatAvatarRes by remember {
        mutableStateOf<Int?>(null)
    }

    NavHost(
        navController = navController,
        startDestination = TriggerDestinations.LANDING,
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
                }
            )

            if (showNotificationDialogOnLandingToAuth) {
                NotificationPermissionDialog(
                    onAllow = {
                        showNotificationDialogOnLandingToAuth = false
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
                onNavigateToOtp = { _, email, generatedOtp ->
                    currentEmail = email
                    currentOtp = generatedOtp
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
                onWrongEmailClick = {
                    navController.popBackStack()
                },
                onVerificationSuccess = {
                    if (otpPurpose == OtpPurpose.SIGN_UP) {
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
            WhatsAppDashboardScreen(
                onOpenChat = { contactId, contactName, avatarRes ->
                    activeChatContactId = contactId
                    activeChatContactName = contactName
                    activeChatAvatarRes = avatarRes
                    navController.navigate(TriggerDestinations.CHAT)
                },
                onOpenSelectContact = {
                    navController.navigate(TriggerDestinations.SELECT_CONTACT)
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
                    kotlinx.coroutines.MainScope().launch {
                        com.example.di.AppServiceContainer.supabaseClient.signOut()
                        com.example.model.UserRepository.clear()
                    }
                    navController.navigate(TriggerDestinations.LANDING) {
                        popUpTo(0) { inclusive = true }
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
                    kotlinx.coroutines.MainScope().launch {
                        com.example.di.AppServiceContainer.supabaseClient.signOut()
                        com.example.model.UserRepository.clear()
                    }
                    navController.navigate(TriggerDestinations.LANDING) {
                        popUpTo(0) { inclusive = true }
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
                onDeleteAccountConfirmed = {
                    navController.navigate(TriggerDestinations.LANDING) {
                        popUpTo(0) { inclusive = true }
                    }
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

        composable(TriggerDestinations.SELECT_CONTACT) {
            SelectContactScreen(
                onBack = {
                    navController.popBackStack()
                },
                onSelectContact = { contactId, contactName, avatarRes ->
                    activeChatContactId = contactId
                    activeChatContactName = contactName
                    activeChatAvatarRes = avatarRes
                    navController.navigate(TriggerDestinations.CHAT)
                }
            )
        }

        composable(TriggerDestinations.CHAT) {
            ChatScreen(
                contactId = activeChatContactId,
                contactName = activeChatContactName,
                contactAvatarRes = activeChatAvatarRes,
                onBack = {
                    navController.popBackStack()
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

