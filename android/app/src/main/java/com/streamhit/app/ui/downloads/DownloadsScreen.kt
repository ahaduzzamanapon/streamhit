package com.streamhit.app.ui.downloads

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhit.app.R
import com.streamhit.app.data.local.DownloadItem
import com.streamhit.app.data.local.DownloadStatus
import com.streamhit.app.data.local.MediaDownloadManager
import com.streamhit.app.data.model.SubjectSummary
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.components.AppShareDialog
import com.streamhit.app.ui.components.BackgroundDownloadPermissionDialog
import com.streamhit.app.ui.components.MediaCard
import com.streamhit.app.ui.components.SponsorSupportDialog
import com.streamhit.app.ui.sponsor.SponsorWebViewActivity
import com.streamhit.app.util.BackgroundDownloadHelper
import com.streamhit.app.util.SponsorAdManager
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun DownloadsScreen(
    repository: MovieRepository,
    onPlayOffline: (item: DownloadItem) -> Unit,
    onNavigateToDetails: (detailPath: String, subjectId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val downloadItems by MediaDownloadManager.downloadsFlow.collectAsState()
    val maxConcurrent by MediaDownloadManager.maxConcurrentFlow.collectAsState()
    var availableStorage by remember { mutableStateOf("") }
    var recommendations by remember { mutableStateOf<List<SubjectSummary>>(emptyList()) }
    var selectedFilterIndex by remember { mutableStateOf(0) }
    var selectedSeriesForDetail by remember { mutableStateOf<String?>(null) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showTurboSponsorDialog by remember { mutableStateOf(false) }
    var isBgDownloadActive by remember { mutableStateOf(BackgroundDownloadHelper.isBatteryOptimizationIgnored(context)) }
    var showBgDownloadPermissionDialog by remember { mutableStateOf(false) }

    val turboSponsorLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            showTurboSponsorDialog = false
            MediaDownloadManager.unlockTurbo(context)
            MediaDownloadManager.setMaxConcurrentDownloads(context, 3)
            Toast.makeText(context, "🎉 একসাথে ৩টি ভিডিও ডাউনলোড আনলক হয়েছে!", Toast.LENGTH_LONG).show()
        }
    }

    // Intercept back button when inside a Series Download Detail view
    BackHandler(enabled = selectedSeriesForDetail != null) {
        selectedSeriesForDetail = null
    }

    LaunchedEffect(Unit) {
        availableStorage = MediaDownloadManager.getAvailableStorageFormatted()
        MediaDownloadManager.getAllDownloads(context)
        MediaDownloadManager.refreshDownloadStatuses(context)
        try {
            val feed = repository.getHomeFeed()
            val allSubs = feed.flatMap { it.subjects ?: emptyList() }
            recommendations = allSubs.take(12)
        } catch (_: Exception) {}

        // Periodic storage and background permission check
        while (true) {
            delay(2500)
            isBgDownloadActive = BackgroundDownloadHelper.isBatteryOptimizationIgnored(context)
            availableStorage = MediaDownloadManager.getAvailableStorageFormatted()
        }
    }

    val downloadingList = downloadItems.filter {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED
    }.sortedWith(
        compareBy<DownloadItem> {
            when (it.status) {
                DownloadStatus.DOWNLOADING -> 0
                DownloadStatus.PENDING -> 1
                DownloadStatus.PAUSED -> 2
                DownloadStatus.FAILED -> 3
                else -> 4
            }
        }.thenBy { it.addedTime }
    )
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
                        MediaDownloadManager.refreshDownloadStatuses(context)
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
                            onPlayOffline(ep)
                        },
                        onDeleteClick = {
                            MediaDownloadManager.deleteDownload(context, ep.downloadId)
                            MediaDownloadManager.refreshDownloadStatuses(context)
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
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(BgDark)
        ) {
        // Compact Cinema Downloads Header
        Surface(
            color = Color(0xFF070B14).copy(alpha = 0.98f),
            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.05f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Logo & OFFLINE Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NXLY",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = " OFFLINE",
                            color = AccentEmerald,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Right: Storage Available Pill & Share
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (availableStorage.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SdStorage,
                                    contentDescription = "Storage",
                                    tint = AccentEmerald,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = availableStorage,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = AccentEmerald.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .size(30.dp)
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
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark),
            contentPadding = PaddingValues(bottom = 90.dp)
        ) {

            // Background Download Optimization Status Banner
            item {
                BackgroundDownloadStatusBanner(
                    isGranted = isBgDownloadActive,
                    onClick = {
                        if (isBgDownloadActive) {
                            Toast.makeText(context, "✅ ব্যাকগ্রাউন্ড ডাউনলোড সক্রিয় আছে! স্ক্রিন বন্ধ বা অ্যাপ মিনিমাইজ থাকলেও ডাউনলোড চলবে।", Toast.LENGTH_LONG).show()
                        } else {
                            showBgDownloadPermissionDialog = true
                        }
                    }
                )
            }

            // Download Concurrency Mode Switcher (1 at a time vs 3 at a time)
            item {
                DownloadConcurrencySelectorCard(
                    maxConcurrent = maxConcurrent,
                    isTurboUnlocked = MediaDownloadManager.isTurboUnlocked(context),
                    activeCount = downloadItems.count { it.status == DownloadStatus.DOWNLOADING },
                    pendingCount = downloadItems.count { it.status == DownloadStatus.PENDING },
                    onSelectLimit = { limit ->
                        if (limit == 3) {
                            if (MediaDownloadManager.isTurboUnlocked(context)) {
                                MediaDownloadManager.setMaxConcurrentDownloads(context, 3)
                                Toast.makeText(context, "⚡ একসাথে ৩টি ভিডিও ডাউনলোড সক্রিয় করা হয়েছে!", Toast.LENGTH_SHORT).show()
                            } else {
                                showTurboSponsorDialog = true
                            }
                        } else {
                            MediaDownloadManager.setMaxConcurrentDownloads(context, 1)
                            Toast.makeText(context, "১টি করে ডাউনলোড মোড সক্রিয় করা হয়েছে।", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
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

                items(downloadingList, key = { it.downloadId }) { item ->
                    DownloadingItemCard(
                        item = item,
                        onPlayClick = {
                            onPlayOffline(item)
                        },
                        onDeleteClick = {
                            MediaDownloadManager.deleteDownload(context, item.downloadId)
                        },
                        onPauseClick = {
                            MediaDownloadManager.pauseDownload(context, item.downloadId)
                        },
                        onResumeClick = {
                            MediaDownloadManager.resumeDownload(context, item.downloadId)
                        },
                        onRetryClick = {
                            MediaDownloadManager.resumeDownload(context, item.downloadId)
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
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) AccentEmerald.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.04f),
                                border = BorderStroke(1.dp, if (isSelected) AccentEmerald else Color.White.copy(alpha = 0.09f)),
                                modifier = Modifier.clickable { selectedFilterIndex = index }
                            ) {
                                Text(
                                    text = title,
                                    color = if (isSelected) AccentEmerald else TextSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
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
                                onPlayOffline(item)
                            },
                            onDeleteClick = {
                                MediaDownloadManager.deleteDownload(context, item.downloadId)
                                MediaDownloadManager.refreshDownloadStatuses(context)
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

    if (showShareDialog) {
        AppShareDialog(onDismiss = { showShareDialog = false })
    }

    if (showBgDownloadPermissionDialog) {
        BackgroundDownloadPermissionDialog(
            onGrantClick = {
                showBgDownloadPermissionDialog = false
                BackgroundDownloadHelper.setPromptedInitialPermission(context, true)
                BackgroundDownloadHelper.requestIgnoreBatteryOptimization(context)
                Toast.makeText(context, "ব্যাকগ্রাউন্ড পারমিশন উইন্ডো খোলা হয়েছে। 'Allow' বা 'No restrictions' দিন।", Toast.LENGTH_LONG).show()
            },
            onDismiss = {
                showBgDownloadPermissionDialog = false
                BackgroundDownloadHelper.setPromptedInitialPermission(context, true)
            }
        )
    }

    if (showTurboSponsorDialog) {
        SponsorSupportDialog(
            videoTitle = "একসাথে ৩টি ভিডিও ডাউনলোড",
            onSupportClick = {
                val intent = Intent(context, SponsorWebViewActivity::class.java).apply {
                    putExtra("EXTRA_TARGET_URL", SponsorAdManager.getPlacementUrl(SponsorAdManager.PLACEMENT_DOWNLOAD_TURBO))
                    putExtra("EXTRA_IS_DOWNLOAD_AD", true)
                }
                turboSponsorLauncher.launch(intent)
            },
            onCancel = {
                showTurboSponsorDialog = false
            }
        )
    }
}
}

@Composable
fun DownloadConcurrencySelectorCard(
    maxConcurrent: Int,
    isTurboUnlocked: Boolean,
    activeCount: Int,
    pendingCount: Int,
    onSelectLimit: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Speed",
                        tint = if (maxConcurrent == 3) AccentCyan else AccentEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "ডাউনলোড কিউ স্পিড",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (activeCount > 0 || pendingCount > 0) {
                    Text(
                        text = "${activeCount}টি চলছে${if (pendingCount > 0) " • ${pendingCount}টি লাইনে" else ""}",
                        color = if (maxConcurrent == 3) AccentCyan else AccentEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val is1Selected = maxConcurrent == 1
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (is1Selected) AccentEmerald.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(
                        1.dp,
                        if (is1Selected) AccentEmerald else Color.White.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectLimit(1) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (is1Selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = AccentEmerald,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = "১টি করে (Normal)",
                            color = if (is1Selected) AccentEmerald else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (is1Selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }

                val is3Selected = maxConcurrent == 3
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (is3Selected) AccentCyan.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(
                        1.dp,
                        if (is3Selected) AccentCyan else Color.White.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectLimit(3) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "⚡ একসাথে ৩টি (VIP)",
                            color = if (is3Selected) AccentCyan else TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = if (is3Selected) FontWeight.Bold else FontWeight.Medium
                        )
                        if (is3Selected) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(14.dp)
                            )
                        } else if (!isTurboUnlocked) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = AccentGold.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "AD",
                                    color = AccentGold,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
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
fun BackgroundDownloadStatusBanner(
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isGranted) Color(0xFF0F2624) else Color(0xFF131F33),
        border = BorderStroke(
            1.2.dp,
            if (isGranted) Color(0xFF00F5D4).copy(alpha = 0.5f) else AccentCyan.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isGranted) Color(0x2200F5D4) else Color(0x2200C6FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Bolt,
                        contentDescription = null,
                        tint = if (isGranted) Color(0xFF00F5D4) else AccentCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isGranted) "ব্যাকগ্রাউন্ড ডাউনলোড সক্রিয়" else "ব্যাকগ্রাউন্ড ডাউনলোড চালু করুন",
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isGranted) "অ্যাপ মিনিমাইজ বা স্ক্রিন অফ থাকলেও সচল থাকবে" else "অ্যাপ থেকে বের হলেও যেন ডাউনলোড না থামে",
                        color = if (isGranted) Color(0xFF00F5D4).copy(alpha = 0.85f) else TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isGranted) Color(0xFF00F5D4).copy(alpha = 0.15f) else AccentEmerald.copy(alpha = 0.2f),
                border = BorderStroke(
                    1.dp,
                    if (isGranted) Color(0xFF00F5D4) else AccentEmerald
                ),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = if (isGranted) "Active ✓" else "Turn ON >",
                    color = if (isGranted) Color(0xFF00F5D4) else AccentCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
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
    onDeleteClick: () -> Unit,
    onPauseClick: () -> Unit = {},
    onResumeClick: () -> Unit = {},
    onRetryClick: () -> Unit = {}
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
                    progress = { if (item.status == DownloadStatus.PENDING) 0f else (item.progressPercent / 100f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = when (item.status) {
                        DownloadStatus.FAILED -> Color(0xFFFF5252)
                        DownloadStatus.PAUSED -> AccentGold
                        DownloadStatus.COMPLETED -> AccentEmerald
                        DownloadStatus.PENDING -> Color(0xFF6B7280)
                        else -> AccentCyan
                    },
                    trackColor = Color.White.copy(alpha = 0.1f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusText = when (item.status) {
                        DownloadStatus.FAILED -> "Failed • Tap to retry"
                        DownloadStatus.PAUSED -> "Paused • ${item.formattedSize}"
                        DownloadStatus.COMPLETED -> "Completed"
                        DownloadStatus.PENDING -> "In Queue (Waiting...)"
                        else -> "${item.formattedSize} • ${item.progressPercent}%"
                    }
                    val statusColor = when (item.status) {
                        DownloadStatus.FAILED -> Color(0xFFFF5252)
                        DownloadStatus.PAUSED -> AccentGold
                        DownloadStatus.COMPLETED -> AccentEmerald
                        DownloadStatus.PENDING -> Color(0xFF9CA3AF)
                        else -> TextMuted
                    }
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = if (item.status == DownloadStatus.FAILED) FontWeight.Bold else FontWeight.Normal
                    )

                    // Action Button (Retry / Pause / Resume)
                    when (item.status) {
                        DownloadStatus.FAILED -> {
                            IconButton(onClick = onRetryClick, modifier = Modifier.size(20.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry",
                                    tint = AccentEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        DownloadStatus.PAUSED -> {
                            IconButton(onClick = onResumeClick, modifier = Modifier.size(20.dp)) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Resume",
                                    tint = AccentCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        DownloadStatus.DOWNLOADING, DownloadStatus.PENDING -> {
                            IconButton(onClick = onPauseClick, modifier = Modifier.size(20.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = "Pause",
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        else -> {}
                    }
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
