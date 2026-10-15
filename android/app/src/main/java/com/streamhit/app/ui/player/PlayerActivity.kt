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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.ui.AspectRatioFrameLayout
import coil.compose.AsyncImage
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
import com.streamhit.app.ui.details.DownloadQualityBottomSheet
import com.streamhit.app.ui.details.SeriesDownloadBottomSheet
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

fun resolveDirectPlayableUrl(url: String): String {
    if (url.contains("source_url=")) {
        try {
            val uri = Uri.parse(url)
            val source = uri.getQueryParameter("source_url")
            if (!source.isNullOrBlank() && (source.startsWith("http://") || source.startsWith("https://"))) {
                return source
            }
        } catch (_: Exception) {}
    }
    return url
}

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
        val dubLanguage = intent.getStringExtra("EXTRA_DUB_LANGUAGE") ?: ""
        val dubSubjectId = intent.getStringExtra("EXTRA_DUB_SUBJECT_ID") ?: ""
        val dubDetailPath = intent.getStringExtra("EXTRA_DUB_DETAIL_PATH") ?: ""
        val dubName = intent.getStringExtra("EXTRA_DUB_NAME") ?: ""

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
                    initialDubLanguage = dubLanguage,
                    initialDubSubjectId = dubSubjectId,
                    initialDubDetailPath = dubDetailPath,
                    initialDubName = dubName,
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
    initialDubLanguage: String = "",
    initialDubSubjectId: String = "",
    initialDubDetailPath: String = "",
    initialDubName: String = "",
    onBack: () -> Unit,
    onEnterPip: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val scope = rememberCoroutineScope()
    val repository = remember { MovieRepository() }

    var targetStartPositionMs by remember { mutableStateOf<Long?>(if (initialStartPositionMs > 1000L) initialStartPositionMs else null) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }

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

    // Audio Sync state
    val audioSyncProcessor = remember { AudioSyncAudioProcessor() }
    var audioSyncOffsetMs by remember { mutableLongStateOf(0L) }
    var showAudioSyncDialog by remember { mutableStateOf(false) }

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
    var volumePercent by remember { mutableIntStateOf(100) }
    var showBrightnessOverlay by remember { mutableStateOf(false) }
    var brightnessPercent by remember { mutableIntStateOf(50) }
    var doubleTapFeedbackText by remember { mutableStateOf("") }
    var showDoubleTapFeedback by remember { mutableStateOf(false) }

    val maxSystemVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }

    val isLiveTv = subjectId.isBlank() && detailPath.isBlank()
    val liveTvRepository = remember { LiveTvRepository() }
    var liveChannels by remember { mutableStateOf<List<LiveTvChannel>>(emptyList()) }
    var currentChannel by remember { mutableStateOf<LiveTvChannel?>(null) }
    var currentServerIndex by remember { mutableIntStateOf(0) }

    // Dynamic loading status messages
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
        val directUrl = resolveDirectPlayableUrl(s.url)
        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(directUrl)))
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
        val directUrl = resolveDirectPlayableUrl(s.url)
        exoPlayer?.apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(directUrl)))
            prepare()
            play()
        }
        Toast.makeText(context, "Playing ${channel.name}", Toast.LENGTH_SHORT).show()
    }

    fun selectFastestResolution(resList: List<StreamResource>): StreamResource? {
        if (resList.isEmpty()) return null
        return resList.firstOrNull { it.resolution == 720 }
            ?: resList.firstOrNull { it.resolution == 480 }
            ?: resList.firstOrNull { it.resolution == 1080 }
            ?: resList.firstOrNull { it.resolution == 360 }
            ?: resList.firstOrNull()
    }

    LaunchedEffect(detailPath, subjectId) {
        if (detailPath.isNotBlank() || subjectId.isNotBlank()) {
            val sIdParam = subjectId.ifBlank { detailPath }
            coroutineScope {
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

                // Restore user's preferred dubbing version (e.g. Hindi dub)
                if (selectedDub == null) {
                    val history = RecentHistoryManager.getHistoryForSubject(context, subjectId, detailPath)
                    val prefSubId = initialDubSubjectId.ifBlank { history?.dubSubjectId ?: "" }
                    val prefLang = initialDubLanguage.ifBlank { history?.dubLanguage ?: history?.dubName ?: "" }

                    val matchedDub = d?.dubs?.firstOrNull {
                        (prefSubId.isNotBlank() && it.subjectId == prefSubId) ||
                        (prefLang.isNotBlank() && (
                            it.language.equals(prefLang, ignoreCase = true) ||
                            it.name.equals(prefLang, ignoreCase = true) ||
                            it.lanName.equals(prefLang, ignoreCase = true)
                        ))
                    }
                    selectedDub = matchedDub ?: d?.dubs?.firstOrNull { it.original == true } ?: d?.dubs?.firstOrNull()
                }

                if (resources.isEmpty() || (selectedDub != null && selectedDub?.original != true && selectedDub?.subjectId?.isNotBlank() == true && selectedDub?.subjectId != subjectId)) {
                    val sId = selectedDub?.subjectId?.ifBlank { d?.subjectId?.ifEmpty { subjectId } } ?: subjectId
                    val dPath = selectedDub?.detailPath?.ifBlank { d?.detailPath?.ifEmpty { detailPath } } ?: detailPath
                    val dubRes = repository.getStreamingResources(sId, dPath, currentSeasonNum, currentEpisodeNum)
                    if (dubRes.isNotEmpty()) {
                        resources = dubRes
                        val best = selectFastestResolution(dubRes)
                        if (best != null && best.url.isNotBlank()) {
                            currentStreamUrl = best.url
                        }
                    }
                }

                launch {
                    try {
                        val recs = repository.filterContent(page = 1, subjectType = d?.subjectType ?: 1)
                        recommendations = recs.filter { 
                            it.subjectId != (d?.subjectId ?: subjectId) &&
                            !com.streamhit.app.util.AdultContentFilter.isAdult(it.title, it.genres, it.detailPath)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun switchEpisode(season: Int, episode: Int) {
        // 1. Save progress of current episode before changing episode indices
        exoPlayer?.let { p ->
            if (p.duration > 0L) {
                RecentHistoryManager.saveProgress(
                    context = context,
                    subjectId = subjectId,
                    detailPath = detailPath,
                    title = currentVideoTitle,
                    coverUrl = detail?.coverUrl ?: initialCoverUrl,
                    streamUrl = currentStreamUrl,
                    positionMs = p.currentPosition,
                    durationMs = p.duration,
                    subjectType = detail?.subjectType ?: 2,
                    seasonNum = currentSeasonNum,
                    episodeNum = currentEpisodeNum,
                    dubLanguage = selectedDub?.language ?: selectedDub?.name,
                    dubSubjectId = selectedDub?.subjectId,
                    dubDetailPath = selectedDub?.detailPath,
                    dubName = selectedDub?.name
                )
            }
        }

        // 2. Clear intent initial position so it doesn't contaminate new episode
        targetStartPositionMs = null

        // 3. Check if user previously watched THIS specific episode:
        val savedForNewEp = RecentHistoryManager.getSavedPosition(
            context = context,
            streamUrl = "",
            subjectId = subjectId,
            detailPath = detailPath,
            seasonNum = season,
            episodeNum = episode,
            subjectType = detail?.subjectType ?: 2
        )
        // If never watched, savedForNewEp is 0L -> starts cleanly from the beginning!
        pendingSeekMs = if (savedForNewEp > 3000L) savedForNewEp else 0L

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
                val baseTitle = detail?.title ?: initialVideoTitle
                currentVideoTitle = "$baseTitle S${season}E${episode}"
                currentStreamUrl = best.url
            } else {
                Toast.makeText(context, "Streaming link unavailable for Episode $episode.", Toast.LENGTH_SHORT).show()
                isBuffering = false
            }
        }
    }

    fun switchDub(dub: DubInfo) {
        val currentPos = exoPlayer?.currentPosition ?: 0L
        pendingSeekMs = currentPos
        selectedDub = dub
        isBuffering = true
        scope.launch {
            val sId = dub.subjectId.ifBlank { detail?.subjectId?.ifEmpty { subjectId } } ?: subjectId
            val dPath = dub.detailPath.ifBlank { detail?.detailPath?.ifEmpty { detailPath } } ?: detailPath
            val resList = repository.getStreamingResources(sId, dPath, currentSeasonNum, currentEpisodeNum)
            resources = resList
            val best = selectFastestResolution(resList)
            if (best != null && best.url.isNotBlank()) {
                currentStreamUrl = best.url
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

    fun switchQuality(res: StreamResource) {
        if (res.url.isNotBlank()) {
            val currentPos = exoPlayer?.currentPosition ?: 0L
            pendingSeekMs = currentPos
            isBuffering = true
            currentStreamUrl = res.url
            Toast.makeText(context, "Playing ${res.displayResolution}", Toast.LENGTH_SHORT).show()
        }
    }

    // Initialize ExoPlayer with MediaCacheManager and tuned LoadControl
    DisposableEffect(currentStreamUrl) {
        if (currentStreamUrl.isBlank()) return@DisposableEffect onDispose {}
        isBuffering = true

        val playingSeason = currentSeasonNum
        val playingEpisode = currentEpisodeNum
        val playingTitle = currentVideoTitle
        val playingSubjectType = detail?.subjectType ?: 1

        val resolvedUrl = resolveDirectPlayableUrl(currentStreamUrl)
        var triedProxyFallback = false

        val dataSourceFactory = MediaCacheManager.getDataSourceFactory(context)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                30_000,  // minBufferMs (Aggressive 30s continuous buffer: utilizes full network speed!)
                120_000, // maxBufferMs (120s buffer cap)
                500,     // bufferForPlaybackMs (Starts playback instantly with 0.5s buffer)
                1_200    // bufferForPlaybackAfterRebufferMs (Fast rebuffer recovery)
            )
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(20_000, false)
            .build()

        val initialStartMs = when {
            pendingSeekMs != null -> {
                val p = pendingSeekMs!!
                pendingSeekMs = null
                p
            }
            targetStartPositionMs != null -> {
                val p = targetStartPositionMs!!
                targetStartPositionMs = null
                p
            }
            else -> {
                val pos = RecentHistoryManager.getSavedPosition(
                    context = context,
                    streamUrl = currentStreamUrl,
                    subjectId = subjectId,
                    detailPath = detailPath,
                    seasonNum = playingSeason,
                    episodeNum = playingEpisode,
                    subjectType = playingSubjectType
                )
                if (pos > 3000L) pos else 0L
            }
        }

        val prepareStartTimeMs = System.currentTimeMillis()
        var firstFrameRendered = false
        var rebufferCount = 0

        val renderersFactory = CustomRenderersFactory(context, audioSyncProcessor)
        audioSyncProcessor.setAudioOffsetMs(audioSyncOffsetMs)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build().apply {
                val mediaItem = MediaItem.fromUri(Uri.parse(resolvedUrl))
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
                            rebufferCount++
                            android.util.Log.d("StreamHitPlayer", "Rebuffering started at pos=${player.currentPosition}ms (count=$rebufferCount)")
                        }
                    }
                    Player.STATE_READY -> {
                        isBuffering = false
                        if (!firstFrameRendered) {
                            firstFrameRendered = true
                            val startupLatency = System.currentTimeMillis() - prepareStartTimeMs
                            android.util.Log.d("StreamHitPlayer", "Startup time: ${startupLatency}ms. BufferedPos=${player.bufferedPosition}ms")
                        }
                    }
                    Player.STATE_ENDED -> {
                        isBuffering = false
                        if (hasNextEpisode) {
                            playNextEpisode()
                        }
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isBuffering = false
                val sanitizedMsg = error.localizedMessage ?: "Playback error"
                android.util.Log.e("StreamHitPlayer", "Player error code=${error.errorCode}: $sanitizedMsg")
                // Automatic fallback: if direct CDN failed and we haven't tried proxy yet:
                if (resolvedUrl != currentStreamUrl && !triedProxyFallback) {
                    triedProxyFallback = true
                    android.util.Log.w("StreamHitPlayer", "Direct CDN stream failed. Falling back to proxy: $currentStreamUrl")
                    player.setMediaItem(MediaItem.fromUri(Uri.parse(currentStreamUrl)), player.currentPosition)
                    player.prepare()
                    player.play()
                    return
                }
                Toast.makeText(context, "Playback error: Try selecting another server", Toast.LENGTH_LONG).show()
            }
        }
        player.addListener(listener)

        onDispose {
            if (player.duration > 0L) {
                RecentHistoryManager.saveProgress(
                    context = context,
                    subjectId = subjectId,
                    detailPath = detailPath,
                    title = playingTitle,
                    coverUrl = detail?.coverUrl ?: initialCoverUrl,
                    streamUrl = currentStreamUrl,
                    positionMs = player.currentPosition,
                    durationMs = player.duration,
                    subjectType = playingSubjectType,
                    seasonNum = playingSeason,
                    episodeNum = playingEpisode,
                    dubLanguage = selectedDub?.language ?: selectedDub?.name,
                    dubSubjectId = selectedDub?.subjectId,
                    dubDetailPath = selectedDub?.detailPath,
                    dubName = selectedDub?.name
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
                        episodeNum = currentEpisodeNum,
                        dubLanguage = selectedDub?.language ?: selectedDub?.name,
                        dubSubjectId = selectedDub?.subjectId,
                        dubDetailPath = selectedDub?.detailPath,
                        dubName = selectedDub?.name
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

    // Gesture detector definition with live references
    val livePlayerRef = rememberUpdatedState(exoPlayer)
    val liveLockedRef = rememberUpdatedState(isLocked)
    val liveSpeedRef = rememberUpdatedState(playbackSpeed)

    val gestureDetector = remember {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showControls = !showControls
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (liveLockedRef.value) return true
                val p = livePlayerRef.value ?: return true
                val width = context.resources.displayMetrics.widthPixels
                if (e.x > width / 2) {
                    p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration))
                    doubleTapFeedbackText = "+10 sec"
                    showDoubleTapFeedback = true
                } else {
                    p.seekTo((p.currentPosition - 10000).coerceAtLeast(0))
                    doubleTapFeedbackText = "-10 sec"
                    showDoubleTapFeedback = true
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (liveLockedRef.value) return
                isFastForwarding2x = true
                livePlayerRef.value?.setPlaybackSpeed(2.0f)
            }
        })
    }

    LaunchedEffect(overlayDismissToken) {
        if (overlayDismissToken > 0L) {
            delay(1000)
            showVolumeOverlay = false
            showBrightnessOverlay = false
        }
    }
    LaunchedEffect(showDoubleTapFeedback) {
        if (showDoubleTapFeedback) {
            delay(700)
            showDoubleTapFeedback = false
        }
    }

    // ── MAIN UI ROOT ──
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        if (isFullscreen) {
            LandscapePlayerView(
                exoPlayer = exoPlayer,
                currentVideoTitle = currentVideoTitle,
                detail = detail,
                selectedDub = selectedDub,
                isBuffering = isBuffering,
                loadingStatusText = loadingStatuses[loadingStatusIndex],
                isPlaying = isPlaying,
                currentPosition = currentPosition,
                totalDuration = totalDuration,
                bufferedPosition = bufferedPosition,
                bufferedAheadMs = bufferedAheadMs,
                playbackSpeed = playbackSpeed,
                isFastForwarding2x = isFastForwarding2x,
                onFastForward2xChange = { fastForward ->
                    isFastForwarding2x = fastForward
                    if (!fastForward) {
                        exoPlayer?.setPlaybackSpeed(playbackSpeed)
                    }
                },
                showControls = showControls,
                isLocked = isLocked,
                onToggleLock = { isLocked = !isLocked },
                resizeMode = resizeMode,
                onToggleResizeMode = {
                    resizeMode = when (resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                onEnterPip = onEnterPip,
                onExitFullscreen = { isFullscreen = false },
                onTogglePlayPause = {
                    exoPlayer?.let { p -> if (p.isPlaying) p.pause() else p.play() }
                },
                onRewind10 = {
                    exoPlayer?.let { p -> p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)) }
                },
                onForward10 = {
                    exoPlayer?.let { p -> p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration)) }
                },
                onSeekTo = { newPos ->
                    currentPosition = newPos
                    exoPlayer?.seekTo(newPos)
                },
                onCyclePlaybackSpeed = {
                    playbackSpeed = when (playbackSpeed) {
                        1.0f -> 1.25f
                        1.25f -> 1.5f
                        1.5f -> 2.0f
                        else -> 1.0f
                    }
                    exoPlayer?.setPlaybackSpeed(playbackSpeed)
                },
                onSwitchDub = { switchDub(it) },
                hasNextEpisode = hasNextEpisode,
                onPlayNextEpisode = { playNextEpisode() },
                gestureDetector = gestureDetector,
                volumePercent = volumePercent,
                brightnessPercent = brightnessPercent,
                showVolumeOverlay = showVolumeOverlay,
                showBrightnessOverlay = showBrightnessOverlay,
                onVolumeAdjust = { step -> setVolumeBoosted(volumePercent + step) },
                onBrightnessAdjust = { step -> setScreenBrightness(brightnessPercent + step) },
                onResetOverlayToken = { overlayDismissToken = System.currentTimeMillis() },
                audioSyncOffsetMs = audioSyncOffsetMs,
                onOpenAudioSync = { showAudioSyncDialog = true }
            )
        } else {
            val isLiveStream = subjectId.isBlank() && detailPath.isBlank()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BgDark)
                    .statusBarsPadding()
            ) {
                PortraitVideoPlayerBox(
                    exoPlayer = exoPlayer,
                    currentVideoTitle = currentVideoTitle,
                    showControls = showControls,
                    isBuffering = isBuffering,
                    loadingStatusText = loadingStatuses[loadingStatusIndex],
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    totalDuration = totalDuration,
                    bufferedPosition = bufferedPosition,
                    bufferedAheadMs = bufferedAheadMs,
                    isFastForwarding2x = isFastForwarding2x,
                    onFastForward2xChange = { fastForward ->
                        isFastForwarding2x = fastForward
                        if (!fastForward) {
                            exoPlayer?.setPlaybackSpeed(playbackSpeed)
                        }
                    },
                    playbackSpeed = playbackSpeed,
                    hasNextEpisode = hasNextEpisode,
                    onPlayNextEpisode = { playNextEpisode() },
                    onEnterFullscreen = { isFullscreen = true },
                    onBack = onBack,
                    onEnterPip = onEnterPip,
                    onTogglePlayPause = {
                        exoPlayer?.let { p -> if (p.isPlaying) p.pause() else p.play() }
                    },
                    onRewind10 = {
                        exoPlayer?.let { p -> p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)) }
                    },
                    onForward10 = {
                        exoPlayer?.let { p -> p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration)) }
                    },
                    onSeekTo = { newPos ->
                        currentPosition = newPos
                        exoPlayer?.seekTo(newPos)
                    },
                    gestureDetector = gestureDetector,
                    isLiveStream = isLiveStream,
                    audioSyncOffsetMs = audioSyncOffsetMs,
                    onOpenAudioSync = { showAudioSyncDialog = true }
                )

                PortraitMediaDetails(
                    context = context,
                    detail = detail,
                    currentVideoTitle = currentVideoTitle,
                    isLiveStream = isLiveStream,
                    currentChannel = currentChannel,
                    activeServers = currentChannel?.activeServers ?: emptyList(),
                    currentServerIndex = currentServerIndex,
                    onSwitchLiveServer = { switchLiveServer(it) },
                    relatedChannels = relatedChannels,
                    onSwitchLiveChannel = { switchLiveChannel(it) },
                    currentSeasonNum = currentSeasonNum,
                    currentEpisodeNum = currentEpisodeNum,
                    selectedDub = selectedDub,
                    resources = resources,
                    recommendations = recommendations,
                    onSwitchDub = { switchDub(it) },
                    onSwitchEpisode = { s, e -> switchEpisode(s, e) },
                    onSwitchQuality = { switchQuality(it) },
                    onOpenDownloadSheet = { showDownloadSheet = true },
                    onRecommendationClick = { rec ->
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
                    },
                    audioSyncOffsetMs = audioSyncOffsetMs,
                    onOpenAudioSync = { showAudioSyncDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (showAudioSyncDialog) {
            AudioSyncDialog(
                currentOffsetMs = audioSyncOffsetMs,
                onOffsetChange = { newOffset ->
                    audioSyncOffsetMs = newOffset
                    audioSyncProcessor.setAudioOffsetMs(newOffset)
                },
                onReset = {
                    audioSyncOffsetMs = 0L
                    audioSyncProcessor.resetOffset()
                },
                onDismiss = { showAudioSyncDialog = false }
            )
        }
    }
}

fun formatDuration(millis: Long): String {
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

fun formatBufferedAhead(aheadMs: Long): String {
    val totalSecs = (aheadMs.coerceAtLeast(0L) / 1000L)
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return if (mins > 0) "${mins}m ${secs}s ahead" else "${secs}s ahead"
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
