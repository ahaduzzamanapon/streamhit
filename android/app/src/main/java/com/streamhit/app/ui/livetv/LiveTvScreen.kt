package com.streamhit.app.ui.livetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
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
import coil.compose.AsyncImage
import com.streamhit.app.data.model.LiveTvChannel
import com.streamhit.app.data.model.StreamLinkItem
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
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedChannelForServers by remember { mutableStateOf<LiveTvChannel?>(null) }

    val pullRefreshState = rememberPullToRefreshState()

    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            val list = repository.getChannels(context, forceRefresh = true)
            channels = list
            pullRefreshState.endRefresh()
        }
    }

    // Fetch channels using 5h cache
    LaunchedEffect(Unit) {
        val list = repository.getChannels(context)
        channels = list
        isLoading = false
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
                                    onPlayStream(server.url, "${ch.name} (${server.label})")
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        AppBrandHeader(
            tagText = "LIVE TV",
            tagColor = Color(0xFFFF334B),
            showSearchAction = false,
            showShareAction = true
        )

        // Search & Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text("Search Live TV channels...", color = TextMuted, fontSize = 13.sp)
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Category Filter Pills
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(categories) { cat ->
                    val isSelected = selectedCategory == cat
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) AccentEmerald.copy(alpha = 0.2f) else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) AccentEmerald else Color.White.copy(alpha = 0.12f)
                        ),
                        modifier = Modifier.clickable { selectedCategory = cat }
                    ) {
                        Text(
                            text = cat,
                            color = if (isSelected) AccentEmerald else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
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

            PullToRefreshContainer(
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = BgCard,
                contentColor = AccentEmerald
            )
        }
    }
}

@Composable
fun LiveChannelCard(
    channel: LiveTvChannel,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Channel Logo Box
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                if (channel.logo.isNotBlank()) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = channel.name,
                        tint = TextMuted,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Live Dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(AccentEmerald, CircleShape)
                        .align(Alignment.TopEnd)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Channel Name
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Multi-server or HD badge
            val serverCount = channel.activeServers.size
            if (serverCount > 1) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = AccentEmerald.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "$serverCount Servers",
                        color = AccentEmerald,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
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
    }
}
