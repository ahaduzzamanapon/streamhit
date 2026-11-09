package com.streamhit.app.ui.livetv

import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhit.app.data.model.LiveTvChannel
import com.streamhit.app.data.model.StreamLinkItem
import com.streamhit.app.data.model.WorldSportMatch
import com.streamhit.app.data.repository.LiveTvRepository
import com.streamhit.app.ui.components.AppBrandHeader
import com.streamhit.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTvScreen(
    repository: LiveTvRepository = remember { LiveTvRepository() },
    onPlayStream: (streamUrl: String, title: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var channels by remember { mutableStateOf<List<LiveTvChannel>>(emptyList()) }
    var worldSports by remember { mutableStateOf<List<WorldSportMatch>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedChannelForServers by remember { mutableStateOf<LiveTvChannel?>(null) }
    var selectedSportForServers by remember { mutableStateOf<WorldSportMatch?>(null) }

    val pullRefreshState = rememberPullToRefreshState()

    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            val list = repository.getChannels(context, forceRefresh = true)
            channels = list
            worldSports = repository.getWorldSports()
            pullRefreshState.endRefresh()
        }
    }

    // Fetch channels using 5h cache, and auto-fetch real-time Live World Sports
    LaunchedEffect(Unit) {
        val list = repository.getChannels(context)
        channels = list
        isLoading = false
        // Real-time world sports auto-update whenever user enters
        worldSports = repository.getWorldSports()
    }

    // Categories list: "All" + unique sorted categories
    val categories = remember(channels) {
        val unique = channels.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
        listOf("All") + unique
    }

    // Filter channels based on search and selected category
    val filteredChannels = remember(channels, selectedCategory, searchQuery) {
        channels.filter { ch ->
            val matchCategory = if (selectedCategory == "All") true else ch.category.equals(selectedCategory, ignoreCase = true)
            val matchSearch = if (searchQuery.isBlank()) true else ch.name.contains(searchQuery, ignoreCase = true)
            matchCategory && matchSearch
        }
    }

    // Server Selection Modal BottomSheet for channels with multiple servers
    if (selectedChannelForServers != null) {
        val ch = selectedChannelForServers!!
        ModalBottomSheet(
            onDismissRequest = { selectedChannelForServers = null },
            containerColor = BgSurface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = ch.name,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Select Streaming Server (${ch.activeServers.size} Available)",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { selectedChannelForServers = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ch.activeServers.forEachIndexed { index, server ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BgCard,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedChannelForServers = null
                                    onPlayStream(server.url, ch.name)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(AccentEmerald.copy(alpha = 0.15f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            color = AccentEmerald,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = server.label,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "High Speed BDIX Server",
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = AccentEmerald,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Server Selection Modal BottomSheet for World Sports with multiple servers
    if (selectedSportForServers != null) {
        val match = selectedSportForServers!!
        ModalBottomSheet(
            onDismissRequest = { selectedSportForServers = null },
            containerColor = BgSurface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = match.displayTitle,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${match.league.ifBlank { "Live Sports" }} • ${match.activeServers.size} Servers Available",
                            color = Color(0xFFFF4B6E),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    IconButton(onClick = { selectedSportForServers = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    match.activeServers.forEachIndexed { index, server ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BgCard,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedSportForServers = null
                                    onPlayStream(server.url, "${match.displayTitle} (${server.label})")
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(Color(0xFFFF2A54).copy(alpha = 0.15f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            color = Color(0xFFFF4B6E),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = server.label,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (index == 0) "Primary Live Stream" else "Alternative Stream ${index + 1}",
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color(0xFFFF2A54),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // Compact Cinema Live TV Header
        Surface(
            color = Color(0xFF070B14).copy(alpha = 0.98f),
            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.05f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Logo & Live TV Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White)
                    ) {
                        Image(
                            painter = painterResource(id = com.streamhit.app.R.drawable.app_logo),
                            contentDescription = "NXLY",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NXLY",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = " LIVE TV",
                            color = Color(0xFFFF334B),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Right: Compact Search Capsule
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier.width(160.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color(0xFFFF334B),
                            modifier = Modifier.size(13.dp)
                        )
                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.5.sp),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text("Search TV...", color = TextMuted, fontSize = 11.5.sp)
                                }
                                innerTextField()
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(16.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }
        }

        // Category Filter Row (Directly below compact header)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            // Category Filter Pills with Emojis & Glowing active state
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(categories) { cat ->
                    val isSelected = selectedCategory == cat
                    val icon = when (cat.lowercase()) {
                        "all" -> "📺"
                        "sports" -> "⚽"
                        "news" -> "📰"
                        "entertainment" -> "🎬"
                        "bangla", "bengali" -> "🇧🇩"
                        "hindi", "india" -> "🇮🇳"
                        "kids" -> "🧸"
                        "movies", "movie" -> "🍿"
                        else -> "⚡"
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) Color(0xFFFF2A54).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFFFF2A54) else Color.White.copy(alpha = 0.10f)
                        ),
                        modifier = Modifier.clickable { selectedCategory = cat }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(text = icon, fontSize = 11.5.sp)
                            Text(
                                text = cat,
                                color = if (isSelected) Color(0xFFFF4B6E) else TextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AccentEmerald)
                    }
                }
                filteredChannels.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Tv, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "No channels matching \"$searchQuery\"" else "No channels in $selectedCategory",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                else -> {
                    // Channels Grid (3 Columns on phone)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 90.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 1. LIVE WORLD SPORTS SECTION (Pinned to very top)
                        // In fallback mode or if no active matches: this section is automatically hidden!
                        if (worldSports.isNotEmpty() && (selectedCategory == "All" || selectedCategory.equals("Sports", ignoreCase = true)) && searchQuery.isBlank()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                WorldSportsSection(
                                    matches = worldSports,
                                    onMatchClick = { match ->
                                        if (match.activeServers.size > 1) {
                                            selectedSportForServers = match
                                        } else if (match.activeServers.isNotEmpty()) {
                                            onPlayStream(match.activeServers[0].url, match.displayTitle)
                                        }
                                    }
                                )
                            }

                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 2.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "📺 LIVE TV CHANNELS",
                                        color = Color.White,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                    Text(
                                        text = "${filteredChannels.size} Channels",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        // 2. Regular channels
                        items(filteredChannels, key = { it.id + it.name }) { channel ->
                            LiveChannelCard(
                                channel = channel,
                                onClick = {
                                    val servers = channel.activeServers
                                    if (servers.size > 1) {
                                        // Multiple servers: let user choose server
                                        selectedChannelForServers = channel
                                    } else if (servers.isNotEmpty()) {
                                        onPlayStream(servers[0].url, channel.name)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (pullRefreshState.isRefreshing || pullRefreshState.verticalOffset > 0.5f) {
                PullToRefreshContainer(
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = Color(0xFF0F172A),
                    contentColor = AccentEmerald
                )
            }
        }
    }
}

@Composable
fun WorldSportsSection(
    matches: List<WorldSportMatch>,
    onMatchClick: (WorldSportMatch) -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "worldSportsPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    ) {
        // Section Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pulsing Red Live Dot
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(Color(0xFFFF2A54).copy(alpha = pulseAlpha), CircleShape)
                )
                Text(
                    text = "LIVE WORLD SPORTS",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFF2A54).copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, Color(0xFFFF2A54).copy(alpha = 0.40f))
                ) {
                    Text(
                        text = "${matches.size} LIVE",
                        color = Color(0xFFFF4B6E),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Swipe ▶",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Horizontal matches row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(matches, key = { it.matchId.ifBlank { it.displayTitle } }) { match ->
                WorldSportMatchCard(
                    match = match,
                    pulseAlpha = pulseAlpha,
                    onClick = { onMatchClick(match) }
                )
            }
        }
    }
}

@Composable
fun WorldSportMatchCard(
    match: WorldSportMatch,
    pulseAlpha: Float,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cardScale"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F1523),
        border = BorderStroke(
            1.2.dp,
            if (match.isLive) Color(0xFFFF2A54).copy(alpha = 0.40f) else Color.White.copy(alpha = 0.10f)
        ),
        modifier = Modifier
            .width(300.dp)
            .height(165.dp)
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Thumbnail with Dark Gradient Scrim
            if (match.thumbnail.isNotBlank()) {
                AsyncImage(
                    model = match.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.20f }
                )
            }

            // Dark gradient overlay to guarantee text legibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xCC090D18),
                                Color(0xEE0B101E),
                                Color(0xFA090D18)
                            )
                        )
                    )
            )

            // Content Column
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Meta Row: Live Status & League / Sport
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Live Status Pill
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (match.isLive) Color(0xFFFF2A54).copy(alpha = 0.20f) else Color(0xFFFFA000).copy(alpha = 0.20f),
                        border = BorderStroke(
                            0.8.dp,
                            if (match.isLive) Color(0xFFFF2A54).copy(alpha = pulseAlpha) else Color(0xFFFFA000).copy(alpha = 0.50f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .background(
                                        if (match.isLive) Color(0xFFFF2A54) else Color(0xFFFFA000),
                                        CircleShape
                                    )
                            )
                            Text(
                                text = if (match.isLive) "LIVE" else "UPCOMING",
                                color = if (match.isLive) Color(0xFFFF4B6E) else Color(0xFFFFB300),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    // Sport & League
                    val sportEmoji = when (match.sport.lowercase()) {
                        "football", "soccer" -> "⚽"
                        "cricket" -> "🏏"
                        "basketball" -> "🏀"
                        "tennis" -> "🎾"
                        else -> "🏆"
                    }
                    Text(
                        text = "$sportEmoji ${match.league.ifBlank { match.sport.uppercase() }}",
                        color = TextMuted,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(start = 8.dp)
                    )
                }

                // Middle: Team 1 vs Team 2 ("kar shathe k khelbe")
                if (match.team2.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Team 1 Pill
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = match.team1,
                                color = Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp)
                            )
                        }

                        // VS Badge
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Color(0xFFFFA000).copy(alpha = 0.20f), CircleShape)
                                .border(1.dp, Color(0xFFFFA000).copy(alpha = 0.40f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "VS",
                                color = Color(0xFFFFD54F),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        // Team 2 Pill
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = match.team2,
                                color = Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp)
                            )
                        }
                    }
                } else {
                    // Single Title
                    Text(
                        text = match.displayTitle,
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                // Bottom Meta Row: Tournament Stage & Watch Action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = match.extraInfo,
                            color = Color(0xFF7DFFB3),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!match.viewers.isNullOrBlank()) {
                            Text(
                                text = "👁 ${match.viewers} Watching",
                                color = TextMuted,
                                fontSize = 9.5.sp,
                                maxLines = 1
                            )
                        }
                    }

                    // Watch / Servers Button
                    val serverCount = match.activeServers.size
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFFF2A54),
                        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.8f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (serverCount > 1) "$serverCount Servers" else "Watch Live",
                                color = Color.White,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveChannelCard(
    channel: LiveTvChannel,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "liveCardScale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "livePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101626)),
        border = BorderStroke(
            1.dp,
            if (isPressed) Color(0xFFFF2A54) else Color.White.copy(alpha = 0.09f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xFF162038), Color(0xFF0C1322))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large & Clear Channel Logo Box (Separated from LIVE badge so it is never covered)
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .border(0.8.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                        .padding(6.dp),
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
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = channel.name,
                            tint = TextMuted,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Channel Name
                Text(
                    text = channel.name,
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Multi-server or Category badge
                val serverCount = channel.activeServers.size
                if (serverCount > 1) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = AccentEmerald.copy(alpha = 0.15f),
                        border = BorderStroke(0.5.dp, AccentEmerald.copy(alpha = 0.40f))
                    ) {
                        Text(
                            text = "⚡ $serverCount Servers",
                            color = AccentEmerald,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Text(
                        text = channel.category,
                        color = TextMuted,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Pulsating Red Live Badge cleanly pinned to Card top corner (Does NOT cover logo!)
            Surface(
                shape = RoundedCornerShape(bottomStart = 8.dp, topEnd = 14.dp),
                color = Color(0xDD12050A),
                border = BorderStroke(0.6.dp, Color(0xFFFF2A54).copy(alpha = 0.6f)),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                            .background(Color(0xFFFF2A54), CircleShape)
                    )
                    Text(
                        text = "LIVE",
                        color = Color(0xFFFF4B6E),
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}
