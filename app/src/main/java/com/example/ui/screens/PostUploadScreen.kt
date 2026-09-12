package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.di.AppServiceContainer
import com.example.model.CurrencyOption
import com.example.model.FeedPost
import com.example.model.PostMediaType
import com.example.model.PostType
import com.example.ui.theme.TriggerFabGreen
import com.example.ui.theme.TriggerHeaderGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val BrandGreen = TriggerHeaderGreen
private val AccentGreen = TriggerFabGreen

private const val DESCRIPTION_MAX = 300

private enum class UploadStage {
    CROP,            // user is cropping the picked media
    DETAILS,         // description / type / amount form
    UPLOADING,       // backend create → media upload → publish (loading screen)
    RESULT_SUCCESS,
    RESULT_ERROR
}

/**
 * REAL backend post upload: pick → crop (4:5 photo / 9:16 video) → details
 * → [create draft on backend] → [upload media to private storage] →
 * [publish]. Instagram-style drafts are saved on the backend (`feed_posts`
 * status = draft) and resumable from the feed's Drafts strip.
 *
 * draftId != null resumes an existing draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostUploadScreen(
    initialMediaType: String,
    draftId: String? = null,
    initialMediaUri: Uri? = null,
    onBack: () -> Unit,
    onPostCreatedSuccessfully: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val feedRepository = AppServiceContainer.feedRepository

    val isVideoPost = initialMediaType == "video"
    val targetAspect = if (isVideoPost) 9f / 16f else 4f / 5f

    var stage by remember { mutableStateOf(UploadStage.CROP) }

    // picked + cropped media
    var pickedUri by remember { mutableStateOf<Uri?>(initialMediaUri) }
    var croppedFile by remember { mutableStateOf<File?>(null) }
    var croppedDims by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // draft state
    var backendPostId by remember { mutableStateOf<String?>(draftId) }
    var resumedDraft by remember { mutableStateOf<FeedPost?>(null) }
    var draftMediaReplaced by remember { mutableStateOf(draftId == null) }

    // details form
    var description by remember { mutableStateOf("") }
    var selectedPostType by remember { mutableStateOf<PostType?>(null) }
    var priceAmountText by remember { mutableStateOf("") }
    var selectedCurrency by remember { mutableStateOf<CurrencyOption?>(null) }
    var currencyDropdownOpen by remember { mutableStateOf(false) }

    // upload progress
    var uploadStatusMessage by remember { mutableStateOf("") }
    var uploadProgressPercent by remember { mutableStateOf(0f) }
    var uploadErrorMessage by remember { mutableStateOf<String?>(null) }

    var autoPicked by remember { mutableStateOf(initialMediaUri != null) }

    val visualMediaPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            croppedFile = null
            croppedDims = null
            draftMediaReplaced = true
            stage = UploadStage.CROP
        } else if (croppedFile == null && backendPostId == null && pickedUri == null) {
            onBack() // nothing selected and nothing to resume
        } else {
            stage = UploadStage.DETAILS
        }
    }

    fun pickMedia() {
        val request = if (isVideoPost) {
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        } else {
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        }
        visualMediaPickerLauncher.launch(request)
    }

    // Resume an existing draft: prefill from repository, use its media
    LaunchedEffect(draftId) {
        if (draftId != null) {
            feedRepository.refreshDrafts()
            val draft = feedRepository.drafts.value.find { it.id == draftId }
            resumedDraft = draft
            backendPostId = draftId
            draftMediaReplaced = false
            if (draft != null) {
                description = draft.description
                selectedPostType = draft.postType
                if (draft.postType == PostType.PAID) {
                    priceAmountText = if (draft.priceAmount % 1.0 == 0.0)
                        draft.priceAmount.toInt().toString() else draft.priceAmount.toString()
                    feedRepository.loadCurrencies()
                    selectedCurrency = feedRepository.currencies.value.find { it.code == draft.currency }
                }
                pickedUri = draft.mediaUrls.firstOrNull()?.let { Uri.parse(it) }
                stage = UploadStage.DETAILS
            }
        }
    }

    LaunchedEffect(Unit) {
        feedRepository.loadCurrencies()
        if (draftId == null && pickedUri == null && !autoPicked) {
            autoPicked = true
            pickMedia()
        }
    }

    // --------------------------------------------------------------------
    // Backend publish pipeline
    // --------------------------------------------------------------------
    fun readBytesQuiet(fileOrUri: Any): ByteArray? = try {
        when (fileOrUri) {
            is File -> fileOrUri.readBytes()
            is Uri -> context.contentResolver.openInputStream(fileOrUri)?.use { it.readBytes() }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    fun startBackendUpload() {
        val postType = selectedPostType ?: run {
            Toast.makeText(context, "Choose Free or Paid first", Toast.LENGTH_SHORT).show()
            return
        }
        val amount = priceAmountText.toDoubleOrNull() ?: 0.0
        if (postType == PostType.PAID) {
            if (amount <= 0.0) {
                Toast.makeText(context, "Enter an amount greater than zero", Toast.LENGTH_SHORT).show()
                return
            }
            if (selectedCurrency == null) {
                Toast.makeText(context, "Choose a currency", Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (croppedFile == null && draftMediaReplaced) {
            Toast.makeText(context, "Crop your media first", Toast.LENGTH_SHORT).show()
            stage = UploadStage.CROP
            return
        }
        if (croppedFile == null && !draftMediaReplaced && pickedUri == null) {
            Toast.makeText(context, "Add media for the post", Toast.LENGTH_SHORT).show()
            return
        }

        stage = UploadStage.UPLOADING
        uploadProgressPercent = 0.04f
        uploadStatusMessage = if (backendPostId == null) "Creating your post on the server…" else "Saving draft changes…"
        uploadErrorMessage = null

        coroutineScope.launch {
            try {
                val currency = selectedCurrency?.code ?: "INR"
                val postTypeLower = postType.name.lowercase()

                // 1) Ensure a backend post row exists (id is backend-generated)
                val postId: String
                if (backendPostId == null) {
                    uploadStatusMessage = "Creating your post on the server…"
                    when (val created = feedRepository.createPost(
                        description = description,
                        mediaType = if (isVideoPost) PostMediaType.VIDEO else PostMediaType.IMAGE,
                        postType = postType,
                        priceAmount = amount,
                        currency = currency
                    )) {
                        is com.example.service.FeedResultWithId.Success -> {
                            postId = created.postId
                            backendPostId = postId
                        }
                        is com.example.service.FeedResultWithId.BackendMissing -> {
                            throw Exception(created.detail)
                        }
                        is com.example.service.FeedResultWithId.Error -> throw Exception(created.message)
                    }
                } else {
                    postId = backendPostId!!
                    when (val saved = feedRepository.saveDraft(
                        postId = postId,
                        description = description,
                        postType = postType,
                        priceAmount = amount,
                        currency = currency
                    )) {
                        is com.example.service.FeedResult.Error -> throw Exception(saved.message)
                        is com.example.service.FeedResult.BackendMissing -> throw Exception(saved.detail)
                        else -> {}
                    }
                }
                uploadProgressPercent = 0.15f

                // 2) Upload media (only when the user replaced it)
                var storagePath: String? = null
                if (draftMediaReplaced) {
                    uploadStatusMessage = "Uploading ${if (isVideoPost) "video" else "photo"} to secure storage…"
                    val bytes = croppedFile?.let { readBytesQuiet(it) }
                        ?: pickedUri?.let { readBytesQuiet(it) }
                        ?: throw Exception("Failed to read media data")
                    if (bytes.isEmpty()) throw Exception("Media file is empty")

                    val mime = if (isVideoPost) "video/mp4" else "image/jpeg"
                    when (val up = feedRepository.uploadFeedMedia(
                        postId = postId,
                        position = 0,
                        bytes = bytes,
                        mimeType = mime
                    )) {
                        is com.example.service.FeedResultWithPath.Success -> storagePath = up.storagePath
                        is com.example.service.FeedResultWithPath.BackendMissing -> throw Exception(up.detail)
                        is com.example.service.FeedResultWithPath.Error -> throw Exception(up.message)
                    }
                    uploadProgressPercent = 0.65f
                }

                // 3) Attach media metadata rows
                if (storagePath != null) {
                    uploadStatusMessage = "Linking media to your post…"
                    when (val att = feedRepository.attachMediaMetadata(
                        postId = postId,
                        items = listOf(Triple(storagePath!!, mediaMime(isVideoPost), croppedDims)),
                        durationMs = null
                    )) {
                        is com.example.service.FeedResult.Error -> throw Exception(att.message)
                        is com.example.service.FeedResult.BackendMissing -> throw Exception(att.detail)
                        else -> {}
                    }
                }
                uploadProgressPercent = 0.85f

                // 4) Publish
                uploadStatusMessage = "Publishing your post…"
                when (val pub = feedRepository.publishPost(
                    postId = postId,
                    description = description,
                    postType = postType,
                    priceAmount = amount,
                    currency = currency
                )) {
                    is com.example.service.FeedResult.Success -> {}
                    is com.example.service.FeedResult.BackendMissing -> throw Exception(pub.detail)
                    is com.example.service.FeedResult.Error -> throw Exception(pub.message)
                }

                uploadProgressPercent = 1f
                uploadStatusMessage = "Published!"
                feedRepository.refresh()
                feedRepository.refreshDrafts()
                delay(450)
                stage = UploadStage.RESULT_SUCCESS
            } catch (e: Exception) {
                uploadErrorMessage = e.message ?: "Upload failed"
                stage = UploadStage.RESULT_ERROR
            }
        }
    }

    fun saveAsDraft() {
        val postType = selectedPostType ?: PostType.FREE
        val amount = priceAmountText.toDoubleOrNull() ?: 0.0
        if (postType == PostType.PAID && amount <= 0.0) {
            Toast.makeText(context, "Paid drafts need an amount too", Toast.LENGTH_SHORT).show()
            return
        }
        coroutineScope.launch {
            try {
                stage = UploadStage.UPLOADING
                uploadProgressPercent = 0.1f
                uploadStatusMessage = "Saving draft…"

                val postId: String
                if (backendPostId == null) {
                    when (val created = feedRepository.createPost(
                        description = description,
                        mediaType = if (isVideoPost) PostMediaType.VIDEO else PostMediaType.IMAGE,
                        postType = postType,
                        priceAmount = amount,
                        currency = selectedCurrency?.code ?: "INR"
                    )) {
                        is com.example.service.FeedResultWithId.Success -> {
                            postId = created.postId
                            backendPostId = postId
                        }
                        else -> throw Exception("Could not create draft (backend missing?)")
                    }
                } else {
                    postId = backendPostId!!
                    feedRepository.saveDraft(postId, description, postType, amount, selectedCurrency?.code ?: "INR")
                }
                uploadProgressPercent = 0.5f

                if (draftMediaReplaced) {
                    val bytes = croppedFile?.let { readBytesQuiet(it) }
                        ?: pickedUri?.let { readBytesQuiet(it) }
                    if (bytes != null && bytes.isNotEmpty()) {
                        uploadStatusMessage = "Uploading draft media…"
                        when (val up = feedRepository.uploadFeedMedia(postId, 0, bytes, if (isVideoPost) "video/mp4" else "image/jpeg")) {
                            is com.example.service.FeedResultWithPath.Success -> {
                                feedRepository.attachMediaMetadata(
                                    postId,
                                    listOf(Triple(up.storagePath, mediaMime(isVideoPost), croppedDims))
                                )
                            }
                            else -> {}
                        }
                    }
                }
                uploadProgressPercent = 1f
                feedRepository.refreshDrafts()
                delay(300)
                Toast.makeText(context, "Draft saved", Toast.LENGTH_SHORT).show()
                onBack()
            } catch (e: Exception) {
                uploadErrorMessage = e.message ?: "Draft save failed"
                stage = UploadStage.RESULT_ERROR
            }
        }
    }

    // --------------------------------------------------------------------
    // Scaffold
    // --------------------------------------------------------------------
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("post_upload_screen"),
        topBar = {
            if (stage != UploadStage.CROP) {
                TopAppBar(
                    title = {
                        Text(
                            when (stage) {
                                UploadStage.CROP -> ""
                                UploadStage.DETAILS -> if (resumedDraft != null) "Edit Draft" else "New Post"
                                UploadStage.UPLOADING -> "Uploading…"
                                UploadStage.RESULT_SUCCESS -> "Done"
                                UploadStage.RESULT_ERROR -> "Something went wrong"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            when (stage) {
                                UploadStage.CROP -> onBack()
                                UploadStage.DETAILS -> {
                                    if (pickedUri != null) stage = UploadStage.CROP
                                    else onBack()
                                }
                                else -> {}
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandGreen)
                )
            }
        },
        containerColor = Color(0xFFF7F8FA)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (stage != UploadStage.CROP) Modifier.padding(innerPadding) else Modifier)
        ) {
            when (stage) {
                UploadStage.CROP -> {
                    val uri = pickedUri
                    if (uri != null) {
                        FeedCropEditor(
                            mediaUri = uri,
                            isVideo = isVideoPost,
                            targetAspect = targetAspect,
                            frameTitle = if (isVideoPost) "Output: 9:16 (story format)" else "Output: 4:5 (feed format)",
                            onConfirmed = { output ->
                                when (output) {
                                    is CropOutput.CroppedImage -> {
                                        croppedFile = output.file
                                        croppedDims = output.width to output.height
                                        stage = UploadStage.DETAILS
                                    }
                                    is CropOutput.CroppedVideo -> {
                                        croppedFile = output.file
                                        croppedDims = null
                                        stage = UploadStage.DETAILS
                                    }
                                    CropOutput.Cancelled -> {}
                                }
                            },
                            onCancel = {
                                if (croppedFile != null || resumedDraft != null) {
                                    stage = UploadStage.DETAILS
                                } else {
                                    onBack()
                                }
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0B141A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Button(
                                    onClick = { pickMedia() },
                                    shape = RoundedCornerShape(22.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                                ) {
                                    Icon(
                                        if (isVideoPost) Icons.Filled.VideoLibrary else Icons.Filled.PhotoLibrary,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Select ${if (isVideoPost) "Video" else "Photo"}", color = Color.White)
                                }
                                Spacer(Modifier.height(10.dp))
                                TextButton(onClick = onBack) {
                                    Text("Cancel", color = Color(0xFF8696A0))
                                }

                                if (!isVideoPost) {
                                    Spacer(Modifier.height(20.dp))
                                    Text(
                                        "Or try a sample photo:",
                                        color = Color(0xFF8696A0),
                                        fontSize = 12.sp
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.horizontalScroll(rememberScrollState())
                                    ) {
                                        com.example.util.SampleMediaSeeder.samplePresets.forEach { preset ->
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        val sampleUri = com.example.util.SampleMediaSeeder.getPresetUri(context, preset)
                                                        pickedUri = sampleUri
                                                        croppedDims = null
                                                        stage = UploadStage.CROP
                                                    }
                                                    .padding(2.dp)
                                            ) {
                                                Image(
                                                    painter = painterResource(id = preset.resId),
                                                    contentDescription = preset.title,
                                                    modifier = Modifier
                                                        .size(60.dp)
                                                        .clip(RoundedCornerShape(8.dp)),
                                                    contentScale = ContentScale.Crop
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    preset.title,
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                UploadStage.DETAILS -> DetailsStage(
                    croppedFile = croppedFile,
                    pickedUri = pickedUri,
                    resumedDraft = resumedDraft,
                    isVideoPost = isVideoPost,
                    description = description,
                    onDescriptionChange = { if (it.length <= DESCRIPTION_MAX) description = it },
                    selectedPostType = selectedPostType,
                    onPostTypeChange = { selectedPostType = it },
                    priceAmountText = priceAmountText,
                    onPriceChange = { if (it.length <= 9 && it.all { c -> c.isDigit() || c == '.' }) priceAmountText = it },
                    currencies = feedRepository.currencies.collectAsState().value,
                    selectedCurrency = selectedCurrency,
                    currencyDropdownOpen = currencyDropdownOpen,
                    onCurrencyDropdownToggle = {
                        currencyDropdownOpen = !currencyDropdownOpen
                    },
                    onCurrencySelected = {
                        selectedCurrency = it
                        currencyDropdownOpen = false
                    },
                    onPost = { startBackendUpload() },
                    onSaveDraft = { saveAsDraft() }
                )

                UploadStage.UPLOADING -> FullLoadingScreen(
                    progress = uploadProgressPercent,
                    statusMessage = uploadStatusMessage,
                    onNothing = {}
                )

                UploadStage.RESULT_SUCCESS -> SuccessScreen(
                    onDone = onPostCreatedSuccessfully
                )

                UploadStage.RESULT_ERROR -> ErrorScreen(
                    message = uploadErrorMessage ?: "Upload failed",
                    onRetry = { stage = UploadStage.DETAILS },
                    onBack = onBack
                )
            }
        }
    }
}

private fun mediaMime(isVideo: Boolean) = if (isVideo) "video/mp4" else "image/jpeg"

// ---------------------------------------------------------------------------
// DETAILS stage UI
// ---------------------------------------------------------------------------
@Composable
private fun DetailsStage(
    croppedFile: File?,
    pickedUri: Uri?,
    resumedDraft: FeedPost?,
    isVideoPost: Boolean,
    description: String,
    onDescriptionChange: (String) -> Unit,
    selectedPostType: PostType?,
    onPostTypeChange: (PostType) -> Unit,
    priceAmountText: String,
    onPriceChange: (String) -> Unit,
    currencies: List<CurrencyOption>,
    selectedCurrency: CurrencyOption?,
    currencyDropdownOpen: Boolean,
    onCurrencyDropdownToggle: () -> Unit,
    onCurrencySelected: (CurrencyOption) -> Unit,
    onPost: () -> Unit,
    onSaveDraft: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Media preview card — 4:5 / 9:16 as chosen
        val previewModel: Any? = croppedFile ?: pickedUri ?: resumedDraft?.mediaUrls?.firstOrNull()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (isVideoPost) 9f / 16f else 4f / 5f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0B141A))
                .border(1.dp, Color(0xFFE5E9EC), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (previewModel != null) {
                AsyncImage(
                    model = previewModel,
                    contentDescription = "Post media preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.Image, contentDescription = null, tint = Color(0xFF54656F))
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
            ) {
                Text(
                    if (isVideoPost) "9:16 VIDEO" else "4:5 PHOTO",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Description (max 300, optional)
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("Description (optional)") },
            placeholder = { Text("Write a caption…", color = Color(0xFF8696A0)) },
            supportingText = {
                Text(
                    "${description.length}/$DESCRIPTION_MAX",
                    color = if (description.length >= DESCRIPTION_MAX) Color(0xFFD32F2F) else Color(0xFF8696A0),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            },
            minLines = 3,
            maxLines = 5,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentGreen,
                focusedLabelColor = AccentGreen
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // Type — FREE / PAID dropdown (stored as DB-level check constraint)
        Text("Post Type", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF111B21))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(PostType.FREE to "Free", PostType.PAID to "Paid").forEach { (type, label) ->
                val selected = selectedPostType == type
                Surface(
                    onClick = { onPostTypeChange(type) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) AccentGreen.copy(alpha = 0.14f) else Color.White,
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (selected) AccentGreen else Color(0xFFE5E9EC)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (type == PostType.FREE) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            contentDescription = null,
                            tint = if (selected) AccentGreen else Color(0xFF8696A0),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) AccentGreen else Color(0xFF54656F)
                        )
                    }
                }
            }
        }

        // Amount + currency (paid only) — currency list from backend countries table
        if (selectedPostType == PostType.PAID) {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Amount
                        OutlinedTextField(
                            value = priceAmountText,
                            onValueChange = onPriceChange,
                            label = { Text("Amount") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentGreen,
                                focusedLabelColor = AccentGreen
                            ),
                            modifier = Modifier.weight(1.2f)
                        )

                        // Currency picker
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = onCurrencyDropdownToggle,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF111B21)),
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Text(
                                    selectedCurrency?.let { "${it.symbol ?: ""} ${it.code}" } ?: "Currency",
                                    fontSize = 14.sp,
                                    maxLines = 1
                                )
                            }
                            DropdownMenu(
                                expanded = currencyDropdownOpen,
                                onDismissRequest = onCurrencyDropdownToggle,
                                modifier = Modifier.heightIn(max = 280.dp)
                            ) {
                                if (currencies.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Loading currencies…", color = Color(0xFF8696A0)) },
                                        onClick = {}
                                    )
                                }
                                currencies.forEach { c ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("${c.symbol ?: ""} ${c.code}", fontWeight = FontWeight.Bold)
                                                c.countryName?.let {
                                                    Text(it, fontSize = 11.sp, color = Color(0xFF8696A0))
                                                }
                                            }
                                        },
                                        onClick = { onCurrencySelected(c) }
                                    )
                                }
                            }
                        }
                    }
                    if (selectedCurrency != null && selectedCurrency!!.code != "INR") {
                        val inr = currencies.find { it.code == "INR" }
                        if (inr != null && selectedCurrency!!.fxRate > 0.0) {
                            val amount = priceAmountText.toDoubleOrNull() ?: 0.0
                            val approxInr = amount * (inr.fxRate / selectedCurrency!!.fxRate)
                            Text(
                                "≈ ₹%.2f (1 ${selectedCurrency!!.code} = ₹%.2f)".format(approxInr, inr.fxRate / selectedCurrency!!.fxRate),
                                fontSize = 12.sp,
                                color = Color(0xFF667781)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Actions — Instagram-style: draft + post
        Button(
            onClick = onPost,
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            Icon(Icons.Filled.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Post to Feed", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        OutlinedButton(
            onClick = onSaveDraft,
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF111B21)),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Save as Draft", fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------------------
// Loading / result screens
// ---------------------------------------------------------------------------
@Composable
private fun FullLoadingScreen(progress: Float, statusMessage: String, onNothing: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B141A)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            color = AccentGreen,
            strokeWidth = 5.dp,
            modifier = Modifier.size(84.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "${(progress * 100).toInt()}%",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            statusMessage,
            color = Color(0xFF8696A0),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "Your post is being saved to the Trigger backend — please keep the app open.",
            color = Color(0xFF54656F),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp)
        )
    }
}

@Composable
private fun SuccessScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FA))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8F5E9)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(60.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("Post Published!", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF111B21))
        Spacer(Modifier.height(8.dp))
        Text(
            "Your media, description and settings were saved on the Trigger backend. It is now live at the top of the feed.",
            fontSize = 13.sp, color = Color(0xFF667781), textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onDone,
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Back to Feed", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FA))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Color(0xFFD32F2F), modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("Upload failed", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111B21))
        Spacer(Modifier.height(8.dp))
        Text(message, fontSize = 13.sp, color = Color(0xFF667781), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("Try Again", color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onBack,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Back", color = Color(0xFF111B21)) }
    }
}
