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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                    onPlayVideo = { url, title, subjectId, detailPath, coverUrl, seasonNum, episodeNum, startPositionMs ->
                        val intent = Intent(this, PlayerActivity::class.java).apply {
                            putExtra("EXTRA_STREAM_URL", url)
                            putExtra("EXTRA_VIDEO_TITLE", title)
                            putExtra("EXTRA_SUBJECT_ID", subjectId)
                            putExtra("EXTRA_DETAIL_PATH", detailPath)
                            putExtra("EXTRA_COVER_URL", coverUrl)
                            putExtra("EXTRA_SEASON_NUM", seasonNum)
                            putExtra("EXTRA_EPISODE_NUM", episodeNum)
                            putExtra("EXTRA_START_POSITION_MS", startPositionMs)
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
    onPlayVideo: (streamUrl: String, title: String, subjectId: String, detailPath: String, coverUrl: String, seasonNum: Int, episodeNum: Int, startPositionMs: Long) -> Unit
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

    // Android System Back Button Handler
    BackHandler(enabled = true) {
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

    // In-app Version Control State
    var updateInfo by remember { mutableStateOf<AppVersionInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
            if (activeDetailPath == null) {
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
                    onPlayClick = { url, title, cover, seasonNum, episodeNum, startPos ->
                        onPlayVideo(url, title, activeSubjectId ?: "", activeDetailPath ?: "", cover, seasonNum, episodeNum, startPos)
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
                        onPlayDirect = { url, title, subjectId, detailPath, coverUrl, seasonNum, episodeNum, startPos ->
                            onPlayVideo(url, title, subjectId, detailPath, coverUrl, seasonNum, episodeNum, startPos)
                        },
                        onNavigateToSearch = {
                            switchTab(Screen.Search)
                        }
                    )
                    Screen.LiveTv -> LiveTvScreen(
                        onPlayStream = { url, title ->
                            onPlayVideo(url, title, "", "", "", 1, 1, 0L)
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
                            onPlayVideo(localUri, title, "", "", "", 1, 1, 0L)
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

            // In-app Update Dialog (Version Controller)
            if (showUpdateDialog && updateInfo != null) {
                val info = updateInfo!!
                val context = androidx.compose.ui.platform.LocalContext.current
                var isDownloading by remember { mutableStateOf(false) }

                AlertDialog(
                    onDismissRequest = {
                        if (!info.mustUpdate) showUpdateDialog = false
                    },
                    title = {
                        Text(
                            text = "Update Available (v${info.versionName})",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = info.releaseNotes ?: "A newer version of NXLY Stream is available with improvements and fixes.",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            if (info.mustUpdate) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "This is a required update to continue watching.",
                                    color = AccentPink,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
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
                                    if (!info.mustUpdate) showUpdateDialog = false
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
                                    if (!info.mustUpdate) showUpdateDialog = false
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
                        if (!info.mustUpdate) {
                            TextButton(onClick = { showUpdateDialog = false }) {
                                Text("Later", color = TextMuted)
                            }
                        }
                    },
                    containerColor = BgSurface,
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
    }
}
