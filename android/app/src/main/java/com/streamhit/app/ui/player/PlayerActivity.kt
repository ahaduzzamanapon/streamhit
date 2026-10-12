package com.streamhit.app.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import coil.compose.AsyncImage
import com.streamhit.app.data.local.MediaDownloadManager
import com.streamhit.app.data.local.RecentHistoryManager
import com.streamhit.app.data.model.DubInfo
import com.streamhit.app.data.model.StreamResource
import com.streamhit.app.data.model.SubjectDetail
import com.streamhit.app.data.model.SubjectSummary
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.components.MediaCard
import com.streamhit.app.ui.details.DownloadQualityBottomSheet
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

        // Start in portrait orientation as requested by user
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val streamUrl = intent.getStringExtra("EXTRA_STREAM_URL") ?: ""
        val videoTitle = intent.getStringExtra("EXTRA_VIDEO_TITLE") ?: "Watching"
        val subjectId = intent.getStringExtra("EXTRA_SUBJECT_ID") ?: ""
        val detailPath = intent.getStringExtra("EXTRA_DETAIL_PATH") ?: ""
        val coverUrl = intent.getStringExtra("EXTRA_COVER_URL") ?: ""

        setContent {
            StreamfitTheme {
                AdaptivePlayerScreen(
                    initialStreamUrl = streamUrl,
                    initialVideoTitle = videoTitle,
                    subjectId = subjectId,
                    detailPath = detailPath,
                    initialCoverUrl = coverUrl,
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
fun AdaptivePlayerScreen(
    initialStreamUrl: String,
    initialVideoTitle: String,
    subjectId: String = "",
    detailPath: String = "",
    initialCoverUrl: String = "",
    onBack: () -> Unit,
    onEnterPip: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val scope = rememberCoroutineScope()
    val repository = remember { MovieRepository() }

    // Orientation / Fullscreen state
    var isFullscreen by remember { mutableStateOf(false) }

    // Handle system back button: return to portrait if in fullscreen
    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    // React to fullscreen toggle
    LaunchedEffect(isFullscreen) {
        val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isFullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Dynamic stream and detail states
    var currentStreamUrl by remember { mutableStateOf(initialStreamUrl) }
    var currentVideoTitle by remember { mutableStateOf(initialVideoTitle) }
    var detail by remember { mutableStateOf<SubjectDetail?>(null) }
    var resources by remember { mutableStateOf<List<StreamResource>>(emptyList()) }
    var recommendations by remember { mutableStateOf<List<SubjectSummary>>(emptyList()) }
    var currentSeasonNum by remember { mutableIntStateOf(1) }
    var currentEpisodeNum by remember { mutableIntStateOf(1) }
    var selectedDub by remember { mutableStateOf<DubInfo?>(null) }
    var showDownloadSheet by remember { mutableStateOf(false) }

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

    val maxSystemVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }

    // Load detail & available resources & recommendations
    LaunchedEffect(detailPath, subjectId) {
        if (detailPath.isNotBlank() || subjectId.isNotBlank()) {
            val d = repository.getDetail(detailPath, subjectId)
            detail = d
            selectedDub = d?.dubs?.firstOrNull { it.original == true } ?: d?.dubs?.firstOrNull()

            val sId = selectedDub?.subjectId?.ifBlank { d?.subjectId?.ifEmpty { subjectId } } ?: subjectId
            val dPath = selectedDub?.detailPath?.ifBlank { d?.detailPath?.ifEmpty { detailPath } } ?: detailPath

            val resList = repository.getStreamingResources(sId, dPath, currentSeasonNum, currentEpisodeNum)
            resources = resList

            // If initial stream was empty, pick fastest resolution
            if (currentStreamUrl.isBlank() && resList.isNotEmpty()) {
                val best = resList.maxByOrNull { it.resolution ?: 720 } ?: resList.first()
                currentStreamUrl = best.url
            }

            // Fetch recommendations
            try {
                val recs = repository.filterContent(page = 1, subjectType = d?.subjectType ?: 1)
                recommendations = recs.filter { it.subjectId != (d?.subjectId ?: subjectId) }
            } catch (_: Exception) {}
        }
    }

    // Play new episode helper
    fun switchEpisode(season: Int, episode: Int) {
        currentSeasonNum = season
        currentEpisodeNum = episode
        scope.launch {
            val sId = selectedDub?.subjectId?.ifBlank { detail?.subjectId?.ifEmpty { subjectId } } ?: subjectId
            val dPath = selectedDub?.detailPath?.ifBlank { detail?.detailPath?.ifEmpty { detailPath } } ?: detailPath
            val resList = repository.getStreamingResources(sId, dPath, season, episode)
            resources = resList
            val best = resList.maxByOrNull { it.resolution ?: 720 } ?: resList.firstOrNull()
            if (best != null && best.url.isNotBlank()) {
                currentStreamUrl = best.url
                val baseTitle = detail?.title ?: initialVideoTitle
                currentVideoTitle = "$baseTitle S${season}E${episode}"
                exoPlayer?.apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(best.url)))
                    prepare()
                    play()
                }
            } else {
                Toast.makeText(context, "Streaming link unavailable for Episode $episode.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Switch Quality
    fun switchQuality(res: StreamResource) {
        if (res.url.isNotBlank()) {
            currentStreamUrl = res.url
            val savedPos = exoPlayer?.currentPosition ?: 0L
            exoPlayer?.apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(res.url)))
                prepare()
                seekTo(savedPos)
                play()
            }
            Toast.makeText(context, "Playing ${res.displayResolution}", Toast.LENGTH_SHORT).show()
        }
    }

    // Initialize ExoPlayer
    DisposableEffect(currentStreamUrl) {
        if (currentStreamUrl.isBlank()) return@DisposableEffect onDispose {}

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
                val mediaItem = MediaItem.fromUri(Uri.parse(currentStreamUrl))
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }

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
                        val savedPos = RecentHistoryManager.getSavedPosition(context, currentStreamUrl, subjectId)
                        if (savedPos > 3000L && savedPos < player.duration - 5000L) {
                            player.seekTo(savedPos)
                        }
                    }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Toast.makeText(context, "Buffering stream...", Toast.LENGTH_SHORT).show()
            }
        }
        player.addListener(listener)

        onDispose {
            if (player.currentPosition > 2000L && player.duration > 0L) {
                RecentHistoryManager.saveProgress(
                    context = context,
                    subjectId = subjectId,
                    detailPath = detailPath,
                    title = currentVideoTitle,
                    coverUrl = detail?.coverUrl ?: initialCoverUrl,
                    streamUrl = currentStreamUrl,
                    positionMs = player.currentPosition,
                    durationMs = player.duration
                )
            }
            player.removeListener(listener)
            enhancer?.release()
            player.release()
        }
    }

    // Auto-update playback position
    LaunchedEffect(exoPlayer) {
        while (true) {
            exoPlayer?.let { p ->
                currentPosition = p.currentPosition.coerceAtLeast(0L)
                totalDuration = p.duration.coerceAtLeast(0L)
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

    fun setVolumeBoosted(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        volumePercent = clamped
        showVolumeOverlay = true

        if (clamped <= 100) {
            val sysVol = ((clamped / 100f) * maxSystemVolume).roundToInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysVol, 0)
            try { loudnessEnhancer?.setTargetGain(0) } catch (_: Exception) {}
        } else {
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

    // Download bottom sheet
    if (showDownloadSheet && resources.isNotEmpty()) {
        DownloadQualityBottomSheet(
            resources = resources,
            title = detail?.title ?: currentVideoTitle,
            onDismiss = { showDownloadSheet = false },
            onDownloadSelected = { res ->
                showDownloadSheet = false
                MediaDownloadManager.startDownload(
                    context = context,
                    subjectId = detail?.subjectId ?: subjectId,
                    detailPath = detail?.detailPath ?: detailPath,
                    title = "${detail?.title ?: currentVideoTitle} (${res.displayResolution})",
                    coverUrl = detail?.coverUrl ?: initialCoverUrl,
                    quality = res.displayResolution,
                    downloadUrl = res.url
                )
                Toast.makeText(context, "Download started: ${res.displayResolution}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Gesture detector definition
    val gestureDetector = remember {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showControls = !showControls
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return true
                val width = context.resources.displayMetrics.widthPixels
                if (e.x > width / 2) {
                    exoPlayer?.let { p ->
                        p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration))
                    }
                    doubleTapFeedbackText = "+10 sec"
                    showDoubleTapFeedback = true
                } else {
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

    // Gesture indicator timers
    LaunchedEffect(showVolumeOverlay) { if (showVolumeOverlay) { delay(1200); showVolumeOverlay = false } }
    LaunchedEffect(showBrightnessOverlay) { if (showBrightnessOverlay) { delay(1200); showBrightnessOverlay = false } }
    LaunchedEffect(showDoubleTapFeedback) { if (showDoubleTapFeedback) { delay(700); showDoubleTapFeedback = false } }

    var initialTouchX by remember { mutableFloatStateOf(0f) }
    var initialTouchY by remember { mutableFloatStateOf(0f) }
    var isHorizontalScrub by remember { mutableStateOf(false) }
    var isVerticalSwipe by remember { mutableStateOf(false) }
    var scrubStartPos by remember { mutableLongStateOf(0L) }

    // ── MAIN UI ROOT ──
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        if (isFullscreen) {
            // ══════════════════════════════════════════════════════════════
            // LANDSCAPE MODE: Fullscreen Player with MX Gestures
            // ══════════════════════════════════════════════════════════════
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
                        View(ctx).apply {
                            setOnTouchListener { _, event ->
                                gestureDetector.onTouchEvent(event)
                                if (isLocked) return@setOnTouchListener true

                                val displayMetrics = context.resources.displayMetrics
                                val widthPx = displayMetrics.widthPixels.toFloat()
                                val heightPx = displayMetrics.heightPixels.toFloat()

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
                                        val deltaY = initialTouchY - event.y

                                        if (!isHorizontalScrub && !isVerticalSwipe) {
                                            if (abs(deltaX) > 40 && abs(deltaX) > abs(deltaY)) {
                                                isHorizontalScrub = true
                                            } else if (abs(deltaY) > 40) {
                                                isVerticalSwipe = true
                                            }
                                        }

                                        if (isHorizontalScrub && widthPx > 0f) {
                                            val seekDeltaMs = ((deltaX / widthPx) * 90000f).toLong()
                                            val target = (scrubStartPos + seekDeltaMs).coerceIn(0L, totalDuration.coerceAtLeast(1L))
                                            seekTargetTime = target
                                            seekDeltaText = if (seekDeltaMs >= 0) "+${seekDeltaMs / 1000}s" else "${seekDeltaMs / 1000}s"
                                            showSeekOverlay = true
                                        } else if (isVerticalSwipe && heightPx > 0f) {
                                            val step = ((deltaY / heightPx) * 120f).toInt()
                                            if (initialTouchX > (widthPx / 2f)) {
                                                setVolumeBoosted(volumePercent + step)
                                                initialTouchY = event.y
                                            } else {
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
                                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Rotate back to Portrait button
                                    IconButton(onClick = { isFullscreen = false }) {
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
                                    IconButton(
                                        onClick = {
                                            resizeMode = when (resizeMode) {
                                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.AspectRatio, contentDescription = "Fit/Zoom", tint = Color.White)
                                    }

                                    IconButton(onClick = onEnterPip) {
                                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                                    }

                                    IconButton(onClick = { isLocked = true }) {
                                        Icon(Icons.Default.LockOpen, contentDescription = "Lock", tint = Color.White)
                                    }
                                }
                            }
                        }

                        // Center Controls
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(40.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { exoPlayer?.let { p -> p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)) } },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(48.dp)
                            ) {
                                Icon(Icons.Default.Replay10, contentDescription = "Rewind", tint = Color.White)
                            }

                            IconButton(
                                onClick = { exoPlayer?.let { p -> if (p.isPlaying) p.pause() else p.play() } },
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
                                onClick = { exoPlayer?.let { p -> p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration)) } },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(48.dp)
                            ) {
                                Icon(Icons.Default.Forward10, contentDescription = "Forward", tint = Color.White)
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
                                Slider(
                                    value = currentPosition.toFloat(),
                                    onValueChange = { newPos ->
                                        currentPosition = newPos.toLong()
                                        exoPlayer?.seekTo(newPos.toLong())
                                    },
                                    valueRange = 0f..totalDuration.coerceAtLeast(1L).toFloat(),
                                    colors = SliderDefaults.colors(thumbColor = AccentEmerald, activeTrackColor = AccentEmerald),
                                    modifier = Modifier.fillMaxWidth().height(20.dp)
                                )

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

                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        // Speed button
                                        Text(
                                            text = "${playbackSpeed}x",
                                            color = AccentCyan,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                                .clickable {
                                                    playbackSpeed = when (playbackSpeed) {
                                                        1.0f -> 1.25f
                                                        1.25f -> 1.5f
                                                        1.5f -> 2.0f
                                                        else -> 1.0f
                                                    }
                                                    exoPlayer?.setPlaybackSpeed(playbackSpeed)
                                                }
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        )

                                        // Exit Fullscreen / Rotate back to Portrait button (User Request)
                                        IconButton(
                                            onClick = { isFullscreen = false },
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
        } else {
            // ══════════════════════════════════════════════════════════════
            // PORTRAIT MODE: Top 16:9 Player + Bottom Movie/Series Content
            // ══════════════════════════════════════════════════════════════
            Column(modifier = Modifier.fillMaxSize()) {
                // Top 16:9 Video Player Box
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

                    // Touch listener on player
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { showControls = !showControls }
                    )

                    // Portrait Player Controls Overlay
                    androidx.compose.animation.AnimatedVisibility(
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
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = currentVideoTitle,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(onClick = onEnterPip, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                                }
                            }

                            // Center Play / Rewind / Forward
                            Row(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalArrangement = Arrangement.spacedBy(30.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { exoPlayer?.let { p -> p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)) } },
                                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(38.dp)
                                ) {
                                    Icon(Icons.Default.Replay10, contentDescription = "Rewind", tint = Color.White)
                                }

                                IconButton(
                                    onClick = { exoPlayer?.let { p -> if (p.isPlaying) p.pause() else p.play() } },
                                    modifier = Modifier.background(AccentEmerald, CircleShape).size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.Black,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { exoPlayer?.let { p -> p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration)) } },
                                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(38.dp)
                                ) {
                                    Icon(Icons.Default.Forward10, contentDescription = "Forward", tint = Color.White)
                                }
                            }

                            // Bottom Bar: Progress + Fullscreen / Rotate Button
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Column {
                                    Slider(
                                        value = currentPosition.toFloat(),
                                        onValueChange = { newPos ->
                                            currentPosition = newPos.toLong()
                                            exoPlayer?.seekTo(newPos.toLong())
                                        },
                                        valueRange = 0f..totalDuration.coerceAtLeast(1L).toFloat(),
                                        colors = SliderDefaults.colors(thumbColor = AccentEmerald, activeTrackColor = AccentEmerald),
                                        modifier = Modifier.fillMaxWidth().height(16.dp)
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${formatDuration(currentPosition)} / ${formatDuration(totalDuration)}",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )

                                        // Fullscreen / Rotate Button (User Request)
                                        IconButton(
                                            onClick = { isFullscreen = true },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Fullscreen,
                                                contentDescription = "Rotate to Landscape",
                                                tint = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Content Scrollable Column (MovieBox / Screenshot 1 Style)
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Movie / Series Title
                    Text(
                        text = detail?.title ?: currentVideoTitle,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Metadata Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = AccentGold, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(detail?.imdbRatingValue ?: "8.4", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Text(detail?.releaseDate?.split("-")?.firstOrNull() ?: "2026", color = TextSecondary, fontSize = 13.sp)
                        Text(detail?.countryName ?: "HD", color = TextMuted, fontSize = 13.sp)
                        Text(detail?.duration ?: "-- min", color = TextMuted, fontSize = 13.sp)

                        Box(
                            modifier = Modifier
                                .background(AccentCyan.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(if (detail?.subjectType == 2) "TV Series" else "Movie", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Buttons Row: Download & Share (Screenshot 1 Style)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { showDownloadSheet = true },
                            colors = ButtonDefaults.buttonColors(containerColor = BgCard),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "Download", tint = AccentEmerald, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Watch ${detail?.title ?: currentVideoTitle} on NXLY: https://nxly.online/movie/${detailPath}")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share with"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BgCard),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Synopsis
                    if (!detail?.description.isNullOrBlank()) {
                        Text(
                            text = detail!!.description!!,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // ── Resources By NXLY Header ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Resources", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("By NXLY", color = AccentEmerald, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Audio Dub Dropdown & Season Dropdown
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Language Dropdown (Screenshot 1 Style)
                        var langDropdownOpen by remember { mutableStateOf(false) }
                        val dubs = detail?.dubs ?: emptyList()
                        if (dubs.isNotEmpty()) {
                            Box(modifier = Modifier.weight(1f)) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = BgCard,
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { langDropdownOpen = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = selectedDub?.name ?: "Original Audio",
                                            color = TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextMuted)
                                    }
                                }

                                DropdownMenu(
                                    expanded = langDropdownOpen,
                                    onDismissRequest = { langDropdownOpen = false },
                                    modifier = Modifier.background(BgCard)
                                ) {
                                    dubs.forEach { dub ->
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
                                                langDropdownOpen = false
                                                switchEpisode(currentSeasonNum, currentEpisodeNum)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Season Dropdown (Screenshot 1 Style)
                        val seasons = detail?.seasons ?: emptyList()
                        if (seasons.isNotEmpty()) {
                            var seasonDropdownOpen by remember { mutableStateOf(false) }
                            Box(modifier = Modifier.weight(1f)) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = BgCard,
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { seasonDropdownOpen = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Season ${if (currentSeasonNum < 10) "0$currentSeasonNum" else "$currentSeasonNum"}",
                                            color = TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextMuted)
                                    }
                                }

                                DropdownMenu(
                                    expanded = seasonDropdownOpen,
                                    onDismissRequest = { seasonDropdownOpen = false },
                                    modifier = Modifier.background(BgCard)
                                ) {
                                    seasons.forEach { s ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Season ${s.seasonNumber}",
                                                    color = if (currentSeasonNum == s.seasonNumber) AccentEmerald else TextPrimary,
                                                    fontWeight = if (currentSeasonNum == s.seasonNumber) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                seasonDropdownOpen = false
                                                switchEpisode(s.seasonNumber, 1)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Episodes Grid (Screenshot 1: 01, 02, 03... chips in rows of 6)
                    val activeSeason = detail?.seasons?.firstOrNull { it.seasonNumber == currentSeasonNum } ?: detail?.seasons?.firstOrNull()
                    val episodes = activeSeason?.episodes ?: emptyList()

                    if (episodes.isNotEmpty()) {
                        val epChunks = episodes.chunked(6)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            epChunks.forEach { rowEpisodes ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                                .height(44.dp)
                                                .clickable {
                                                    switchEpisode(currentSeasonNum, ep.episodeNumber)
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
                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    // Available Qualities / Servers
                    if (resources.isNotEmpty()) {
                        Text("Available Streaming Servers", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            resources.forEachIndexed { idx, res ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = BgCard,
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { switchQuality(res) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .background(AccentEmerald.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(res.displayResolution, color = AccentEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text("Server ${idx + 1} • High Speed", color = TextPrimary, fontSize = 13.sp)
                                        }

                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    // For You Recommendations
                    if (recommendations.isNotEmpty()) {
                        Text("For You", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recommendations) { rec ->
                                MediaCard(
                                    item = rec,
                                    onClick = {
                                        val nextPath = rec.detailPath.ifEmpty { rec.subjectId }
                                        val intent = Intent(context, PlayerActivity::class.java).apply {
                                            putExtra("EXTRA_STREAM_URL", "")
                                            putExtra("EXTRA_VIDEO_TITLE", rec.title)
                                            putExtra("EXTRA_SUBJECT_ID", rec.subjectId)
                                            putExtra("EXTRA_DETAIL_PATH", nextPath)
                                            putExtra("EXTRA_COVER_URL", rec.coverUrl)
                                        }
                                        context.startActivity(intent)
                                        activity.finish()
                                    }
                                )
                            }
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
