package com.streamhit.app.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system status and navigation bars for immersive fullscreen
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        // Default landscape orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val streamUrl = intent.getStringExtra("EXTRA_STREAM_URL") ?: ""
        val videoTitle = intent.getStringExtra("EXTRA_VIDEO_TITLE") ?: "Watching"
        val subjectId = intent.getStringExtra("EXTRA_SUBJECT_ID") ?: ""
        val detailPath = intent.getStringExtra("EXTRA_DETAIL_PATH") ?: ""
        val coverUrl = intent.getStringExtra("EXTRA_COVER_URL") ?: ""

        setContent {
            StreamfitTheme {
                MxPlayerScreen(
                    streamUrl = streamUrl,
                    videoTitle = videoTitle,
                    subjectId = subjectId,
                    detailPath = detailPath,
                    coverUrl = coverUrl,
                    onBack = { finish() },
                    onEnterPip = { enterPipMode() }
                )
            }
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (exoPlayer?.isPlaying == true) {
            enterPipMode()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        exoPlayer?.release()
        exoPlayer = null
    }
}

@OptIn(UnstableApi::class)
@Composable
fun MxPlayerScreen(
    streamUrl: String,
    videoTitle: String,
    subjectId: String = "",
    detailPath: String = "",
    coverUrl: String = "",
    onBack: () -> Unit,
    onEnterPip: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var loudnessEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }

    // Controls visibility
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }

    // Playback state
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // Gesture indicator states
    var showVolumeOverlay by remember { mutableStateOf(false) }
    var volumePercent by remember { mutableIntStateOf(100) } // 0 - 200%

    var showBrightnessOverlay by remember { mutableStateOf(false) }
    var brightnessPercent by remember { mutableIntStateOf(50) } // 0 - 100%

    var showSeekOverlay by remember { mutableStateOf(false) }
    var seekTargetTime by remember { mutableLongStateOf(0L) }
    var seekDeltaText by remember { mutableStateOf("") }

    var doubleTapFeedbackText by remember { mutableStateOf("") }
    var showDoubleTapFeedback by remember { mutableStateOf(false) }

    // Max system stream volume
    val maxSystemVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }

    // Initialize ExoPlayer
    DisposableEffect(streamUrl) {
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }

        // Initialize LoudnessEnhancer for 200% volume boost
        val enhancer = try {
            LoudnessEnhancer(player.audioSessionId).apply { enabled = true }
        } catch (_: Exception) {
            null
        }

        exoPlayer = player
        loudnessEnhancer = enhancer

        var hasResumedPosition = false

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    totalDuration = player.duration.coerceAtLeast(0L)
                    if (!hasResumedPosition) {
                        hasResumedPosition = true
                        val savedPos = com.streamhit.app.data.local.RecentHistoryManager.getSavedPosition(context, streamUrl, subjectId)
                        if (savedPos > 3000L && savedPos < player.duration - 5000L) {
                            player.seekTo(savedPos)
                        }
                    }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.widget.Toast.makeText(context, "Streaming playback error. Retrying...", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        player.addListener(listener)

        onDispose {
            if (player.currentPosition > 2000L && player.duration > 0L) {
                com.streamhit.app.data.local.RecentHistoryManager.saveProgress(
                    context = context,
                    subjectId = subjectId,
                    detailPath = detailPath,
                    title = videoTitle,
                    coverUrl = coverUrl,
                    streamUrl = streamUrl,
                    positionMs = player.currentPosition,
                    durationMs = player.duration
                )
            }
            player.removeListener(listener)
            enhancer?.release()
            player.release()
        }
    }

    // Auto-update playback position and save history
    LaunchedEffect(exoPlayer) {
        var tickCount = 0
        while (true) {
            exoPlayer?.let { p ->
                currentPosition = p.currentPosition.coerceAtLeast(0L)
                totalDuration = p.duration.coerceAtLeast(0L)
                tickCount++
                if (tickCount % 10 == 0 && p.currentPosition > 2000L && p.duration > 0L) {
                    com.streamhit.app.data.local.RecentHistoryManager.saveProgress(
                        context = context,
                        subjectId = subjectId,
                        detailPath = detailPath,
                        title = videoTitle,
                        coverUrl = coverUrl,
                        streamUrl = streamUrl,
                        positionMs = p.currentPosition,
                        durationMs = p.duration
                    )
                }
            }
            delay(500)
        }
    }

    // Auto-hide controls after 4 seconds
    LaunchedEffect(showControls) {
        if (showControls && !isLocked) {
            delay(4000)
            showControls = false
        }
    }

    // Gesture controller helpers
    fun setVolumeBoosted(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        volumePercent = clamped
        showVolumeOverlay = true

        if (clamped <= 100) {
            // Normal system volume
            val sysVol = ((clamped / 100f) * maxSystemVolume).roundToInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysVol, 0)
            try { loudnessEnhancer?.setTargetGain(0) } catch (_: Exception) {}
        } else {
            // 100% System volume + Digital Boost up to 200% (+10 dB = 1000 mB)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSystemVolume.toInt(), 0)
            val boostGain = (((clamped - 100) / 100f) * 1000f).roundToInt()
            try { loudnessEnhancer?.setTargetGain(boostGain) } catch (_: Exception) {}
        }
    }

    fun setScreenBrightness(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        brightnessPercent = clamped
        showBrightnessOverlay = true

        val lp = activity.window.attributes
        lp.screenBrightness = (clamped / 100f).coerceIn(0.01f, 1.0f)
        activity.window.attributes = lp
    }

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

        // MX Player Gesture Detection Overlay
        var initialTouchX by remember { mutableFloatStateOf(0f) }
        var initialTouchY by remember { mutableFloatStateOf(0f) }
        var isHorizontalScrub by remember { mutableStateOf(false) }
        var isVerticalSwipe by remember { mutableStateOf(false) }
        var scrubStartPos by remember { mutableLongStateOf(0L) }

        val gestureDetector = remember {
            GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (isLocked) {
                        showControls = !showControls
                    } else {
                        showControls = !showControls
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isLocked) return true
                    val width = context.resources.displayMetrics.widthPixels
                    if (e.x > width / 2) {
                        // Fast forward +10s
                        exoPlayer?.let { p ->
                            p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration))
                        }
                        doubleTapFeedbackText = "+10 sec"
                        showDoubleTapFeedback = true
                    } else {
                        // Rewind -10s
                        exoPlayer?.let { p ->
                            p.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
                        }
                        doubleTapFeedbackText = "-10 sec"
                        showDoubleTapFeedback = true
                    }
                    return true
                }
            })
        }

        // Hide feedback animations
        LaunchedEffect(showVolumeOverlay) {
            if (showVolumeOverlay) { delay(1200); showVolumeOverlay = false }
        }
        LaunchedEffect(showBrightnessOverlay) {
            if (showBrightnessOverlay) { delay(1200); showBrightnessOverlay = false }
        }
        LaunchedEffect(showDoubleTapFeedback) {
            if (showDoubleTapFeedback) { delay(700); showDoubleTapFeedback = false }
        }

        // Raw Touch Handler for Smooth MX Player Drag Gestures
        AndroidView(
            factory = { ctx ->
                View(ctx).apply {
                    setOnTouchListener { _, event ->
                        gestureDetector.onTouchEvent(event)

                        if (isLocked) return@setOnTouchListener true

                        val width = resources.displayMetrics.widthPixels
                        val height = resources.displayMetrics.heightPixels

                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialTouchX = event.x
                                initialTouchY = event.y
                                isHorizontalScrub = false
                                isVerticalSwipe = false
                                scrubStartPos = exoPlayer?.currentPosition ?: 0L
                            }

                            MotionEvent.ACTION_MOVE -> {
                                val deltaX = event.x - initialTouchX
                                val deltaY = initialTouchY - event.y // Up is positive

                                if (!isHorizontalScrub && !isVerticalSwipe) {
                                    if (abs(deltaX) > 40 && abs(deltaX) > abs(deltaY)) {
                                        isHorizontalScrub = true
                                    } else if (abs(deltaY) > 40) {
                                        isVerticalSwipe = true
                                    }
                                }

                                if (isHorizontalScrub) {
                                    // Horizontal progress scrub (like MX Player)
                                    val seekDeltaMs = ((deltaX / width) * 90000).toLong() // drag full width = 90 seconds
                                    val target = (scrubStartPos + seekDeltaMs).coerceIn(0L, totalDuration.coerceAtLeast(1L))
                                    seekTargetTime = target
                                    seekDeltaText = if (seekDeltaMs >= 0) "+${seekDeltaMs / 1000}s" else "${seekDeltaMs / 1000}s"
                                    showSeekOverlay = true
                                } else if (isVerticalSwipe) {
                                    val step = (deltaY / height * 120).toInt()
                                    if (initialTouchX > width / 2) {
                                        // Right side -> Volume (0 - 200%)
                                        setVolumeBoosted(volumePercent + step)
                                        initialTouchY = event.y
                                    } else {
                                        // Left side -> Brightness (0 - 100%)
                                        setScreenBrightness(brightnessPercent + step)
                                        initialTouchY = event.y
                                    }
                                }
                            }

                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (isHorizontalScrub && showSeekOverlay) {
                                    exoPlayer?.seekTo(seekTargetTime)
                                    showSeekOverlay = false
                                }
                                isHorizontalScrub = false
                                isVerticalSwipe = false
                            }
                        }
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── MX Player Indicators (Volume, Brightness, Progress) ──

        // 1. Volume Overlay (0 - 200%)
        if (showVolumeOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (volumePercent > 100) Icons.Default.VolumeUp else if (volumePercent == 0) Icons.Default.VolumeMute else Icons.Default.VolumeDown,
                        contentDescription = "Volume",
                        tint = if (volumePercent > 100) AccentPink else AccentEmerald,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (volumePercent > 100) "Volume Boost: $volumePercent%" else "Volume: $volumePercent%",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (volumePercent / 200f).coerceIn(0f, 1f) },
                        modifier = Modifier.width(140.dp).height(6.dp),
                        color = if (volumePercent > 100) AccentPink else AccentEmerald,
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                }
            }
        }

        // 2. Brightness Overlay (0 - 100%)
        if (showBrightnessOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.BrightnessMedium,
                        contentDescription = "Brightness",
                        tint = AccentCyan,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Brightness: $brightnessPercent%",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (brightnessPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.width(140.dp).height(6.dp),
                        color = AccentCyan,
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                }
            }
        }

        // 3. Fast Seek Progress Overlay
        if (showSeekOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "[$seekDeltaText]",
                        color = AccentEmerald,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${formatDuration(seekTargetTime)} / ${formatDuration(totalDuration)}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 4. Double Tap Feedback (+10s / -10s)
        if (showDoubleTapFeedback) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = doubleTapFeedbackText,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Controls Overlay ──
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                // If Locked: Only show unlock button
                if (isLocked) {
                    IconButton(
                        onClick = { isLocked = false },
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
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = videoTitle,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        // Top Action Icons
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Aspect ratio toggle
                            IconButton(
                                onClick = {
                                    resizeMode = when (resizeMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                }
                            ) {
                                Icon(Icons.Default.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White)
                            }

                            // PiP Mode
                            IconButton(onClick = onEnterPip) {
                                Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                            }

                            // Screen Lock
                            IconButton(onClick = { isLocked = true }) {
                                Icon(Icons.Default.LockOpen, contentDescription = "Lock", tint = Color.White)
                            }
                        }
                    }
                }

                // Center Play/Pause & Seek Buttons
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rewind 10s
                    IconButton(
                        onClick = {
                            exoPlayer?.let { p ->
                                p.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
                            }
                        },
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .size(46.dp)
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Rewind", tint = Color.White)
                    }

                    // Play/Pause
                    IconButton(
                        onClick = {
                            exoPlayer?.let { p ->
                                if (p.isPlaying) p.pause() else p.play()
                            }
                        },
                        modifier = Modifier
                            .background(AccentEmerald, CircleShape)
                            .size(60.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Forward 10s
                    IconButton(
                        onClick = {
                            exoPlayer?.let { p ->
                                p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration))
                            }
                        },
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .size(46.dp)
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward", tint = Color.White)
                    }
                }

                // Bottom Timeline Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Column {
                        // Slider Timeline
                        Slider(
                            value = currentPosition.toFloat(),
                            onValueChange = { newPos ->
                                currentPosition = newPos.toLong()
                                exoPlayer?.seekTo(newPos.toLong())
                            },
                            valueRange = 0f..totalDuration.coerceAtLeast(1L).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = AccentEmerald,
                                activeTrackColor = AccentEmerald,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth().height(20.dp)
                        )

                        // Bottom Row: Times + Speed selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${formatDuration(currentPosition)} / ${formatDuration(totalDuration)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            // Speed Selector
                            Text(
                                text = "${playbackSpeed}x",
                                color = AccentCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                    .clickable {
                                        playbackSpeed = when (playbackSpeed) {
                                            1.0f -> 1.25f
                                            1.25f -> 1.5f
                                            1.5f -> 2.0f
                                            2.0f -> 0.75f
                                            else -> 1.0f
                                        }
                                        exoPlayer?.setPlaybackSpeed(playbackSpeed)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
