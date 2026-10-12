package com.streamhit.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhit.app.data.local.RecentHistoryManager
import com.streamhit.app.data.local.WatchHistoryItem
import com.streamhit.app.data.model.BannerItem
import com.streamhit.app.data.model.HomeSection
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.components.HeroBannerSlider
import com.streamhit.app.ui.components.MediaCard
import com.streamhit.app.ui.components.SectionHeader
import com.streamhit.app.ui.theme.*

@Composable
fun HomeScreen(
    repository: MovieRepository,
    onMediaClick: (detailPath: String, subjectId: String, subjectType: Int) -> Unit,
    onPlayDirect: (streamUrl: String, title: String) -> Unit = { _, _ -> },
    onNavigateToSearch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var banners by remember { mutableStateOf<List<BannerItem>>(emptyList()) }
    var sections by remember { mutableStateOf<List<HomeSection>>(emptyList()) }
    var recentHistory by remember { mutableStateOf<List<WatchHistoryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedCategoryIndex by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val b = repository.getBanners()
        val s = repository.getHomeFeed()
        banners = if (b.isNotEmpty()) b else (s.firstOrNull { it.banner != null && it.banner.items.isNotEmpty() }?.banner?.items ?: emptyList())
        sections = s
        recentHistory = RecentHistoryManager.getRecentHistory(context)
        isLoading = false
    }

    if (isLoading) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(BgDark),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = AccentEmerald)
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        // Top Search Bar Header (MovieBox Pro style)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // Search Pill
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSearch() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Search movies, series, anime...",
                                color = TextMuted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Green Search Action Button
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = AccentEmerald,
                            modifier = Modifier.clickable { onNavigateToSearch() }
                        ) {
                            Text(
                                text = "Search",
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Category Filter Pills
                val categories = listOf("Trending", "Movies", "TV Shows", "Bengali", "Hindi Dub", "Anime")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    itemsIndexed(categories) { index, cat ->
                        val isSelected = selectedCategoryIndex == index
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) AccentEmerald.copy(alpha = 0.2f) else Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) AccentEmerald else Color.White.copy(alpha = 0.12f)
                            ),
                            modifier = Modifier.clickable { selectedCategoryIndex = index }
                        ) {
                            Text(
                                text = cat,
                                color = if (isSelected) AccentEmerald else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }

        // Hero Banner
        if (banners.isNotEmpty()) {
            item {
                HeroBannerSlider(
                    banners = banners,
                    onBannerClick = { item ->
                        val path = item.detailPath.ifEmpty { item.subjectId }
                        onMediaClick(path, item.subjectId, item.subjectType)
                    }
                )
            }
        }

        // Continue Watching / Recently Viewed (User Request)
        if (recentHistory.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    SectionHeader(title = "Continue Watching")

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(recentHistory) { item ->
                            ContinueWatchingCard(
                                item = item,
                                onClick = {
                                    if (item.streamUrl.isNotBlank()) {
                                        onPlayDirect(item.streamUrl, item.title)
                                    } else {
                                        onMediaClick(item.detailPath, item.subjectId, 1)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Home Sections (Movies, TV Shows, Trending)
        itemsIndexed(sections.filter { it.subjects != null && it.subjects.isNotEmpty() }) { sectionIndex, section ->
            val subjects = section.subjects ?: emptyList()
            val title = section.title ?: "Trending"
            val isRankedSection = sectionIndex == 0 || title.contains("Top", ignoreCase = true) || title.contains("Trending", ignoreCase = true)

            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                SectionHeader(title = title)

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(subjects) { index, subject ->
                        MediaCard(
                            item = subject,
                            rankNumber = if (isRankedSection && index < 10) (index + 1) else null,
                            onClick = {
                                val path = subject.detailPath.ifEmpty { subject.subjectId }
                                onMediaClick(path, subject.subjectId, subject.subjectType)
                            }
                        )
                    }
                }
            }
        }

        if (sections.isEmpty() && banners.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No content found. Check connection.", color = TextMuted, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun ContinueWatchingCard(
    item: WatchHistoryItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(95.dp)
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Play Icon Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Resume",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Green Progress Line at bottom of thumbnail
                LinearProgressIndicator(
                    progress = { item.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter),
                    color = AccentEmerald,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }

            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.progressPercent}% watched",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
        }
    }
}
