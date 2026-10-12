package com.streamhit.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val repository = MovieRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            StreamfitTheme {
                MainAppScreen(
                    repository = repository,
                    onPlayVideo = { url, title, subjectId, detailPath, coverUrl ->
                        val intent = Intent(this, PlayerActivity::class.java).apply {
                            putExtra("EXTRA_STREAM_URL", url)
                            putExtra("EXTRA_VIDEO_TITLE", title)
                            putExtra("EXTRA_SUBJECT_ID", subjectId)
                            putExtra("EXTRA_DETAIL_PATH", detailPath)
                            putExtra("EXTRA_COVER_URL", coverUrl)
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
    onPlayVideo: (streamUrl: String, title: String, subjectId: String, detailPath: String, coverUrl: String) -> Unit
) {
    var currentTab by remember { mutableStateOf<Screen>(Screen.Home) }

    // Navigation state to DetailsScreen
    var activeDetailPath by remember { mutableStateOf<String?>(null) }
    var activeSubjectId by remember { mutableStateOf<String?>(null) }

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
                NavigationBar(
                    containerColor = BgSurface,
                    contentColor = TextPrimary,
                    tonalElevation = 8.dp
                ) {
                    val tabs = listOf(Screen.Home, Screen.LiveTv, Screen.Explore, Screen.Downloads, Screen.Search)
                    tabs.forEach { tab ->
                        val isSelected = currentTab == tab
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { currentTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                    tint = if (isSelected) AccentEmerald else TextMuted
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    color = if (isSelected) AccentEmerald else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = AccentEmerald.copy(alpha = 0.12f)
                            )
                        )
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
                        activeDetailPath = null
                        activeSubjectId = null
                    },
                    onPlayClick = { url, title, cover ->
                        onPlayVideo(url, title, activeSubjectId ?: "", activeDetailPath ?: "", cover)
                    },
                    onNavigateToDownloads = {
                        activeDetailPath = null
                        activeSubjectId = null
                        currentTab = Screen.Downloads
                    }
                )
            } else {
                when (currentTab) {
                    Screen.Home -> HomeScreen(
                        repository = repository,
                        onMediaClick = { path, id, _ ->
                            activeDetailPath = path
                            activeSubjectId = id
                        },
                        onPlayDirect = { url, title ->
                            onPlayVideo(url, title, "", "", "")
                        },
                        onNavigateToSearch = {
                            currentTab = Screen.Search
                        }
                    )
                    Screen.LiveTv -> LiveTvScreen(
                        onPlayStream = { url, title ->
                            onPlayVideo(url, title, "", "", "")
                        }
                    )
                    Screen.Explore -> ExploreScreen(
                        repository = repository,
                        onMediaClick = { path, id, _ ->
                            activeDetailPath = path
                            activeSubjectId = id
                        }
                    )
                    Screen.Downloads -> DownloadsScreen(
                        repository = repository,
                        onPlayOffline = { localUri, title ->
                            onPlayVideo(localUri, title, "", "", "")
                        },
                        onNavigateToDetails = { path, id ->
                            activeDetailPath = path
                            activeSubjectId = id
                        }
                    )
                    Screen.Search -> SearchScreen(
                        repository = repository,
                        onMediaClick = { path, id, _ ->
                            activeDetailPath = path
                            activeSubjectId = id
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
