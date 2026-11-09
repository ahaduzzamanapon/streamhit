package com.streamhit.app.ui.tv

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhit.app.R
import com.streamhit.app.data.model.LiveTvChannel
import com.streamhit.app.data.repository.LiveTvRepository
import com.streamhit.app.util.DeviceUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TvPortalScreen(
    onSelectMovies: () -> Unit,
    onSelectLiveTv: (channel: LiveTvChannel?, streamUrl: String) -> Unit
) {
    val context = LocalContext.current
    var isSplashDone by remember { mutableStateOf(false) }

    // Splash animation launch
    LaunchedEffect(Unit) {
        delay(1400)
        isSplashDone = true
    }

    if (!isSplashDone) {
        TvSplashScreen()
    } else {
        TvHubMainContent(
            context = context,
            onSelectMovies = onSelectMovies,
            onSelectLiveTv = onSelectLiveTv
        )
    }
}

/**
 * High-end animated splash screen for Google TV / Android TV on app launch
 */
@Composable
fun TvSplashScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "tvSplashPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val alphaAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alphaAnim.animateTo(1f, animationSpec = tween(600))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0F231D),
                        Color(0xFF060A12),
                        Color(0xFF030509)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.scale(alphaAnim.value)
        ) {
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF10B981).copy(alpha = 0.35f), Color.Transparent)
                        )
                    )
                    .border(2.5.dp, Color(0xFF10B981), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(72.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "NXLY STREAM",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Android TV & Google TV Edition • 4K & Ultra HD",
                fontSize = 14.sp,
                color = Color(0xFF10B981),
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }
    }
}

/**
 * Main TV Hub Screen with two massive remote-focus tiles: Movies & Series vs Live TV
 */
@Composable
fun TvHubMainContent(
    context: Context,
    onSelectMovies: () -> Unit,
    onSelectLiveTv: (channel: LiveTvChannel?, streamUrl: String) -> Unit
) {
    val liveTvRepo = remember { LiveTvRepository() }
    val moviesFocusRequester = remember { FocusRequester() }

    // Live clock for TV top bar
    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        while (true) {
            currentTime = sdf.format(Date())
            delay(10000)
        }
    }

    // Auto-focus the first card when hub opens
    LaunchedEffect(Unit) {
        delay(200)
        try {
            moviesFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    val handleLiveTvLaunch: () -> Unit = {
        // Retrieve last played channel from SharedPreferences
        val (lastChannel, lastServerIdx) = DeviceUtils.getLastPlayedLiveChannel(context)
        if (lastChannel != null && lastChannel.activeServers.isNotEmpty()) {
            val sIdx = if (lastServerIdx in lastChannel.activeServers.indices) lastServerIdx else 0
            val targetUrl = lastChannel.activeServers[sIdx].url
            onSelectLiveTv(lastChannel, targetUrl)
        } else {
            // If no previous channel, load default channel from cache/repo
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                val channels = liveTvRepo.getChannels(context)
                val firstChannel = channels.firstOrNull()
                val targetUrl = firstChannel?.activeServers?.firstOrNull()?.url ?: ""
                onSelectLiveTv(firstChannel, targetUrl)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF090D1A),
                        Color(0xFF04060C),
                        Color(0xFF020306)
                    )
                )
            )
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Top Bar ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF06B6D4)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF022C22),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "NXLY STREAM",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF10B981).copy(alpha = 0.2f))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "TV EDITION",
                            color = Color(0xFF10B981),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (currentTime.isNotBlank()) {
                    Text(
                        text = currentTime,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // ── Center Content: Two Massive TV Tiles ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "কী দেখতে চান নির্বাচন করুন",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "রিমোটের ডানে/বামে সিলেক্ট করে OK চাপুন",
                    fontSize = 14.sp,
                    color = Color(0xFF64748B)
                )

                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // ── Tile 1: Movies & Series ──
                    TvHubCard(
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(moviesFocusRequester),
                        title = "মুভি ও সিরিজ",
                        subtitle = "হাজারো এইচডি মুভি, হলিউড, বলিউড ও বাংলা ডাবিং",
                        badgeText = "CINEMA & SERIES",
                        badgeColor = Color(0xFF3B82F6),
                        icon = Icons.Default.Movie,
                        accentGradient = listOf(Color(0xFF1E3A8A), Color(0xFF0F172A)),
                        onClick = onSelectMovies
                    )

                    // ── Tile 2: Live TV ──
                    TvHubCard(
                        modifier = Modifier.weight(1f),
                        title = "লাইভ টিভি",
                        subtitle = "ওয়ার্ল্ড স্পোর্টস, খেলাধুলা ও সকল টিভি চ্যানেল",
                        badgeText = "LIVE NOW",
                        badgeColor = Color(0xFFEF4444),
                        icon = Icons.Default.Tv,
                        accentGradient = listOf(Color(0xFF064E3B), Color(0xFF09141D)),
                        isLiveIndicator = true,
                        onClick = handleLiveTvLaunch
                    )
                }
            }

            // ── Bottom Remote Guide ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvRemoteKeyHint(keyLabel = "◄ ►", desc = "সিলেক্ট")
                Spacer(modifier = Modifier.width(20.dp))
                TvRemoteKeyHint(keyLabel = "OK", desc = "ওপেন করুন")
                Spacer(modifier = Modifier.width(20.dp))
                TvRemoteKeyHint(keyLabel = "BACK", desc = "বাহির হন")
            }
        }
    }
}

/**
 * Individual remote-focusable hub card with scale & glowing border on D-pad focus
 */
@Composable
fun TvHubCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentGradient: List<Color>,
    isLiveIndicator: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "cardScale"
    )

    val borderColor = if (isFocused) Color(0xFF10B981) else Color.White.copy(alpha = 0.08f)
    val borderWidth = if (isFocused) 3.5.dp else 1.dp

    Box(
        modifier = modifier
            .scale(scale)
            .height(230.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    if (isFocused) listOf(accentGradient[0].copy(alpha = 0.95f), accentGradient[1])
                    else listOf(accentGradient[0].copy(alpha = 0.45f), Color(0xFF0B101D))
                )
            )
            .border(borderWidth, borderColor, RoundedCornerShape(22.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Row: Badge & Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(badgeColor.copy(alpha = 0.2f))
                        .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    if (isLiveIndicator) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (isFocused) Color(0xFF10B981) else Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isFocused) Color(0xFF022C22) else Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Bottom Content
            Column {
                Text(
                    text = title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (isFocused) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF10B981))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF022C22),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "প্লে করতে OK চাপুন",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF022C22)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TvRemoteKeyHint(keyLabel: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = keyLabel,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = desc,
            color = Color(0xFF64748B),
            fontSize = 12.sp
        )
    }
}

