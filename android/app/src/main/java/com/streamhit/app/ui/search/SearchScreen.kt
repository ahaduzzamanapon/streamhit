package com.streamhit.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhit.app.data.local.RecentSearchManager
import com.streamhit.app.data.model.SubjectSummary
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.components.AppBrandHeader
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
        AppBrandHeader(
            tagText = "SEARCH",
            tagColor = AccentEmerald,
            showSearchAction = false,
            showShareAction = true
        )

        // Search Input Field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .background(BgCard, RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp)
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search movies, shows, anime...", color = TextMuted, fontSize = 13.5.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = AccentEmerald
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { 
                            query = ""
                            recentItems = RecentSearchManager.getRecentItems(context)
                        }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = TextMuted
                            )
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
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
            // Empty Search query: Show Recent Searches from clicks
            if (recentItems.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
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
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Recent Searches",
                                color = Color.White,
                                fontSize = 14.sp,
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
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear All", color = TextMuted, fontSize = 12.sp)
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 80.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = TextMuted.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Search movies, series & anime",
                            color = TextMuted,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Items you search and click will appear here",
                            color = TextMuted.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
