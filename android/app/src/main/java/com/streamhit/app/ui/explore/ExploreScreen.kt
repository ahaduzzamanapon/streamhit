package com.streamhit.app.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhit.app.data.model.SubjectSummary
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.components.MediaCard
import com.streamhit.app.ui.theme.AccentEmerald
import com.streamhit.app.ui.theme.BgCard
import com.streamhit.app.ui.theme.BgDark
import com.streamhit.app.ui.theme.TextMuted
import com.streamhit.app.ui.theme.TextPrimary
import kotlinx.coroutines.delay

@Composable
fun ExploreScreen(
    repository: MovieRepository,
    initialSubjectType: Int = 1, // 1 = Movie, 2 = TV
    onMediaClick: (detailPath: String, subjectId: String, subjectType: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SubjectSummary>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    var selectedType by remember { mutableStateOf(initialSubjectType) }
    var selectedGenre by remember { mutableStateOf("All") }
    var items by remember { mutableStateOf<List<SubjectSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val genres = listOf("All", "Action", "Comedy", "Drama", "Romance", "Animation", "Sci-Fi", "Horror", "Thriller")

    // Live search when user enters a query in explore tab
    LaunchedEffect(searchQuery) {
        if (searchQuery.trim().length >= 2) {
            delay(350)
            isSearching = true
            searchResults = repository.search(searchQuery.trim())
            isSearching = false
        } else {
            searchResults = emptyList()
            isSearching = false
        }
    }

    LaunchedEffect(selectedType, selectedGenre) {
        isLoading = true
        val list = repository.filterContent(
            page = 1,
            subjectType = selectedType,
            genre = selectedGenre
        )
        items = list
        isLoading = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // Search Input Bar at top of Explore tab
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .background(BgCard, RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp)
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search in explore...", color = TextMuted, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = AccentEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
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
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (searchQuery.isNotBlank()) {
            // Search Mode Results View
            if (isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentEmerald)
                }
            } else if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No results found for \"$searchQuery\"",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(searchResults) { item ->
                        MediaCard(
                            item = item,
                            onClick = {
                                val path = item.detailPath.ifEmpty { item.subjectId }
                                onMediaClick(path, item.subjectId, item.subjectType)
                            }
                        )
                    }
                }
            }
        } else {
            // Normal Explore Mode
            // Type selector tabs (Movies vs TV Series)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(BgCard, RoundedCornerShape(10.dp))
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selectedType == 1) AccentEmerald else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedType = 1 }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Movies",
                        color = if (selectedType == 1) Color.Black else TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selectedType == 2) AccentEmerald else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedType = 2 }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "TV Series",
                        color = if (selectedType == 2) Color.Black else TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            // Horizontal Genre Chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                items(genres) { genre ->
                    val isSelected = selectedGenre == genre
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) AccentEmerald.copy(alpha = 0.2f) else BgCard,
                                RoundedCornerShape(20.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) AccentEmerald else Color.White.copy(alpha = 0.08f),
                                RoundedCornerShape(20.dp)
                            )
                            .clickable { selectedGenre = genre }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = genre,
                            color = if (isSelected) AccentEmerald else TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Content Grid
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentEmerald)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items) { item ->
                        MediaCard(
                            item = item,
                            onClick = {
                                val path = item.detailPath.ifEmpty { item.subjectId }
                                onMediaClick(path, item.subjectId, item.subjectType)
                            }
                        )
                    }
                }
            }
        }
    }
}
