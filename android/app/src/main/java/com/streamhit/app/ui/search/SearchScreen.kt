package com.streamhit.app.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhit.app.R
import com.streamhit.app.data.local.RecentSearchManager
import com.streamhit.app.data.model.SubjectSummary
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.components.AppShareDialog
import com.streamhit.app.ui.components.MediaCard
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    repository: MovieRepository,
    onMediaClick: (detailPath: String, subjectId: String, subjectType: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SubjectSummary>>(emptyList()) }
    var recentItems by remember { mutableStateOf<List<SubjectSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Load recent clicked search items
    LaunchedEffect(Unit) {
        recentItems = RecentSearchManager.getRecentItems(context)
    }

    // Live search as user types with 400ms debounce
    LaunchedEffect(query) {
        if (query.trim().length >= 2) {
            delay(400)
            isLoading = true
            results = repository.search(query.trim())
            isLoading = false
        } else if (query.isEmpty()) {
            results = emptyList()
            recentItems = RecentSearchManager.getRecentItems(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // Compact Cinema Header with Integrated Live Search Input & Share
        Surface(
            color = Color(0xFF070B14).copy(alpha = 0.98f),
            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.05f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Left: Logo
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "NXLY",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Center: Search Input Bar (fills width)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = AccentEmerald,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text("Search movies, shows, anime...", color = TextMuted, fontSize = 12.sp)
                                }
                                innerTextField()
                            }
                        )
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    query = ""
                                    recentItems = RecentSearchManager.getRecentItems(context)
                                },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }

                // Right: Share Button
                Surface(
                    shape = CircleShape,
                    color = AccentEmerald.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { showShareDialog = true }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = AccentEmerald,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Search Results or Recent Searches
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentEmerald)
            }
        } else if (results.isNotEmpty()) {
            // Live Search Results
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(results) { item ->
                    MediaCard(
                        item = item,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            RecentSearchManager.saveClickedItem(context, item)
                            val path = item.detailPath.ifEmpty { item.subjectId }
                            onMediaClick(path, item.subjectId, item.subjectType)
                        }
                    )
                }
            }
        } else if (query.length >= 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No results found for \"$query\"", color = TextMuted, fontSize = 14.sp)
            }
        } else {
            // Empty Search query: Show Trending Quick Chips + Recent Searches
            Column(modifier = Modifier.fillMaxSize()) {
                // Trending Quick Suggestions Row
                Column(modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
                    Text(
                        text = "🔥 Trending Searches",
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                    )

                    val trendingTags = listOf(
                        "Spider-Man",
                        "Bengali",
                        "Hindi",
                        "Anime",
                        "Avatar",
                        "Avengers",
                        "Bangla",
                        "Action"
                    )

                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        items(trendingTags) { tag ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color.White.copy(alpha = 0.06f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                modifier = Modifier.clickable {
                                    query = tag
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = tag,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                if (recentItems.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Recent Searches",
                                tint = AccentEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Recently Viewed",
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        TextButton(
                            onClick = {
                                RecentSearchManager.clearAll(context)
                                recentItems = emptyList()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Clear All",
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear", color = TextMuted, fontSize = 11.5.sp)
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 80.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(recentItems) { item ->
                            MediaCard(
                                item = item,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    RecentSearchManager.saveClickedItem(context, item)
                                    val path = item.detailPath.ifEmpty { item.subjectId }
                                    onMediaClick(path, item.subjectId, item.subjectType)
                                }
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = TextMuted.copy(alpha = 0.4f),
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Search movies, series & anime",
                                color = TextMuted,
                                fontSize = 13.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap any trending search or type above",
                                color = TextMuted.copy(alpha = 0.6f),
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showShareDialog) {
        AppShareDialog(onDismiss = { showShareDialog = false })
    }
}
