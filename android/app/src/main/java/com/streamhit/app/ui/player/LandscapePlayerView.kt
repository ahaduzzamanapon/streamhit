package com.streamhit.app.ui.player

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamhit.app.data.model.DubInfo
import com.streamhit.app.data.model.SubjectDetail
import com.streamhit.app.ui.theme.*
import kotlin.math.abs

@OptIn(UnstableApi::class)
@Composable
fun LandscapePlayerView(
    exoPlayer: ExoPlayer?,
    currentVideoTitle: String,
    detail: SubjectDetail?,
    selectedDub: DubInfo?,
    isBuffering: Boolean,
    loadingStatusText: String,
    isPlaying: Boolean,
    currentPosition: Long,
    totalDuration: Long,
    bufferedPosition: Long,
    bufferedAheadMs: Long,
    playbackSpeed: Float,
    isFastForwarding2x: Boolean,
    onFastForward2xChange: (Boolean) -> Unit,
    showControls: Boolean,
    isLocked: Boolean,
    onToggleLock: () -> Unit,
    resizeMode: Int,
    onToggleResizeMode: () -> Unit,
    onEnterPip: () -> Unit,
    onExitFullscreen: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCyclePlaybackSpeed: () -> Unit,
    onSwitchDub: (DubInfo) -> Unit,
    hasNextEpisode: Boolean,
    onPlayNextEpisode: () -> Unit,
    gestureDetector: GestureDetector,
    volumePercent: Int,
    brightnessPercent: Int,
    showVolumeOverlay: Boolean,
    showBrightnessOverlay: Boolean,
    onVolumeAdjust: (Int) -> Unit,
    onBrightnessAdjust: (Int) -> Unit,
    onResetOverlayToken: () -> Unit,
    audioSyncOffsetMs: Long = 0L,
    onOpenAudioSync: () -> Unit = {}
) {
    var initialTouchX by remember { mutableFloatStateOf(0f) }
    var initialTouchY by remember { mutableFloatStateOf(0f) }
    var isHorizontalScrub by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) }
    var scrubStartPos by remember { mutableLongStateOf(0L) }
    var showSeekOverlay by remember { mutableStateOf(false) }
    var seekTargetTime by remember { mutableLongStateOf(0L) }
    var seekDeltaText by remember { mutableStateOf("") }

    val currentExoPlayer by rememberUpdatedState(exoPlayer)
    val currentPlaybackSpeed by rememberUpdatedState(playbackSpeed)
    val currentIsFastForwarding by rememberUpdatedState(isFastForwarding2x)
    val currentTotalDuration by rememberUpdatedState(totalDuration)
    val currentIsLocked by rememberUpdatedState(isLocked)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ExoPlayer View
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.resizeMode = resizeMode
                    this.player = exoPlayer
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // Touch & Gesture Handler
        AndroidView(
            factory = { ctx ->
                View(ctx)
            },
            update = { view ->
                view.setOnTouchListener { _, event ->
                    if (currentIsLocked) {
                        val displayMetrics = view.context.resources.displayMetrics
                        val heightPx = displayMetrics.heightPixels.toFloat()
                        val density = displayMetrics.density

                        // Unlock button hit target at Alignment.CenterStart
                        val unlockMinX = 0f
                        val unlockMaxX = 100f * density
                        val centerY = heightPx / 2f
                        val unlockMinY = centerY - (50f * density)
                        val unlockMaxY = centerY + (50f * density)

                        val isOverUnlock = (event.x in unlockMinX..unlockMaxX) && (event.y in unlockMinY..unlockMaxY)

                        if (event.action == MotionEvent.ACTION_UP) {
                            if (showControls && isOverUnlock) {
                                onToggleLock()
                            } else {
                                gestureDetector.onTouchEvent(event)
                            }
                        } else {
                            gestureDetector.onTouchEvent(event)
                        }
                        return@setOnTouchListener true
                    }

                    if (currentIsFastForwarding) {
                        isHorizontalScrub = false
                        isVerticalSwipe = false
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
                    val heightPx = displayMetrics.heightPixels.toFloat()

                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialTouchX = event.x
                            initialTouchY = event.y
                            isHorizontalScrub = false
                            isVerticalSwipe = false
                            scrubStartPos = currentExoPlayer?.currentPosition ?: 0L
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = event.x - initialTouchX
                            val deltaY = initialTouchY - event.y

                            if (!isHorizontalScrub && !isVerticalSwipe) {
                                if (abs(deltaX) > 40 && abs(deltaX) > abs(deltaY)) {
                                    isHorizontalScrub = true
                                } else if (abs(deltaY) > 40) {
                                    isVerticalSwipe = true
                                }
                            }

                            if (isHorizontalScrub && widthPx > 0f) {
                                val duration = if (currentTotalDuration > 0L) currentTotalDuration else (currentExoPlayer?.duration ?: 0L)
                                val seekDeltaMs = ((deltaX / widthPx) * 90000f).toLong()
                                val target = (scrubStartPos + seekDeltaMs).coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE)
                                seekTargetTime = target
                                seekDeltaText = if (seekDeltaMs >= 0) "+${seekDeltaMs / 1000}s" else "${seekDeltaMs / 1000}s"
                                showSeekOverlay = true
                            } else if (isVerticalSwipe && heightPx > 0f) {
                                val step = ((deltaY / heightPx) * 120f).toInt()
                                if (initialTouchX > (widthPx / 2f)) {
                                    onVolumeAdjust(step)
                                    initialTouchY = event.y
                                } else {
                                    onBrightnessAdjust(step)
                                    initialTouchY = event.y
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (isHorizontalScrub && showSeekOverlay) {
                                currentExoPlayer?.seekTo(seekTargetTime)
                                onSeekTo(seekTargetTime)
                                showSeekOverlay = false
                            }
                            onFastForward2xChange(false)
                            currentExoPlayer?.setPlaybackSpeed(currentPlaybackSpeed)
                            onResetOverlayToken()
                            isHorizontalScrub = false
                            isVerticalSwipe = false
                        }
                    }
                    true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2X Speed Hold Indicator Badge
        if (isFastForwarding2x) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 36.dp)
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

        // Next Episode Countdown card (Landscape)
        if (hasNextEpisode && totalDuration > 45000L && (totalDuration - currentPosition) in 1000L..35000L) {
            val remainingSecs = ((totalDuration - currentPosition) / 1000L).coerceAtLeast(1)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 64.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .border(1.dp, AccentEmerald, RoundedCornerShape(10.dp))
                    .clickable { onPlayNextEpisode() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Next Ep in ${remainingSecs}s", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Play Now", color = AccentEmerald, fontSize = 10.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Gesture Overlays
        if (showVolumeOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (volumePercent > 100) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                        contentDescription = "Volume",
                        tint = if (volumePercent > 100) AccentGold else AccentEmerald,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (volumePercent > 100) "Boost: $volumePercent%" else "Volume: $volumePercent%",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        if (showBrightnessOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BrightnessMedium, contentDescription = "Brightness", tint = AccentCyan, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Brightness: $brightnessPercent%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        if (showSeekOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(seekDeltaText, color = AccentEmerald, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text("${formatDuration(seekTargetTime)} / ${formatDuration(totalDuration)}", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        // Landscape Controls Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isLocked) {
                    IconButton(
                        onClick = onToggleLock,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 24.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = "Unlock", tint = AccentEmerald)
                    }
                    return@Box
                }

                // Top Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onExitFullscreen) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = currentVideoTitle,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Landscape Dub Language Selector
                            if ((detail?.dubs?.size ?: 0) > 1) {
                                var landscapeLangDropdownOpen by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { landscapeLangDropdownOpen = true }) {
                                        Icon(Icons.Default.Translate, contentDescription = "Audio Language", tint = Color.White)
                                    }
                                    DropdownMenu(
                                        expanded = landscapeLangDropdownOpen,
                                        onDismissRequest = { landscapeLangDropdownOpen = false },
                                        modifier = Modifier.background(BgCard)
                                    ) {
                                        (detail?.dubs ?: emptyList()).forEach { dub ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = dub.name.ifBlank { dub.language },
                                                        color = if (selectedDub == dub) AccentEmerald else TextPrimary,
                                                        fontWeight = if (selectedDub == dub) FontWeight.Bold else FontWeight.Normal,
                                                        fontSize = 12.sp
                                                    )
                                                },
                                                onClick = {
                                                    landscapeLangDropdownOpen = false
                                                    onSwitchDub(dub)
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            IconButton(onClick = onOpenAudioSync) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "Audio Sync",
                                    tint = if (audioSyncOffsetMs != 0L) AccentEmerald else Color.White
                                )
                            }

                            IconButton(onClick = onToggleResizeMode) {
                                Icon(Icons.Default.AspectRatio, contentDescription = "Fit/Zoom", tint = Color.White)
                            }

                            IconButton(onClick = onEnterPip) {
                                Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                            }

                            IconButton(onClick = onToggleLock) {
                                Icon(Icons.Default.LockOpen, contentDescription = "Lock", tint = Color.White)
                            }
                        }
                    }
                }

                // Center Controls / Buffering Loader with dynamic status
                if (isBuffering) {
                    Box(
                        modifier = Modifier.align(Alignment.Center),
                        contentAlignment = Alignment.Center
                    ) {
                        PlayerBufferingIndicator(
                            statusText = loadingStatusText,
                            isLarge = true
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onRewind10,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(48.dp)
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = "Rewind", tint = Color.White)
                        }

                        IconButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier.background(AccentEmerald, CircleShape).size(62.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.Black,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        IconButton(
                            onClick = onForward10,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(48.dp)
                        ) {
                            Icon(Icons.Default.Forward10, contentDescription = "Forward", tint = Color.White)
                        }

                        if (hasNextEpisode) {
                            IconButton(
                                onClick = onPlayNextEpisode,
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(48.dp)
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next Episode", tint = AccentEmerald)
                            }
                        }
                    }
                }

                // Bottom Timeline Bar with Rotate Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Column {
                        val bufferedRatio = if (totalDuration > 0L) {
                            (bufferedPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            // Background base track & buffered progress track
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                                    .height(4.dp)
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

                            // Playback active track & thumb
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
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (bufferedAheadMs > 0L && totalDuration > 0L) {
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "•  Buffer: ${formatBufferedAhead(bufferedAheadMs)}",
                                        color = AccentEmerald.copy(alpha = 0.85f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text(
                                    text = "${playbackSpeed}x",
                                    color = AccentCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                        .clickable { onCyclePlaybackSpeed() }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )

                                IconButton(
                                    onClick = onExitFullscreen,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.FullscreenExit, contentDescription = "Exit Fullscreen", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
