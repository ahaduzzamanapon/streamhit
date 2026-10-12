package com.streamhit.app.ui.home

import androidx.compose.animation.core.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhit.app.data.local.HomeCacheManager
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
fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_anim"
    )
    return Brush.linearGradient(
        colors = listOf(
            Color(0xFF161C2C),
            Color(0xFF28344E),
            Color(0xFF161C2C)
        ),
        start = Offset(translateAnim - 350f, translateAnim - 350f),
        end = Offset(translateAnim, translateAnim)
    )
}

@Composable
fun HomeScreenShimmer(brush: Brush) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Search bar skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(brush)
        )
        Spacer(modifier = Modifier.height(14.dp))

        // Category pills skeleton
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(5) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(brush)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Hero banner skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(290.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(brush)
        )
        Spacer(modifier = Modifier.height(22.dp))

        // Section title skeleton
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
        Spacer(modifier = Modifier.height(14.dp))

        // Media cards skeleton row
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) {
                Column {
                    Box(
                        modifier = Modifier
                            .width(115.dp)
                            .height(165.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(brush)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(brush)
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    repository: MovieRepository,
    onMediaClick: (detailPath: String, subjectId: String, subjectType: Int) -> Unit,
    onPlayDirect: (streamUrl: String, title: String, subjectId: String, detailPath: String, coverUrl: String) -> Unit = { _, _, _, _, _ -> },
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
        recentHistory = RecentHistoryManager.getRecentHistory(context)
        val cachedBanners = HomeCacheManager.getCachedBanners(context)
        val cachedSections = HomeCacheManager.getCachedSections(context)
        val isExpired = HomeCacheManager.isCacheExpired(context)

        // Show cache instantly if available
        if (cachedBanners.isNotEmpty() || cachedSections.isNotEmpty()) {
            banners = cachedBanners
            sections = cachedSections
            isLoading = false
        }

        // Fetch fresh data once a day (or if cache is empty)
        if (isExpired || cachedSections.isEmpty()) {
            try {
                val b = repository.getBanners()
                val s = repository.getHomeFeed()
                val finalBanners = if (b.isNotEmpty()) b else (s.firstOrNull { it.banner != null && it.banner.items.isNotEmpty() }?.banner?.items ?: emptyList())
                if (finalBanners.isNotEmpty() || s.isNotEmpty()) {
                    banners = finalBanners
                    sections = s
                    HomeCacheManager.saveHomeCache(context, finalBanners, s)
                }
            } catch (_: Exception) {}
            isLoading = false
        }
    }

    if (isLoading) {
        val shimmerBrush = rememberShimmerBrush()
        HomeScreenShimmer(shimmerBrush)
        return
    }

    val categories = listOf("Trending", "Movies", "TV Shows", "Bengali", "Hindi Dub", "Anime")

    // Dynamic filtering based on category selection
    val filteredSections = remember(sections, selectedCategoryIndex) {
        if (selectedCategoryIndex == 0) {
            sections
        } else {
            val cat = categories[selectedCategoryIndex]
            val result = sections.mapNotNull { sec ->
                val matching = sec.subjects?.filter { item ->
                    when (cat) {
                        "Movies" -> item.subjectType == 1 || sec.title?.contains("Movie", ignoreCase = true) == true
                        "TV Shows" -> item.subjectType == 2 || sec.title?.contains("TV", ignoreCase = true) == true || sec.title?.contains("Series", ignoreCase = true) == true
                        "Bengali" -> item.title.contains("Bangla", ignoreCase = true) || item.title.contains("Bengali", ignoreCase = true) || item.genres.contains("Bengali", ignoreCase = true)
                        "Hindi Dub" -> item.title.contains("Hindi", ignoreCase = true) || item.genres.contains("Hindi", ignoreCase = true)
                        "Anime" -> item.genres.contains("Anime", ignoreCase = true) || item.genres.contains("Animation", ignoreCase = true) || item.title.contains("Anime", ignoreCase = true)
                        else -> true
                    }
                } ?: emptyList()
                if (matching.isNotEmpty()) sec.copy(subjects = matching) else null
            }
            if (result.isNotEmpty()) result
            else {
                val allSubjects = sections.flatMap { it.subjects ?: emptyList() }
                val fallback = allSubjects.filter { item ->
                    when (cat) {
                        "Movies" -> item.subjectType == 1
                        "TV Shows" -> item.subjectType == 2
                        "Bengali" -> item.title.contains("Bangla", ignoreCase = true) || item.title.contains("Bengali", ignoreCase = true)
                        "Hindi Dub" -> item.title.contains("Hindi", ignoreCase = true)
                        "Anime" -> item.genres.contains("Anime", ignoreCase = true) || item.genres.contains("Animation", ignoreCase = true)
                        else -> true
                    }
                }
                if (fallback.isNotEmpty()) listOf(HomeSection(title = cat, subjects = fallback)) else emptyList()
            }
        }
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
        if (banners.isNotEmpty() && selectedCategoryIndex == 0) {
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

        // Continue Watching / Recently Viewed
        if (recentHistory.isNotEmpty() && selectedCategoryIndex == 0) {
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
                                    val path = item.detailPath.ifEmpty { item.subjectId }
                                    onMediaClick(path, item.subjectId, item.subjectType)
                                },
                                onResumeClick = {
                                    if (item.streamUrl.isNotBlank()) {
                                        onPlayDirect(item.streamUrl, item.title, item.subjectId, item.detailPath, item.coverUrl)
                                    } else {
                                        val path = item.detailPath.ifEmpty { item.subjectId }
                                        onMediaClick(path, item.subjectId, item.subjectType)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Home Sections (Movies, TV Shows, Trending, Filtered Content)
        itemsIndexed(filteredSections.filter { it.subjects != null && it.subjects.isNotEmpty() }) { sectionIndex, section ->
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

        if (filteredSections.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (selectedCategoryIndex > 0) "No ${categories[selectedCategoryIndex]} titles found." else "No content found. Check connection.",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ContinueWatchingCard(
    item: WatchHistoryItem,
    onClick: () -> Unit,
    onResumeClick: () -> Unit = onClick
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
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            .border(1.5.dp, AccentEmerald, CircleShape)
                            .clickable(onClick = onResumeClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Resume",
                            tint = AccentEmerald,
                            modifier = Modifier.size(22.dp)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (item.subjectType == 2) "Series" else "Movie",
                        color = AccentEmerald,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${item.progressPercent}%",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
