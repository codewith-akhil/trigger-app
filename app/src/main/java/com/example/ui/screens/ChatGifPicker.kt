package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.config.GiphyConfig
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * ChatGifPicker — REAL GIPHY integration.
 *
 * Fetches trending GIFs (and search results once the user types a query)
 * from the GIPHY REST API using the server-configured GIPHY_API_KEY.
 * Tapping a GIF hands its metadata to the parent, which downloads the file
 * to the app cache and sends it through the regular IMAGE/media pipeline
 * (mime image/gif — uploads skip JPEG compression so animation survives).
 *
 * When no API key is configured the parent hides the GIF button entirely —
 * this picker never renders a fake/stub grid.
 */
data class GifItem(
    val id: String,
    val previewUrl: String,
    val fullUrl: String,
    val width: Int,
    val height: Int
)

@Composable
fun ChatGifPicker(
    onGifSelected: (GifItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var gifs by remember { mutableStateOf<List<GifItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun fetchGifs(search: String?) {
        isLoading = true
        errorMessage = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = if (search.isNullOrBlank()) "trending" else "search"
                val key = GiphyConfig.API_KEY
                val url = buildString {
                    append("https://api.giphy.com/v1/gifs/$endpoint?api_key=")
                    append(java.net.URLEncoder.encode(key, "UTF-8"))
                    append("&limit=36&rating=pg-13")
                    if (!search.isNullOrBlank()) {
                        append("&q=").append(java.net.URLEncoder.encode(search, "UTF-8"))
                    }
                }
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                try {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (json.optJSONObject("meta")?.optInt("status", 500) != 200) {
                        error("GIPHY error ${json.optJSONObject("meta")?.optInt("status")}")
                    }
                    val data = json.optJSONArray("data") ?: org.json.JSONArray()
                    val items = mutableListOf<GifItem>()
                    for (i in 0 until data.length()) {
                        val obj = data.optJSONObject(i) ?: continue
                        val images = obj.optJSONObject("images") ?: continue
                        val preview = images.optJSONObject("fixed_width_downsampled")
                            ?: images.optJSONObject("fixed_width_small")
                            ?: continue
                        val original = images.optJSONObject("original") ?: continue
                        items += GifItem(
                            id = obj.optString("id"),
                            previewUrl = preview.optString("url"),
                            fullUrl = original.optString("url"),
                            width = original.optInt("width", 200),
                            height = original.optInt("height", 200)
                        )
                    }
                    items
                } finally {
                    conn.disconnect()
                }
            }
        }
        isLoading = false
        result.fold(
            onSuccess = { gifs = it },
            onFailure = { errorMessage = it.message ?: "Couldn't load GIFs" }
        )
    }

    LaunchedEffect(Unit) { fetchGifs(null) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .testTag("gif_picker"),
        color = Color(0xFFF0F2F5)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search field
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search GIPHY", fontSize = 14.sp, color = Color(0xFF8696A0)) },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    scope.launch { fetchGifs(query.trim().ifBlank { null }) }
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WhatsAppFabGreen,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                ),
                trailingIcon = {
                    TextButton(onClick = {
                        scope.launch { fetchGifs(query.trim().ifBlank { null }) }
                    }) { Text("Search", color = WhatsAppFabGreen) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )

            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = WhatsAppFabGreen)
                    }
                }
                errorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Couldn't load GIFs — check your connection",
                            fontSize = 13.sp,
                            color = Color(0xFF8696A0)
                        )
                    }
                }
                gifs.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No GIFs found", fontSize = 13.sp, color = Color(0xFF8696A0))
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(gifs, key = { it.id }) { gif ->
                            AsyncImage(
                                model = gif.previewUrl,
                                contentDescription = "GIF",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .aspectRatio(
                                        if (gif.width > 0 && gif.height > 0)
                                            gif.width.toFloat() / gif.height.toFloat()
                                        else 1f
                                    )
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFE4E6EB))
                                    .clickable { onGifSelected(gif) }
                            )
                        }
                    }
                }
            }
        }
    }
}
