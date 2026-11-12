package com.streamhit.app.ui.details

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhit.app.data.model.StreamResource
import com.streamhit.app.data.model.SubjectDetail
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.components.AppBrandHeader
import com.streamhit.app.ui.components.AppShareDialog
import com.streamhit.app.ui.components.BackgroundDownloadPermissionDialog
import com.streamhit.app.ui.components.ShareAppBannerCard
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.streamhit.app.util.DeviceUtils
import com.streamhit.app.ui.components.SponsorSupportDialog
import com.streamhit.app.ui.sponsor.SponsorWebViewActivity
import com.streamhit.app.util.BackgroundDownloadHelper
import com.streamhit.app.util.SponsorAdManager
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun DetailsScreen(
    repository: MovieRepository,
    detailPath: String,
    subjectId: String,
    onBackClick: () -> Unit,
    onPlayClick: (
        streamUrl: String,
        title: String,
        coverUrl: String,
        seasonNum: Int,
        episodeNum: Int,
        startPositionMs: Long,
        dubLanguage: String?,
        dubSubjectId: String?,
        dubDetailPath: String?,
        dubName: String?
    ) -> Unit,
    onNavigateToDownloads: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var detail by remember { mutableStateOf<SubjectDetail?>(null) }
    var resources by remember { mutableStateOf<List<StreamResource>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isResourcesLoading by remember { mutableStateOf(true) }
    var selectedSeasonIndex by remember { mutableStateOf(0) }
    var selectedEpisodeNumber by remember { mutableIntStateOf(1) }
    var languageDropdownExpanded by remember { mutableStateOf(false) }
    var seasonDropdownExpanded by remember { mutableStateOf(false) }
    var selectedDub by remember { mutableStateOf<com.streamhit.app.data.model.DubInfo?>(null) }
    var isResolvingStream by remember { mutableStateOf(false) }
    var showDownloadSheet by remember { mutableStateOf(false) }
    var isDownloadingActive by remember { mutableStateOf(false) }
    var savedHistory by remember { mutableStateOf<com.streamhit.app.data.local.WatchHistoryItem?>(null) }
    var showAppShareDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isTvDevice = remember { DeviceUtils.isTvDevice(context) }
    val playButtonFocusRequester = remember { FocusRequester() }

    var pendingDownloadRes by remember { mutableStateOf<StreamResource?>(null) }
    var showDownloadSponsorDialog by remember { mutableStateOf(false) }
    var showBgPermissionDialog by remember { mutableStateOf(false) }

    val downloadSponsorLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val res = pendingDownloadRes
            pendingDownloadRes = null
            showDownloadSponsorDialog = false
            if (res != null && detail != null) {
                val item = detail!!
                val sId = item.subjectId.ifEmpty { subjectId }
                val dPath = item.detailPath.ifEmpty { detailPath }
                val downloadId = com.streamhit.app.data.local.MediaDownloadManager.startDownload(
                    context = context,
                    subjectId = sId,
                    detailPath = dPath,
                    title = item.title,
                    coverUrl = item.coverUrl,
                    quality = res.displayResolution,
                    downloadUrl = res.url,
                    isSeries = false
                )
                if (downloadId != -1L) {
                    isDownloadingActive = true
                    Toast.makeText(context, "Downloading ${res.displayResolution} ${item.title}", Toast.LENGTH_SHORT).show()
                    if (!BackgroundDownloadHelper.isBatteryOptimizationIgnored(context) && !BackgroundDownloadHelper.hasPromptedInitialPermission(context)) {
                        showBgPermissionDialog = true
                    }
                } else {
                    Toast.makeText(context, "Failed to start download.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun selectFastestPlayable(resList: List<StreamResource>): StreamResource? {
        if (resList.isEmpty()) return null
        return resList.firstOrNull { it.resolution == 720 }
            ?: resList.firstOrNull { it.resolution == 480 }
            ?: resList.firstOrNull { it.resolution == 1080 }
            ?: resList.firstOrNull { it.resolution == 360 }
            ?: resList.firstOrNull()
    }

    LaunchedEffect(detailPath, subjectId) {
        isLoading = true
        isResourcesLoading = true
        coroutineScope {
            val history = com.streamhit.app.data.local.RecentHistoryManager.getHistoryForSubject(context, subjectId, detailPath)
            savedHistory = history

            val sIdParam = subjectId.ifBlank { detailPath }
            val dDeferred = async { repository.getDetail(detailPath, subjectId) }
            val initialSeason = history?.seasonNum ?: 1
            val initialEp = history?.episodeNum ?: 1

            val resDeferred = if (sIdParam.isNotBlank()) {
                async { repository.getStreamingResources(sIdParam, detailPath, initialSeason, initialEp) }
            } else null

            val d = dDeferred.await()
            detail = d

            val matchedDub = if (history != null) {
                d?.dubs?.firstOrNull { dub ->
                    (!history.dubSubjectId.isNullOrBlank() && dub.subjectId == history.dubSubjectId) ||
                    (!history.dubDetailPath.isNullOrBlank() && dub.detailPath == history.dubDetailPath) ||
                    (!history.dubLanguage.isNullOrBlank() && dub.language.equals(history.dubLanguage, ignoreCase = true)) ||
                    (!history.dubName.isNullOrBlank() && dub.name.equals(history.dubName, ignoreCase = true))
                }
            } else null
            selectedDub = matchedDub ?: d?.dubs?.firstOrNull { it.original == true } ?: d?.dubs?.firstOrNull()

            if (history != null && d?.subjectType == 2) {
                val sIdx = (history.seasonNum - 1).coerceAtLeast(0)
                selectedSeasonIndex = sIdx
                selectedEpisodeNumber = history.episodeNum
            }

            isLoading = false

            var resList = resDeferred?.await() ?: emptyList()
            if (resList.isEmpty()) {
                val sId = selectedDub?.subjectId?.ifBlank { d?.subjectId?.ifEmpty { subjectId } } ?: subjectId
                val dPath = selectedDub?.detailPath?.ifBlank { d?.detailPath?.ifEmpty { detailPath } } ?: detailPath
                resList = repository.getStreamingResources(sId, dPath, initialSeason, initialEp)
            }
            resources = resList
            isResourcesLoading = false
        }
    }

    fun playStream(res: StreamResource, season: Int = 1, episode: Int = 1, startPos: Long = 0L) {
        val title = detail?.title ?: "Stream"
        val epText = if (detail?.subjectType == 2) " - S${season}E${episode}" else ""
        val cover = detail?.coverUrl ?: ""
        if (res.url.isNotBlank()) {
            onPlayClick(
                res.url,
                "$title$epText (${res.displayResolution})",
                cover,
                season,
                episode,
                startPos,
                selectedDub?.language ?: selectedDub?.name,
                selectedDub?.subjectId,
                selectedDub?.detailPath,
                selectedDub?.name
            )
        } else {
            Toast.makeText(context, "Streaming link unavailable for this server.", Toast.LENGTH_SHORT).show()
        }
    }

    fun playBestContent(season: Int = 1, episode: Int = 1, startPos: Long = 0L) {
        // If we already have resources for season & episode
        if (resources.isNotEmpty() && season == (savedHistory?.seasonNum ?: 1) && episode == (savedHistory?.episodeNum ?: 1)) {
            val best = selectFastestPlayable(resources) ?: resources.first()
            playStream(best, season, episode, startPos)
            return
        }

        isResolvingStream = true
        scope.launch {
            val d = detail ?: repository.getDetail(detailPath, subjectId).also { detail = it }
            val currentDub = selectedDub ?: d?.dubs?.firstOrNull { it.original == true } ?: d?.dubs?.firstOrNull()
            if (selectedDub == null && currentDub != null) {
                selectedDub = currentDub
            }

            val sId = currentDub?.subjectId?.ifBlank { d?.subjectId?.ifEmpty { subjectId } } ?: subjectId
            val dPath = currentDub?.detailPath?.ifBlank { d?.detailPath?.ifEmpty { detailPath } } ?: detailPath

            var resList = repository.getStreamingResources(sId, dPath, season, episode)

            // Fallback 1: Try with main detail subjectId & detailPath if different from sId
            if (resList.isEmpty() && d != null && d.subjectId.isNotBlank() && d.subjectId != sId) {
                resList = repository.getStreamingResources(d.subjectId, d.detailPath.ifBlank { dPath }, season, episode)
            }

            // Fallback 2: Try with candidate dubs
            if (resList.isEmpty()) {
                val candidateDubs = d?.dubs?.filter { it.subjectId.isNotBlank() && it.subjectId != sId } ?: emptyList()
                for (dub in candidateDubs) {
                    val fallbackList = repository.getStreamingResources(dub.subjectId, dub.detailPath.ifBlank { dPath }, season, episode)
                    if (fallbackList.isNotEmpty()) {
                        resList = fallbackList
                        selectedDub = dub
                        break
                    }
                }
            }

            resources = resList
            isResolvingStream = false
            val best = selectFastestPlayable(resList)

            if (best != null && best.url.isNotBlank()) {
                playStream(best, season, episode, startPos)
            } else {
                Toast.makeText(context, "Streaming sources are being updated. Please try again shortly.", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (isLoading) {
        Box(
            modifier = modifier.fillMaxSize().background(BgDark),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = AccentEmerald)
        }
        return
    }

    val item = detail
    if (item == null) {
        Box(
            modifier = modifier.fillMaxSize().background(BgDark),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Failed to load details.", color = TextMuted)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onBackClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald)
                ) {
                    Text("Go Back", color = Color.Black)
                }
            }
        }
        return
    }

    val validDetailSeasons = item.seasons?.filter { it.episodes.isNotEmpty() } ?: emptyList()

    Box(modifier = modifier.fillMaxSize().background(BgDark)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            // Edge-to-Edge Backdrop Header (Pure Netflix/Apple TV Cinema Style)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isTvDevice) 360.dp else 270.dp)
                ) {
                    AsyncImage(
                        model = item.coverUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Gradient
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.4f),
                                        Color.Transparent,
                                        BgDark.copy(alpha = 0.8f),
                                        BgDark
                                    )
                                )
                            )
                    )
                }
            }

            // Info Section
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    // Title
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Meta Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Rating
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = AccentGold, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(item.imdbRatingValue ?: "7.5", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        // Year
                        Text(item.releaseDate?.split("-")?.firstOrNull() ?: "2026", color = TextSecondary, fontSize = 13.sp)

                        // Duration
                        Text(item.duration ?: "-- min", color = TextMuted, fontSize = 13.sp)

                        // Type badge
                        Box(
                            modifier = Modifier
                                .background(AccentCyan.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(if (item.subjectType == 2) "TV Series" else "Movie", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Big Watch / Resume Button
                    val resumeTargetSeason = savedHistory?.seasonNum ?: 1
                    val resumeTargetEpisode = savedHistory?.episodeNum ?: 1
                    val resumeTargetPos = savedHistory?.positionMs ?: 0L
                    val hasResume = savedHistory != null && (savedHistory!!.positionMs > 2000L || savedHistory!!.seasonNum > 1 || savedHistory!!.episodeNum > 1)

                    val buttonLabel = when {
                        isResolvingStream -> "Connecting Server..."
                        hasResume && item.subjectType == 2 -> "Resume S${resumeTargetSeason} E${resumeTargetEpisode} (${savedHistory?.progressPercent}%)"
                        hasResume -> "Resume Movie (${savedHistory?.progressPercent}%)"
                        else -> "Watch Online Now"
                    }

                    val infinitePulse = rememberInfiniteTransition(label = "watchPulse")
                    val pulseScale by infinitePulse.animateFloat(
                        initialValue = 0.985f,
                        targetValue = 1.015f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "watchScale"
                    )

                    LaunchedEffect(item) {
                        if (isTvDevice && item != null) {
                            kotlinx.coroutines.delay(300)
                            try {
                                playButtonFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    }

                    val playBtnInteractionSource = remember { MutableInteractionSource() }
                    val isPlayBtnFocused by playBtnInteractionSource.collectIsFocusedAsState()

                    Button(
                        onClick = {
                            if (hasResume) {
                                playBestContent(resumeTargetSeason, resumeTargetEpisode, resumeTargetPos)
                            } else {
                                val sNum = detail?.seasons?.firstOrNull()?.seasonNumber ?: 1
                                val epNum = detail?.seasons?.firstOrNull()?.episodes?.firstOrNull()?.episodeNumber ?: 1
                                playBestContent(sNum, epNum, 0L)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(),
                        shape = RoundedCornerShape(50.dp),
                        border = if (isPlayBtnFocused) BorderStroke(3.5.dp, Color.White) else null,
                        interactionSource = playBtnInteractionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isTvDevice) 52.dp else 46.dp)
                            .focusRequester(playButtonFocusRequester)
                            .graphicsLayer {
                                scaleX = if (isPlayBtnFocused) 1.05f else pulseScale
                                scaleY = if (isPlayBtnFocused) 1.05f else pulseScale
                            }
                            .background(
                                Brush.horizontalGradient(listOf(Color(0xFF00E676), Color(0xFF00E5FF))),
                                RoundedCornerShape(50.dp)
                            ),
                        enabled = !isResolvingStream
                    ) {
                        if (isResolvingStream) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connecting Server...", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(buttonLabel, fontSize = 15.sp, fontWeight = FontWeight.Black)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Action Buttons Row (Add to list, Share, Download - Screenshot 5)
                    // Cineb/OnStream Modern Action Pill Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Add to list
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.06f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    Toast.makeText(context, "Added to My List", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 9.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("My List", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // Share
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.06f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        val shareText = "🎬 Watch ${item.title} in HD for FREE on NXLY Stream!\n\n👉 Watch Now: https://nxly.online/movie/${detailPath}\n📲 Download App: https://nxly.online/download"
                                        putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share with"))
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 9.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Share", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // Download (Highlighted)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AccentEmerald.copy(alpha = 0.14f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    showDownloadSheet = true
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 9.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = AccentEmerald, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Download", color = AccentEmerald, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Prominent Share App Banner Card
                    ShareAppBannerCard(
                        onShareClick = { showAppShareDialog = true }
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Storyline / Synopsis
                    Text("Synopsis", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.description ?: "No description available.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            // Audio Languages Dropdown (User requested: details page e languegse dropdown e asbe)
            if (!item.dubs.isNullOrEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text("Audio Language", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Box {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = BgCard,
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.5f)),
                                modifier = Modifier.clickable { languageDropdownExpanded = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Language, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = selectedDub?.name?.ifBlank { selectedDub?.language } ?: "Audio Dub",
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextMuted)
                                }
                            }

                            DropdownMenu(
                                expanded = languageDropdownExpanded,
                                onDismissRequest = { languageDropdownExpanded = false },
                                modifier = Modifier.background(BgCard)
                            ) {
                                item.dubs.forEach { dub ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = dub.name.ifBlank { dub.language },
                                                color = if (selectedDub == dub) AccentEmerald else TextPrimary,
                                                fontWeight = if (selectedDub == dub) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            val targetDub = dub
                                            selectedDub = targetDub
                                            languageDropdownExpanded = false
                                            val dubSid = targetDub.subjectId.ifBlank { subjectId }
                                            val dubPath = targetDub.detailPath.ifBlank { if (dubSid == subjectId) detailPath else "" }
                                            scope.launch {
                                                isResourcesLoading = true
                                                try {
                                                    val newDetail = repository.getDetail(dubPath, dubSid)
                                                    if (newDetail != null) {
                                                        val mergedDubs = if (!newDetail.dubs.isNullOrEmpty()) newDetail.dubs else detail?.dubs
                                                        detail = newDetail.copy(dubs = mergedDubs)
                                                        val currentSeasons = newDetail.seasons?.filter { it.episodes.isNotEmpty() } ?: emptyList()
                                                        if (currentSeasons.isNotEmpty()) {
                                                            if (selectedSeasonIndex >= currentSeasons.size) {
                                                                selectedSeasonIndex = 0
                                                            }
                                                            val curSeason = currentSeasons.getOrNull(selectedSeasonIndex) ?: currentSeasons.first()
                                                            if (curSeason.episodes.none { it.episodeNumber == selectedEpisodeNumber }) {
                                                                selectedEpisodeNumber = curSeason.episodes.firstOrNull()?.episodeNumber ?: 1
                                                            }
                                                        }
                                                    }
                                                } catch (_: Exception) {}
                                                val activeSeason = detail?.seasons?.filter { it.episodes.isNotEmpty() }?.getOrNull(selectedSeasonIndex)
                                                val curSeasonNum = activeSeason?.seasonNumber ?: (selectedSeasonIndex + 1)
                                                val targetPath = detail?.detailPath?.ifBlank { dubPath } ?: dubPath
                                                val res = repository.getStreamingResources(dubSid, targetPath, curSeasonNum, selectedEpisodeNumber)
                                                resources = res
                                                isResourcesLoading = false
                                                Toast.makeText(context, "Switched to ${targetDub.name.ifBlank { targetDub.language }}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }

            // Available Streaming Resources / Servers Panel
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Available Resources",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "By NXLY",
                                color = AccentEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (resources.isNotEmpty()) {
                            Text(
                                text = "${resources.size} Streams Ready",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isResourcesLoading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BgCard, RoundedCornerShape(10.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(color = AccentEmerald, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Fetching fastest streaming links...", color = TextMuted, fontSize = 12.sp)
                        }
                    } else if (resources.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BgCard, RoundedCornerShape(10.dp))
                                .padding(14.dp)
                        ) {
                            Text("Streaming resources currently loading. Tap Watch Online Now to auto-connect.", color = TextMuted, fontSize = 12.sp)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            resources.forEachIndexed { index, res ->
                                val curS = validDetailSeasons.getOrNull(selectedSeasonIndex) ?: validDetailSeasons.firstOrNull()
                                val targetSeason = if (item.subjectType == 2) (curS?.seasonNumber ?: (selectedSeasonIndex + 1)) else 1
                                val targetEpisode = if (item.subjectType == 2) selectedEpisodeNumber else 1
                                val targetPos = com.streamhit.app.data.local.RecentHistoryManager.getSavedPosition(
                                    context = context,
                                    streamUrl = "",
                                    subjectId = subjectId,
                                    detailPath = detailPath,
                                    seasonNum = targetSeason,
                                    episodeNum = targetEpisode,
                                    subjectType = item.subjectType
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BgCard, RoundedCornerShape(10.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                                        .clickable { playStream(res, targetSeason, targetEpisode, targetPos) }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .background(AccentEmerald.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = res.displayResolution,
                                                color = AccentEmerald,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column {
                                            Text(
                                                text = "Server ${index + 1} • High Speed",
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (res.displaySize.isNotBlank()) {
                                                Text(
                                                    text = res.displaySize,
                                                    color = TextMuted,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(AccentEmerald, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Play Quality",
                                            tint = Color.Black,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            // TV Series Seasons & Episodes Selector (MovieBox / Screenshot 1 Style)
            if (item.subjectType == 2 && validDetailSeasons.isNotEmpty()) {
                item {
                    val seasons = validDetailSeasons
                    val currentSeason = seasons.getOrNull(selectedSeasonIndex) ?: seasons.first()
                    val episodes = currentSeason.episodes

                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Episodes", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                            // Season Dropdown / Tabs
                            if (seasons.size > 1) {
                                Box {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = BgCard,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                        modifier = Modifier.clickable { seasonDropdownExpanded = true }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "Season ${currentSeason.seasonNumber}",
                                                color = AccentEmerald,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = seasonDropdownExpanded,
                                        onDismissRequest = { seasonDropdownExpanded = false },
                                        modifier = Modifier.background(BgCard)
                                    ) {
                                        seasons.forEachIndexed { idx, s ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = "Season ${s.seasonNumber}",
                                                        color = if (selectedSeasonIndex == idx) AccentEmerald else TextPrimary,
                                                        fontWeight = if (selectedSeasonIndex == idx) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                },
                                                onClick = {
                                                    selectedSeasonIndex = idx
                                                    seasonDropdownExpanded = false
                                                    val firstEp = s.episodes.firstOrNull()?.episodeNumber ?: 1
                                                    selectedEpisodeNumber = firstEp
                                                    scope.launch {
                                                        isResourcesLoading = true
                                                        val sid = selectedDub?.subjectId?.ifBlank { subjectId } ?: subjectId
                                                        val path = detail?.detailPath?.ifBlank { selectedDub?.detailPath?.ifBlank { detailPath } } ?: detailPath
                                                        resources = repository.getStreamingResources(sid, path, s.seasonNumber, firstEp)
                                                        isResourcesLoading = false
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text("Season ${currentSeason.seasonNumber}", color = TextMuted, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 4-Column Modern Cineb/OnStream Episode Grid
                        val epChunks = episodes.chunked(4)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            epChunks.forEach { rowEpisodes ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowEpisodes.forEach { ep ->
                                        val isSelected = selectedEpisodeNumber == ep.episodeNumber
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) Color.Transparent else Color(0xFF101728),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                if (isSelected) AccentEmerald else Color.White.copy(alpha = 0.08f)
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp)
                                                .then(
                                                    if (isSelected) {
                                                        Modifier.background(
                                                            Brush.horizontalGradient(
                                                                listOf(Color(0xFF00E676), Color(0xFF00E5FF))
                                                            ),
                                                            RoundedCornerShape(10.dp)
                                                        )
                                                    } else Modifier
                                                )
                                                .clickable {
                                                    selectedEpisodeNumber = ep.episodeNumber
                                                    val epStartPos = com.streamhit.app.data.local.RecentHistoryManager.getSavedPosition(
                                                        context = context,
                                                        streamUrl = "",
                                                        subjectId = subjectId,
                                                        detailPath = detailPath,
                                                        seasonNum = currentSeason.seasonNumber,
                                                        episodeNum = ep.episodeNumber,
                                                        subjectType = 2
                                                    )
                                                    playBestContent(currentSeason.seasonNumber, ep.episodeNumber, epStartPos)
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = null,
                                                    tint = if (isSelected) Color.Black else AccentEmerald,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "EP ${ep.episodeNumber}",
                                                    color = if (isSelected) Color.Black else Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    repeat(4 - rowEpisodes.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Download Bottom Banner (Screenshot 5)
        if (isDownloadingActive) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.BottomCenter),
                colors = CardDefaults.cardColors(containerColor = BgCard),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ArrowCircleDown,
                                contentDescription = "Downloading",
                                tint = AccentEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Downloading 1 file(s)",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        IconButton(
                            onClick = { isDownloadingActive = false },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Watch now while it downloads, with no extra data usage.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onNavigateToDownloads()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
                        ) {
                            Text("View downloads", color = Color.White, fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                playBestContent(1, 1)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald)
                        ) {
                            Text("Watch Now", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Download Bottom Sheet Modal (Series multi-episode download or Movie quality)
        if (showDownloadSheet) {
            if (item.subjectType == 2) {
                val activeSeason = detail?.seasons?.filter { it.episodes.isNotEmpty() }?.getOrNull(selectedSeasonIndex)
                val curSeasonNum = activeSeason?.seasonNumber ?: (selectedSeasonIndex + 1)
                SeriesDownloadBottomSheet(
                    detail = item,
                    repository = repository,
                    initialSeason = curSeasonNum,
                    initialDub = selectedDub,
                    onDismiss = { showDownloadSheet = false }
                )
            } else {
                DownloadQualityBottomSheet(
                    resources = resources,
                    title = item.title,
                    onDismiss = { showDownloadSheet = false },
                    onDownloadSelected = { res ->
                        showDownloadSheet = false
                        if (SponsorAdManager.isPlacementEnabled(SponsorAdManager.PLACEMENT_DOWNLOAD_SINGLE)) {
                            pendingDownloadRes = res
                            showDownloadSponsorDialog = true
                        } else {
                            val sId = item.subjectId.ifEmpty { subjectId }
                            val dPath = item.detailPath.ifEmpty { detailPath }
                            val downloadId = com.streamhit.app.data.local.MediaDownloadManager.startDownload(
                                context = context,
                                subjectId = sId,
                                detailPath = dPath,
                                title = item.title,
                                coverUrl = item.coverUrl,
                                quality = res.displayResolution,
                                downloadUrl = res.url,
                                isSeries = false
                            )
                            if (downloadId != -1L) {
                                isDownloadingActive = true
                                Toast.makeText(context, "Downloading ${res.displayResolution} ${item.title}", Toast.LENGTH_SHORT).show()
                                if (!BackgroundDownloadHelper.isBatteryOptimizationIgnored(context) && !BackgroundDownloadHelper.hasPromptedInitialPermission(context)) {
                                    showBgPermissionDialog = true
                                }
                            } else {
                                Toast.makeText(context, "Failed to start download.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }

        // Floating Frosted Glass Action Bar (Cinema Style)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                modifier = Modifier
                    .size(36.dp)
                    .clickable(onClick = onBackClick)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.50f),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, Color.White.copy(alpha = 0.15f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("NXLY", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    Text("STREAM", color = AccentEmerald, fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
            }

            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.40f)),
                modifier = Modifier
                    .size(36.dp)
                    .clickable { showAppShareDialog = true }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = AccentEmerald,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }

        if (showAppShareDialog) {
            AppShareDialog(onDismiss = { showAppShareDialog = false })
        }

        // Sponsor gate for movie download
        if (showDownloadSponsorDialog && pendingDownloadRes != null) {
            SponsorSupportDialog(
                videoTitle = "${detail?.title ?: ""} (${pendingDownloadRes?.displayResolution})",
                onSupportClick = {
                    val intent = Intent(context, SponsorWebViewActivity::class.java).apply {
                        putExtra("EXTRA_TARGET_URL", SponsorAdManager.getPlacementUrl(SponsorAdManager.PLACEMENT_DOWNLOAD_SINGLE))
                        putExtra("EXTRA_IS_DOWNLOAD_AD", true)
                    }
                    downloadSponsorLauncher.launch(intent)
                },
                onCancel = {
                    showDownloadSponsorDialog = false
                    pendingDownloadRes = null
                }
            )
        }

        // Initial background download permission explanation
        if (showBgPermissionDialog) {
            BackgroundDownloadPermissionDialog(
                onGrantClick = {
                    showBgPermissionDialog = false
                    BackgroundDownloadHelper.setPromptedInitialPermission(context, true)
                    BackgroundDownloadHelper.requestIgnoreBatteryOptimization(context)
                    Toast.makeText(context, "ব্যাকগ্রাউন্ড পারমিশন উইন্ডো খোলা হয়েছে। 'Allow' দিন।", Toast.LENGTH_LONG).show()
                },
                onDismiss = {
                    showBgPermissionDialog = false
                    BackgroundDownloadHelper.setPromptedInitialPermission(context, true)
                }
            )
        }
    }
}
