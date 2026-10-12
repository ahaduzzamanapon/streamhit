package com.streamhit.app.ui.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhit.app.data.local.DownloadItem
import com.streamhit.app.data.local.DownloadStatus
import com.streamhit.app.data.local.MediaDownloadManager
import com.streamhit.app.data.model.SubjectSummary
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.components.MediaCard
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun DownloadsScreen(
    repository: MovieRepository,
    onPlayOffline: (localUri: String, title: String) -> Unit,
    onNavigateToDetails: (detailPath: String, subjectId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var downloadItems by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }
    var availableStorage by remember { mutableStateOf("") }
    var recommendations by remember { mutableStateOf<List<SubjectSummary>>(emptyList()) }
    var selectedFilterIndex by remember { mutableStateOf(0) }
    var selectedSeriesForDetail by remember { mutableStateOf<String?>(null) }

    // Intercept back button when inside a Series Download Detail view
    BackHandler(enabled = selectedSeriesForDetail != null) {
        selectedSeriesForDetail = null
    }

    // Poll download progress every 2 seconds
    LaunchedEffect(Unit) {
        availableStorage = MediaDownloadManager.getAvailableStorageFormatted()
        try {
            val feed = repository.getHomeFeed()
            val allSubs = feed.flatMap { it.subjects ?: emptyList() }
            recommendations = allSubs.take(12)
        } catch (_: Exception) {}

        while (true) {
            downloadItems = MediaDownloadManager.refreshDownloadStatuses(context)
            delay(2000)
        }
    }

    val downloadingList = downloadItems.filter {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED
    }
    val completedList = downloadItems.filter { it.status == DownloadStatus.COMPLETED }

    // Helper to extract series name
    fun getSeriesName(item: DownloadItem): String {
        return if (item.seriesTitle.isNotBlank()) {
            item.seriesTitle
        } else {
            val regex = Regex(" - S\\d+| S\\d+E\\d+", RegexOption.IGNORE_CASE)
            val match = regex.find(item.title)
            if (match != null) {
                item.title.substring(0, match.range.first).trim()
            } else {
                item.title
            }
        }
    }

    // Separate into Series Groups and Standalone Movies
    val isSeriesPredicate = { item: DownloadItem ->
        item.isSeries || item.seriesTitle.isNotBlank() || item.title.contains(" - S", ignoreCase = true) || item.title.contains(" S0", ignoreCase = true) || item.title.contains(" S1", ignoreCase = true)
    }

    val seriesGroups = completedList.filter(isSeriesPredicate).groupBy { getSeriesName(it) }
    val standaloneMovies = completedList.filterNot(isSeriesPredicate)

    if (selectedSeriesForDetail != null) {
        // Detailed View for a selected series' downloaded episodes in serial order
        val seriesTitle = selectedSeriesForDetail!!
        val episodes = (seriesGroups[seriesTitle] ?: emptyList()).sortedWith(
            compareBy<DownloadItem> { it.seasonNum }.thenBy { it.episodeNum }.thenBy { it.title }
        )
        val firstItem = episodes.firstOrNull()

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(BgDark)
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedSeriesForDetail = null }) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = seriesTitle,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${episodes.size} Episodes downloaded",
                        color = AccentEmerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Delete All button
                if (episodes.isNotEmpty()) {
                    IconButton(onClick = {
                        episodes.forEach { ep ->
                            MediaDownloadManager.deleteDownload(context, ep.downloadId)
                        }
                        downloadItems = MediaDownloadManager.refreshDownloadStatuses(context)
                        selectedSeriesForDetail = null
                    }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Delete All Episodes",
                            tint = TextMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Series Poster Banner Card
            if (firstItem != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(BgCard, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 65.dp, height = 90.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                    ) {
                        AsyncImage(
                            model = firstItem.coverUrl,
                            contentDescription = seriesTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = seriesTitle,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val totalBytes = episodes.sumOf { if (it.totalBytes > 0) it.totalBytes else it.downloadedBytes }
                        Text(
                            text = "Total Size: ${DownloadItem.formatFileSize(totalBytes)}",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ready to watch offline anytime",
                            color = AccentEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Text(
                text = "Episodes in Order",
                color = TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Serial list of episodes
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(episodes) { ep ->
                    DownloadedEpisodeItemCard(
                        item = ep,
                        onPlayClick = {
                            onPlayOffline(ep.playUri, ep.title)
                        },
                        onDeleteClick = {
                            MediaDownloadManager.deleteDownload(context, ep.downloadId)
                            downloadItems = MediaDownloadManager.refreshDownloadStatuses(context)
                            if (episodes.size <= 1) {
                                selectedSeriesForDetail = null
                            }
                        }
                    )
                }
            }
        }
    } else {
        // Main Downloads Screen View
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(BgDark),
            contentPadding = PaddingValues(bottom = 90.dp)
        ) {
            // Top Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Downloads",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Transfer Button Pill
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan.copy(alpha = 0.4f)),
                        modifier = Modifier.clickable { /* Transfer info / action */ }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Transfer",
                                tint = AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Transfer",
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Section: Downloading (Active tasks)
            if (downloadingList.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Downloading (${downloadingList.size})",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Manage",
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                items(downloadingList) { item ->
                    DownloadingItemCard(
                        item = item,
                        onPlayClick = {
                            onPlayOffline(item.playUri, item.title)
                        },
                        onDeleteClick = {
                            MediaDownloadManager.deleteDownload(context, item.downloadId)
                            downloadItems = MediaDownloadManager.refreshDownloadStatuses(context)
                        }
                    )
                }
            }

            // Section: Downloaded
            item {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Downloaded",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = availableStorage,
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }

                    // Filter Tabs: All, Movies, Series
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val filters = listOf(
                            "All (${completedList.size})",
                            "Series (${seriesGroups.size})",
                            "Movies (${standaloneMovies.size})"
                        )
                        filters.forEachIndexed { index, title ->
                            val isSelected = selectedFilterIndex == index
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.08f),
                                modifier = Modifier.clickable { selectedFilterIndex = index }
                            ) {
                                Text(
                                    text = title,
                                    color = if (isSelected) Color.Black else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (completedList.isEmpty() && downloadingList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = "No Downloads",
                                tint = TextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No downloaded movies yet",
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Download movies or series to watch offline anytime!",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            } else {
                // Show Grouped Series Cards
                if (selectedFilterIndex == 0 || selectedFilterIndex == 1) {
                    items(seriesGroups.entries.toList()) { (sTitle, epList) ->
                        DownloadedSeriesFolderCard(
                            seriesTitle = sTitle,
                            coverUrl = epList.firstOrNull()?.coverUrl ?: "",
                            episodes = epList,
                            onClick = {
                                selectedSeriesForDetail = sTitle
                            }
                        )
                    }
                }

                // Show Standalone Movie Cards
                if (selectedFilterIndex == 0 || selectedFilterIndex == 2) {
                    items(standaloneMovies) { item ->
                        DownloadedItemCard(
                            item = item,
                            onPlayClick = {
                                onPlayOffline(item.playUri, item.title)
                            },
                            onDeleteClick = {
                                MediaDownloadManager.deleteDownload(context, item.downloadId)
                                downloadItems = MediaDownloadManager.refreshDownloadStatuses(context)
                            }
                        )
                    }
                }
            }

            // Section: For You recommendations
            if (recommendations.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "For You",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                item {
                    val chunked = recommendations.chunked(3)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        chunked.forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowItems.forEach { sub ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        MediaCard(
                                            item = sub,
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = {
                                                val path = sub.detailPath.ifEmpty { sub.subjectId }
                                                onNavigateToDetails(path, sub.subjectId)
                                            }
                                        )
                                    }
                                }
                                if (rowItems.size < 3) {
                                    repeat(3 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier.clickable {
                                recommendations = recommendations.shuffled()
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Refresh new content",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadedSeriesFolderCard(
    seriesTitle: String,
    coverUrl: String,
    episodes: List<DownloadItem>,
    onClick: () -> Unit
) {
    val totalBytes = episodes.sumOf { if (it.totalBytes > 0) it.totalBytes else it.downloadedBytes }
    val formattedTotalSize = DownloadItem.formatFileSize(totalBytes)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Poster / Cover with SERIES badge
            Box(
                modifier = Modifier
                    .size(width = 65.dp, height = 85.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = seriesTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(AccentEmerald, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text("SERIES", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = seriesTitle,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${episodes.size} Episodes • $formattedTotalSize",
                    color = AccentEmerald,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Tap to view downloaded episodes",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open Series",
                tint = TextMuted,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun DownloadedEpisodeItemCard(
    item: DownloadItem,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onPlayClick),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Episode Badge Box
            Box(
                modifier = Modifier
                    .size(width = 54.dp, height = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "EP",
                        color = AccentEmerald,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format("%02d", item.episodeNum),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${item.quality} • ${item.formattedSize}",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            // Play Button
            IconButton(onClick = onPlayClick) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = "Play",
                    tint = AccentEmerald,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Delete Button
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun DownloadingItemCard(
    item: DownloadItem,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 80.dp, height = 54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .clickable(onClick = onPlayClick)
            ) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { item.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = AccentCyan,
                    trackColor = Color.White.copy(alpha = 0.1f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = item.formattedSize,
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "${item.progressPercent}%",
                        color = AccentGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun DownloadedItemCard(
    item: DownloadItem,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onPlayClick),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 90.dp, height = 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${item.quality} • ${item.formattedSize}",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = AccentEmerald,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Ready to watch offline",
                        color = AccentEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
