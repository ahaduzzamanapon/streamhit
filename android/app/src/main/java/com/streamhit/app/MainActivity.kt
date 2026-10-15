package com.streamhit.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.filled.Tv
import com.streamhit.app.data.model.AppVersionInfo
import com.streamhit.app.data.repository.MovieRepository
import com.streamhit.app.ui.details.DetailsScreen
import com.streamhit.app.ui.downloads.DownloadsScreen
import com.streamhit.app.ui.explore.ExploreScreen
import com.streamhit.app.ui.home.HomeScreen
import com.streamhit.app.ui.livetv.LiveTvScreen
import com.streamhit.app.ui.player.PlayerActivity
import com.streamhit.app.ui.search.SearchScreen
import com.streamhit.app.ui.theme.*
import com.streamhit.app.update.AppUpdateManager
import com.streamhit.app.data.analytics.AppAnalyticsManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val repository = MovieRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register device & start telemetry heartbeat
        AppAnalyticsManager.getDeviceId(this)
        AppAnalyticsManager.startHeartbeat(this, lifecycleScope)

        setContent {
            StreamfitTheme {
                MainAppScreen(
                    repository = repository,
                    onPlayVideo = { url, title, subjectId, detailPath, coverUrl, seasonNum, episodeNum, startPositionMs, dubLanguage, dubSubjectId, dubDetailPath, dubName ->
                        val intent = Intent(this, PlayerActivity::class.java).apply {
                            putExtra("EXTRA_STREAM_URL", url)
                            putExtra("EXTRA_VIDEO_TITLE", title)
                            putExtra("EXTRA_SUBJECT_ID", subjectId)
                            putExtra("EXTRA_DETAIL_PATH", detailPath)
                            putExtra("EXTRA_COVER_URL", coverUrl)
                            putExtra("EXTRA_SEASON_NUM", seasonNum)
                            putExtra("EXTRA_EPISODE_NUM", episodeNum)
                            putExtra("EXTRA_START_POSITION_MS", startPositionMs)
                            if (!dubLanguage.isNullOrBlank()) putExtra("EXTRA_DUB_LANGUAGE", dubLanguage)
                            if (!dubSubjectId.isNullOrBlank()) putExtra("EXTRA_DUB_SUBJECT_ID", dubSubjectId)
                            if (!dubDetailPath.isNullOrBlank()) putExtra("EXTRA_DUB_DETAIL_PATH", dubDetailPath)
                            if (!dubName.isNullOrBlank()) putExtra("EXTRA_DUB_NAME", dubName)
                        }
                        startActivity(intent)
                    }
                )
            }
        }
    }
}

sealed class Screen(val title: String, val icon: ImageVector) {
    object Home : Screen("Home", Icons.Default.Home)
    object LiveTv : Screen("Live TV", Icons.Default.Tv)
    object Explore : Screen("Explore", Icons.Default.Movie)
    object Downloads : Screen("Downloads", Icons.Default.FileDownload)
    object Search : Screen("Search", Icons.Default.Search)
}

@Composable
fun MainAppScreen(
    repository: MovieRepository,
    onPlayVideo: (streamUrl: String, title: String, subjectId: String, detailPath: String, coverUrl: String, seasonNum: Int, episodeNum: Int, startPositionMs: Long, dubLanguage: String?, dubSubjectId: String?, dubDetailPath: String?, dubName: String?) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var lastBackPressedTime by remember { mutableLongStateOf(0L) }

    var currentTab by remember { mutableStateOf<Screen>(Screen.Home) }
    val tabBackStack = remember { mutableStateListOf<Screen>() }

    // Navigation state to DetailsScreen & stack for nested details
    var activeDetailPath by remember { mutableStateOf<String?>(null) }
    var activeSubjectId by remember { mutableStateOf<String?>(null) }
    val detailBackStack = remember { mutableStateListOf<Pair<String, String>>() }

    fun navigateToDetails(path: String, id: String) {
        if (activeDetailPath != null) {
            detailBackStack.add(Pair(activeDetailPath!!, activeSubjectId ?: ""))
        }
        activeDetailPath = path
        activeSubjectId = id
    }

    fun popDetails() {
        if (detailBackStack.isNotEmpty()) {
            val prev = detailBackStack.removeAt(detailBackStack.size - 1)
            activeDetailPath = prev.first
            activeSubjectId = prev.second
        } else {
            activeDetailPath = null
            activeSubjectId = null
        }
    }

    fun switchTab(newTab: Screen) {
        if (currentTab != newTab) {
            tabBackStack.remove(newTab)
            tabBackStack.add(currentTab)
            currentTab = newTab
        }
    }

    // In-app Version Control State
    var updateInfo by remember { mutableStateOf<AppVersionInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Android System Back Button Handler
    BackHandler(enabled = true) {
        if (showUpdateDialog && updateInfo?.mustUpdate == true) {
            val now = System.currentTimeMillis()
            if (now - lastBackPressedTime < 2000L) {
                activity?.finish()
            } else {
                lastBackPressedTime = now
                Toast.makeText(context, "App update is required. Press back again to exit", Toast.LENGTH_SHORT).show()
            }
            return@BackHandler
        }
        if (activeDetailPath != null) {
            // 1. If currently in Details page, back returns to previous page / details
            popDetails()
        } else if (tabBackStack.isNotEmpty()) {
            // 2. If navigated from other tabs, back returns to previous tab
            val prevTab = tabBackStack.removeAt(tabBackStack.size - 1)
            currentTab = prevTab
        } else if (currentTab != Screen.Home) {
            // 3. Fallback: If not on Home, back goes to Home page
            currentTab = Screen.Home
        } else {
            // 4. On Home screen: Double-back to exit within 2 seconds
            val now = System.currentTimeMillis()
            if (now - lastBackPressedTime < 2000L) {
                activity?.finish()
            } else {
                lastBackPressedTime = now
                Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Check version update against backend admin panel
    LaunchedEffect(Unit) {
        scope.launch {
            val update = AppUpdateManager.checkForUpdate()
            if (update != null) {
                updateInfo = update
                showUpdateDialog = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (activeDetailPath == null && !(showUpdateDialog && updateInfo?.mustUpdate == true)) {
                // Sleek, compact animated bottom navigation bar
                Surface(
                    color = Color(0xFF0F1420).copy(alpha = 0.96f),
                    tonalElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val tabs = listOf(Screen.Home, Screen.LiveTv, Screen.Explore, Screen.Downloads, Screen.Search)
                        tabs.forEach { tab ->
                            val isSelected = currentTab == tab
                            val iconScale by animateFloatAsState(
                                targetValue = if (isSelected) 1.12f else 1.0f,
                                animationSpec = tween(220),
                                label = "tabIconScale"
                            )
                            val tintColor by animateColorAsState(
                                targetValue = if (isSelected) AccentEmerald else TextMuted,
                                animationSpec = tween(220),
                                label = "tabColor"
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(horizontal = 2.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) AccentEmerald.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable { switchTab(tab) },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.title,
                                        tint = tintColor,
                                        modifier = Modifier
                                            .size(19.dp)
                                            .graphicsLayer {
                                                scaleX = iconScale
                                                scaleY = iconScale
                                            }
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = tab.title,
                                        color = tintColor,
                                        fontSize = 9.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.height(1.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(3.dp)
                                                .background(AccentEmerald, CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = BgDark
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (activeDetailPath != null) {
                DetailsScreen(
                    repository = repository,
                    detailPath = activeDetailPath!!,
                    subjectId = activeSubjectId ?: "",
                    onBackClick = {
                        popDetails()
                    },
                    onPlayClick = { url, title, cover, seasonNum, episodeNum, startPos, dubLanguage, dubSubjectId, dubDetailPath, dubName ->
                        onPlayVideo(url, title, activeSubjectId ?: "", activeDetailPath ?: "", cover, seasonNum, episodeNum, startPos, dubLanguage, dubSubjectId, dubDetailPath, dubName)
                    },
                    onNavigateToDownloads = {
                        activeDetailPath = null
                        activeSubjectId = null
                        detailBackStack.clear()
                        switchTab(Screen.Downloads)
                    }
                )
            } else {
                when (currentTab) {
                    Screen.Home -> HomeScreen(
                        repository = repository,
                        onMediaClick = { path, id, _ ->
                            navigateToDetails(path, id)
                        },
                        onPlayDirect = { url, title, subjectId, detailPath, coverUrl, seasonNum, episodeNum, startPos, dubLanguage, dubSubjectId, dubDetailPath, dubName ->
                            onPlayVideo(url, title, subjectId, detailPath, coverUrl, seasonNum, episodeNum, startPos, dubLanguage, dubSubjectId, dubDetailPath, dubName)
                        },
                        onNavigateToSearch = {
                            switchTab(Screen.Search)
                        }
                    )
                    Screen.LiveTv -> LiveTvScreen(
                        onPlayStream = { url, title ->
                            onPlayVideo(url, title, "", "", "", 1, 1, 0L, null, null, null, null)
                        }
                    )
                    Screen.Explore -> ExploreScreen(
                        repository = repository,
                        onMediaClick = { path, id, _ ->
                            navigateToDetails(path, id)
                        }
                    )
                    Screen.Downloads -> DownloadsScreen(
                        repository = repository,
                        onPlayOffline = { localUri, title ->
                            onPlayVideo(localUri, title, "", "", "", 1, 1, 0L, null, null, null, null)
                        },
                        onNavigateToDetails = { path, id ->
                            navigateToDetails(path, id)
                        }
                    )
                    Screen.Search -> SearchScreen(
                        repository = repository,
                        onMediaClick = { path, id, _ ->
                            navigateToDetails(path, id)
                        }
                    )
                }
            }

            // ─── STRICT MANDATORY UPDATE LOCK SCREEN ───
            if (showUpdateDialog && updateInfo != null && updateInfo!!.mustUpdate) {
                val info = updateInfo!!
                val context = androidx.compose.ui.platform.LocalContext.current
                var isDownloading by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BgDark)
                        .zIndex(999f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BgSurface),
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE50914).copy(alpha = 0.45f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // High-contrast Warning Pill
                            Surface(
                                color = Color(0xFFE50914).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(50),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE50914).copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Alert",
                                        tint = Color(0xFFFF4D4D),
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "CRITICAL UPDATE REQUIRED",
                                        color = Color(0xFFFF4D4D),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "App Update Required",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "v${com.streamhit.app.BuildConfig.VERSION_NAME} ➔ v${info.versionName}",
                                color = AccentEmerald,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "This update is required to continue streaming movies and live TV. Please update the app to proceed.",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                textAlign = TextAlign.Center
                            )

                            if (!info.releaseNotes.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    color = Color.White.copy(alpha = 0.04f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "What's New in v${info.versionName}:",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = info.releaseNotes,
                                            color = TextMuted,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }

                            if (isDownloading) {
                                Spacer(modifier = Modifier.height(16.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = AccentEmerald,
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Downloading update package in background... Installation will start automatically once downloaded.",
                                    color = AccentEmerald,
                                    fontSize = 11.5.sp,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // Action Buttons
                            Button(
                                onClick = {
                                    isDownloading = true
                                    AppUpdateManager.startDownloadAndInstall(context, info)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Update",
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isDownloading) "Downloading..." else "Update Now",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = {
                                    AppUpdateManager.openBrowserDownload(context, info)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Download via Browser", fontSize = 13.sp)
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            TextButton(
                                onClick = { activity?.finish() }
                            ) {
                                Text("Exit App", color = TextMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // ─── OPTIONAL UPDATE DIALOG ───
            if (showUpdateDialog && updateInfo != null && !updateInfo!!.mustUpdate) {
                val info = updateInfo!!
                val context = androidx.compose.ui.platform.LocalContext.current
                var isDownloading by remember { mutableStateOf(false) }

                AlertDialog(
                    onDismissRequest = {
                        showUpdateDialog = false
                    },
                    properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = "Update",
                                tint = AccentEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Update Available (v${info.versionName})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        }
                    },
                    text = {
                        Column {
                            Text(
                                text = info.releaseNotes ?: "A newer version of NXLY Stream is available with improvements and fixes.",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            if (isDownloading) {
                                Spacer(modifier = Modifier.height(14.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = AccentEmerald,
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Downloading APK in background... You can also tap Browser to download directly.",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    AppUpdateManager.openBrowserDownload(context, info)
                                    showUpdateDialog = false
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Browser", fontSize = 13.sp)
                            }

                            Button(
                                onClick = {
                                    isDownloading = true
                                    AppUpdateManager.startDownloadAndInstall(context, info)
                                    showUpdateDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (isDownloading) "Downloading..." else "Update Now",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("Later", color = TextMuted)
                        }
                    },
                    containerColor = BgSurface,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}
