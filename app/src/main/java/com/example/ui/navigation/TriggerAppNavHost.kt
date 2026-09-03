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
    const val SELECT_CONTACT = "select_contact"
    const val HOME = "home"
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
        mutableStateOf("akhil@gmail.com")
    }
    var currentOtp by remember {
        mutableStateOf("123456")
    }
    var otpPurpose by remember {
        mutableStateOf(OtpPurpose.SIGN_UP)
    }
    var showNotificationDialogOnLandingToAuth by remember {
        mutableStateOf(false)
    }
    var activeChatContactId by remember {
        mutableStateOf("darling")
    }
    var activeChatContactName by remember {
        mutableStateOf("darling")
    }
    var activeChatAvatarRes by remember {
        mutableStateOf<Int?>(com.example.R.drawable.img_darling_avatar)
    }

    NavHost(
        navController = navController,
        startDestination = TriggerDestinations.DASHBOARD,
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
                }
            )
        }

        composable(TriggerDestinations.RESET_PASSWORD) {
            ResetPasswordScreen(
                email = currentEmail,
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
                }
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

