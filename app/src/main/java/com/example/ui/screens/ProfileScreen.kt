package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.model.UserRepository
import com.example.service.ProfileService
import com.example.service.supabase.SupabaseResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================================
// Color palette (kept identical to the previous version)
// ============================================================================
private val TriggerLightBg = Color(0xFFF7F8FA)
private val TriggerCardBg = Color(0xFFFFFFFF)
private val TriggerGreenAccent = Color(0xFF008069)
private val TriggerFabGreen = Color(0xFF00A884)
private val TriggerTextPrimary = Color(0xFF111B21)
private val TriggerTextSecondary = Color(0xFF667781)
private val TriggerDivider = Color(0xFFF0F2F5)
private val TriggerDanger = Color(0xFFD32F2F)

// ============================================================================
// Username validation rules — mirrored from the check-username-availability
// edge function so we can give the user instant client-side feedback.
// ============================================================================
private const val MIN_USERNAME = 5
private const val MAX_USERNAME = 25
private val USERNAME_REGEX = Regex("^[a-z0-9_.]+$")
private val RESERVED_USERNAMES = setOf(
    "admin", "root", "support", "help", "api", "trigger", "official",
    "system", "moderator", "mod", "staff", "team", "info", "contact",
    "about", "settings", "login", "signup", "register", "auth", "user",
    "profile", "me", "self", "superuser", "operator", "service", "bot",
    "anonymous", "guest", "null", "undefined", "test", "demo", "example",
    "sample", "owner", "master"
)

// ============================================================================
// About / Links limits
// ============================================================================
private const val MIN_NAME = 2
private const val MAX_NAME = 50
private const val MAX_ABOUT = 350
private const val MAX_LINK_NAME = 25
private const val MAX_LINK_URL = 2048
private const val MAX_LINKS = 3
private const val MIN_DOB_AGE = 13
private const val MAX_DOB_AGE = 120

/**
 * Validates a full name: 2-50 chars, allows letters (any script), spaces,
 * hyphens, apostrophes, dots. Blocks emoji and control characters.
 * Returns null if valid, or an error message string.
 */
private fun validateName(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Name is required"
    if (trimmed.length < MIN_NAME) return "Name must be at least $MIN_NAME characters"
    if (trimmed.length > MAX_NAME) return "Name must be $MAX_NAME characters or fewer"
    // Allow letters (Unicode \p{L}), spaces, hyphens, apostrophes, dots, commas.
    // This already blocks digits, emoji (which are Symbol category, not Letter),
    // and control characters.
    if (!Regex("^[\\p{L}][\\p{L}\\s'\\-.,]*$").matches(trimmed)) {
        return "Name can only contain letters, spaces, hyphens, and apostrophes"
    }
    return null
}

/**
 * Validates a URL: must start with http:// or https://, max 2048 chars,
 * must have a valid hostname.
 */
private fun validateUrl(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return "URL is required"
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return "URL must start with http:// or https://"
    }
    if (trimmed.length > MAX_LINK_URL) return "URL is too long (max $MAX_LINK_URL chars)"
    try {
        val u = java.net.URI(trimmed)
        if (u.host.isNullOrBlank()) return "URL is missing a domain"
    } catch (_: Exception) {
        return "Invalid URL format"
    }
    return null
}

/**
 * Validates a DOB string (YYYY-MM-DD): must be a real date, age >= 13, <= 120.
 */
private fun validateDob(dobStr: String): String? {
    if (dobStr.isEmpty()) return "Date of birth is required"
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        sdf.isLenient = false
        val dob = sdf.parse(dobStr) ?: return "Invalid date"
        val now = java.util.Date()
        if (dob.after(now)) return "Date of birth can't be in the future"
        val ageMs = now.time - dob.time
        val ageYears = ageMs / (1000.0 * 60 * 60 * 24 * 365.25)
        if (ageYears < MIN_DOB_AGE) return "You must be at least $MIN_DOB_AGE years old"
        if (ageYears > MAX_DOB_AGE) return "Please enter a valid date of birth"
        null
    } catch (_: Exception) {
        "Invalid date format"
    }
}

private data class LinkItem(val name: String, val url: String)

private sealed class UsernameAvailability {
    object Idle : UsernameAvailability()
    object Checking : UsernameAvailability()
    object Available : UsernameAvailability()
    object Taken : UsernameAvailability()
    object Error : UsernameAvailability()
}

// ============================================================================
// ProfileTopHeader
// ============================================================================
@Composable
fun ProfileTopHeader(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TriggerGreenAccent,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Fill area above the header section (status bar) with header green background
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(TriggerGreenAccent)
            )
            // Header content
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Profile",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ============================================================================
// ProfileScreen
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit = {},
    onNavigateToDeleteAccount: () -> Unit = {},
    showHeader: Boolean = true,
    modifier: Modifier = Modifier
) {
    val profile by UserRepository.profile.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Dialog visibility flags
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showEditAboutDialog by remember { mutableStateOf(false) }
    var showEditUsernameDialog by remember { mutableStateOf(false) }
    var showEditLinksDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showPhotoSheet by remember { mutableStateOf(false) }
    var showEditGenderDialog by remember { mutableStateOf(false) }
    var showEditCountryDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Per-field saving flags — drive the saving UI in each dialog + on the avatar
    var isAvatarSaving by remember { mutableStateOf(false) }
    var isNameSaving by remember { mutableStateOf(false) }
    var isAboutSaving by remember { mutableStateOf(false) }
    var isUsernameSaving by remember { mutableStateOf(false) }
    var isLinksSaving by remember { mutableStateOf(false) }
    var isGenderSaving by remember { mutableStateOf(false) }
    var isCountrySaving by remember { mutableStateOf(false) }
    var isDobSaving by remember { mutableStateOf(false) }

    // Genders + Countries fetched from backend (NO hardcoded fallback).
    // If the fetch fails, the list stays empty and the corresponding dialog
    // shows an error message instead of falling back to hardcoded values.
    var genders by remember { mutableStateOf<List<String>>(emptyList()) }
    var countries by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var gendersFetchFailed by remember { mutableStateOf(false) }
    var countriesFetchFailed by remember { mutableStateOf(false) }

    // Fetch profile + reference data (genders, countries) from DB on first launch.
    // Also hydrate UserRepository from the server so name/email always show.
    LaunchedEffect(Unit) {
        // 1) Hydrate the profile from the server (ensures name + email show
        //    on the Profile screen even after app restart or fresh login).
        ProfileService.refreshFromServer(AppServiceContainer.supabaseClient)

        // 2) Fetch genders from the genders table — NO fallback.
        try {
            val sr = AppServiceContainer.supabaseClient.getTable("genders", "select=name&order=sort_order.asc")
            if (sr is SupabaseResult.Success) {
                val list = mutableListOf<String>()
                for (i in 0 until sr.data.length()) {
                    list.add(sr.data.getJSONObject(i).getString("name"))
                }
                genders = list
                gendersFetchFailed = false
            } else {
                gendersFetchFailed = true
            }
        } catch (_: Exception) {
            gendersFetchFailed = true
        }

        // 3) Fetch countries from the countries table — NO fallback.
        try {
            val cr = AppServiceContainer.supabaseClient.getTable("countries", "select=currency_code,country_name&order=country_name.asc")
            if (cr is SupabaseResult.Success) {
                val list = mutableListOf<Pair<String, String>>()
                for (i in 0 until cr.data.length()) {
                    val obj = cr.data.getJSONObject(i)
                    list.add(Pair(obj.getString("currency_code"), obj.getString("country_name")))
                }
                countries = list
                countriesFetchFailed = false
            } else {
                countriesFetchFailed = true
            }
        } catch (_: Exception) {
            countriesFetchFailed = true
        }
    }

    // Holds the content:// URI handed to TakePicture() so the result callback
    // knows which file was being captured.
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    // ------------------------------------------------------------------------
    // Avatar — upload to Supabase Storage bucket 'avatars', then sync the
    // public URL to profiles.avatar_url via the sync-user-profile edge fn.
    // ------------------------------------------------------------------------
    fun saveAvatar(uriString: String) {
        if (isAvatarSaving) return
        isAvatarSaving = true
        coroutineScope.launch {
            try {
                val uri = Uri.parse(uriString)

                // 1) Resolve MIME type from ContentResolver
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"

                // 2) Validate MIME type — avatars bucket only accepts these
                val allowedMimeTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
                if (mimeType !in allowedMimeTypes) {
                    isAvatarSaving = false
                    Toast.makeText(
                        context,
                        "Unsupported image format. Please use JPG, PNG, WEBP, or GIF.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // 3) Read bytes
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run {
                        isAvatarSaving = false
                        Toast.makeText(context, "Could not read the selected image", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                // 4) Validate file size — avatars bucket limit is 10 MB
                val maxBytes = 10L * 1024 * 1024
                if (bytes.size > maxBytes) {
                    isAvatarSaving = false
                    Toast.makeText(
                        context,
                        "Image is too large. Maximum size is 10 MB.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // 5) Generate a unique object path: {userId}/{timestamp}.{ext}
                val ext = when (mimeType) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    else -> "jpg"
                }
                val userId = AppServiceContainer.supabaseClient.currentUser?.id
                    ?: java.util.UUID.randomUUID().toString()
                val fileName = "$userId/${System.currentTimeMillis()}.$ext"

                // 6) Upload to the 'avatars' Storage bucket (upsert = true so
                //    re-uploads overwrite cleanly)
                val uploadResult = AppServiceContainer.supabaseClient.uploadFile(
                    bucketName = "avatars",
                    fileName = fileName,
                    fileBytes = bytes,
                    mimeType = mimeType,
                    upsert = true
                )

                if (uploadResult is SupabaseResult.Error) {
                    isAvatarSaving = false
                    Toast.makeText(
                        context,
                        "Upload failed: ${uploadResult.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val publicUrl = (uploadResult as SupabaseResult.Success).data

                // 7) Sync the public URL to profiles.avatar_url
                val payload = JSONObject().put("avatarUrl", publicUrl)
                val syncResult = AppServiceContainer.supabaseClient.invokeFunction("sync-user-profile", payload)
                isAvatarSaving = false
                if (syncResult is SupabaseResult.Success) {
                    UserRepository.updateAvatarUri(publicUrl)
                    Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to save photo", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                isAvatarSaving = false
                Toast.makeText(
                    context,
                    "Failed to upload photo: ${e.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraImageUri
        if (success && uri != null) {
            saveAvatar(uri.toString())
        }
        cameraImageUri = null
    }

    fun launchCameraCapture() {
        try {
            val photoFile = File(context.cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            cameraImageUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to start camera", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCameraCapture()
        } else {
            Toast.makeText(context, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) saveAvatar(uri.toString())
    }

    fun openCamera() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) launchCameraCapture() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    fun openGallery() {
        galleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // ------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("profile_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = TriggerLightBg,
        topBar = {
            if (showHeader) {
                ProfileTopHeader(onBack = onBack)
            }
        },
        bottomBar = {
            if (showHeader) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                        .background(TriggerGreenAccent)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- Large centered circular profile avatar with green camera badge ----
            Box(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .size(150.dp),
                contentAlignment = Alignment.Center
            ) {
                // Main avatar circle (display-cropped via CircleShape + ContentScale.Crop)
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE2E8F0)),
                    contentAlignment = Alignment.Center
                ) {
                    if (profile.avatarUri != null) {
                        AsyncImage(
                            model = profile.avatarUri,
                            contentDescription = "Profile Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = "Profile Photo",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(90.dp)
                        )
                    }
                }

                // Saving overlay (circular progress indicator)
                if (isAvatarSaving) {
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Green camera badge — opens the photo bottom sheet
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(TriggerFabGreen)
                        .clickable(enabled = !isAvatarSaving) { showPhotoSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = "Change Profile Photo",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Profile details card ----
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TriggerCardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // 1) Name
                    ProfileDetailItem(
                        icon = Icons.Outlined.Person,
                        label = "Name",
                        value = profile.name.ifEmpty { "Set Name" },
                        isValueGreen = profile.name.isEmpty(),
                        onClick = { showEditNameDialog = true },
                        testTag = "profile_name_item"
                    )

                    HorizontalDivider(color = TriggerDivider, thickness = 1.dp)

                    // 2) About
                    ProfileDetailItem(
                        icon = Icons.Outlined.Info,
                        label = "About",
                        value = profile.about.ifEmpty { "Set About" },
                        isValueGreen = profile.about.isEmpty() || profile.about == "Set About",
                        onClick = { showEditAboutDialog = true },
                        testTag = "profile_about_item"
                    )

                    HorizontalDivider(color = TriggerDivider, thickness = 1.dp)

                    // 3) Username
                    ProfileDetailItem(
                        icon = Icons.Outlined.AlternateEmail,
                        label = "Username",
                        value = if (profile.username.isNotEmpty()) "@${profile.username}" else "Create a username",
                        isValueGreen = profile.username.isEmpty(),
                        onClick = { showEditUsernameDialog = true },
                        testTag = "profile_username_item"
                    )

                    HorizontalDivider(color = TriggerDivider, thickness = 1.dp)

                    // 4) Email — read-only (no edit icon, not clickable)
                    ProfileDetailItem(
                        icon = Icons.Outlined.Email,
                        label = "Email",
                        value = profile.email.ifEmpty { "No email address" },
                        isValueGreen = profile.email.isEmpty(),
                        onClick = null,
                        testTag = "profile_email_item"
                    )

                    HorizontalDivider(color = TriggerDivider, thickness = 1.dp)

                    // 5) Gender — opens a dropdown dialog
                    ProfileDetailItem(
                        icon = Icons.Outlined.Person,
                        label = "Gender",
                        value = profile.gender ?: "Select gender",
                        isValueGreen = profile.gender == null,
                        onClick = { showEditGenderDialog = true },
                        testTag = "profile_gender_item"
                    )

                    HorizontalDivider(color = TriggerDivider, thickness = 1.dp)

                    // 6) Country — opens a dropdown dialog
                    ProfileDetailItem(
                        icon = Icons.Outlined.Public,
                        label = "Country",
                        value = profile.countryName ?: "Select country",
                        isValueGreen = profile.countryName == null,
                        onClick = { showEditCountryDialog = true },
                        testTag = "profile_country_item"
                    )

                    HorizontalDivider(color = TriggerDivider, thickness = 1.dp)

                    // 7) DOB — opens a date picker
                    ProfileDetailItem(
                        icon = Icons.Outlined.Cake,
                        label = "Date of Birth",
                        value = profile.dob ?: "Select date of birth",
                        isValueGreen = profile.dob == null,
                        onClick = { showDatePicker = true },
                        testTag = "profile_dob_item"
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---- Delete Account button (above Logout) ----
            OutlinedButton(
                onClick = onNavigateToDeleteAccount,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("delete_account_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White,
                    contentColor = TriggerDanger
                ),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFFFFCDD2))
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete account",
                    tint = TriggerDanger,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Delete my account",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TriggerDanger
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Logout button ----
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("logout_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White,
                    contentColor = TriggerDanger
                ),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFFFFCDD2))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = "Log out",
                    tint = TriggerDanger,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Log out",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TriggerDanger
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // ------------------------------------------------------------------------
    // Photo bottom sheet (Camera / Gallery)
    // ------------------------------------------------------------------------
    if (showPhotoSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPhotoSheet = false },
            containerColor = Color.White
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = "Change Profile Photo",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TriggerTextPrimary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showPhotoSheet = false
                            openCamera()
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoCamera,
                        contentDescription = "Camera",
                        tint = TriggerGreenAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    Text("Camera", fontSize = 16.sp, color = TriggerTextPrimary)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showPhotoSheet = false
                            openGallery()
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = "Gallery",
                        tint = TriggerGreenAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    Text("Gallery", fontSize = 16.sp, color = TriggerTextPrimary)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // ------------------------------------------------------------------------
    // Logout confirmation dialog
    // ------------------------------------------------------------------------
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "Log out of Trigger App?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    color = TriggerTextPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to log out? You will need to verify your account to log back in.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = TriggerTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TriggerDanger),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Log out", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel", color = TriggerTextSecondary, fontWeight = FontWeight.Medium)
                }
            }
        )
    }

    // ------------------------------------------------------------------------
    // Edit dialogs — Name / About / Username / Links
    // ------------------------------------------------------------------------
    if (showEditNameDialog) {
        ProfileEditDialog(
            title = "Enter your name",
            initialValue = profile.name,
            isSaving = isNameSaving,
            validator = { validateName(it) },
            maxLength = MAX_NAME,
            onDismiss = { if (!isNameSaving) showEditNameDialog = false },
            onConfirm = { value ->
                val trimmed = value.trim()
                // Defense in depth — validator already blocks this
                val validationError = validateName(trimmed)
                if (validationError != null) {
                    Toast.makeText(context, validationError, Toast.LENGTH_LONG).show()
                    return@ProfileEditDialog
                }
                isNameSaving = true
                coroutineScope.launch {
                    val payload = JSONObject().put("fullName", trimmed)
                    val result = AppServiceContainer.supabaseClient.invokeFunction("sync-user-profile", payload)
                    isNameSaving = false
                    if (result is SupabaseResult.Success) {
                        UserRepository.updateName(trimmed)
                        Toast.makeText(context, "Name saved", Toast.LENGTH_SHORT).show()
                        showEditNameDialog = false
                    } else {
                        val err = (result as? SupabaseResult.Error)?.message ?: "Failed to save name"
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showEditAboutDialog) {
        AboutEditDialog(
            initialValue = if (profile.about == "Set About") "" else profile.about,
            isSaving = isAboutSaving,
            onDismiss = { if (!isAboutSaving) showEditAboutDialog = false },
            onConfirm = { value ->
                val cleaned = value.trim()
                isAboutSaving = true
                coroutineScope.launch {
                    val payload = JSONObject().put("about", cleaned)
                    val result = AppServiceContainer.supabaseClient.invokeFunction("sync-user-profile", payload)
                    isAboutSaving = false
                    if (result is SupabaseResult.Success) {
                        UserRepository.updateAbout(cleaned)
                        Toast.makeText(context, "About updated", Toast.LENGTH_SHORT).show()
                        showEditAboutDialog = false
                    } else {
                        val err = (result as? SupabaseResult.Error)?.message ?: "Failed to save about"
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showEditUsernameDialog) {
        UsernameEditDialog(
            currentUsername = profile.username,
            isSaving = isUsernameSaving,
            onDismiss = { if (!isUsernameSaving) showEditUsernameDialog = false },
            onConfirm = { cleanUsername ->
                isUsernameSaving = true
                coroutineScope.launch {
                    val payload = JSONObject().put("username", cleanUsername)
                    val result = AppServiceContainer.supabaseClient.invokeFunction("sync-user-profile", payload)
                    isUsernameSaving = false
                    if (result is SupabaseResult.Success) {
                        UserRepository.updateUsername(cleanUsername)
                        Toast.makeText(context, "Username saved", Toast.LENGTH_SHORT).show()
                        showEditUsernameDialog = false
                    } else {
                        val err = (result as? SupabaseResult.Error)?.message ?: "Failed to save username"
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showEditLinksDialog) {
        LinksEditDialog(
            initialLinksJson = profile.links,
            isSaving = isLinksSaving,
            onDismiss = { if (!isLinksSaving) showEditLinksDialog = false },
            onConfirm = { linksJson ->
                isLinksSaving = true
                coroutineScope.launch {
                    val arr = JSONArray(linksJson)
                    val payload = JSONObject().put("links", arr)
                    val result = AppServiceContainer.supabaseClient.invokeFunction("sync-user-profile", payload)
                    isLinksSaving = false
                    if (result is SupabaseResult.Success) {
                        UserRepository.updateLinks(linksJson)
                        Toast.makeText(context, "Links saved", Toast.LENGTH_SHORT).show()
                        showEditLinksDialog = false
                    } else {
                        val err = (result as? SupabaseResult.Error)?.message ?: "Failed to save links"
                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    // ------------------------------------------------------------------------
    // Gender dropdown dialog
    // ------------------------------------------------------------------------
    if (showEditGenderDialog) {
        var selectedGender by remember { mutableStateOf(profile.gender ?: "") }
        AlertDialog(
            onDismissRequest = { if (!isGenderSaving) showEditGenderDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = { Text("Select Gender", color = TriggerTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column {
                    if (genders.isEmpty()) {
                        // NO hardcoded fallback — show an error if the fetch
                        // failed or is still loading.
                        Text(
                            text = if (gendersFetchFailed) {
                                "Couldn't load genders from the server. Please check your connection and reopen this dialog."
                            } else {
                                "Loading genders..."
                            },
                            fontSize = 14.sp,
                            color = if (gendersFetchFailed) TriggerDanger else TriggerTextSecondary,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        genders.forEach { g ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedGender = g }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedGender == g,
                                    onClick = { selectedGender = g },
                                    colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = TriggerGreenAccent)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(g, fontSize = 15.sp, color = TriggerTextPrimary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedGender.isBlank()) return@Button
                        if (genders.isNotEmpty() && selectedGender !in genders) {
                            Toast.makeText(context, "Please select a valid gender from the list", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        isGenderSaving = true
                        coroutineScope.launch {
                            val payload = JSONObject().put("gender", selectedGender)
                            val result = AppServiceContainer.supabaseClient.invokeFunction("sync-user-profile", payload)
                            isGenderSaving = false
                            if (result is SupabaseResult.Success) {
                                UserRepository.updateGender(selectedGender)
                                Toast.makeText(context, "Gender saved", Toast.LENGTH_SHORT).show()
                                showEditGenderDialog = false
                            } else {
                                val err = (result as? SupabaseResult.Error)?.message ?: "Failed to save gender"
                                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isGenderSaving && selectedGender.isNotBlank() && genders.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent),
                    shape = RoundedCornerShape(8.dp)
                ) { SaveButtonContent(isSaving = isGenderSaving) }
            },
            dismissButton = {
                TextButton(onClick = { showEditGenderDialog = false }, enabled = !isGenderSaving) {
                    Text("Cancel", color = TriggerTextSecondary)
                }
            }
        )
    }

    // ------------------------------------------------------------------------
    // Country dropdown dialog
    // ------------------------------------------------------------------------
    if (showEditCountryDialog) {
        var searchQuery by remember { mutableStateOf("") }
        var selectedCountry by remember { mutableStateOf<Pair<String, String>?>(null) }
        val filtered = if (searchQuery.isBlank()) countries else countries.filter { it.second.contains(searchQuery, ignoreCase = true) || it.first.contains(searchQuery, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { if (!isCountrySaving) showEditCountryDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = { Text("Select Country", color = TriggerTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (countries.isEmpty()) {
                        // NO hardcoded fallback — show an error if the fetch failed.
                        Text(
                            text = if (countriesFetchFailed) {
                                "Couldn't load countries from the server. Please check your connection and reopen this dialog."
                            } else {
                                "Loading countries..."
                            },
                            fontSize = 14.sp,
                            color = if (countriesFetchFailed) TriggerDanger else TriggerTextSecondary,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search country...") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TriggerGreenAccent,
                                unfocusedBorderColor = Color(0xFFCFD8DC),
                                cursorColor = TriggerGreenAccent
                            ),
                            enabled = !isCountrySaving,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Scrollable country list
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (filtered.isEmpty()) {
                                Text(
                                    text = "No countries match \"$searchQuery\"",
                                    fontSize = 13.sp,
                                    color = TriggerTextSecondary,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            } else {
                                filtered.forEach { (code, name) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedCountry = Pair(code, name) }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selectedCountry?.first == code,
                                            onClick = { selectedCountry = Pair(code, name) },
                                            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = TriggerGreenAccent)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("$name ($code)", fontSize = 14.sp, color = TriggerTextPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cc = selectedCountry ?: return@Button
                        if (countries.isNotEmpty() && countries.none { it.first == cc.first }) {
                            Toast.makeText(context, "Please select a valid country from the list", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        isCountrySaving = true
                        coroutineScope.launch {
                            val payload = JSONObject().put("country_code", cc.first)
                            val result = AppServiceContainer.supabaseClient.invokeFunction("sync-user-profile", payload)
                            isCountrySaving = false
                            if (result is SupabaseResult.Success) {
                                UserRepository.updateCountry(cc.second, cc.first)
                                Toast.makeText(context, "Country saved", Toast.LENGTH_SHORT).show()
                                showEditCountryDialog = false
                            } else {
                                val err = (result as? SupabaseResult.Error)?.message ?: "Failed to save country"
                                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isCountrySaving && selectedCountry != null && countries.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent),
                    shape = RoundedCornerShape(8.dp)
                ) { SaveButtonContent(isSaving = isCountrySaving) }
            },
            dismissButton = {
                TextButton(onClick = { showEditCountryDialog = false }, enabled = !isCountrySaving) {
                    Text("Cancel", color = TriggerTextSecondary)
                }
            }
        )
    }

    // ------------------------------------------------------------------------
    // DOB date picker — enforces age >= 13 and <= 120 via SelectableDates.
    // ------------------------------------------------------------------------
    if (showDatePicker) {
        // Compute the min selectable date (120 years ago) and max selectable
        // date (13 years ago). Dates outside this range are greyed out.
        val calendar = java.util.Calendar.getInstance()
        val maxSelectable = calendar.clone() as java.util.Calendar
        maxSelectable.add(java.util.Calendar.YEAR, -MIN_DOB_AGE)
        val minSelectable = calendar.clone() as java.util.Calendar
        minSelectable.add(java.util.Calendar.YEAR, -MAX_DOB_AGE)

        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = profile.dob?.let {
                try { java.text.SimpleDateFormat("yyyy-MM-dd").parse(it)?.time } catch (_: Exception) { null }
            },
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    return utcTimeMillis <= maxSelectable.timeInMillis &&
                           utcTimeMillis >= minSelectable.timeInMillis
                }
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                Button(
                    onClick = {
                        val millis = datePickerState.selectedDateMillis
                        if (millis != null) {
                            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(millis))
                            // Client-side validation (defense in depth — server
                            // also validates via the edge function + DB CHECK)
                            val validationError = validateDob(dateStr)
                            if (validationError != null) {
                                Toast.makeText(context, validationError, Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            isDobSaving = true
                            coroutineScope.launch {
                                val payload = JSONObject().put("dob", dateStr)
                                val result = AppServiceContainer.supabaseClient.invokeFunction("sync-user-profile", payload)
                                isDobSaving = false
                                if (result is SupabaseResult.Success) {
                                    UserRepository.updateDob(dateStr)
                                    Toast.makeText(context, "Date of birth saved", Toast.LENGTH_SHORT).show()
                                    showDatePicker = false
                                } else {
                                    val err = (result as? SupabaseResult.Error)?.message ?: "Failed to save date of birth"
                                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !isDobSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent)
                ) { SaveButtonContent(isSaving = isDobSaving) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = TriggerTextSecondary)
                }
            }
        )
    }
}

// ============================================================================
// ProfileDetailItem — a single row in the profile card.
// When `onClick == null` the row is read-only (no edit icon, not clickable).
// ============================================================================
@Composable
fun ProfileDetailItem(
    icon: ImageVector,
    label: String,
    value: String,
    isValueGreen: Boolean,
    onClick: (() -> Unit)?,
    testTag: String = ""
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = TriggerGreenAccent,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(20.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TriggerTextSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = if (isValueGreen) TriggerFabGreen else TriggerTextPrimary
            )
        }

        // Only show the edit pencil when the row is editable
        if (onClick != null) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Edit $label",
                tint = TriggerTextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ============================================================================
// ProfileEditDialog — generic single-line text editor (used for Name).
// Supports an optional validator for live client-side validation.
// ============================================================================
@Composable
fun ProfileEditDialog(
    title: String,
    initialValue: String,
    isSaving: Boolean = false,
    prefix: String? = null,
    validator: ((String) -> String?)? = null,
    maxLength: Int = Int.MAX_VALUE,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var textValue by remember { mutableStateOf(initialValue) }
    val liveError = validator?.invoke(textValue)

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(text = title, color = TriggerTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = {
                        if (!isSaving && it.length <= maxLength) textValue = it
                    },
                    prefix = if (prefix != null) {
                        { Text(prefix, color = TriggerFabGreen, fontWeight = FontWeight.Bold) }
                    } else null,
                    singleLine = true,
                    isError = liveError != null && textValue.isNotBlank(),
                    supportingText = if (liveError != null && textValue.isNotBlank()) {
                        { Text(liveError, color = TriggerDanger, fontSize = 12.sp) }
                    } else null,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TriggerTextPrimary,
                        unfocusedTextColor = TriggerTextPrimary,
                        focusedBorderColor = if (liveError != null && textValue.isNotBlank()) TriggerDanger else TriggerGreenAccent,
                        unfocusedBorderColor = Color(0xFFCFD8DC),
                        cursorColor = TriggerGreenAccent
                    ),
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                if (maxLength != Int.MAX_VALUE) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${textValue.length}/$maxLength",
                        fontSize = 12.sp,
                        color = if (textValue.length >= maxLength) TriggerDanger else TriggerTextSecondary,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(textValue.trim()) },
                enabled = !isSaving && textValue.isNotBlank() && liveError == null,
                colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent),
                shape = RoundedCornerShape(8.dp)
            ) {
                SaveButtonContent(isSaving = isSaving)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel", color = TriggerTextSecondary)
            }
        }
    )
}

// ============================================================================
// AboutEditDialog — multi-line, 350 char max, with live counter.
// ============================================================================
@Composable
fun AboutEditDialog(
    initialValue: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var textValue by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(text = "About", color = TriggerTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { if (it.length <= MAX_ABOUT) textValue = it },
                    singleLine = false,
                    minLines = 3,
                    maxLines = 5,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TriggerTextPrimary,
                        unfocusedTextColor = TriggerTextPrimary,
                        focusedBorderColor = TriggerGreenAccent,
                        unfocusedBorderColor = Color(0xFFCFD8DC),
                        cursorColor = TriggerGreenAccent
                    ),
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${textValue.length}/$MAX_ABOUT",
                    fontSize = 12.sp,
                    color = if (textValue.length >= MAX_ABOUT) TriggerDanger else TriggerTextSecondary,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(textValue) },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent),
                shape = RoundedCornerShape(8.dp)
            ) {
                SaveButtonContent(isSaving = isSaving)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel", color = TriggerTextSecondary)
            }
        }
    )
}

// ============================================================================
// UsernameEditDialog — real-time availability check via the
// check-username-availability edge function (debounced 500ms).
// ============================================================================
@Composable
fun UsernameEditDialog(
    currentUsername: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var textValue by remember { mutableStateOf(currentUsername) }
    var availability by remember { mutableStateOf<UsernameAvailability>(UsernameAvailability.Idle) }

    val cleanUsername = textValue.replace("@", "").trim().lowercase()

    // Fast client-side validation mirroring the edge function rules.
    val localError: String? = when {
        cleanUsername.isEmpty() -> null
        cleanUsername.length < MIN_USERNAME -> "Must be at least $MIN_USERNAME characters"
        cleanUsername.length > MAX_USERNAME -> "Must be $MAX_USERNAME characters or fewer"
        !USERNAME_REGEX.matches(cleanUsername) ->
            "Only lowercase letters, numbers, underscores, dots"
        cleanUsername in RESERVED_USERNAMES -> "This username is reserved"
        else -> null
    }

    // Debounced server-side availability check.
    LaunchedEffect(cleanUsername) {
        availability = when {
            localError != null -> UsernameAvailability.Idle
            cleanUsername.isEmpty() -> UsernameAvailability.Idle
            cleanUsername == currentUsername -> UsernameAvailability.Available
            else -> {
                availability = UsernameAvailability.Checking
                delay(500)
                val payload = JSONObject().put("username", cleanUsername)
                when (val result = AppServiceContainer.supabaseClient
                    .invokeFunction("check-username-availability", payload)) {
                    is SupabaseResult.Success -> {
                        if (result.data.optBoolean("available", false)) {
                            UsernameAvailability.Available
                        } else {
                            UsernameAvailability.Taken
                        }
                    }
                    is SupabaseResult.Error -> UsernameAvailability.Error
                }
            }
        }
    }

    val canSave = !isSaving &&
        localError == null &&
        cleanUsername.isNotEmpty() &&
        availability == UsernameAvailability.Available

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(text = "Create username", color = TriggerTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { if (!isSaving) textValue = it.lowercase() },
                    prefix = { Text("@", color = TriggerFabGreen, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TriggerTextPrimary,
                        unfocusedTextColor = TriggerTextPrimary,
                        focusedBorderColor = TriggerGreenAccent,
                        unfocusedBorderColor = Color(0xFFCFD8DC),
                        cursorColor = TriggerGreenAccent
                    ),
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                when {
                    localError != null ->
                        Text(localError, color = TriggerDanger, fontSize = 12.sp)
                    availability == UsernameAvailability.Checking ->
                        Text("Checking...", color = TriggerTextSecondary, fontSize = 12.sp)
                    availability == UsernameAvailability.Available ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2196F3), // blue tick
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Available",
                                color = Color(0xFF2196F3),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    availability == UsernameAvailability.Taken ->
                        Text(
                            "$cleanUsername is already taken",
                            color = TriggerDanger,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    availability == UsernameAvailability.Error ->
                        Text("Unable to verify availability", color = TriggerTextSecondary, fontSize = 12.sp)
                    else -> Spacer(modifier = Modifier.height(1.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(cleanUsername) },
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent),
                shape = RoundedCornerShape(8.dp)
            ) {
                SaveButtonContent(isSaving = isSaving)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel", color = TriggerTextSecondary)
            }
        }
    )
}

// ============================================================================
// LinksEditDialog — list of {name, url} pairs with Add (max 3) and per-row
// delete. Stored as a JSON array string in UserProfile.links.
// ============================================================================
@Composable
fun LinksEditDialog(
    initialLinksJson: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val initialLinks = remember(initialLinksJson) {
        try {
            val arr = JSONArray(initialLinksJson)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                LinkItem(obj.optString("name"), obj.optString("url"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    var links by remember { mutableStateOf(initialLinks) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    // Use the shared validateUrl function for thorough URL validation
    // (checks protocol, hostname, length).
    val urlError = if (newUrl.isEmpty()) null else validateUrl(newUrl)
    val nameError = if (newName.isEmpty()) null else if (newName.length > MAX_LINK_NAME) "Name too long (max $MAX_LINK_NAME)" else null
    val canAdd = !isSaving &&
        links.size < MAX_LINKS &&
        newName.isNotBlank() &&
        newName.length <= MAX_LINK_NAME &&
        urlError == null &&
        newUrl.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(text = "Links", color = TriggerTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (links.isEmpty()) {
                    Text(
                        text = "No links yet. Add up to $MAX_LINKS.",
                        fontSize = 13.sp,
                        color = TriggerTextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    links.forEachIndexed { idx, link ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = link.name.ifBlank { "Untitled" },
                                    fontWeight = FontWeight.Medium,
                                    color = TriggerTextPrimary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = link.url,
                                    color = TriggerTextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    links = links.toMutableList().also { it.removeAt(idx) }
                                },
                                enabled = !isSaving
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete link",
                                    tint = TriggerDanger
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = TriggerDivider, thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Add new",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TriggerTextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = newName,
                    onValueChange = { if (it.length <= MAX_LINK_NAME) newName = it },
                    label = { Text("Name (max $MAX_LINK_NAME)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TriggerTextPrimary,
                        unfocusedTextColor = TriggerTextPrimary,
                        focusedBorderColor = TriggerGreenAccent,
                        unfocusedBorderColor = Color(0xFFCFD8DC),
                        cursorColor = TriggerGreenAccent
                    ),
                    enabled = !isSaving && links.size < MAX_LINKS,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    label = { Text("URL (https://...)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    isError = urlError != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TriggerTextPrimary,
                        unfocusedTextColor = TriggerTextPrimary,
                        focusedBorderColor = TriggerGreenAccent,
                        unfocusedBorderColor = Color(0xFFCFD8DC),
                        cursorColor = TriggerGreenAccent
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    enabled = !isSaving && links.size < MAX_LINKS,
                    modifier = Modifier.fillMaxWidth()
                )
                if (urlError != null) {
                    Text(
                        text = urlError,
                        color = TriggerDanger,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            if (canAdd) {
                                links = links + LinkItem(
                                    name = newName.trim().take(MAX_LINK_NAME),
                                    url = newUrl.trim()
                                )
                                newName = ""
                                newUrl = ""
                            }
                        },
                        enabled = canAdd,
                        colors = ButtonDefaults.buttonColors(containerColor = TriggerFabGreen),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add link",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add", color = Color.White)
                    }
                }

                if (links.size >= MAX_LINKS) {
                    Text(
                        text = "Maximum $MAX_LINKS links reached.",
                        fontSize = 12.sp,
                        color = TriggerTextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val arr = JSONArray()
                    links.forEach { l ->
                        arr.put(JSONObject().put("name", l.name).put("url", l.url))
                    }
                    onConfirm(arr.toString())
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = TriggerGreenAccent),
                shape = RoundedCornerShape(8.dp)
            ) {
                SaveButtonContent(isSaving = isSaving)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel", color = TriggerTextSecondary)
            }
        }
    )
}

// ============================================================================
// SaveButtonContent — shared "Save / Saving..." button body with a small
// CircularProgressIndicator while saving.
// ============================================================================
@Composable
private fun SaveButtonContent(isSaving: Boolean) {
    if (isSaving) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
            color = Color.White
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Saving...", color = Color.White, fontWeight = FontWeight.Bold)
    } else {
        Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
    }
}
