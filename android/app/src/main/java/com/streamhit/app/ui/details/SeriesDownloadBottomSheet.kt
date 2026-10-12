package com.streamhit.app.ui.details

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhit.app.data.local.MediaDownloadManager
import com.streamhit.app.data.model.DubInfo
import com.streamhit.app.data.model.SeasonInfo
import com.streamhit.app.data.model.SubjectDetail
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDownloadBottomSheet(
    detail: SubjectDetail,
    repository: MovieRepository,
    initialSeason: Int = 1,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val seasons = detail.seasons ?: emptyList()
    var selectedSeasonNum by remember { mutableIntStateOf(if (seasons.any { it.se == initialSeason }) initialSeason else (seasons.firstOrNull()?.se ?: 1)) }
    var seasonDropdownExpanded by remember { mutableStateOf(false) }

    val dubs = detail.dubs ?: emptyList()
    var selectedDub by remember { mutableStateOf<DubInfo?>(dubs.firstOrNull { it.original == true } ?: dubs.firstOrNull()) }
    var dubDropdownExpanded by remember { mutableStateOf(false) }

    val availableResolutions = listOf(480, 720, 1080)
    var selectedResolution by remember { mutableIntStateOf(720) }

    val currentSeason = seasons.firstOrNull { it.se == selectedSeasonNum } ?: seasons.firstOrNull()
    val episodeList = remember(currentSeason) {
        val maxEp = currentSeason?.maxEp ?: 1
        val allEpStr = currentSeason?.allEp ?: ""
        if (allEpStr.isNotBlank()) {
            allEpStr.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
        } else {
            (1..maxEp).toList()
        }
    }

    val selectedEpisodes = remember { mutableStateListOf<Int>() }

    // Start with episode 1 selected by default if available
    LaunchedEffect(episodeList) {
        selectedEpisodes.clear()
        if (episodeList.isNotEmpty()) {
            selectedEpisodes.add(episodeList.first())
        }
    }

    var isBatchDownloading by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = null
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            // Header Row: Title & Close 'X'
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Download",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Subtitle
            Text(
                text = "Resources Uploaded by NXLY CDN etc. ⓘ",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 14.dp)
            )

            // Selectors Row (Language Dub & Season Dropdowns matching user screenshot)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Dub Selector
                if (dubs.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BgCard, RoundedCornerShape(10.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                                .clickable { dubDropdownExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedDub?.lanName ?: "Audio",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Select Audio",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = dubDropdownExpanded,
                            onDismissRequest = { dubDropdownExpanded = false },
                            modifier = Modifier.background(BgCard)
                        ) {
                            dubs.forEach { dub ->
                                DropdownMenuItem(
                                    text = { Text(dub.lanName, color = if (selectedDub == dub) AccentEmerald else Color.White) },
                                    onClick = {
                                        selectedDub = dub
                                        dubDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Season Selector
                if (seasons.isNotEmpty()) {
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BgCard, RoundedCornerShape(10.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                                .clickable { seasonDropdownExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Season ${String.format("%02d", selectedSeasonNum)}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Select Season",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = seasonDropdownExpanded,
                            onDismissRequest = { seasonDropdownExpanded = false },
                            modifier = Modifier.background(BgCard)
                        ) {
                            seasons.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text("Season ${String.format("%02d", s.se)}", color = if (s.se == selectedSeasonNum) AccentEmerald else Color.White) },
                                    onClick = {
                                        selectedSeasonNum = s.se
                                        seasonDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Resolution Pills Selector ([480P] [720P] [1080P])
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                availableResolutions.forEach { res ->
                    val isSelected = selectedResolution == res
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) Brush.horizontalGradient(listOf(AccentEmerald, AccentCyan))
                                else Brush.linearGradient(listOf(BgCard, BgCard))
                            )
                            .border(
                                1.dp,
                                if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.08f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedResolution = res }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${res}P",
                            color = if (isSelected) Color.Black else TextMuted,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Episodes List Header & Select All Option
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Season ${String.format("%02d", selectedSeasonNum)} Episodes (${episodeList.size})",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = if (selectedEpisodes.size == episodeList.size) "Deselect All" else "Select All",
                    color = AccentEmerald,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable {
                            if (selectedEpisodes.size == episodeList.size) {
                                selectedEpisodes.clear()
                            } else {
                                selectedEpisodes.clear()
                                selectedEpisodes.addAll(episodeList)
                            }
                        }
                        .padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Multi-Select Episode List matching user screenshot
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(episodeList) { ep ->
                    val isChecked = selectedEpisodes.contains(ep)
                    val formattedEp = "S${String.format("%02d", selectedSeasonNum)} E${String.format("%02d", ep)}"
                    // Approximate size per episode based on resolution
                    val estSizeMb = when (selectedResolution) {
                        1080 -> 240
                        720 -> 160
                        else -> 95
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isChecked) Color.White.copy(alpha = 0.04f) else Color.Transparent)
                            .clickable {
                                if (isChecked) selectedEpisodes.remove(ep) else selectedEpisodes.add(ep)
                            }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Radio / Checkbox Circle
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (isChecked) AccentEmerald else Color.Transparent)
                                .border(
                                    width = 1.5.dp,
                                    color = if (isChecked) AccentEmerald else TextMuted.copy(alpha = 0.5f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isChecked) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.Black,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                text = formattedEp,
                                color = if (isChecked) Color.White else TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = "${estSizeMb}.0MB | Ready for download",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom Green Action Button: "Download (N Episodes)"
            Button(
                onClick = {
                    if (selectedEpisodes.isEmpty()) {
                        Toast.makeText(context, "Please select at least 1 episode.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    isBatchDownloading = true
                    val activeSubjectId = selectedDub?.subjectId?.ifBlank { detail.subjectId } ?: detail.subjectId
                    val activeDetailPath = selectedDub?.detailPath?.ifBlank { detail.detailPath } ?: detail.detailPath
                    val seriesName = detail.title
                    val sNum = selectedSeasonNum
                    val episodesToDownload = selectedEpisodes.toList().sorted()

                    scope.launch {
                        var queuedCount = 0
                        for (ep in episodesToDownload) {
                            val epTitle = "$seriesName S${String.format("%02d", sNum)}E${String.format("%02d", ep)}"
                            try {
                                val resList = repository.getStreamingResources(activeSubjectId, activeDetailPath, sNum, ep)
                                val matchingRes = resList.firstOrNull { it.resolution == selectedResolution }
                                    ?: resList.maxByOrNull { it.resolution ?: 720 }
                                    ?: resList.firstOrNull()

                                if (matchingRes != null && matchingRes.url.isNotBlank()) {
                                    MediaDownloadManager.startDownload(
                                        context = context,
                                        subjectId = activeSubjectId,
                                        detailPath = activeDetailPath,
                                        title = epTitle,
                                        coverUrl = detail.coverUrl,
                                        quality = "${selectedResolution}p",
                                        downloadUrl = matchingRes.url,
                                        isSeries = true,
                                        seriesTitle = seriesName,
                                        seasonNum = sNum,
                                        episodeNum = ep
                                    )
                                    queuedCount++
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        isBatchDownloading = false
                        onDismiss()
                        Toast.makeText(
                            context,
                            "Started downloading $queuedCount episodes in background!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                enabled = selectedEpisodes.isNotEmpty() && !isBatchDownloading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentEmerald,
                    disabledContainerColor = AccentEmerald.copy(alpha = 0.4f)
                )
            ) {
                if (isBatchDownloading) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Preparing downloads...",
                        color = Color.Black,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download",
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val countText = if (selectedEpisodes.isNotEmpty()) " (${selectedEpisodes.size})" else ""
                    Text(
                        text = "Download$countText",
                        color = Color.Black,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
