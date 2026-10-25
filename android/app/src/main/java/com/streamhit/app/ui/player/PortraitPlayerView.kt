package com.streamhit.app.ui.player

import android.content.Context
import android.content.Intent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.streamhit.app.ui.components.ShareAppBannerCard
import com.streamhit.app.ui.components.AppShareDialog
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamhit.app.data.model.*
import com.streamhit.app.ui.components.MediaCard
import com.streamhit.app.ui.theme.*
import kotlin.math.abs

@OptIn(UnstableApi::class)
@Composable
fun PortraitVideoPlayerBox(
    exoPlayer: ExoPlayer?,
    currentVideoTitle: String,
    showControls: Boolean,
    isBuffering: Boolean,
    loadingStatusText: String,
    isPlaying: Boolean,
    currentPosition: Long,
    totalDuration: Long,
    bufferedPosition: Long,
    bufferedAheadMs: Long,
    isFastForwarding2x: Boolean,
    onFastForward2xChange: (Boolean) -> Unit,
    playbackSpeed: Float,
    hasNextEpisode: Boolean,
    onPlayNextEpisode: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onBack: () -> Unit,
    onEnterPip: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onSeekTo: (Long) -> Unit,
    gestureDetector: GestureDetector,
    isLiveStream: Boolean,
    audioSyncOffsetMs: Long = 0L,
    onOpenAudioSync: () -> Unit = {}
) {
    var initialTouchX by remember { mutableFloatStateOf(0f) }
    var initialTouchY by remember { mutableFloatStateOf(0f) }
    var isHorizontalScrub by remember { mutableStateOf(false) }
    var scrubStartPos by remember { mutableLongStateOf(0L) }
    var showSeekOverlay by remember { mutableStateOf(false) }
    var seekTargetTime by remember { mutableLongStateOf(0L) }
    var seekDeltaText by remember { mutableStateOf("") }

    val currentExoPlayer by rememberUpdatedState(exoPlayer)
    val currentPlaybackSpeed by rememberUpdatedState(playbackSpeed)
    val currentIsFastForwarding by rememberUpdatedState(isFastForwarding2x)
    val currentTotalDuration by rememberUpdatedState(totalDuration)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = exoPlayer
                }
            },
            update = { view ->
                view.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize()
        )

        // Touch & Gesture listener on portrait player (Supports 2X Hold, Taps, Swipe-to-Seek, and Double Tap Seek)
        AndroidView(
            factory = { ctx ->
                View(ctx)
            },
            update = { view ->
                view.setOnTouchListener { _, event ->
                    if (currentIsFastForwarding) {
                        isHorizontalScrub = false
                        showSeekOverlay = false
                        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                            onFastForward2xChange(false)
                            currentExoPlayer?.setPlaybackSpeed(currentPlaybackSpeed)
                        }
                        return@setOnTouchListener true
                    }

                    gestureDetector.onTouchEvent(event)

                    val displayMetrics = view.context.resources.displayMetrics
                    val widthPx = displayMetrics.widthPixels.toFloat()

                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialTouchX = event.x
                            initialTouchY = event.y
                            isHorizontalScrub = false
                            scrubStartPos = currentExoPlayer?.currentPosition ?: 0L
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = event.x - initialTouchX
                            val deltaY = initialTouchY - event.y

                            if (!isHorizontalScrub && abs(deltaX) > 40 && abs(deltaX) > abs(deltaY) * 1.3f) {
                                isHorizontalScrub = true
                            }

                            if (isHorizontalScrub && widthPx > 0f) {
                                val duration = if (currentTotalDuration > 0L) currentTotalDuration else (currentExoPlayer?.duration ?: 0L)
                                val seekDeltaMs = ((deltaX / widthPx) * 90000f).toLong()
                                val target = (scrubStartPos + seekDeltaMs).coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE)
                                seekTargetTime = target
                                seekDeltaText = if (seekDeltaMs >= 0) "+${seekDeltaMs / 1000}s" else "${seekDeltaMs / 1000}s"
                                showSeekOverlay = true
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (isHorizontalScrub && showSeekOverlay) {
                                currentExoPlayer?.seekTo(seekTargetTime)
                                onSeekTo(seekTargetTime)
                                showSeekOverlay = false
                                isHorizontalScrub = false
                            }
                            onFastForward2xChange(false)
                            currentExoPlayer?.setPlaybackSpeed(currentPlaybackSpeed)
                        }
                    }
                    true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Scrub Seeking Overlay (Portrait)
        if (showSeekOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                    .border(1.dp, AccentEmerald, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = seekDeltaText,
                        color = AccentEmerald,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${formatDuration(seekTargetTime)} / ${formatDuration(totalDuration)}",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 2X Speed Hold Indicator Badge (Portrait)
        if (isFastForwarding2x) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(20.dp))
                    .border(1.dp, AccentEmerald, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = null,
                        tint = AccentEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "2X Speed >>",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Next Episode Countdown card (Portrait)
        if (hasNextEpisode && totalDuration > 45000L && (totalDuration - currentPosition) in 1000L..35000L) {
            val remainingSecs = ((totalDuration - currentPosition) / 1000L).coerceAtLeast(1)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 32.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .border(1.dp, AccentEmerald, RoundedCornerShape(10.dp))
                    .clickable { onPlayNextEpisode() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Next Ep in ${remainingSecs}s", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Play Now", color = AccentEmerald, fontSize = 9.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(14.dp))
                }
            }
        }

        // Always show buffer loader if buffering and controls hidden
        if (isBuffering && !showControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                PlayerBufferingIndicator(
                    statusText = loadingStatusText,
                    isLarge = false
                )
            }
        }

        // Portrait Player Controls Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // Top Bar: Back, Title, PiP
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(onClick = onBack, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentVideoTitle,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onOpenAudioSync, modifier = Modifier.size(30.dp)) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Audio Sync",
                                tint = if (audioSyncOffsetMs != 0L) AccentEmerald else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onEnterPip, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                        }
                    }
                }

                // Center Play / Rewind / Forward OR Buffering Loader
                if (isBuffering) {
                    Box(
                        modifier = Modifier.align(Alignment.Center),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayerBufferingIndicator(
                            statusText = loadingStatusText,
                            isLarge = false
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onRewind10,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(36.dp)
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = "Rewind", tint = Color.White)
                        }

                        IconButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier.background(AccentEmerald, CircleShape).size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.Black,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(
                            onClick = onForward10,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(36.dp)
                        ) {
                            Icon(Icons.Default.Forward10, contentDescription = "Forward", tint = Color.White)
                        }

                        if (hasNextEpisode) {
                            IconButton(
                                onClick = onPlayNextEpisode,
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(36.dp)
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next Episode", tint = AccentEmerald)
                            }
                        }
                    }
                }

                // Bottom Bar: Progress / LIVE indicator + Fullscreen Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    if (isLiveStream) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(Color.Red.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Box(modifier = Modifier.size(6.dp).background(Color.Red, CircleShape))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("LIVE STREAM", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            if (bufferedAheadMs > 0L) {
                                Text(
                                    text = "Buffer: ${formatBufferedAhead(bufferedAheadMs)}",
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 10.sp
                                )
                            }

                            IconButton(
                                onClick = onEnterFullscreen,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = "Rotate to Landscape",
                                    tint = Color.White
                                )
                            }
                        }
                    } else {
                        Column {
                            val bufferedRatio = if (totalDuration > 0L) {
                                (bufferedPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                            } else 0f

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                // Base track & buffered track
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp)
                                        .height(3.5.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (bufferedRatio > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction = bufferedRatio)
                                                .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                                        )
                                    }
                                }

                                Slider(
                                    value = currentPosition.toFloat(),
                                    onValueChange = { newPos -> onSeekTo(newPos.toLong()) },
                                    valueRange = 0f..totalDuration.coerceAtLeast(1L).toFloat(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = AccentEmerald,
                                        activeTrackColor = AccentEmerald,
                                        inactiveTrackColor = Color.Transparent
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${formatDuration(currentPosition)} / ${formatDuration(totalDuration)}",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (bufferedAheadMs > 0L) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "•  Buf: ${formatBufferedAhead(bufferedAheadMs)}",
                                            color = AccentEmerald.copy(alpha = 0.85f),
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = onEnterFullscreen,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fullscreen,
                                        contentDescription = "Rotate to Landscape",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PortraitMediaDetails(
    context: Context,
    detail: SubjectDetail?,
    currentVideoTitle: String,
    isLiveStream: Boolean,
    isOffline: Boolean = false,
    currentChannel: LiveTvChannel?,
    activeServers: List<StreamLinkItem>,
    currentServerIndex: Int,
    onSwitchLiveServer: (Int) -> Unit,
    relatedChannels: List<LiveTvChannel>,
    onSwitchLiveChannel: (LiveTvChannel) -> Unit,
    currentSeasonNum: Int,
    currentEpisodeNum: Int,
    selectedDub: DubInfo?,
    resources: List<StreamResource>,
    recommendations: List<SubjectSummary>,
    onSwitchDub: (DubInfo) -> Unit,
    onSwitchEpisode: (Int, Int) -> Unit,
    onSwitchQuality: (StreamResource) -> Unit,
    onOpenDownloadSheet: () -> Unit,
    onRecommendationClick: (SubjectSummary) -> Unit,
    audioSyncOffsetMs: Long = 0L,
    onOpenAudioSync: () -> Unit = {},
    onOpenTranslator: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Title
        Text(
            text = detail?.title ?: currentVideoTitle,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (isLiveStream && !isOffline) {
            // Live TV Details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(AccentEmerald.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("LIVE TV", color = AccentEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(currentChannel?.category ?: "BDIX", color = TextMuted, fontSize = 11.sp)
                }

                IconButton(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Watch $currentVideoTitle on NXLY Live TV: https://nxly.online")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share with"))
                    },
                    modifier = Modifier.size(32.dp).background(BgCard, CircleShape)
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }

            // Alternative Streaming Servers
            if (activeServers.size > 1) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Streaming Servers", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("${activeServers.size} Servers Available", color = TextMuted, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activeServers.forEachIndexed { idx, srv ->
                        val isSelected = currentServerIndex == idx
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) AccentEmerald.copy(alpha = 0.2f) else BgCard,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) AccentEmerald else Color.White.copy(alpha = 0.08f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clickable { onSwitchLiveServer(idx) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(if (isSelected) AccentEmerald else TextMuted, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = srv.label,
                                    color = if (isSelected) AccentEmerald else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Related Channels
            if (relatedChannels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Related Channels", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(currentChannel?.category ?: "Live TV", color = AccentEmerald, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(relatedChannels) { ch ->
                        LiveTvMiniCard(
                            channel = ch,
                            onClick = { onSwitchLiveChannel(ch) }
                        )
                    }
                }
            }
        } else {
            // Movie / Series Metadata Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = AccentGold, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(detail?.imdbRatingValue ?: "8.4", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Text(detail?.releaseDate?.split("-")?.firstOrNull() ?: "2026", color = TextSecondary, fontSize = 12.sp)
                Text(detail?.countryName ?: "HD", color = TextMuted, fontSize = 12.sp)
                Text(detail?.duration ?: "-- min", color = TextMuted, fontSize = 12.sp)

                Box(
                    modifier = Modifier
                        .background((if (isOffline) AccentEmerald else AccentCyan).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(if (isOffline) "OFFLINE" else if (detail?.subjectType == 2) "TV Series" else "Movie", color = if (isOffline) AccentEmerald else AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons Row (Download, AI Dub, Audio Sync & Share)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!isOffline) {
                    Button(
                        onClick = onOpenDownloadSheet,
                        colors = ButtonDefaults.buttonColors(containerColor = BgCard),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).height(36.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = AccentEmerald, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Download", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = onOpenTranslator,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (com.streamhit.app.util.SubtitleTtsManager.isEnabled) AccentEmerald.copy(alpha = 0.25f) else BgCard
                    ),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (com.streamhit.app.util.SubtitleTtsManager.isEnabled) AccentEmerald else Color.White.copy(alpha = 0.1f)
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1.05f).height(36.dp)
                ) {
                    Icon(
                        Icons.Default.RecordVoiceOver,
                        contentDescription = "AI Dub",
                        tint = if (com.streamhit.app.util.SubtitleTtsManager.isEnabled) AccentEmerald else Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (com.streamhit.app.util.SubtitleTtsManager.isEnabled) "AI Dub: ${com.streamhit.app.util.SubtitleTtsManager.targetLanguage.uppercase()}" else "AI Dub",
                        color = if (com.streamhit.app.util.SubtitleTtsManager.isEnabled) AccentEmerald else Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onOpenAudioSync,
                    colors = ButtonDefaults.buttonColors(containerColor = BgCard),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (audioSyncOffsetMs != 0L) AccentEmerald else Color.White.copy(alpha = 0.1f)
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = "Audio Sync",
                        tint = if (audioSyncOffsetMs != 0L) AccentEmerald else Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (audioSyncOffsetMs != 0L) "${if (audioSyncOffsetMs > 0) "+" else ""}${audioSyncOffsetMs}ms" else "Audio Sync",
                        color = if (audioSyncOffsetMs != 0L) AccentEmerald else Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            val path = detail?.detailPath ?: ""
                            putExtra(Intent.EXTRA_TEXT, "Watch ${detail?.title ?: currentVideoTitle} on NXLY: https://nxly.online/movie/${path}")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share with"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BgCard),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    modifier = Modifier.weight(0.9f).height(36.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Share", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Prominent Share This App Card below Player
            var showAppShareDialog by remember { mutableStateOf(false) }
            ShareAppBannerCard(
                onShareClick = { showAppShareDialog = true },
                modifier = Modifier.padding(vertical = 2.dp)
            )

            if (showAppShareDialog) {
                AppShareDialog(onDismiss = { showAppShareDialog = false })
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Synopsis
            if (!detail?.description.isNullOrBlank()) {
                Text(
                    text = detail?.description ?: "",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Resources Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Resources", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("By NXLY", color = AccentEmerald, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Audio Dub Dropdown & Season Dropdown
            val dubs = detail?.dubs ?: emptyList()
            val validSeasons = detail?.seasons?.filter { it.episodes.isNotEmpty() } ?: emptyList()
            if (dubs.isNotEmpty() || validSeasons.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (dubs.isNotEmpty()) {
                        var langDropdownOpen by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BgCard,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { langDropdownOpen = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selectedDub?.name?.ifBlank { selectedDub?.language } ?: "Original Audio",
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                                }
                            }

                            DropdownMenu(
                                expanded = langDropdownOpen,
                                onDismissRequest = { langDropdownOpen = false },
                                modifier = Modifier.background(BgCard)
                            ) {
                                dubs.forEach { dub ->
                                    val isDubActive = (selectedDub?.subjectId?.isNotBlank() == true && selectedDub?.subjectId == dub.subjectId) ||
                                                      (selectedDub == dub)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = dub.name.ifBlank { dub.language },
                                                color = if (isDubActive) AccentEmerald else TextPrimary,
                                                fontWeight = if (isDubActive) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 12.sp
                                            )
                                        },
                                        onClick = {
                                            langDropdownOpen = false
                                            onSwitchDub(dub)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (validSeasons.isNotEmpty()) {
                        var seasonDropdownOpen by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BgCard,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { seasonDropdownOpen = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Season ${if (currentSeasonNum < 10) "0$currentSeasonNum" else "$currentSeasonNum"}",
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                                }
                            }

                            DropdownMenu(
                                expanded = seasonDropdownOpen,
                                onDismissRequest = { seasonDropdownOpen = false },
                                modifier = Modifier.background(BgCard)
                            ) {
                                validSeasons.forEach { s ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Season ${s.seasonNumber}",
                                                color = if (currentSeasonNum == s.seasonNumber) AccentEmerald else TextPrimary,
                                                fontWeight = if (currentSeasonNum == s.seasonNumber) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 12.sp
                                            )
                                        },
                                        onClick = {
                                            seasonDropdownOpen = false
                                            val firstEp = s.episodes.firstOrNull()?.episodeNumber ?: 1
                                            onSwitchEpisode(s.seasonNumber, firstEp)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Compact Episodes Grid
            val activeSeason = validSeasons.firstOrNull { it.seasonNumber == currentSeasonNum } ?: validSeasons.firstOrNull()
            val episodes = activeSeason?.episodes ?: emptyList()

            if (episodes.isNotEmpty()) {
                val epChunks = episodes.chunked(6)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    epChunks.forEach { rowEpisodes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowEpisodes.forEach { ep ->
                                val isSelected = currentEpisodeNum == ep.episodeNumber
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) AccentEmerald else BgCard,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) AccentEmerald else Color.White.copy(alpha = 0.08f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clickable {
                                            onSwitchEpisode(currentSeasonNum, ep.episodeNumber)
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (ep.episodeNumber < 10) "0${ep.episodeNumber}" else "${ep.episodeNumber}",
                                            color = if (isSelected) Color.Black else TextPrimary,
                                            fontSize = 12.sp,
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
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Compact Streaming Servers
            if (resources.isNotEmpty()) {
                Text("Available Streaming Servers", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    resources.forEachIndexed { idx, res ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BgCard,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSwitchQuality(res) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .background(AccentEmerald.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(res.displayResolution, color = AccentEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Server ${idx + 1} • High Speed", color = TextPrimary, fontSize = 12.sp)
                                }

                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Compact For You Recommendations
            if (recommendations.isNotEmpty()) {
                Text("For You", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recommendations) { rec ->
                        MediaCard(
                            item = rec,
                            onClick = { onRecommendationClick(rec) }
                        )
                    }
                }
            }
        }
    }
}
