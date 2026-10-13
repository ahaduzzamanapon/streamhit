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
import androidx.compose.ui.text.style.TextAlign
import com.streamhit.app.data.local.MediaCacheManager
import com.streamhit.app.data.local.MediaDownloadManager
import com.streamhit.app.data.local.RecentHistoryManager
import com.streamhit.app.data.model.DubInfo
import com.streamhit.app.data.model.LiveTvChannel
import com.streamhit.app.data.model.StreamResource
import com.streamhit.app.data.model.SubjectDetail
import com.streamhit.app.data.model.SubjectSummary
import com.streamhit.app.data.repository.LiveTvRepository
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.components.MediaCard
import com.streamhit.app.ui.details.DownloadQualityBottomSheet
import com.streamhit.app.ui.details.SeriesDownloadBottomSheet
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        val seasonNum = intent.getIntExtra("EXTRA_SEASON_NUM", 1).coerceAtLeast(1)
        val episodeNum = intent.getIntExtra("EXTRA_EPISODE_NUM", 1).coerceAtLeast(1)
        val startPositionMs = intent.getLongExtra("EXTRA_START_POSITION_MS", 0L)

        setContent {
            StreamfitTheme {
                AdaptivePlayerScreen(
                    initialStreamUrl = streamUrl,
                    initialVideoTitle = videoTitle,
                    subjectId = subjectId,
                    detailPath = detailPath,
                    initialCoverUrl = coverUrl,
                    initialSeasonNum = seasonNum,
                    initialEpisodeNum = episodeNum,
                    initialStartPositionMs = startPositionMs,
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
    initialSeasonNum: Int = 1,
    initialEpisodeNum: Int = 1,
    initialStartPositionMs: Long = 0L,
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
    var currentSeasonNum by remember { mutableIntStateOf(initialSeasonNum) }
    var currentEpisodeNum by remember { mutableIntStateOf(initialEpisodeNum) }
    var selectedDub by remember { mutableStateOf<DubInfo?>(null) }
    var showDownloadSheet by remember { mutableStateOf(false) }

    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var loudnessEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }

    // Controls visibility
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }

    // Playback state
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var bufferedAheadMs by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var isFastForwarding2x by remember { mutableStateOf(false) }
    var overlayDismissToken by remember { mutableLongStateOf(0L) }
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

    val isLiveTv = subjectId.isBlank() && detailPath.isBlank()
    val liveTvRepository = remember { LiveTvRepository() }
    var liveChannels by remember { mutableStateOf<List<LiveTvChannel>>(emptyList()) }
    var currentChannel by remember { mutableStateOf<LiveTvChannel?>(null) }
    var currentServerIndex by remember { mutableIntStateOf(0) }

    // Dynamic loading status messages (Connecting, Resolving, Parsing, Buffering...)
    val loadingStatuses = remember {
        listOf(
            "Connecting to stream server...",
            "Resolving high-speed CDN...",
            "Parsing playlist & manifests...",
            "Processing video & audio chunks...",
            "Buffering media packets...",
            "Optimizing playback buffer...",
            "Starting stream playback..."
        )
    }
    var loadingStatusIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(isBuffering) {
        if (isBuffering) {
            loadingStatusIndex = 0
            while (isBuffering) {
                delay(800)
                loadingStatusIndex = (loadingStatusIndex + 1) % loadingStatuses.size
            }
        }
    }

    LaunchedEffect(isLiveTv, initialVideoTitle) {
        if (isLiveTv) {
            val all = liveTvRepository.getChannels(context)
            liveChannels = all
            val matched = all.firstOrNull { 
                it.name.equals(initialVideoTitle, ignoreCase = true) ||
                initialVideoTitle.startsWith(it.name, ignoreCase = true) ||
                it.name.startsWith(initialVideoTitle, ignoreCase = true)
            }
            currentChannel = matched
            if (matched != null) {
                val idx = matched.activeServers.indexOfFirst { it.url == currentStreamUrl }
                currentServerIndex = if (idx >= 0) idx else 0
            }
        }
    }

    fun switchLiveServer(index: Int) {
        val servers = currentChannel?.activeServers ?: emptyList()
        val s = servers.getOrNull(index) ?: return
        currentServerIndex = index
        currentStreamUrl = s.url
        isBuffering = true
        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(s.url)))
            prepare()
            play()
        }
        Toast.makeText(context, "Switched to ${s.label}", Toast.LENGTH_SHORT).show()
    }

    val relatedChannels = remember(liveChannels, currentChannel) {
        if (!isLiveTv) emptyList()
        else {
            val cat = currentChannel?.category ?: "General"
            val curName = currentChannel?.name ?: initialVideoTitle
            val sameCat = liveChannels.filter { 
                it.category.equals(cat, ignoreCase = true) && !it.name.equals(curName, ignoreCase = true) 
            }
            if (sameCat.size >= 7) sameCat.take(7)
            else {
                val others = liveChannels.filter { !it.name.equals(curName, ignoreCase = true) && !sameCat.contains(it) }
                (sameCat + others).take(7)
            }
        }
    }

    fun switchLiveChannel(channel: LiveTvChannel) {
        currentChannel = channel
        currentVideoTitle = channel.name
        val s = channel.activeServers.firstOrNull() ?: return
        currentServerIndex = 0
        currentStreamUrl = s.url
        isBuffering = true
        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(s.url)))
            prepare()
            play()
        }
        Toast.makeText(context, "Playing ${channel.name}", Toast.LENGTH_SHORT).show()
    }

    // Helper to select fastest playable resolution (720p / 480p first for instant playback, then 1080p)
    fun selectFastestResolution(resList: List<StreamResource>): StreamResource? {
        if (resList.isEmpty()) return null
        return resList.firstOrNull { it.resolution == 720 }
            ?: resList.firstOrNull { it.resolution == 480 }
            ?: resList.firstOrNull { it.resolution == 1080 }
            ?: resList.firstOrNull { it.resolution == 360 }
            ?: resList.firstOrNull()
    }

    // Load detail & available resources & recommendations
    LaunchedEffect(detailPath, subjectId) {
        if (detailPath.isNotBlank() || subjectId.isNotBlank()) {
            val sIdParam = subjectId.ifBlank { detailPath }
            coroutineScope {
                // If initial stream was empty, fetch resources immediately in parallel
                val resDeferred = async {
                    try {
                        repository.getStreamingResources(sIdParam, detailPath, currentSeasonNum, currentEpisodeNum)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val detailDeferred = async {
                    try {
                        repository.getDetail(detailPath, subjectId)
                    } catch (_: Exception) {
                        null
                    }
                }

                val resList = resDeferred.await()
                if (resList.isNotEmpty()) {
                    resources = resList
                    if (currentStreamUrl.isBlank()) {
                        val best = selectFastestResolution(resList)
                        if (best != null && best.url.isNotBlank()) {
                            currentStreamUrl = best.url
                        }
                    }
                }

                val d = detailDeferred.await()
                detail = d
                if (selectedDub == null) {
                    selectedDub = d?.dubs?.firstOrNull { it.original == true } ?: d?.dubs?.firstOrNull()
                }

                // If first attempt returned no resources, try with dub subjectId
                if (resources.isEmpty()) {
                    val sId = selectedDub?.subjectId?.ifBlank { d?.subjectId?.ifEmpty { subjectId } } ?: subjectId
                    val dPath = selectedDub?.detailPath?.ifBlank { d?.detailPath?.ifEmpty { detailPath } } ?: detailPath
                    val dubRes = repository.getStreamingResources(sId, dPath, currentSeasonNum, currentEpisodeNum)
                    resources = dubRes
                    if (currentStreamUrl.isBlank()) {
                        val best = selectFastestResolution(dubRes)
                        if (best != null && best.url.isNotBlank()) {
                            currentStreamUrl = best.url
                        }
                    }
                }

                // Fetch recommendations in background
                launch {
                    try {
                        val recs = repository.filterContent(page = 1, subjectType = d?.subjectType ?: 1)
                        recommendations = recs.filter { it.subjectId != (d?.subjectId ?: subjectId) }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // Play new episode helper
    fun switchEpisode(season: Int, episode: Int) {
        currentSeasonNum = season
        currentEpisodeNum = episode
        isBuffering = true
        scope.launch {
            val sId = selectedDub?.subjectId?.ifBlank { detail?.subjectId?.ifEmpty { subjectId } } ?: subjectId
            val dPath = selectedDub?.detailPath?.ifBlank { detail?.detailPath?.ifEmpty { detailPath } } ?: detailPath
            val resList = repository.getStreamingResources(sId, dPath, season, episode)
            resources = resList
            val best = selectFastestResolution(resList)
            if (best != null && best.url.isNotBlank()) {
                currentStreamUrl = best.url
                val baseTitle = detail?.title ?: initialVideoTitle
                currentVideoTitle = "$baseTitle S${season}E${episode}"
                exoPlayer?.apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(best.url)), 0L)
                    prepare()
                    play()
                }
            } else {
                Toast.makeText(context, "Streaming link unavailable for Episode $episode.", Toast.LENGTH_SHORT).show()
                isBuffering = false
            }
        }
    }

    fun switchDub(dub: DubInfo) {
        selectedDub = dub
        val currentPos = exoPlayer?.currentPosition ?: 0L
        isBuffering = true
        scope.launch {
            val sId = dub.subjectId.ifBlank { detail?.subjectId?.ifEmpty { subjectId } } ?: subjectId
            val dPath = dub.detailPath.ifBlank { detail?.detailPath?.ifEmpty { detailPath } } ?: detailPath
            val resList = repository.getStreamingResources(sId, dPath, currentSeasonNum, currentEpisodeNum)
            resources = resList
            val best = selectFastestResolution(resList)
            if (best != null && best.url.isNotBlank()) {
                currentStreamUrl = best.url
                exoPlayer?.apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(best.url)), currentPos)
                    prepare()
                    play()
                }
                Toast.makeText(context, "Audio: ${dub.name.ifBlank { dub.language }}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Stream unavailable for this audio track", Toast.LENGTH_SHORT).show()
                isBuffering = false
            }
        }
    }

    val hasNextEpisode = remember(detail, currentSeasonNum, currentEpisodeNum) {
        if (detail?.subjectType != 2) false
        else {
            val s = detail?.seasons?.firstOrNull { it.seasonNumber == currentSeasonNum }
            val maxEp = s?.episodes?.maxOfOrNull { it.episodeNumber } ?: 0
            if (currentEpisodeNum < maxEp) true
            else {
                val nextS = detail?.seasons?.firstOrNull { it.seasonNumber == currentSeasonNum + 1 }
                nextS != null && (nextS.episodes?.isNotEmpty() == true)
            }
        }
    }

    fun playNextEpisode() {
        val s = detail?.seasons?.firstOrNull { it.seasonNumber == currentSeasonNum }
        val maxEp = s?.episodes?.maxOfOrNull { it.episodeNumber } ?: 0
        if (currentEpisodeNum < maxEp) {
            switchEpisode(currentSeasonNum, currentEpisodeNum + 1)
        } else {
            val nextS = detail?.seasons?.firstOrNull { it.seasonNumber == currentSeasonNum + 1 }
            if (nextS != null && (nextS.episodes?.isNotEmpty() == true)) {
                switchEpisode(currentSeasonNum + 1, 1)
            }
        }
    }

    // Switch Quality
    fun switchQuality(res: StreamResource) {
        if (res.url.isNotBlank()) {
            isBuffering = true
            currentStreamUrl = res.url
            val savedPos = exoPlayer?.currentPosition ?: 0L
            exoPlayer?.apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(res.url)), savedPos)
                prepare()
                play()
            }
            Toast.makeText(context, "Playing ${res.displayResolution}", Toast.LENGTH_SHORT).show()
        }
    }

    // Helper to format buffered ahead time for UI
    fun formatBufferedAhead(aheadMs: Long): String {
        val totalSecs = (aheadMs.coerceAtLeast(0L) / 1000L)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return if (mins > 0) "${mins}m ${secs}s ahead" else "${secs}s ahead"
    }

    // Initialize ExoPlayer with MediaCacheManager and tuned LoadControl
    DisposableEffect(currentStreamUrl) {
        if (currentStreamUrl.isBlank()) return@DisposableEffect onDispose {}
        isBuffering = true

        val dataSourceFactory = MediaCacheManager.getDataSourceFactory(context)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_500,  // minBufferMs (2.5s buffer before playback)
                60_000, // maxBufferMs (60s max buffer cap to bound memory & speculative downloading)
                1_000,  // bufferForPlaybackMs (1.0s buffer required to start playback)
                2_000   // bufferForPlaybackAfterRebufferMs (2.0s buffer required after stall/rebuffer)
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(10_000, false)
            .build()

        // Read saved progress before prepare so we start directly at the saved position without double-buffering
        val savedPos = if (initialStartPositionMs > 1000L) {
            initialStartPositionMs
        } else {
            RecentHistoryManager.getSavedPosition(context, currentStreamUrl, subjectId)
        }
        val initialStartMs = if (savedPos > 3000L) savedPos else 0L

        val prepareStartTimeMs = System.currentTimeMillis()
        var firstFrameRendered = false
        var rebufferStartTimeMs = 0L
        var rebufferCount = 0

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            .build().apply {
                val mediaItem = MediaItem.fromUri(Uri.parse(currentStreamUrl))
                setMediaItem(mediaItem, initialStartMs)
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

        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                for (group in tracks.groups) {
                    if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && group.isSelected) {
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                val f = group.getTrackFormat(i)
                                android.util.Log.d(
                                    "StreamHitPlayer",
                                    "Selected Video Track: ${f.width}x${f.height} @ ${f.bitrate} bps, mime=${f.sampleMimeType}"
                                )
                            }
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    isBuffering = false
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        isBuffering = true
                        if (firstFrameRendered) {
                            rebufferStartTimeMs = System.currentTimeMillis()
                            rebufferCount++
                            android.util.Log.d(
                                "StreamHitPlayer",
                                "Rebuffering started at pos=${player.currentPosition}ms (rebufferCount=$rebufferCount)"
                            )
                        } else {
                            android.util.Log.d("StreamHitPlayer", "Initial buffering...")
                        }
                    }
                    Player.STATE_READY -> {
                        isBuffering = false
                        if (!firstFrameRendered) {
                            firstFrameRendered = true
                            val startupLatency = System.currentTimeMillis() - prepareStartTimeMs
                            android.util.Log.d(
                                "StreamHitPlayer",
                                "Startup time to first STATE_READY: ${startupLatency}ms. CurrentPos=${player.currentPosition}ms, BufferedPos=${player.bufferedPosition}ms"
                            )
                        } else if (rebufferStartTimeMs > 0L) {
                            val duration = System.currentTimeMillis() - rebufferStartTimeMs
                            rebufferStartTimeMs = 0L
                            android.util.Log.d(
                                "StreamHitPlayer",
                                "Rebuffer resolved in ${duration}ms. Resume pos=${player.currentPosition}ms, bufferedAhead=${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0)}ms"
                            )
                        }
                        totalDuration = player.duration.coerceAtLeast(0L)
                        if (player.duration > 0L) {
                            RecentHistoryManager.saveProgress(
                                context = context,
                                subjectId = subjectId,
                                detailPath = detailPath,
                                title = currentVideoTitle,
                                coverUrl = detail?.coverUrl ?: initialCoverUrl,
                                streamUrl = currentStreamUrl,
                                positionMs = player.currentPosition,
                                durationMs = player.duration,
                                subjectType = detail?.subjectType ?: 1,
                                seasonNum = currentSeasonNum,
                                episodeNum = currentEpisodeNum
                            )
                        }
                    }
                    Player.STATE_ENDED -> {
                        isBuffering = false
                    }
                    Player.STATE_IDLE -> {
                        isBuffering = true
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e(
                    "StreamHitPlayer",
                    "Playback error: codeName=${error.errorCodeName} (${error.errorCode}), message=${error.message}"
                )
                val servers = currentChannel?.activeServers ?: emptyList()
                if (isLiveTv && servers.size > 1 && currentServerIndex < servers.size - 1) {
                    val nextIdx = currentServerIndex + 1
                    Toast.makeText(context, "Server offline. Auto switching to Server ${nextIdx + 1}...", Toast.LENGTH_SHORT).show()
                    switchLiveServer(nextIdx)
                } else {
                    isBuffering = true
                    Toast.makeText(context, "Buffering stream...", Toast.LENGTH_SHORT).show()
                }
            }
        }
        player.addListener(listener)

        onDispose {
            if (player.duration > 0L) {
                RecentHistoryManager.saveProgress(
                    context = context,
                    subjectId = subjectId,
                    detailPath = detailPath,
                    title = currentVideoTitle,
                    coverUrl = detail?.coverUrl ?: initialCoverUrl,
                    streamUrl = currentStreamUrl,
                    positionMs = player.currentPosition,
                    durationMs = player.duration,
                    subjectType = detail?.subjectType ?: 1,
                    seasonNum = currentSeasonNum,
                    episodeNum = currentEpisodeNum
                )
            }
            player.removeListener(listener)
            enhancer?.release()
            player.release()
        }
    }

    // Periodic progress saving for continuous resume accuracy
    LaunchedEffect(exoPlayer, isPlaying) {
        while (true) {
            delay(5000)
            exoPlayer?.let { p ->
                if (p.isPlaying && p.duration > 0L) {
                    RecentHistoryManager.saveProgress(
                        context = context,
                        subjectId = subjectId,
                        detailPath = detailPath,
                        title = currentVideoTitle,
                        coverUrl = detail?.coverUrl ?: initialCoverUrl,
                        streamUrl = currentStreamUrl,
                        positionMs = p.currentPosition,
                        durationMs = p.duration,
                        subjectType = detail?.subjectType ?: 1,
                        seasonNum = currentSeasonNum,
                        episodeNum = currentEpisodeNum
                    )
                }
            }
        }
    }

    // Auto-update playback position and buffer stats
    LaunchedEffect(exoPlayer) {
        while (true) {
            exoPlayer?.let { p ->
                val pos = p.currentPosition.coerceAtLeast(0L)
                val dur = p.duration.coerceAtLeast(0L)
                val buf = p.bufferedPosition.coerceAtLeast(pos)
                val ahead = (buf - pos).coerceAtLeast(0L)

                currentPosition = pos
                totalDuration = dur
                bufferedPosition = buf
                bufferedAheadMs = ahead

                if (p.playbackState == Player.STATE_BUFFERING) {
                    isBuffering = true
                } else if (p.playbackState == Player.STATE_READY && p.isPlaying) {
                    isBuffering = false
                }
            }
            delay(400)
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
        showBrightnessOverlay = false
        overlayDismissToken = System.currentTimeMillis()

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
        showVolumeOverlay = false
        overlayDismissToken = System.currentTimeMillis()

        val lp = activity.window.attributes
        lp.screenBrightness = (clamped / 100f).coerceIn(0.01f, 1.0f)
        activity.window.attributes = lp
    }

    // Download bottom sheet
    if (showDownloadSheet) {
        if (detail?.subjectType == 2 && detail != null) {
            SeriesDownloadBottomSheet(
                detail = detail!!,
                repository = repository,
                initialSeason = currentSeasonNum,
                onDismiss = { showDownloadSheet = false }
            )
        } else if (resources.isNotEmpty()) {
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
                        downloadUrl = res.url,
                        isSeries = false
                    )
                    Toast.makeText(context, "Download started: ${res.displayResolution}", Toast.LENGTH_SHORT).show()
                }
            )
        }
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

            override fun onLongPress(e: MotionEvent) {
                if (isLocked) return
                isFastForwarding2x = true
                exoPlayer?.setPlaybackSpeed(2.0f)
            }
        })
    }

    // Gesture indicator timers
    LaunchedEffect(overlayDismissToken) {
        if (overlayDismissToken > 0L) {
            delay(1000)
            showVolumeOverlay = false
            showBrightnessOverlay = false
        }
    }
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
                                        if (isFastForwarding2x) {
                                            isFastForwarding2x = false
                                            exoPlayer?.setPlaybackSpeed(playbackSpeed)
                                        }
                                        overlayDismissToken = System.currentTimeMillis()
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
                            .clickable { playNextEpisode() }
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
                                                            switchDub(dub)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

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

                        // Center Controls / Buffering Loader with dynamic status
                        if (isBuffering) {
                            Box(
                                modifier = Modifier.align(Alignment.Center),
                                contentAlignment = Alignment.Center
                            ) {
                                PlayerBufferingIndicator(
                                    statusText = loadingStatuses[loadingStatusIndex],
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

                                if (hasNextEpisode) {
                                    IconButton(
                                        onClick = { playNextEpisode() },
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
                                        onValueChange = { newPos ->
                                            currentPosition = newPos.toLong()
                                            exoPlayer?.seekTo(newPos.toLong())
                                        },
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
            // PORTRAIT MODE: Top 16:9 Player (Pushed below status bar) + Compact Content
            // ══════════════════════════════════════════════════════════════
            val isLiveStream = subjectId.isBlank() && detailPath.isBlank()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BgDark)
                    .statusBarsPadding()
            ) {
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

                    // Touch & Gesture listener on portrait player (Supports 2X Hold, Taps, and Double Tap Seek)
                    AndroidView(
                        factory = { ctx ->
                            View(ctx).apply {
                                setOnTouchListener { _, event ->
                                    gestureDetector.onTouchEvent(event)
                                    if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                                        if (isFastForwarding2x) {
                                            isFastForwarding2x = false
                                            exoPlayer?.setPlaybackSpeed(playbackSpeed)
                                        }
                                    }
                                    true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

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
                                .clickable { playNextEpisode() }
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
                                statusText = loadingStatuses[loadingStatusIndex],
                                isLarge = false
                            )
                        }
                    }

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
                            // Top Bar: Back, Title, PiP (Safe inside player)
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

                                IconButton(onClick = onEnterPip, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                                }
                            }

                            // Center Play / Rewind / Forward OR Buffering Loader with dynamic status
                            if (isBuffering) {
                                Box(
                                    modifier = Modifier.align(Alignment.Center),
                                    contentAlignment = Alignment.Center
                                ) {
                                    PlayerBufferingIndicator(
                                        statusText = loadingStatuses[loadingStatusIndex],
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
                                        onClick = { exoPlayer?.let { p -> p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)) } },
                                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(36.dp)
                                    ) {
                                        Icon(Icons.Default.Replay10, contentDescription = "Rewind", tint = Color.White)
                                    }

                                    IconButton(
                                        onClick = { exoPlayer?.let { p -> if (p.isPlaying) p.pause() else p.play() } },
                                        modifier = Modifier.background(AccentEmerald, CircleShape).size(46.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play/Pause",
                                            tint = Color.Black,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { exoPlayer?.let { p -> p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration)) } },
                                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).size(36.dp)
                                    ) {
                                        Icon(Icons.Default.Forward10, contentDescription = "Forward", tint = Color.White)
                                    }

                                    if (hasNextEpisode) {
                                        IconButton(
                                            onClick = { playNextEpisode() },
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
                                if (isLiveStream || totalDuration <= 0L) {
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
                                            onClick = { isFullscreen = true },
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
                                                    .padding(horizontal = 8.dp)
                                                    .height(3.dp)
                                                    .clip(RoundedCornerShape(1.5.dp))
                                                    .background(Color.White.copy(alpha = 0.2f)),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                if (bufferedRatio > 0f) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth(fraction = bufferedRatio)
                                                            .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(1.5.dp))
                                                    )
                                                }
                                            }

                                            // Slider on top
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
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                if (bufferedAheadMs > 0L && totalDuration > 0L) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "•  Buf: ${formatBufferedAhead(bufferedAheadMs)}",
                                                        color = AccentEmerald.copy(alpha = 0.85f),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }

                                            IconButton(
                                                onClick = { isFullscreen = true },
                                                modifier = Modifier.size(28.dp)
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
                }

                // Bottom Content Scrollable Column - Compact & User-friendly
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
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

                    if (isLiveStream) {
                        // Live TV Details: Clean badge, NO download button
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

                            // Compact Share button (Download button is strictly removed for Live TV)
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
                        val activeServers = currentChannel?.activeServers ?: emptyList()
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
                                            .clickable { switchLiveServer(idx) }
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

                        // Related Channels (6-7 channels)
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
                                        onClick = { switchLiveChannel(ch) }
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
                                    .background(AccentCyan.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(if (detail?.subjectType == 2) "TV Series" else "Movie", color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Compact Action Buttons Row (Download & Share)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showDownloadSheet = true },
                                colors = ButtonDefaults.buttonColors(containerColor = BgCard),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f).height(36.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = AccentEmerald, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f).height(36.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Compact Synopsis
                        if (!detail?.description.isNullOrBlank()) {
                            Text(
                                text = detail!!.description!!,
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
                        val seasons = detail?.seasons ?: emptyList()
                        if (dubs.isNotEmpty() || seasons.isNotEmpty()) {
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
                                                    text = selectedDub?.name ?: "Original Audio",
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
                                                        langDropdownOpen = false
                                                        switchDub(dub)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                if (seasons.isNotEmpty()) {
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
                                            seasons.forEach { s ->
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
                                                        switchEpisode(s.seasonNumber, 1)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Compact Episodes Grid (01, 02... chips in rows of 6)
                        val activeSeason = detail?.seasons?.firstOrNull { it.seasonNumber == currentSeasonNum } ?: detail?.seasons?.firstOrNull()
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
                                            .clickable { switchQuality(res) }
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

@Composable
fun LiveTvMiniCard(
    channel: LiveTvChannel,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = BgCard,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .width(88.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                if (channel.logo.isNotBlank()) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(3.dp)
                    )
                } else {
                    Icon(Icons.Default.Tv, contentDescription = null, tint = TextMuted, modifier = Modifier.size(22.dp))
                }
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(AccentEmerald, CircleShape)
                        .align(Alignment.TopEnd)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun PlayerBufferingIndicator(
    statusText: String,
    modifier: Modifier = Modifier,
    isLarge: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = AccentEmerald,
            strokeWidth = if (isLarge) 3.5.dp else 2.8.dp,
            modifier = Modifier.size(if (isLarge) 46.dp else 36.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(AccentEmerald, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                AnimatedContent(
                    targetState = statusText,
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 })
                    },
                    label = "BufferingStatusText"
                ) { target ->
                    Text(
                        text = target,
                        color = Color.White,
                        fontSize = if (isLarge) 12.sp else 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

