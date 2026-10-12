package com.streamhit.app.ui.details

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhit.app.data.model.StreamResource
import com.streamhit.app.data.model.SubjectDetail
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DetailsScreen(
    repository: MovieRepository,
    detailPath: String,
    subjectId: String,
    onBackClick: () -> Unit,
    onPlayClick: (streamUrl: String, title: String, coverUrl: String) -> Unit,
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
    val scope = rememberCoroutineScope()

    LaunchedEffect(detailPath, subjectId) {
        isLoading = true
        isResourcesLoading = true
        val d = repository.getDetail(detailPath, subjectId)
        detail = d
        selectedDub = d?.dubs?.firstOrNull { it.original == true } ?: d?.dubs?.firstOrNull()
        isLoading = false

        val sId = selectedDub?.subjectId?.ifBlank { d?.subjectId?.ifEmpty { subjectId } } ?: subjectId
        val dPath = selectedDub?.detailPath?.ifBlank { d?.detailPath?.ifEmpty { detailPath } } ?: detailPath
        val resList = repository.getStreamingResources(sId, dPath, 1, 1)
        resources = resList
        isResourcesLoading = false
    }

    fun playStream(res: StreamResource, season: Int = 1, episode: Int = 1) {
        val title = detail?.title ?: "Stream"
        val epText = if (detail?.subjectType == 2) " - S${season}E${episode}" else ""
        val cover = detail?.coverUrl ?: ""
        if (res.url.isNotBlank()) {
            onPlayClick(res.url, "$title$epText (${res.displayResolution})", cover)
        } else {
            Toast.makeText(context, "Streaming link unavailable for this server.", Toast.LENGTH_SHORT).show()
        }
    }

    fun playBestContent(season: Int = 1, episode: Int = 1) {
        val sId = selectedDub?.subjectId?.ifBlank { detail?.subjectId?.ifEmpty { subjectId } } ?: subjectId
        val dPath = selectedDub?.detailPath?.ifBlank { detail?.detailPath?.ifEmpty { detailPath } } ?: detailPath
        val title = detail?.title ?: "Stream"

        // If we already have resources for season 1 episode 1
        if (season == 1 && episode == 1 && resources.isNotEmpty()) {
            val best = resources.maxByOrNull { it.resolution ?: 720 } ?: resources.first()
            playStream(best, season, episode)
            return
        }

        isResolvingStream = true
        scope.launch {
            val resList = repository.getStreamingResources(sId, dPath, season, episode)
            if (season == 1 && episode == 1) {
                resources = resList
            }
            isResolvingStream = false
            var best = resList.maxByOrNull { it.resolution ?: 720 } ?: resList.firstOrNull()

            // Fallback: If no streams found, try candidate dubs (original or other language)
            if (best == null || best.url.isBlank()) {
                val candidateDubs = detail?.dubs?.filter { it.subjectId.isNotBlank() && it.subjectId != sId } ?: emptyList()
                for (dub in candidateDubs) {
                    val fallbackList = repository.getStreamingResources(dub.subjectId, dub.detailPath.ifBlank { dPath }, season, episode)
                    val fallbackBest = fallbackList.maxByOrNull { it.resolution ?: 720 } ?: fallbackList.firstOrNull()
                    if (fallbackBest != null && fallbackBest.url.isNotBlank()) {
                        resources = fallbackList
                        selectedDub = dub
                        playStream(fallbackBest, season, episode)
                        return@launch
                    }
                }
            }

            if (best != null && best.url.isNotBlank()) {
                playStream(best, season, episode)
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

    Box(modifier = modifier.fillMaxSize().background(BgDark)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 60.dp)
        ) {
            // Backdrop Header
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
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

                    // Big Watch Now Button (Compact & Modern)
                    Button(
                        onClick = { playBestContent(1, 1) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentEmerald,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(50.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        enabled = !isResolvingStream
                    ) {
                        if (isResolvingStream) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connecting Server...", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Watch Online Now", fontSize = 15.sp, fontWeight = FontWeight.Black)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Action Buttons Row (Add to list, Share, Download - Screenshot 5)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Add to list
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    Toast.makeText(context, "Added to My List", Toast.LENGTH_SHORT).show()
                                }
                                .padding(8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Add to list", color = TextSecondary, fontSize = 11.sp)
                        }

                        // Share
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, "Watch ${item.title} on Streamfit: https://nxly.online/movie/${detailPath}")
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share with"))
                                }
                                .padding(8.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Share", color = TextSecondary, fontSize = 11.sp)
                        }

                        // Download
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable {
                                    showDownloadSheet = true
                                }
                                .padding(8.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Download", tint = AccentEmerald, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Download", color = AccentEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

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
                                            selectedDub = dub
                                            languageDropdownExpanded = false
                                            val dubSid = dub.subjectId.ifBlank { subjectId }
                                            val dubPath = dub.detailPath.ifBlank { detailPath }
                                            scope.launch {
                                                isResourcesLoading = true
                                                val res = repository.getStreamingResources(dubSid, dubPath, selectedSeasonIndex + 1, selectedEpisodeNumber)
                                                resources = res
                                                isResourcesLoading = false
                                                Toast.makeText(context, "Switched to ${dub.name}", Toast.LENGTH_SHORT).show()
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BgCard, RoundedCornerShape(10.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                                        .clickable { playStream(res, 1, 1) }
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
            if (item.subjectType == 2 && !item.seasons.isNullOrEmpty()) {
                val seasons = item.seasons
                val currentSeason = seasons.getOrNull(selectedSeasonIndex) ?: seasons.first()
                val episodes = currentSeason.episodes

                item {
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
                                                    selectedEpisodeNumber = 1
                                                    scope.launch {
                                                        isResourcesLoading = true
                                                        val sid = selectedDub?.subjectId?.ifBlank { subjectId } ?: subjectId
                                                        val path = selectedDub?.detailPath?.ifBlank { detailPath } ?: detailPath
                                                        resources = repository.getStreamingResources(sid, path, s.seasonNumber, 1)
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

                        // Episode Grid (screenshot style: 01, 02, 03... chips in rows of 6)
                        val epChunks = episodes.chunked(6)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            epChunks.forEach { rowEpisodes ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowEpisodes.forEach { ep ->
                                        val isSelected = selectedEpisodeNumber == ep.episodeNumber
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected) AccentEmerald else BgCard,
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                if (isSelected) AccentEmerald else Color.White.copy(alpha = 0.08f)
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                                .clickable {
                                                    selectedEpisodeNumber = ep.episodeNumber
                                                    playBestContent(currentSeason.seasonNumber, ep.episodeNumber)
                                                }
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (ep.episodeNumber < 10) "0${ep.episodeNumber}" else "${ep.episodeNumber}",
                                                    color = if (isSelected) Color.Black else TextPrimary,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    repeat(6 - rowEpisodes.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Top Back Button
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .padding(start = 12.dp, top = 36.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50.dp))
                .size(40.dp)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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

        // Download Quality Bottom Sheet Modal (Screenshot 4)
        if (showDownloadSheet) {
            DownloadQualityBottomSheet(
                resources = resources,
                title = item.title,
                onDismiss = { showDownloadSheet = false },
                onDownloadSelected = { res ->
                    showDownloadSheet = false
                    val sId = item.subjectId.ifEmpty { subjectId }
                    val dPath = item.detailPath.ifEmpty { detailPath }
                    val downloadId = com.streamhit.app.data.local.MediaDownloadManager.startDownload(
                        context = context,
                        subjectId = sId,
                        detailPath = dPath,
                        title = item.title,
                        coverUrl = item.coverUrl,
                        quality = res.displayResolution,
                        downloadUrl = res.url
                    )
                    if (downloadId != -1L) {
                        isDownloadingActive = true
                        Toast.makeText(context, "Downloading ${res.displayResolution} ${item.title}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to start download.", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}
