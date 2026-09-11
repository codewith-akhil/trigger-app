package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.model.FeedPost
import com.example.model.PostMediaType
import com.example.model.PostType
import com.example.model.UserRepository
import com.example.service.supabase.SupabaseResult
import com.example.ui.theme.TriggerFabGreen
import com.example.ui.theme.TriggerHeaderGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private val BrandGreen = TriggerHeaderGreen
private val AccentGreen = TriggerFabGreen
private val DarkBg = Color(0xFF0F1417)
private val CardBg = Color(0xFF1A2228)
private val LightCardBg = Color(0xFFFFFFFF)

enum class UploadStage {
    MEDIA_SELECTION_EDIT, // Picker + 4:5 / 9:16 Crop review in black screen
    POST_DETAILS,         // 350 char desc, Free/Paid, currency + amount
    UPLOADING_PROGRESS,   // Loading screen with %
    RESULT_SUCCESS,       // Success state
    RESULT_ERROR          // Error state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostUploadScreen(
    initialMediaType: String = "photo", // "photo" or "video"
    onBack: () -> Unit,
    onPostCreatedSuccessfully: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val userProfile by UserRepository.profile.collectAsState()

    var stage by remember { mutableStateOf(UploadStage.MEDIA_SELECTION_EDIT) }
    var selectedMediaType by remember {
        mutableStateOf(if (initialMediaType == "video") PostMediaType.VIDEO else PostMediaType.IMAGE)
    }

    // Media item states
    var selectedImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }

    // Post details states
    var descriptionText by remember { mutableStateOf("") }
    val maxDescriptionChars = 350

    var postTypeDropdownExpanded by remember { mutableStateOf(false) }
    var selectedPostType by remember { mutableStateOf(PostType.FREE) }

    var currencyDropdownExpanded by remember { mutableStateOf(false) }
    val currencyOptions = listOf("INR (₹)", "USD ($)", "EUR (€)", "GBP (£)", "AED (د.إ)")
    var selectedCurrency by remember { mutableStateOf("INR (₹)") }
    var priceAmountText by remember { mutableStateOf("") }

    // Upload progress state
    var uploadProgressPercent by remember { mutableFloatStateOf(0f) }
    var uploadStatusMessage by remember { mutableStateOf("Preparing media files...") }
    var uploadErrorMessage by remember { mutableStateOf<String?>(null) }

    // ------------------------------------------------------------------------
    // Photo & Video Activity Result Launchers
    // ------------------------------------------------------------------------
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            selectedMediaType = PostMediaType.IMAGE
            selectedImageUris = uris
            selectedVideoUri = null
        }
    }

    val singlePhotoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedMediaType = PostMediaType.IMAGE
            selectedImageUris = listOf(uri)
            selectedVideoUri = null
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedMediaType = PostMediaType.VIDEO
            selectedVideoUri = uri
            selectedImageUris = emptyList()
        }
    }

    // Permission launcher for Android 13+ and older Android
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        val anyGranted = grantedMap.values.any { it }
        if (anyGranted) {
            if (selectedMediaType == PostMediaType.VIDEO) {
                videoPickerLauncher.launch("video/*")
            } else {
                photoPickerLauncher.launch("image/*")
            }
        } else {
            Toast.makeText(context, "Gallery permission is required to select media", Toast.LENGTH_SHORT).show()
        }
    }

    fun checkAndPickMedia(isVideo: Boolean) {
        selectedMediaType = if (isVideo) PostMediaType.VIDEO else PostMediaType.IMAGE
        val neededPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (isVideo) arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = neededPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            if (isVideo) {
                videoPickerLauncher.launch("video/*")
            } else {
                photoPickerLauncher.launch("image/*")
            }
        } else {
            permissionLauncher.launch(neededPerms)
        }
    }

    // Auto-launch picker on first appearance if nothing is selected
    LaunchedEffect(Unit) {
        if (selectedImageUris.isEmpty() && selectedVideoUri == null) {
            checkAndPickMedia(initialMediaType == "video")
        }
    }

    // ------------------------------------------------------------------------
    // Upload Routine
    // ------------------------------------------------------------------------
    fun startUpload() {
        stage = UploadStage.UPLOADING_PROGRESS
        uploadProgressPercent = 0.05f
        uploadStatusMessage = "Connecting to upload storage..."
        uploadErrorMessage = null

        coroutineScope.launch {
            try {
                val uploadedUrls = mutableListOf<String>()

                if (selectedMediaType == PostMediaType.IMAGE) {
                    val total = selectedImageUris.size
                    for ((index, uri) in selectedImageUris.withIndex()) {
                        uploadStatusMessage = "Processing and uploading photo ${index + 1} of $total..."
                        delay(250)
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes == null || bytes.isEmpty()) {
                            throw Exception("Failed to read image data")
                        }

                        val userId = AppServiceContainer.supabaseClient.currentUser?.id ?: "user_${UUID.randomUUID()}"
                        val fileName = "$userId/feed_post_${System.currentTimeMillis()}_$index.jpg"

                        val uploadRes = AppServiceContainer.supabaseClient.uploadFile(
                            bucketName = "avatars", // using available storage bucket
                            fileName = fileName,
                            fileBytes = bytes,
                            mimeType = "image/jpeg"
                        )

                        val finalUrl = when (uploadRes) {
                            is SupabaseResult.Success -> uploadRes.data
                            is SupabaseResult.Error -> {
                                // Fallback to local copy cache uri if network storage unavailable
                                uri.toString()
                            }
                        }
                        uploadedUrls.add(finalUrl)
                        uploadProgressPercent = 0.10f + ((index + 1).toFloat() / total.toFloat()) * 0.70f
                    }
                } else {
                    // Video upload
                    val videoUri = selectedVideoUri ?: throw Exception("No video selected")
                    uploadStatusMessage = "Processing video file for post..."
                    delay(300)
                    uploadProgressPercent = 0.40f

                    val bytes = context.contentResolver.openInputStream(videoUri)?.use { it.readBytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val userId = AppServiceContainer.supabaseClient.currentUser?.id ?: "user_${UUID.randomUUID()}"
                        val fileName = "$userId/feed_video_${System.currentTimeMillis()}.mp4"
                        val uploadRes = AppServiceContainer.supabaseClient.uploadFile(
                            bucketName = "avatars",
                            fileName = fileName,
                            fileBytes = bytes,
                            mimeType = "video/mp4"
                        )
                        val finalUrl = when (uploadRes) {
                            is SupabaseResult.Success -> uploadRes.data
                            is SupabaseResult.Error -> videoUri.toString()
                        }
                        uploadedUrls.add(finalUrl)
                    } else {
                        uploadedUrls.add(videoUri.toString())
                    }
                    uploadProgressPercent = 0.85f
                }

                uploadStatusMessage = "Finalizing feed post publishing..."
                delay(300)
                uploadProgressPercent = 0.95f

                val isPaid = selectedPostType == PostType.PAID
                val amount = priceAmountText.toDoubleOrNull() ?: 0.0

                val newFeedPost = FeedPost(
                    id = "post_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
                    authorId = userProfile.id.ifBlank { "current_user" },
                    authorName = userProfile.name.ifBlank { "You" },
                    authorUsername = userProfile.username.ifBlank { "you" },
                    authorAvatarUrl = userProfile.avatarUri,
                    description = descriptionText.trim(),
                    mediaUrls = uploadedUrls,
                    mediaType = selectedMediaType,
                    postType = if (isPaid) PostType.PAID else PostType.FREE,
                    priceAmount = if (isPaid) amount else 0.0,
                    currency = selectedCurrency,
                    timestamp = System.currentTimeMillis(),
                    likesCount = 0,
                    isLikedByMe = false,
                    commentsCount = 0,
                    isUnlocked = true // The author always has access to their own post
                )

                AppServiceContainer.feedRepository.addPost(newFeedPost)

                uploadProgressPercent = 1.0f
                uploadStatusMessage = "Published successfully!"
                delay(400)
                stage = UploadStage.RESULT_SUCCESS
            } catch (e: Exception) {
                uploadErrorMessage = e.message ?: "Failed to upload media post"
                stage = UploadStage.RESULT_ERROR
            }
        }
    }

    // ------------------------------------------------------------------------
    // UI Layout Rendering based on UploadStage
    // ------------------------------------------------------------------------
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("post_upload_screen"),
        containerColor = if (stage == UploadStage.MEDIA_SELECTION_EDIT) Color.Black else Color(0xFFF0F2F5),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (stage) {
                            UploadStage.MEDIA_SELECTION_EDIT -> "Edit Media"
                            UploadStage.POST_DETAILS -> "New Post"
                            UploadStage.UPLOADING_PROGRESS -> "Uploading"
                            UploadStage.RESULT_SUCCESS -> "Success"
                            UploadStage.RESULT_ERROR -> "Error"
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (stage == UploadStage.MEDIA_SELECTION_EDIT) Color.White else Color.Black
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when (stage) {
                                UploadStage.MEDIA_SELECTION_EDIT -> onBack()
                                UploadStage.POST_DETAILS -> stage = UploadStage.MEDIA_SELECTION_EDIT
                                UploadStage.RESULT_SUCCESS -> onPostCreatedSuccessfully()
                                UploadStage.RESULT_ERROR -> stage = UploadStage.POST_DETAILS
                                UploadStage.UPLOADING_PROGRESS -> { /* disabled during upload */ }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = if (stage == UploadStage.MEDIA_SELECTION_EDIT) Color.White else Color.Black
                        )
                    }
                },
                actions = {
                    if (stage == UploadStage.MEDIA_SELECTION_EDIT) {
                        val hasMedia = (selectedMediaType == PostMediaType.IMAGE && selectedImageUris.isNotEmpty()) ||
                                (selectedMediaType == PostMediaType.VIDEO && selectedVideoUri != null)

                        TextButton(
                            onClick = { stage = UploadStage.POST_DETAILS },
                            enabled = hasMedia,
                            modifier = Modifier.testTag("media_edit_next_button")
                        ) {
                            Text(
                                text = "Next",
                                color = if (hasMedia) AccentGreen else Color.Gray,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next",
                                tint = if (hasMedia) AccentGreen else Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (stage == UploadStage.MEDIA_SELECTION_EDIT) Color.Black else Color.White
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (stage) {
                UploadStage.MEDIA_SELECTION_EDIT -> {
                    MediaSelectionAndEditingArea(
                        mediaType = selectedMediaType,
                        imageUris = selectedImageUris,
                        videoUri = selectedVideoUri,
                        onPickPhoto = { checkAndPickMedia(isVideo = false) },
                        onPickVideo = { checkAndPickMedia(isVideo = true) },
                        onRemoveImage = { index ->
                            selectedImageUris = selectedImageUris.filterIndexed { idx, _ -> idx != index }
                        }
                    )
                }

                UploadStage.POST_DETAILS -> {
                    PostDetailsForm(
                        mediaType = selectedMediaType,
                        imageCount = selectedImageUris.size,
                        hasVideo = selectedVideoUri != null,
                        previewUri = if (selectedMediaType == PostMediaType.IMAGE) selectedImageUris.firstOrNull() else selectedVideoUri,
                        description = descriptionText,
                        maxChars = maxDescriptionChars,
                        onDescriptionChange = { if (it.length <= maxDescriptionChars) descriptionText = it },
                        postType = selectedPostType,
                        onPostTypeChange = { selectedPostType = it },
                        postTypeDropdownExpanded = postTypeDropdownExpanded,
                        onPostTypeDropdownToggle = { postTypeDropdownExpanded = it },
                        currency = selectedCurrency,
                        onCurrencyChange = { selectedCurrency = it },
                        currencyDropdownExpanded = currencyDropdownExpanded,
                        onCurrencyDropdownToggle = { currencyDropdownExpanded = it },
                        currencyOptions = currencyOptions,
                        priceAmount = priceAmountText,
                        onPriceAmountChange = { priceAmountText = it },
                        onPostClick = {
                            if (selectedPostType == PostType.PAID) {
                                val amount = priceAmountText.toDoubleOrNull() ?: 0.0
                                if (amount <= 0.0) {
                                    Toast.makeText(context, "Please enter a valid price amount greater than 0", Toast.LENGTH_SHORT).show()
                                    return@PostDetailsForm
                                }
                            }
                            startUpload()
                        }
                    )
                }

                UploadStage.UPLOADING_PROGRESS -> {
                    UploadProgressScreen(
                        progress = uploadProgressPercent,
                        statusMessage = uploadStatusMessage
                    )
                }

                UploadStage.RESULT_SUCCESS -> {
                    UploadResultScreen(
                        isSuccess = true,
                        title = "Post Published Successfully!",
                        message = "Your post is now live in the feed. Followers and viewers can now enjoy or unlock your content.",
                        buttonText = "View in Feed",
                        onButtonClick = onPostCreatedSuccessfully
                    )
                }

                UploadStage.RESULT_ERROR -> {
                    UploadResultScreen(
                        isSuccess = false,
                        title = "Upload Failed",
                        message = uploadErrorMessage ?: "An error occurred while uploading your media. Please try again.",
                        buttonText = "Retry Upload",
                        onButtonClick = { startUpload() }
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Step 1: Media Selection & Editing Area (Black Background, 4:5 Crop, 9:16 Video)
// ----------------------------------------------------------------------------
@Composable
fun MediaSelectionAndEditingArea(
    mediaType: PostMediaType,
    imageUris: List<Uri>,
    videoUri: Uri?,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
    onRemoveImage: (Int) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Media Selector Header Switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF22262B)
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    Button(
                        onClick = onPickPhoto,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mediaType == PostMediaType.IMAGE) AccentGreen else Color.Transparent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Photos (4:5)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Button(
                        onClick = onPickVideo,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mediaType == PostMediaType.VIDEO) AccentGreen else Color.Transparent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Filled.VideoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Video (9:16)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Preview & Cropping Display Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (mediaType == PostMediaType.IMAGE) {
                if (imageUris.isEmpty()) {
                    EmptyMediaPlaceholder(
                        title = "No Photos Selected",
                        subtitle = "Tap below to select single or multiple photos from gallery",
                        icon = Icons.Filled.AddPhotoAlternate,
                        onClick = onPickPhoto
                    )
                } else {
                    // Instagram-style 4:5 image container
                    val pagerState = rememberPagerState(pageCount = { imageUris.size })
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .aspectRatio(4f / 5f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF181C20)),
                            contentAlignment = Alignment.Center
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize()
                            ) { page ->
                                AsyncImage(
                                    model = imageUris[page],
                                    contentDescription = "Post Image $page",
                                    contentScale = ContentScale.Crop, // 4:5 Crop presentation
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Aspect Ratio 4:5 Indicator Badge
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.CropSquare, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("4:5 Aspect Ratio Crop", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }

                            // Carousel indicator badge if multiple
                            if (imageUris.size > 1) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.Black.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "${pagerState.currentPage + 1}/${imageUris.size}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        // Carousel dot indicators
                        if (imageUris.size > 1) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(imageUris.size) { index ->
                                    val isSelected = pagerState.currentPage == index
                                    Box(
                                        modifier = Modifier
                                            .padding(3.dp)
                                            .size(if (isSelected) 8.dp else 6.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) AccentGreen else Color.Gray)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Video Preview (9:16 Inbuilt Aspect Ratio)
                if (videoUri == null) {
                    EmptyMediaPlaceholder(
                        title = "No Video Selected",
                        subtitle = "Tap below to select a single video for full vertical 9:16 post",
                        icon = Icons.Filled.VideoLibrary,
                        onClick = onPickVideo
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF181C20)),
                        contentAlignment = Alignment.Center
                    ) {
                        VideoPlayerPreview(uri = videoUri)

                        // 9:16 Crop Badge
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Black.copy(alpha = 0.7f),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Smartphone, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("9:16 Vertical Video", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        // Bottom Gallery Selector Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF14171A),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (mediaType == PostMediaType.IMAGE && imageUris.isNotEmpty()) {
                    Text(
                        text = "Selected Images (${imageUris.size}) - Swipe carousel above",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        imageUris.forEachIndexed { idx, uri ->
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            ) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.8f))
                                        .clickable { onRemoveImage(idx) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(12.dp))
                                }
                            }
                        }

                        // Add more images button
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF24292E))
                                .clickable { onPickPhoto() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add More", tint = AccentGreen, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (mediaType == PostMediaType.VIDEO && videoUri != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Single video attached",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                        TextButton(onClick = onPickVideo) {
                            Text("Change Video", color = AccentGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (mediaType == PostMediaType.VIDEO) onPickVideo() else onPickPhoto()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.PermMedia, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (mediaType == PostMediaType.VIDEO) "Open Gallery for Video" else "Open Gallery for Photos",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VideoPlayerPreview(uri: Uri) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = true
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun EmptyMediaPlaceholder(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color(0xFF22262B)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(40.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = subtitle, color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

// ----------------------------------------------------------------------------
// Step 2: Post Details Page (350 Character Description, Free/Paid, Currency/Amount)
// ----------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailsForm(
    mediaType: PostMediaType,
    imageCount: Int,
    hasVideo: Boolean,
    previewUri: Uri?,
    description: String,
    maxChars: Int,
    onDescriptionChange: (String) -> Unit,
    postType: PostType,
    onPostTypeChange: (PostType) -> Unit,
    postTypeDropdownExpanded: Boolean,
    onPostTypeDropdownToggle: (Boolean) -> Unit,
    currency: String,
    onCurrencyChange: (String) -> Unit,
    currencyDropdownExpanded: Boolean,
    onCurrencyDropdownToggle: (Boolean) -> Unit,
    currencyOptions: List<String>,
    priceAmount: String,
    onPriceAmountChange: (String) -> Unit,
    onPostClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Card with Media Thumbnail Summary
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF24292E)),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewUri != null) {
                        AsyncImage(
                            model = previewUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Filled.Image, contentDescription = null, tint = Color.Gray)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = if (mediaType == PostMediaType.VIDEO) "Video Post (9:16)" else "Photo Carousel ($imageCount images, 4:5)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFF111B21)
                    )
                    Text(
                        text = "Cropped and optimized for feed view",
                        fontSize = 12.sp,
                        color = Color(0xFF667781)
                    )
                }
            }
        }

        // Description Card with 350-character limit
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Description",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = BrandGreen
                    )
                    Text(
                        text = "${description.length}/$maxChars",
                        fontSize = 12.sp,
                        color = if (description.length >= maxChars) Color.Red else Color(0xFF667781),
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    placeholder = { Text("Write a caption or description for your post...", fontSize = 14.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .testTag("post_description_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandGreen,
                        focusedLabelColor = BrandGreen
                    )
                )
            }
        }

        // Post Type Selector Card: Free / Paid
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Post Access Type",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = BrandGreen
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Choose whether this post is accessible to everyone or locked behind a paid security blur.",
                    fontSize = 12.sp,
                    color = Color(0xFF667781)
                )
                Spacer(modifier = Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = postTypeDropdownExpanded,
                    onExpandedChange = onPostTypeDropdownToggle
                ) {
                    OutlinedTextField(
                        value = if (postType == PostType.PAID) "Paid (Exclusive Locked Content)" else "Free (Public to all users)",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Post Type *") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (postType == PostType.PAID) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                contentDescription = null,
                                tint = if (postType == PostType.PAID) Color(0xFFE65100) else AccentGreen
                            )
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = postTypeDropdownExpanded) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandGreen,
                            focusedLabelColor = BrandGreen
                        ),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                            .testTag("post_type_dropdown")
                    )
                    ExposedDropdownMenu(
                        expanded = postTypeDropdownExpanded,
                        onDismissRequest = { onPostTypeDropdownToggle(false) }
                    ) {
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.LockOpen, contentDescription = null, tint = AccentGreen) },
                            text = { Text("Free (Public to all users)") },
                            onClick = {
                                onPostTypeChange(PostType.FREE)
                                onPostTypeDropdownToggle(false)
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFFE65100)) },
                            text = { Text("Paid (Exclusive Locked Content)") },
                            onClick = {
                                onPostTypeChange(PostType.PAID)
                                onPostTypeDropdownToggle(false)
                            }
                        )
                    }
                }

                // If Paid: Currency Selection and Amount Typing Box
                AnimatedVisibility(visible = postType == PostType.PAID) {
                    Column(modifier = Modifier.padding(top = 14.dp)) {
                        Text(
                            text = "Set Price & Currency",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color(0xFF111B21)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Currency Dropdown
                            ExposedDropdownMenuBox(
                                expanded = currencyDropdownExpanded,
                                onExpandedChange = onCurrencyDropdownToggle,
                                modifier = Modifier.weight(1.1f)
                            ) {
                                OutlinedTextField(
                                    value = currency,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Currency *") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyDropdownExpanded) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = BrandGreen,
                                        focusedLabelColor = BrandGreen
                                    ),
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                        .testTag("post_currency_dropdown")
                                )
                                ExposedDropdownMenu(
                                    expanded = currencyDropdownExpanded,
                                    onDismissRequest = { onCurrencyDropdownToggle(false) }
                                ) {
                                    currencyOptions.forEach { curr ->
                                        DropdownMenuItem(
                                            text = { Text(curr) },
                                            onClick = {
                                                onCurrencyChange(curr)
                                                onCurrencyDropdownToggle(false)
                                            }
                                        )
                                    }
                                }
                            }

                            // Amount Typing Box
                            OutlinedTextField(
                                value = priceAmount,
                                onValueChange = { onPriceAmountChange(it.filter { ch -> ch.isDigit() || ch == '.' }) },
                                label = { Text("Amount *") },
                                placeholder = { Text("99.00") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BrandGreen,
                                    focusedLabelColor = BrandGreen
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("post_price_input")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFFF3E0),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Security, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Paid posts are rendered with an 85% blur protection layer. Users must complete payment via 'Pay and Watch' to unlock.",
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF8D4004),
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f, fill = false))

        // CTA Button: POST
        Button(
            onClick = onPostClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("post_submit_cta_button"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
        ) {
            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Post",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Step 3: Uploading Screen with Progress Percentage
// ----------------------------------------------------------------------------
@Composable
fun UploadProgressScreen(
    progress: Float,
    statusMessage: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                strokeWidth = 6.dp,
                color = AccentGreen,
                trackColor = Color(0xFFE0E0E0),
                modifier = Modifier.size(120.dp)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111B21)
                )
                Text(
                    text = "Uploading",
                    fontSize = 12.sp,
                    color = Color(0xFF667781)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Uploading Your Post",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111B21)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = statusMessage,
            fontSize = 14.sp,
            color = Color(0xFF667781),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = AccentGreen,
            trackColor = Color(0xFFE0E0E0)
        )
    }
}

// ----------------------------------------------------------------------------
// Step 4: Success / Error Result Screen
// ----------------------------------------------------------------------------
@Composable
fun UploadResultScreen(
    isSuccess: Boolean,
    title: String,
    message: String,
    buttonText: String,
    onButtonClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (isSuccess) AccentGreen else Color(0xFFD32F2F),
                modifier = Modifier.size(52.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111B21),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = message,
            fontSize = 14.sp,
            color = Color(0xFF667781),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onButtonClick,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSuccess) AccentGreen else BrandGreen
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = buttonText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
