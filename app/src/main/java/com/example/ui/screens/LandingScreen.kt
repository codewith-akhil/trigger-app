package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.HelpModalBottomSheet
import com.example.ui.components.TriggerBottomNavInset
import com.example.ui.components.TriggerTopHeader
import com.example.ui.theme.*

@Composable
fun LandingScreen(
    currentLanguageName: String,
    onNavigateToLanguage: () -> Unit,
    onAgreeAndContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var showHelpSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("landing_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = GeometricCanvasBg,
        topBar = {
            TriggerTopHeader(
                title = "Trigger App",
                actions = {
                    // Accessibility button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .testTag("accessibility_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AccessibilityNew,
                            contentDescription = "Accessibility",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag("landing_more_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options",
                                tint = Color.White
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier
                                .background(Color.White)
                                .testTag("landing_dropdown_menu")
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.help),
                                        color = GeometricTextPrimary,
                                        fontSize = 15.sp
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showHelpSheet = true
                                },
                                modifier = Modifier.testTag("menu_help_item")
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            TriggerBottomNavInset()
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.15f))

            // Center Illustration with balanced geometric circular frame
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .background(GeometricSurfaceVariant)
                    .border(1.dp, GeometricBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "Welcome illustration",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .testTag("welcome_illustration")
                )
            }

            Spacer(modifier = Modifier.weight(0.2f))

            // Welcome Title
            Text(
                text = stringResource(R.string.welcome_title),
                color = GeometricTextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Terms and Privacy text with links
            val annotatedTerms = buildAnnotatedString {
                append(stringResource(R.string.welcome_privacy_prefix))
                withStyle(style = SpanStyle(color = GeometricGreenDark, fontWeight = FontWeight.Medium)) {
                    append(stringResource(R.string.privacy_policies))
                }
                append(stringResource(R.string.welcome_privacy_middle))
                withStyle(style = SpanStyle(color = GeometricGreenDark, fontWeight = FontWeight.Medium)) {
                    append(stringResource(R.string.terms_of_service))
                }
                append(".")
            }

            Text(
                text = annotatedTerms,
                color = GeometricTextSecondary,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Language selector button (Geometric pill with border and green text)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .border(1.dp, GeometricBorder, RoundedCornerShape(20.dp))
                    .clickable(onClick = onNavigateToLanguage)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("language_selector_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = "Language",
                        tint = GeometricGreenPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentLanguageName,
                        color = GeometricGreenPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = GeometricGreenPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.25f))

            // Agree and continue button (Brand green with white bold text and rounded pill shape)
            Button(
                onClick = onAgreeAndContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("agree_and_continue_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GeometricGreenPrimary
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Text(
                    text = stringResource(R.string.agree_and_continue).uppercase(),
                    color = Color.White,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }

        if (showHelpSheet) {
            HelpModalBottomSheet(onDismiss = { showHelpSheet = false })
        }
    }
}
