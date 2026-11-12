package com.streamhit.app.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.streamhit.app.util.DeviceUtils
import com.streamhit.app.ui.tv.TvLivePlaylistSidePanel
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.compose.animation.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.Brush
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
import com.streamhit.app.data.analytics.AppAnalyticsManager
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.streamhit.app.ui.components.BackgroundDownloadPermissionDialog
import com.streamhit.app.ui.components.SponsorSupportDialog
import com.streamhit.app.ui.sponsor.SponsorWebViewActivity
import com.streamhit.app.util.BackgroundDownloadHelper
import com.streamhit.app.util.SponsorAdManager
import com.streamhit.app.ui.details.DownloadQualityBottomSheet
import com.streamhit.app.ui.details.SeriesDownloadBottomSheet
import com.streamhit.app.ui.components.WatchLockShareDialog
import com.streamhit.app.ui.components.AppShareDialog
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

    companion object {
        private const val ACTION_PIP_REWIND = "com.streamhit.app.PIP_ACTION_REWIND"
        private const val ACTION_PIP_PLAY_PAUSE = "com.streamhit.app.PIP_ACTION_PLAY_PAUSE"
        private const val ACTION_PIP_FORWARD = "com.streamhit.app.PIP_ACTION_FORWARD"
    }

    private var exoPlayer: ExoPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private lateinit var audioManager: AudioManager

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_REWIND -> {
                    exoPlayer?.let { p ->
                        val target = (p.currentPosition - 10_000L).coerceAtLeast(0L)
                        p.seekTo(target)
                    }
                }
                ACTION_PIP_PLAY_PAUSE -> {
                    exoPlayer?.let { p ->
                        if (p.isPlaying) {
                            p.pause()
                            updatePipParams(false)
                        } else {
                            p.play()
                            updatePipParams(true)
                        }
                    }
                }
                ACTION_PIP_FORWARD -> {
                    exoPlayer?.let { p ->
                        val dur = p.duration.coerceAtLeast(0L)
                        val target = if (dur > 0L) (p.currentPosition + 10_000L).coerceAtMost(dur) else p.currentPosition + 10_000L
                        p.seekTo(target)
                    }
                }
            }
        }
    }

    var onRemoteKeyEventListener: ((KeyEvent) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (onRemoteKeyEventListener?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // On Smart TV / Android TV devices: start in landscape; on mobile phones: start in portrait
        val isTvDevice = DeviceUtils.isTvDevice(this)

        if (isTvDevice) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Register PiP action broadcast receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_REWIND)
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_FORWARD)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipActionReceiver, filter)
        }

        // Initialize Real-time AI Audio Translation TTS engine
        com.streamhit.app.util.SubtitleTtsManager.init(this)

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
        val isOffline = intent.getBooleanExtra("EXTRA_IS_OFFLINE", false)

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
                    initialIsOffline = isOffline,
                    onBack = { finish() },
                    onEnterPip = { enterPipMode() }
                )
            }
        }
    }

    fun setPlayerInstance(player: ExoPlayer?) {
        this.exoPlayer = player
        updatePipParams(player?.isPlaying == true)
    }

    fun updatePipParams(isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

            val rewindIntent = Intent(ACTION_PIP_REWIND).setPackage(packageName)
            val rewindPending = PendingIntent.getBroadcast(this, 101, rewindIntent, flags)
            val rewindAction = RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_rew),
                "Rewind 10s",
                "Rewind 10s",
                rewindPending
            )

            val playPauseIntent = Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName)
            val playPausePending = PendingIntent.getBroadcast(this, 102, playPauseIntent, flags)
            val playPauseIconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val playPauseText = if (isPlaying) "Pause" else "Play"
            val playPauseAction = RemoteAction(
                Icon.createWithResource(this, playPauseIconRes),
                playPauseText,
                playPauseText,
                playPausePending
            )

            val forwardIntent = Intent(ACTION_PIP_FORWARD).setPackage(packageName)
            val forwardPending = PendingIntent.getBroadcast(this, 103, forwardIntent, flags)
            val forwardAction = RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_ff),
                "Forward 10s",
                "Forward 10s",
                forwardPending
            )

            val actions = listOf(rewindAction, playPauseAction, forwardAction)

            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(actions)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }

            try {
                val params = builder.build()
                setPictureInPictureParams(params)
            } catch (_: Exception) {}
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            updatePipParams(exoPlayer?.isPlaying == true)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            try {
                enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (exoPlayer?.isPlaying == true) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            updatePipParams(exoPlayer?.isPlaying == true)
        } else {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                exoPlayer?.pause()
                com.streamhit.app.util.SubtitleTtsManager.onPause()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        com.streamhit.app.util.SubtitleTtsManager.onPause()
    }

    override fun onStop() {
        super.onStop()
        // If minimized and not in Picture-in-Picture mode, pause video and audio completely
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!isInPictureInPictureMode) {
                exoPlayer?.pause()
                com.streamhit.app.util.SubtitleTtsManager.onPause()
            }
        } else {
            exoPlayer?.pause()
            com.streamhit.app.util.SubtitleTtsManager.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pipActionReceiver)
        } catch (_: Exception) {}
        com.streamhit.app.util.SubtitleTtsManager.destroy(exoPlayer)
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
    initialIsOffline: Boolean = false,
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

    val isTvDevice = remember { DeviceUtils.isTvDevice(context) }

    // Orientation, Fullscreen, and Lock state (defaults to fullscreen landscape on TV devices)
    var isFullscreen by remember { mutableStateOf(isTvDevice) }
    var isLocked by remember { mutableStateOf(false) }

    // TV Live Playlist side panel & auto-dismiss interaction timer (6s)
    var showTvSidePanel by remember { mutableStateOf(false) }
    var tvSidePanelInteractionTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(showTvSidePanel, tvSidePanelInteractionTime) {
        if (showTvSidePanel) {
            delay(6000L)
            showTvSidePanel = false
        }
    }

    // Handle system back button: close TV side panel first, or return to portrait if in fullscreen (unless locked)
    BackHandler(enabled = showTvSidePanel || isFullscreen) {
        if (showTvSidePanel) {
            showTvSidePanel = false
        } else if (!isLocked) {
            isFullscreen = false
        } else {
            Toast.makeText(context, "Screen is locked", Toast.LENGTH_SHORT).show()
        }
    }

    // React to fullscreen and lock toggle
    LaunchedEffect(isFullscreen, isLocked) {
        val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isFullscreen) {
            if (isLocked) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
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
    var showTranslatorDialog by remember { mutableStateOf(false) }

    // 20-minute forced watch lock / share-to-continue state
    val prefs = remember { context.getSharedPreferences("streamhit_unlock_prefs", Context.MODE_PRIVATE) }
    val contentKey = remember(subjectId, detailPath, initialVideoTitle) {
        val raw = subjectId.ifBlank { detailPath }.ifBlank { initialVideoTitle }
        raw.replace("[^a-zA-Z0-9_]".toRegex(), "_")
    }
    var hasSharedToUnlock by remember { mutableStateOf(prefs.getBoolean("unlock_$contentKey", false)) }
    var showWatchLockDialog by remember { mutableStateOf(false) }

    // Load captions for AI Real-time Audio Translation
    LaunchedEffect(subjectId, detailPath, currentSeasonNum, currentEpisodeNum, resources) {
        val sId = subjectId.ifBlank { detailPath }
        if (sId.isNotBlank()) {
            try {
                val resId = resources.firstOrNull()?.resourceId ?: ""
                val caps = repository.getCaptions(sId, detailPath, currentSeasonNum, currentEpisodeNum, resId)
                val targetLang = com.streamhit.app.util.SubtitleTtsManager.targetLanguage
                val bestCap = caps.firstOrNull { it.lang.equals(targetLang, ignoreCase = true) }
                    ?: caps.firstOrNull { it.lang.equals("en", ignoreCase = true) || it.label.contains("English", ignoreCase = true) }
                    ?: caps.firstOrNull()
                if (bestCap != null && bestCap.url.isNotBlank()) {
                    com.streamhit.app.util.SubtitleTtsManager.loadCaptionsFromUrl(bestCap.url)
                }
            } catch (_: Exception) {}
        }
    }

    // Controls visibility
    var showControls by remember { mutableStateOf(true) }

    // Playback state
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var bufferedAheadMs by remember { mutableLongStateOf(0L) }
    var hasPlaybackError by remember { mutableStateOf(false) }
    var playbackErrorMessage by remember { mutableStateOf<String?>(null) }
    val failedStreamUrls = remember { mutableStateListOf<String>() }
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

    var showPeriodicSponsorDialog by remember { mutableStateOf(false) }
    var lastMidrollAdTimestampMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var pendingPlayerDownloadRes by remember { mutableStateOf<StreamResource?>(null) }
    var showPlayerDownloadSponsorDialog by remember { mutableStateOf(false) }
    var showPlayerBgPermissionDialog by remember { mutableStateOf(false) }

    val periodicSponsorLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        lastMidrollAdTimestampMs = System.currentTimeMillis()
        showPeriodicSponsorDialog = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            exoPlayer?.play()
        }
    }

    val playerDownloadSponsorLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val res = pendingPlayerDownloadRes
            pendingPlayerDownloadRes = null
            showPlayerDownloadSponsorDialog = false
            if (res != null) {
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
                if (!BackgroundDownloadHelper.isBatteryOptimizationIgnored(context) && !BackgroundDownloadHelper.hasPromptedInitialPermission(context)) {
                    showPlayerBgPermissionDialog = true
                }
            }
        }
    }

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

    var enteredChannelDigits by remember { mutableStateOf("") }
    var channelOverlayBanner by remember { mutableStateOf<Pair<Int, LiveTvChannel>?>(null) }

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
            val (lastChannel, lastServerIdx) = DeviceUtils.getLastPlayedLiveChannel(context)
            val matched = all.firstOrNull { 
                it.name.equals(initialVideoTitle, ignoreCase = true) ||
                initialVideoTitle.startsWith(it.name, ignoreCase = true) ||
                it.name.startsWith(initialVideoTitle, ignoreCase = true)
            } ?: (if (lastChannel != null) all.firstOrNull { it.name.equals(lastChannel.name, ignoreCase = true) } else null)
              ?: all.firstOrNull()

            currentChannel = matched
            if (matched != null) {
                val idx = if (currentStreamUrl.isNotBlank()) {
                    matched.activeServers.indexOfFirst { it.url == currentStreamUrl }
                } else if (lastChannel != null && lastChannel.name.equals(matched.name, ignoreCase = true)) {
                    lastServerIdx.coerceIn(0, (matched.activeServers.size - 1).coerceAtLeast(0))
                } else 0
                val activeIdx = if (idx >= 0) idx else 0
                currentServerIndex = activeIdx
                val server = matched.activeServers.getOrNull(activeIdx) ?: matched.activeServers.firstOrNull()
                if (server != null && currentStreamUrl.isBlank()) {
                    currentStreamUrl = server.url
                    currentVideoTitle = matched.name
                }
                DeviceUtils.saveLastPlayedLiveChannel(context, matched, activeIdx)
                val chIdx = all.indexOfFirst { it.name.equals(matched.name, ignoreCase = true) }
                channelOverlayBanner = Pair(if (chIdx >= 0) chIdx + 1 else 1, matched)
            }
        }
    }

    fun switchLiveServer(index: Int) {
        val servers = currentChannel?.activeServers ?: emptyList()
        val s = servers.getOrNull(index) ?: return
        currentServerIndex = index
        currentStreamUrl = s.url
        currentChannel?.let {
            DeviceUtils.saveLastPlayedLiveChannel(context, it, index)
        }
        hasPlaybackError = false
        playbackErrorMessage = null
        isBuffering = true
        val directUrl = resolveDirectPlayableUrl(s.url)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(directUrl))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(3500L)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
            .build()
        exoPlayer?.apply {
            stop()
            clearMediaItems()
            setMediaItem(mediaItem)
            prepare()
            play()
        }
        Toast.makeText(context, "📡 ${s.label} চালু করা হয়েছে", Toast.LENGTH_SHORT).show()
    }

    val relatedChannels = remember(liveChannels, currentChannel) {
        if (!isLiveTv) emptyList()
        else {
            val cat = currentChannel?.category ?: "General"
            val curName = currentChannel?.name ?: initialVideoTitle
            val sameCat = liveChannels.filter { 
                it.category.equals(cat, ignoreCase = true) && !it.name.equals(curName, ignoreCase = true) 
            }
            val others = liveChannels.filter { !it.name.equals(curName, ignoreCase = true) && !sameCat.contains(it) }
            sameCat + others
        }
    }

    val currentChannelIndex = remember(liveChannels, currentChannel, currentVideoTitle) {
        val targetName = currentChannel?.name ?: currentVideoTitle
        val idx = liveChannels.indexOfFirst {
            it.name.equals(targetName, ignoreCase = true) ||
            targetName.startsWith(it.name, ignoreCase = true) ||
            it.name.startsWith(targetName, ignoreCase = true)
        }
        if (idx >= 0) idx else 0
    }

    fun switchLiveChannel(channel: LiveTvChannel) {
        currentChannel = channel
        currentVideoTitle = channel.name
        val s = channel.activeServers.firstOrNull() ?: return
        currentServerIndex = 0
        currentStreamUrl = s.url
        DeviceUtils.saveLastPlayedLiveChannel(context, channel, 0)
        failedStreamUrls.clear()
        hasPlaybackError = false
        playbackErrorMessage = null
        isBuffering = true
        val directUrl = resolveDirectPlayableUrl(s.url)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(directUrl))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(3500L)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
            .build()
        exoPlayer?.apply {
            stop()
            clearMediaItems()
            setMediaItem(mediaItem)
            prepare()
            play()
        }
        val idx = liveChannels.indexOfFirst { it.name.equals(channel.name, ignoreCase = true) }
        channelOverlayBanner = Pair(if (idx >= 0) idx + 1 else 1, channel)
        Toast.makeText(context, "📺 চ্যানেল: ${channel.name}", Toast.LENGTH_SHORT).show()
    }

    fun playNextChannel() {
        if (liveChannels.isEmpty()) return
        val curIdx = liveChannels.indexOfFirst { it.name.equals(currentChannel?.name, ignoreCase = true) }
        val baseIdx = if (curIdx >= 0) curIdx else currentChannelIndex
        val nextIdx = (baseIdx + 1) % liveChannels.size
        failedStreamUrls.clear()
        switchLiveChannel(liveChannels[nextIdx])
    }

    fun playPrevChannel() {
        if (liveChannels.isEmpty()) return
        val curIdx = liveChannels.indexOfFirst { it.name.equals(currentChannel?.name, ignoreCase = true) }
        val baseIdx = if (curIdx >= 0) curIdx else currentChannelIndex
        val prevIdx = if (baseIdx > 0) baseIdx - 1 else liveChannels.size - 1
        failedStreamUrls.clear()
        switchLiveChannel(liveChannels[prevIdx])
    }

    fun switchToChannelNumber(number: Int) {
        if (liveChannels.isEmpty()) return
        val targetIdx = (number - 1).coerceIn(0, liveChannels.size - 1)
        switchLiveChannel(liveChannels[targetIdx])
    }

    // Auto-commit remote entered channel numbers after 1.2s debounce
    LaunchedEffect(enteredChannelDigits) {
        if (enteredChannelDigits.isNotEmpty()) {
            delay(1200L)
            val num = enteredChannelDigits.toIntOrNull()
            if (num != null && liveChannels.isNotEmpty()) {
                switchToChannelNumber(num)
            }
            enteredChannelDigits = ""
        }
    }

    // Auto-hide TV Channel switch banner after 3.5s
    LaunchedEffect(channelOverlayBanner) {
        if (channelOverlayBanner != null) {
            delay(3500L)
            channelOverlayBanner = null
        }
    }

    // ── TV Remote Control Key Event Interceptor ──
    val playerActivity = context as? PlayerActivity
    DisposableEffect(playerActivity, isLiveTv, liveChannels, currentChannelIndex, enteredChannelDigits, showControls, showTvSidePanel, isPlaying, exoPlayer) {
        if (playerActivity != null) {
            playerActivity.onRemoteKeyEventListener = { event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val code = event.keyCode
                    if (isLiveTv) {
                        when {
                            // 0a. Close side panel on Back button
                            code == KeyEvent.KEYCODE_BACK && showTvSidePanel -> {
                                showTvSidePanel = false
                                true
                            }
                            // 0b. Reset 6s auto-dismiss timer on any D-Pad navigation while panel is open
                            showTvSidePanel && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) -> {
                                tvSidePanelInteractionTime = System.currentTimeMillis()
                                false // Let Compose focus system navigate the list
                            }
                            // 1. Number keys 0-9 & Numpad 0-9
                            (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) || (code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) -> {
                                val digit = if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                                    code - KeyEvent.KEYCODE_0
                                } else {
                                    code - KeyEvent.KEYCODE_NUMPAD_0
                                }
                                enteredChannelDigits = (enteredChannelDigits + digit.toString()).take(3)
                                true
                            }
                            // 2. ENTER / DPAD_CENTER (Confirm entered number OR Toggle Live TV Playlist Side Panel)
                            code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                if (enteredChannelDigits.isNotEmpty()) {
                                    val num = enteredChannelDigits.toIntOrNull()
                                    if (num != null && liveChannels.isNotEmpty()) {
                                        switchToChannelNumber(num)
                                    }
                                    enteredChannelDigits = ""
                                    true
                                } else {
                                    // Bring up side playlist panel while TV keeps playing smoothly!
                                    showTvSidePanel = !showTvSidePanel
                                    tvSidePanelInteractionTime = System.currentTimeMillis()
                                    true
                                }
                            }
                            // 3. Channel UP / PAGE UP / MEDIA NEXT
                            code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                playNextChannel()
                                true
                            }
                            // 4. Channel DOWN / PAGE DOWN / MEDIA PREVIOUS
                            code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                playPrevChannel()
                                true
                            }
                            // 5. D-Pad UP / DOWN for Channel surfing when controls and side panel are hidden
                            code == KeyEvent.KEYCODE_DPAD_UP && !showControls && !showTvSidePanel -> {
                                playPrevChannel()
                                true
                            }
                            code == KeyEvent.KEYCODE_DPAD_DOWN && !showControls && !showTvSidePanel -> {
                                playNextChannel()
                                true
                            }
                            // 6. D-Pad LEFT / RIGHT for Channel surfing when controls and side panel are hidden on Live TV
                            code == KeyEvent.KEYCODE_DPAD_LEFT && !showControls && !showTvSidePanel -> {
                                playPrevChannel()
                                true
                            }
                            code == KeyEvent.KEYCODE_DPAD_RIGHT && !showControls && !showTvSidePanel -> {
                                playNextChannel()
                                true
                            }
                            else -> false
                        }
                    } else {
                        // ── MOVIE & SERIES KEY HANDLING ──
                        when {
                            // 1. OK / DPAD_CENTER / ENTER -> Toggle Play/Pause & Show Controls
                            code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                exoPlayer?.let { p ->
                                    if (p.isPlaying) {
                                        p.pause()
                                        isPlaying = false
                                        showControls = true
                                    } else {
                                        p.play()
                                        isPlaying = true
                                        showControls = true
                                    }
                                }
                                true
                            }
                            // 2. DPAD_LEFT -> Seek Backward 10 Seconds & Show Controls
                            code == KeyEvent.KEYCODE_DPAD_LEFT -> {
                                exoPlayer?.let { p ->
                                    val target = (p.currentPosition - 10_000L).coerceAtLeast(0L)
                                    p.seekTo(target)
                                    currentPosition = target
                                    showControls = true
                                }
                                true
                            }
                            // 3. DPAD_RIGHT -> Seek Forward 10 Seconds & Show Controls
                            code == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                exoPlayer?.let { p ->
                                    val dur = p.duration.coerceAtLeast(0L)
                                    val target = if (dur > 0L) (p.currentPosition + 10_000L).coerceAtMost(dur) else p.currentPosition + 10_000L
                                    p.seekTo(target)
                                    currentPosition = target
                                    showControls = true
                                }
                                true
                            }
                            // 4. DPAD_UP / DPAD_DOWN -> Toggle or Show Controls
                            code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                                showControls = !showControls
                                true
                            }
                            // 5. Media Play / Pause keys
                            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK -> {
                                exoPlayer?.let { p ->
                                    if (p.isPlaying) {
                                        p.pause()
                                        isPlaying = false
                                        showControls = true
                                    } else {
                                        p.play()
                                        isPlaying = true
                                        showControls = true
                                    }
                                }
                                true
                            }
                            code == KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                exoPlayer?.play()
                                isPlaying = true
                                showControls = true
                                true
                            }
                            code == KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                exoPlayer?.pause()
                                isPlaying = false
                                showControls = true
                                true
                            }
                            // 6. Media Fast Forward / Rewind keys
                            code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                                exoPlayer?.let { p ->
                                    val dur = p.duration.coerceAtLeast(0L)
                                    val target = if (dur > 0L) (p.currentPosition + 10_000L).coerceAtMost(dur) else p.currentPosition + 10_000L
                                    p.seekTo(target)
                                    currentPosition = target
                                    showControls = true
                                }
                                true
                            }
                            code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                                exoPlayer?.let { p ->
                                    val target = (p.currentPosition - 10_000L).coerceAtLeast(0L)
                                    p.seekTo(target)
                                    currentPosition = target
                                    showControls = true
                                }
                                true
                            }
                            // 7. Back button: if controls are shown, hide them first
                            code == KeyEvent.KEYCODE_BACK && showControls -> {
                                showControls = false
                                true
                            }
                            else -> false
                        }
                    }
                } else false
            }
        }
        onDispose {
            playerActivity?.onRemoteKeyEventListener = null
        }
    }

    fun selectFastestResolution(resList: List<StreamResource>): StreamResource? {
        if (resList.isEmpty()) return null
        val available = resList.filter { it.url.isNotBlank() && it.url !in failedStreamUrls }
        val targetList = if (available.isNotEmpty()) available else resList
        return targetList.firstOrNull { it.resolution == 720 }
            ?: targetList.firstOrNull { it.resolution == 480 }
            ?: targetList.firstOrNull { it.resolution == 1080 }
            ?: targetList.firstOrNull { it.resolution == 360 }
            ?: targetList.firstOrNull()
    }

    fun selectNextBestUntried(candidates: List<StreamResource>): StreamResource? {
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.resolution == 720 }
            ?: candidates.firstOrNull { it.resolution == 480 }
            ?: candidates.firstOrNull { it.resolution == 1080 }
            ?: candidates.firstOrNull { it.resolution == 360 }
            ?: candidates.firstOrNull()
    }

    // Report real-time watching telemetry to server
    LaunchedEffect(currentVideoTitle, isPlaying, detailPath) {
        val title = currentVideoTitle.ifBlank { detail?.title ?: initialVideoTitle }
        if (title.isNotBlank()) {
            AppAnalyticsManager.setWatching(
                title = title,
                detailPath = detailPath.ifBlank { detail?.detailPath ?: "" },
                isPlaying = isPlaying
            )
            AppAnalyticsManager.sendImmediateWatchUpdate(context)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            AppAnalyticsManager.clearWatching()
            AppAnalyticsManager.sendImmediateWatchUpdate(context)
        }
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
                    if (currentStreamUrl.isBlank() || currentStreamUrl in failedStreamUrls) {
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

                // If a specific dub was selected (e.g. Hindi dub) whose subjectId differs from main subject:
                if (selectedDub != null && selectedDub?.subjectId?.isNotBlank() == true && selectedDub?.subjectId != d?.subjectId) {
                    val dubSid = selectedDub?.subjectId ?: ""
                    val dubDPath = selectedDub?.detailPath?.ifBlank { if (dubSid == subjectId) detailPath else "" } ?: ""
                    try {
                        val dubDetail = repository.getDetail(dubDPath, dubSid)
                        if (dubDetail != null) {
                            val mergedDubs = if (!dubDetail.dubs.isNullOrEmpty()) dubDetail.dubs else d?.dubs
                            detail = dubDetail.copy(dubs = mergedDubs)
                            val validSeasons = dubDetail.seasons?.filter { it.episodes.isNotEmpty() } ?: emptyList()
                            if (validSeasons.isNotEmpty()) {
                                val curSeason = validSeasons.firstOrNull { it.seasonNumber == currentSeasonNum }
                                    ?: validSeasons.first()
                                currentSeasonNum = curSeason.seasonNumber
                                if (curSeason.episodes.none { it.episodeNumber == currentEpisodeNum }) {
                                    currentEpisodeNum = curSeason.episodes.firstOrNull()?.episodeNumber ?: 1
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (resources.isEmpty() || (selectedDub != null && selectedDub?.original != true && selectedDub?.subjectId?.isNotBlank() == true && selectedDub?.subjectId != subjectId)) {
                    val sId = selectedDub?.subjectId?.ifBlank { detail?.subjectId?.ifEmpty { subjectId } } ?: subjectId
                    val dPath = detail?.detailPath?.ifBlank { selectedDub?.detailPath?.ifBlank { detailPath } } ?: detailPath
                    val dubRes = repository.getStreamingResources(sId, dPath, currentSeasonNum, currentEpisodeNum)
                    if (dubRes.isNotEmpty()) {
                        resources = dubRes
                        if (currentStreamUrl.isBlank() || currentStreamUrl in failedStreamUrls) {
                            val best = selectFastestResolution(dubRes)
                            if (best != null && best.url.isNotBlank()) {
                                currentStreamUrl = best.url
                            }
                        }
                    }
                }

                launch {
                    try {
                        val recs = repository.filterContent(page = 1, subjectType = d?.subjectType ?: 1)
                        recommendations = recs.filter { 
                            it.subjectId != (d?.subjectId ?: subjectId) &&
                            !com.streamhit.app.util.AdultContentFilter.isAdult(it.title, it.genres, it.detailPath, it.coverUrl)
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
        failedStreamUrls.clear()
        hasPlaybackError = false
        playbackErrorMessage = null
        isBuffering = true
        scope.launch {
            val sId = selectedDub?.subjectId?.ifBlank { detail?.subjectId?.ifEmpty { subjectId } } ?: subjectId
            val dPath = selectedDub?.detailPath?.ifBlank { detail?.detailPath?.ifEmpty { detailPath } } ?: detailPath
            var resList = repository.getStreamingResources(sId, dPath, season, episode)

            // Fallback 1: detail subject
            if (resList.isEmpty() && detail != null && detail!!.subjectId.isNotBlank() && detail!!.subjectId != sId) {
                resList = repository.getStreamingResources(detail!!.subjectId, detail!!.detailPath.ifBlank { dPath }, season, episode)
            }

            // Fallback 2: candidate dubs
            if (resList.isEmpty()) {
                val candidateDubs = detail?.dubs?.filter { it.subjectId.isNotBlank() && it.subjectId != sId } ?: emptyList()
                for (dub in candidateDubs) {
                    val dStreams = repository.getStreamingResources(dub.subjectId, dub.detailPath.ifBlank { dPath }, season, episode)
                    if (dStreams.isNotEmpty()) {
                        resList = dStreams
                        selectedDub = dub
                        break
                    }
                }
            }

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
        failedStreamUrls.clear()
        hasPlaybackError = false
        playbackErrorMessage = null
        isBuffering = true
        scope.launch {
            val sId = dub.subjectId.ifBlank { detail?.subjectId?.ifEmpty { subjectId } } ?: subjectId
            val dPath = dub.detailPath.ifBlank { if (sId == subjectId) detailPath else "" }
            try {
                val newDetail = repository.getDetail(dPath, sId)
                if (newDetail != null) {
                    val mergedDubs = if (!newDetail.dubs.isNullOrEmpty()) newDetail.dubs else detail?.dubs
                    detail = newDetail.copy(dubs = mergedDubs)
                    val validSeasons = newDetail.seasons?.filter { it.episodes.isNotEmpty() } ?: emptyList()
                    if (validSeasons.isNotEmpty()) {
                        val curSeason = validSeasons.firstOrNull { it.seasonNumber == currentSeasonNum }
                            ?: validSeasons.first()
                        currentSeasonNum = curSeason.seasonNumber
                        if (curSeason.episodes.none { it.episodeNumber == currentEpisodeNum }) {
                            currentEpisodeNum = curSeason.episodes.firstOrNull()?.episodeNumber ?: 1
                        }
                    }
                }
            } catch (_: Exception) {}

            val targetPath = detail?.detailPath?.ifBlank { dPath } ?: dPath
            val resList = repository.getStreamingResources(sId, targetPath, currentSeasonNum, currentEpisodeNum)
            resources = resList
            val best = selectFastestResolution(resList)
            if (best != null && best.url.isNotBlank()) {
                val baseTitle = detail?.title ?: initialVideoTitle
                currentVideoTitle = "$baseTitle S${currentSeasonNum}E${currentEpisodeNum}"
                currentStreamUrl = best.url
                Toast.makeText(context, "Audio: ${dub.name.ifBlank { dub.language }}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Stream unavailable for Episode $currentEpisodeNum in ${dub.name.ifBlank { dub.language }}", Toast.LENGTH_SHORT).show()
                isBuffering = false
            }
        }
    }

    val hasNextEpisode = remember(detail, currentSeasonNum, currentEpisodeNum) {
        if (detail?.subjectType != 2) false
        else {
            val validSeasons = detail?.seasons?.filter { it.episodes.isNotEmpty() } ?: emptyList()
            val s = validSeasons.firstOrNull { it.seasonNumber == currentSeasonNum }
            val epList = s?.episodes?.map { it.episodeNumber } ?: emptyList()
            val curIdx = epList.indexOf(currentEpisodeNum)
            if (curIdx >= 0 && curIdx + 1 < epList.size) true
            else {
                val nextS = validSeasons.firstOrNull { it.seasonNumber > currentSeasonNum }
                nextS != null && nextS.episodes.isNotEmpty()
            }
        }
    }

    fun playNextEpisode() {
        val validSeasons = detail?.seasons?.filter { it.episodes.isNotEmpty() } ?: emptyList()
        val s = validSeasons.firstOrNull { it.seasonNumber == currentSeasonNum }
        val epList = s?.episodes?.map { it.episodeNumber } ?: emptyList()
        val curIdx = epList.indexOf(currentEpisodeNum)
        if (curIdx >= 0 && curIdx + 1 < epList.size) {
            switchEpisode(currentSeasonNum, epList[curIdx + 1])
        } else {
            val nextS = validSeasons.firstOrNull { it.seasonNumber > currentSeasonNum }
            if (nextS != null && nextS.episodes.isNotEmpty()) {
                val nextEp = nextS.episodes.first().episodeNumber
                switchEpisode(nextS.seasonNumber, nextEp)
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

        val dataSourceFactory = if (isLiveTv) {
            MediaCacheManager.getDirectDataSourceFactory(context)
        } else {
            MediaCacheManager.getDataSourceFactory(context)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = if (isLiveTv) {
            DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    6_000,    // minBufferMs (6s buffer for Live TV eliminates continuous stalls)
                    20_000,   // maxBufferMs (20s buffer max)
                    800,      // bufferForPlaybackMs (Instant 0.8s start for Live TV)
                    1_500     // bufferForPlaybackAfterRebufferMs (1.5s recovery)
                )
                .setTargetBufferBytes(C.LENGTH_UNSET)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    60_000,   // minBufferMs (Aggressive continuous buffer for VOD)
                    900_000,  // maxBufferMs (15 minutes continuous ahead buffer: 15m buffer!)
                    500,      // bufferForPlaybackMs (Starts playback instantly with 0.5s buffer)
                    1_200     // bufferForPlaybackAfterRebufferMs (Fast rebuffer recovery)
                )
                .setTargetBufferBytes(C.LENGTH_UNSET)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(60_000, false)
                .build()
        }

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
                val mediaItem = if (isLiveTv) {
                    MediaItem.Builder()
                        .setUri(Uri.parse(resolvedUrl))
                        .setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(3500L)
                                .setMinPlaybackSpeed(0.97f)
                                .setMaxPlaybackSpeed(1.03f)
                                .build()
                        )
                        .build()
                } else {
                    MediaItem.fromUri(Uri.parse(resolvedUrl))
                }
                setMediaItem(mediaItem, if (isLiveTv) 0L else initialStartMs)
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
        (activity as? PlayerActivity)?.setPlayerInstance(player)

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
                (activity as? PlayerActivity)?.updatePipParams(playing)
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
                        hasPlaybackError = false
                        playbackErrorMessage = null
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
                android.util.Log.e("StreamHitPlayer", "Player error code=${error.errorCode}: $sanitizedMsg on $currentStreamUrl")

                // Step 1: Automatic proxy fallback if direct CDN failed and we haven't tried proxy yet
                if (resolvedUrl != currentStreamUrl && !triedProxyFallback) {
                    triedProxyFallback = true
                    android.util.Log.w("StreamHitPlayer", "Direct CDN stream failed. Falling back to proxy: $currentStreamUrl")
                    val curPos = player.currentPosition.coerceAtLeast(0L)
                    player.setMediaItem(MediaItem.fromUri(Uri.parse(currentStreamUrl)), curPos)
                    player.prepare()
                    player.play()
                    return
                }

                // Step 2: Mark currentStreamUrl as failed so we don't loop on it
                if (!failedStreamUrls.contains(currentStreamUrl)) {
                    failedStreamUrls.add(currentStreamUrl)
                }

                // Step 2.5: Live TV Server Auto-Failover ("chanle er ekta server kaj na korle onno server e jabe")
                if (isLiveTv && currentChannel != null) {
                    val servers = currentChannel!!.activeServers
                    val untriedServerIdx = servers.indexOfFirst { it.url.isNotBlank() && !failedStreamUrls.contains(it.url) }
                    if (untriedServerIdx >= 0) {
                        val nextServer = servers[untriedServerIdx]
                        currentServerIndex = untriedServerIdx
                        currentStreamUrl = nextServer.url
                        isBuffering = true
                        triedProxyFallback = false
                        android.util.Log.w("StreamHitPlayer", "Live TV Auto-failover: Switching to ${nextServer.label} (${nextServer.url})")
                        Toast.makeText(context, "⚠️ সার্ভার বিচ্ছিন্ন। ${nextServer.label}-এ অটো সুইচ করা হচ্ছে...", Toast.LENGTH_SHORT).show()
                        val directUrl = resolveDirectPlayableUrl(nextServer.url)
                        val mediaItem = MediaItem.Builder()
                            .setUri(Uri.parse(directUrl))
                            .setLiveConfiguration(
                                MediaItem.LiveConfiguration.Builder()
                                    .setTargetOffsetMs(3500L)
                                    .setMinPlaybackSpeed(0.97f)
                                    .setMaxPlaybackSpeed(1.03f)
                                    .build()
                            )
                            .build()
                        player.stop()
                        player.clearMediaItems()
                        player.setMediaItem(mediaItem)
                        player.prepare()
                        player.play()
                        return
                    }
                }

                // Step 3: Check all other available untried servers/resolutions (1080p, 720p, 480p, 360p, etc.)
                val untriedStreams = resources.filter { it.url.isNotBlank() && !failedStreamUrls.contains(it.url) }
                if (untriedStreams.isNotEmpty()) {
                    val nextRes = selectNextBestUntried(untriedStreams) ?: untriedStreams.first()
                    android.util.Log.w("StreamHitPlayer", "Auto-failover: Trying next server ${nextRes.displayResolution} (${nextRes.url})")
                    val curPos = player.currentPosition.coerceAtLeast(0L)
                    targetStartPositionMs = curPos
                    Toast.makeText(context, "সার্ভার চেক করা হচ্ছে: ${nextRes.displayResolution}...", Toast.LENGTH_SHORT).show()
                    currentStreamUrl = nextRes.url
                    return
                }

                // Step 4: If resources has no untried streams left, check alternative dubs or subject
                scope.launch {
                    isBuffering = true
                    val sid = selectedDub?.subjectId?.ifBlank { detail?.subjectId?.ifEmpty { subjectId } } ?: subjectId
                    val path = detail?.detailPath?.ifBlank { selectedDub?.detailPath?.ifBlank { detailPath } } ?: detailPath

                    var freshList: List<StreamResource> = emptyList()

                    if (resources.isEmpty() || resources.all { it.url in failedStreamUrls }) {
                        // Check main detail if different
                        if (detail != null && detail!!.subjectId.isNotBlank() && detail!!.subjectId != sid) {
                            try {
                                val mainStreams = repository.getStreamingResources(detail!!.subjectId, detail!!.detailPath.ifBlank { path }, currentSeasonNum, currentEpisodeNum)
                                if (mainStreams.any { it.url !in failedStreamUrls }) {
                                    freshList = mainStreams
                                }
                            } catch (_: Exception) {}
                        }

                        // Check candidate dubs
                        if (freshList.none { it.url !in failedStreamUrls }) {
                            val candidateDubs = detail?.dubs?.filter { it.subjectId.isNotBlank() && it.subjectId != sid } ?: emptyList()
                            for (dub in candidateDubs) {
                                try {
                                    val dStreams = repository.getStreamingResources(dub.subjectId, dub.detailPath.ifBlank { path }, currentSeasonNum, currentEpisodeNum)
                                    if (dStreams.any { it.url !in failedStreamUrls }) {
                                        freshList = dStreams
                                        selectedDub = dub
                                        break
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    val candidates = freshList.filter { it.url.isNotBlank() && it.url !in failedStreamUrls }
                    if (candidates.isNotEmpty()) {
                        resources = freshList
                        val nextBest = selectNextBestUntried(candidates) ?: candidates.first()
                        val curPos = player.currentPosition.coerceAtLeast(0L)
                        targetStartPositionMs = curPos
                        Toast.makeText(context, "বিকল্প সার্ভার পাওয়া গেছে: ${nextBest.displayResolution}...", Toast.LENGTH_SHORT).show()
                        currentStreamUrl = nextBest.url
                    } else {
                        // All servers across all available resolutions (1080p, 720p, 480p, 360p) have been tested and failed!
                        isBuffering = false
                        hasPlaybackError = true
                        playbackErrorMessage = "সকল সার্ভার (1080p, 720p, 480p, 360p) যাচাই করা হয়েছে, কিন্তু কোনোটিতেই ভিডিওটি চালু করা যায়নি।"
                    }
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
            (activity as? PlayerActivity)?.setPlayerInstance(null)
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

    // Auto-update playback position, buffer stats, and AI Audio Translation sync
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

                // 20-minute forced watch lock / share-to-continue for Movies and TV Series
                val isOffline = initialIsOffline || initialStreamUrl.startsWith("content://") || initialStreamUrl.startsWith("file://") || initialStreamUrl.startsWith("/")
                val isLiveStream = !isOffline && (subjectId.isBlank() && detailPath.isBlank())
                if (!isLiveStream && !hasSharedToUnlock && pos >= 20 * 60 * 1000L) {
                    p.pause()
                    showWatchLockDialog = true
                }

                // Automated periodic sponsor ad gate (Mid-roll)
                val intervalMinutes = SponsorAdManager.getPlaybackIntervalMinutes(SponsorAdManager.PLACEMENT_PLAYER_INTERVAL)
                val intervalMs = intervalMinutes * 60 * 1000L
                if (!isOffline && SponsorAdManager.isPlacementEnabled(SponsorAdManager.PLACEMENT_PLAYER_INTERVAL) && !showPeriodicSponsorDialog) {
                    if (System.currentTimeMillis() - lastMidrollAdTimestampMs >= intervalMs) {
                        p.pause()
                        showPeriodicSponsorDialog = true
                    }
                }

                // Real-time AI Audio Translation synchronization
                com.streamhit.app.util.SubtitleTtsManager.syncWithPlayback(pos, p)
            }
            delay(150)
        }
    }

    // Auto-hide controls after 4 seconds (only when playing, stay visible when paused)
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying && !isLocked) {
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
                initialDub = selectedDub,
                onDismiss = { showDownloadSheet = false }
            )
        } else if (resources.isNotEmpty()) {
            DownloadQualityBottomSheet(
                resources = resources,
                title = detail?.title ?: currentVideoTitle,
                onDismiss = { showDownloadSheet = false },
                onDownloadSelected = { res ->
                    showDownloadSheet = false
                    if (SponsorAdManager.isPlacementEnabled(SponsorAdManager.PLACEMENT_DOWNLOAD_SINGLE)) {
                        pendingPlayerDownloadRes = res
                        showPlayerDownloadSponsorDialog = true
                    } else {
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
                        if (!BackgroundDownloadHelper.isBatteryOptimizationIgnored(context) && !BackgroundDownloadHelper.hasPromptedInitialPermission(context)) {
                            showPlayerBgPermissionDialog = true
                        }
                    }
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
                onOpenAudioSync = { showAudioSyncDialog = true },
                onOpenTranslator = { showTranslatorDialog = true },
                isLiveStream = isLiveTv,
                channelNumber = currentChannelIndex + 1,
                onNextChannel = { playNextChannel() },
                onPrevChannel = { playPrevChannel() },
                onSwitchLiveServer = { switchLiveServer(it) },
                currentServerIndex = currentServerIndex,
                serverCount = currentChannel?.activeServers?.size ?: 0
            )
        } else {
            val isOffline = initialIsOffline || initialStreamUrl.startsWith("content://") || initialStreamUrl.startsWith("file://") || initialStreamUrl.startsWith("/")
            val isLiveStream = !isOffline && (subjectId.isBlank() && detailPath.isBlank())
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
                    channelNumber = currentChannelIndex + 1,
                    onNextChannel = { playNextChannel() },
                    onPrevChannel = { playPrevChannel() },
                    onSwitchLiveServer = { switchLiveServer(it) },
                    currentServerIndex = currentServerIndex,
                    serverCount = currentChannel?.activeServers?.size ?: 0,
                    audioSyncOffsetMs = audioSyncOffsetMs,
                    onOpenAudioSync = { showAudioSyncDialog = true }
                )

                PortraitMediaDetails(
                    context = context,
                    detail = detail,
                    currentVideoTitle = currentVideoTitle,
                    isLiveStream = isLiveStream,
                    isOffline = isOffline,
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
                    onOpenTranslator = { showTranslatorDialog = true },
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

        if (showTranslatorDialog) {
            TranslatorConfigDialog(
                onDismiss = { showTranslatorDialog = false }
            )
        }

        if (showWatchLockDialog) {
            WatchLockShareDialog(
                movieTitle = currentVideoTitle.ifBlank { detail?.title ?: initialVideoTitle },
                onShareSuccess = {
                    hasSharedToUnlock = true
                    prefs.edit().putBoolean("unlock_$contentKey", true).apply()
                    showWatchLockDialog = false
                    exoPlayer?.play()
                    Toast.makeText(context, "ধন্যবাদ! আপনার মুভিটি আবার প্লে হচ্ছে 🍿", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // ── PLAYBACK FAILED / RELOAD OVERLAY ──
        if (hasPlaybackError) {
            PlayerErrorReloadOverlay(
                errorMessage = playbackErrorMessage ?: "Playback failed. Tap Reload to try again.",
                onReload = {
                    failedStreamUrls.clear()
                    hasPlaybackError = false
                    playbackErrorMessage = null
                    isBuffering = true
                    exoPlayer?.let { p ->
                        val curPos = p.currentPosition.coerceAtLeast(0L)
                        p.stop()
                        p.clearMediaItems()
                        val targetUrl = resolveDirectPlayableUrl(currentStreamUrl)
                        p.setMediaItem(MediaItem.fromUri(Uri.parse(targetUrl)), curPos)
                        p.prepare()
                        p.play()
                    }
                },
                onSwitchServer = if (currentChannel != null && currentChannel!!.activeServers.size > 1) {
                    {
                        val nextIdx = (currentServerIndex + 1) % currentChannel!!.activeServers.size
                        switchLiveServer(nextIdx)
                    }
                } else if (resources.size > 1) {
                    {
                        val curIdx = resources.indexOfFirst { it.url == currentStreamUrl }
                        val nextIdx = if (curIdx >= 0) (curIdx + 1) % resources.size else 0
                        val nextRes = resources[nextIdx]
                        currentStreamUrl = nextRes.url
                        isBuffering = true
                        hasPlaybackError = false
                        playbackErrorMessage = null
                        exoPlayer?.let { p ->
                            val pos = p.currentPosition.coerceAtLeast(0L)
                            p.stop()
                            p.clearMediaItems()
                            p.setMediaItem(MediaItem.fromUri(Uri.parse(resolveDirectPlayableUrl(nextRes.url))), pos)
                            p.prepare()
                            p.play()
                        }
                        Toast.makeText(context, "Switched to ${nextRes.displayResolution} Server", Toast.LENGTH_SHORT).show()
                    }
                } else null,
                onBack = onBack,
                isFullscreen = isFullscreen
            )
        }

        // ── TV REMOTE DIGIT ENTRY OVERLAY ──
        if (enteredChannelDigits.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.85f),
                    border = BorderStroke(2.dp, AccentCyan),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "CH $enteredChannelDigits _",
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // ── LIVE TV CHANNEL SWITCH BANNER (Auto-hides) ──
        AnimatedVisibility(
            visible = channelOverlayBanner != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (isFullscreen) 24.dp else 48.dp)
        ) {
            channelOverlayBanner?.let { (num, ch) ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.90f),
                    border = BorderStroke(1.5.dp, AccentBlue.copy(alpha = 0.8f)),
                    shadowElevation = 12.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AccentBlue,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Text(
                                text = "CH ${String.format("%02d", num)}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        if (ch.logo.isNotBlank()) {
                            AsyncImage(
                                model = ch.logo,
                                contentDescription = ch.name,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Column {
                            Text(
                                text = ch.name,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "LIVE • ${ch.category.ifBlank { "Channel" }}",
                                color = Color.Red,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // 30-minute automated sponsor ad dialog during playback
        if (showPeriodicSponsorDialog) {
            SponsorSupportDialog(
                videoTitle = currentVideoTitle,
                onSupportClick = {
                    val intent = Intent(context, SponsorWebViewActivity::class.java).apply {
                        putExtra("EXTRA_TARGET_URL", SponsorAdManager.getPlacementUrl(SponsorAdManager.PLACEMENT_PLAYER_INTERVAL))
                    }
                    periodicSponsorLauncher.launch(intent)
                },
                onCancel = {
                    lastMidrollAdTimestampMs = System.currentTimeMillis()
                    showPeriodicSponsorDialog = false
                    exoPlayer?.play()
                }
            )
        }

        // Sponsor gate for movie download
        if (showPlayerDownloadSponsorDialog && pendingPlayerDownloadRes != null) {
            SponsorSupportDialog(
                videoTitle = "${currentVideoTitle} (${pendingPlayerDownloadRes?.displayResolution})",
                onSupportClick = {
                    val intent = Intent(context, SponsorWebViewActivity::class.java).apply {
                        putExtra("EXTRA_TARGET_URL", SponsorAdManager.getPlacementUrl(SponsorAdManager.PLACEMENT_DOWNLOAD_SINGLE))
                        putExtra("EXTRA_IS_DOWNLOAD_AD", true)
                    }
                    playerDownloadSponsorLauncher.launch(intent)
                },
                onCancel = {
                    showPlayerDownloadSponsorDialog = false
                    pendingPlayerDownloadRes = null
                }
            )
        }

        // Initial background download permission explanation
        if (showPlayerBgPermissionDialog) {
            BackgroundDownloadPermissionDialog(
                onGrantClick = {
                    showPlayerBgPermissionDialog = false
                    BackgroundDownloadHelper.setPromptedInitialPermission(context, true)
                    BackgroundDownloadHelper.requestIgnoreBatteryOptimization(context)
                    Toast.makeText(context, "ব্যাকগ্রাউন্ড পারমিশন উইন্ডো খোলা হয়েছে। 'Allow' দিন।", Toast.LENGTH_LONG).show()
                },
                onDismiss = {
                    showPlayerBgPermissionDialog = false
                    BackgroundDownloadHelper.setPromptedInitialPermission(context, true)
                }
            )
        }

        // ── TV LIVE PLAYLIST SIDE PANEL (6s Auto-Dismiss Overlay) ──
        if (isLiveTv) {
            TvLivePlaylistSidePanel(
                isVisible = showTvSidePanel,
                allChannels = liveChannels,
                currentChannel = currentChannel,
                onChannelSelected = { ch: LiveTvChannel ->
                    switchLiveChannel(ch)
                    tvSidePanelInteractionTime = System.currentTimeMillis()
                },
                onClosePanel = { showTvSidePanel = false },
                onUserInteracted = {
                    tvSidePanelInteractionTime = System.currentTimeMillis()
                },
                modifier = Modifier.align(Alignment.CenterStart)
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
    isSelected: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0xFF16253B) else Color(0xFF0F172A),
        border = BorderStroke(
            if (isSelected) 1.2.dp else 0.8.dp,
            if (isSelected) Color(0xFF00F5D4) else Color.White.copy(alpha = 0.08f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                if (channel.logo.isNotBlank()) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Tv, contentDescription = null, tint = TextMuted, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = channel.name,
                color = if (isSelected) Color(0xFF00F5D4) else Color.White,
                fontSize = 10.5.sp,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isSelected) "▶ Playing" else (if (channel.activeServers.size > 1) "${channel.activeServers.size} Servers" else channel.category),
                color = if (isSelected) Color(0xFF00F5D4) else TextMuted,
                fontSize = 8.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
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

@Composable
fun PlayerErrorReloadOverlay(
    errorMessage: String,
    onReload: () -> Unit,
    onSwitchServer: (() -> Unit)? = null,
    onBack: () -> Unit,
    isFullscreen: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.90f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        // Back Button Top Start
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.TopStart
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                modifier = Modifier
                    .size(36.dp)
                    .clickable(onClick = onBack)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Center Error Content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(Color(0xFFE53935).copy(alpha = 0.18f), CircleShape)
                    .border(1.5.dp, Color(0xFFE53935), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reload",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Playback Failed / প্লে করতে সমস্যা হচ্ছে",
                color = Color.White,
                fontSize = if (isFullscreen) 18.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "ভিডিও সার্ভারের সংযোগ বিচ্ছিন্ন হয়েছে। পুনরায় চেষ্টা করতে নিচের বাটনে চাপুন।",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Big Reload Button
                Button(
                    onClick = onReload,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(),
                    shape = RoundedCornerShape(50.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .background(
                            Brush.horizontalGradient(listOf(Color(0xFF00E676), Color(0xFF00E5FF))),
                            RoundedCornerShape(50.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reload",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Reload / পুনরায় চেষ্টা করুন",
                            color = Color.Black,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                if (onSwitchServer != null) {
                    OutlinedButton(
                        onClick = onSwitchServer,
                        shape = RoundedCornerShape(50.dp),
                        border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.6f)),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(
                            text = "Next Server",
                            color = Color(0xFF00E676),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
