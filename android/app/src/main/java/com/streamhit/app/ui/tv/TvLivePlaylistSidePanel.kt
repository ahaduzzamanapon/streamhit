package com.streamhit.app.ui.tv

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhit.app.data.model.LiveTvChannel
import kotlinx.coroutines.delay

@Composable
fun TvLivePlaylistSidePanel(
    isVisible: Boolean,
    allChannels: List<LiveTvChannel>,
    currentChannel: LiveTvChannel?,
    onChannelSelected: (LiveTvChannel) -> Unit,
    onClosePanel: () -> Unit,
    onUserInteracted: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        // Extract distinct categories and add "All TV" at the beginning
        val categories = remember(allChannels) {
            val cats = allChannels.map { it.category.trim() }.filter { it.isNotBlank() }.distinct()
            listOf("All TV") + cats
        }

        // Active category: defaults to the current channel's category
        var selectedCategory by remember(currentChannel) {
            val cat = currentChannel?.category?.trim() ?: "All TV"
            mutableStateOf(if (cat in categories) cat else "All TV")
        }

        // Filtered channels according to category
        val displayedChannels = remember(allChannels, selectedCategory) {
            if (selectedCategory == "All TV") {
                allChannels
            } else {
                allChannels.filter { it.category.equals(selectedCategory, ignoreCase = true) }
            }
        }

        val categoryListState = rememberLazyListState()
        val channelListState = rememberLazyListState()
        val initialFocusRequester = remember { FocusRequester() }
        val channelListFocusRequester = remember { FocusRequester() }

        // Auto-request focus when drawer opens so remote control responds instantly!
        LaunchedEffect(isVisible) {
            if (isVisible) {
                delay(120L)
                try {
                    initialFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
        }

        // Auto-scroll to current playing channel when panel opens or category changes
        LaunchedEffect(isVisible, selectedCategory) {
            if (isVisible && currentChannel != null) {
                val idx = displayedChannels.indexOfFirst { it.name.equals(currentChannel.name, ignoreCase = true) }
                if (idx >= 0) {
                    channelListState.scrollToItem((idx - 2).coerceAtLeast(0))
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(540.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFA050811),
                            Color(0xF5080E1C),
                            Color(0xEB0A1326)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF10B981).copy(alpha = 0.5f), Color.Transparent)
                    ),
                    shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                )
                .padding(top = 16.dp, bottom = 14.dp, start = 14.dp, end = 14.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Panel Header ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF10B981)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LiveTv,
                                contentDescription = null,
                                tint = Color(0xFF022C22),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Live TV Channel Guide",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onClosePanel() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── TWO-PANE SPLIT DRAWER: LEFT = CATEGORIES, RIGHT = CHANNELS ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // ── LEFT PANE: CATEGORY LIST (Width: 175dp) ──
                    Column(
                        modifier = Modifier
                            .width(175.dp)
                            .fillMaxHeight()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Category,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Categories (${categories.size})",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        LazyColumn(
                            state = categoryListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(categories, key = { _, cat -> cat }) { catIdx, cat ->
                                val isSelected = cat == selectedCategory
                                val interactionSource = remember { MutableInteractionSource() }
                                val isFocused by interactionSource.collectIsFocusedAsState()

                                // Auto-update selected category when user moves focus over it with TV remote
                                LaunchedEffect(isFocused) {
                                    if (isFocused) {
                                        selectedCategory = cat
                                        onUserInteracted()
                                    }
                                }

                                val catBg = when {
                                    isFocused -> Color(0xFF10B981)
                                    isSelected -> Color(0xFF10B981).copy(alpha = 0.25f)
                                    else -> Color.White.copy(alpha = 0.05f)
                                }

                                val catTextColor = when {
                                    isFocused -> Color(0xFF022C22)
                                    isSelected -> Color(0xFF10B981)
                                    else -> Color.White.copy(alpha = 0.9f)
                                }

                                val count = if (cat == "All TV") {
                                    allChannels.size
                                } else {
                                    allChannels.count { it.category.equals(cat, ignoreCase = true) }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (catIdx == 0) Modifier.focusRequester(initialFocusRequester) else Modifier)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(catBg)
                                        .border(
                                            width = if (isFocused) 2.dp else if (isSelected) 1.dp else 0.dp,
                                            color = if (isFocused) Color.White else if (isSelected) Color(0xFF10B981) else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable(interactionSource = interactionSource, indication = null) {
                                            selectedCategory = cat
                                            onUserInteracted()
                                            try {
                                                channelListFocusRequester.requestFocus()
                                            } catch (_: Exception) {}
                                        }
                                        .focusable(interactionSource = interactionSource)
                                        .padding(horizontal = 10.dp, vertical = 9.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = cat,
                                            fontSize = 12.sp,
                                            fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = catTextColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = count.toString(),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isFocused) Color(0xFF022C22).copy(alpha = 0.8f) else Color(0xFF64748B)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // ── Vertical Divider ──
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.08f))
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    // ── RIGHT PANE: CHANNEL LIST (Width: remaining ~330dp) ──
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedCategory,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${displayedChannels.size} Channels",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B),
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            state = channelListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(displayedChannels, key = { _, ch -> ch.name + ch.id }) { index, ch ->
                                val isPlaying = currentChannel?.name.equals(ch.name, ignoreCase = true)
                                val interactionSource = remember { MutableInteractionSource() }
                                val isItemFocused by interactionSource.collectIsFocusedAsState()

                                val scale by animateFloatAsState(
                                    targetValue = if (isItemFocused) 1.02f else 1.0f,
                                    label = "chScale"
                                )

                                val itemBg = when {
                                    isPlaying -> Brush.horizontalGradient(listOf(Color(0xFF064E3B), Color(0xFF0F1E2A)))
                                    isItemFocused -> Brush.horizontalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
                                    else -> Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.04f), Color.Transparent))
                                }

                                val itemBorderColor = when {
                                    isItemFocused -> Color(0xFF10B981)
                                    isPlaying -> Color(0xFF10B981).copy(alpha = 0.6f)
                                    else -> Color.White.copy(alpha = 0.05f)
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (index == 0) Modifier.focusRequester(channelListFocusRequester) else Modifier)
                                        .scale(scale)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(itemBg)
                                        .border(
                                            width = if (isItemFocused) 2.dp else 1.dp,
                                            color = itemBorderColor,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable(interactionSource = interactionSource, indication = null) {
                                            onUserInteracted()
                                            onChannelSelected(ch)
                                        }
                                        .focusable(interactionSource = interactionSource)
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Channel index number
                                        Text(
                                            text = String.format("%02d", index + 1),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPlaying) Color(0xFF10B981) else Color(0xFF64748B),
                                            modifier = Modifier.width(22.dp)
                                        )

                                        // Channel Logo
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.Black.copy(alpha = 0.5f))
                                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(6.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (ch.logo.isNotBlank()) {
                                                AsyncImage(
                                                    model = ch.logo,
                                                    contentDescription = ch.name,
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Tv,
                                                    contentDescription = null,
                                                    tint = Color(0xFF10B981),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        // Channel Name & Category
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = ch.name,
                                                fontSize = 13.sp,
                                                fontWeight = if (isPlaying || isItemFocused) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isPlaying) Color(0xFF10B981) else Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = ch.category.ifBlank { "General" },
                                                fontSize = 10.5.sp,
                                                color = Color(0xFF94A3B8),
                                                maxLines = 1
                                            )
                                        }

                                        // Now Playing Live Badge
                                        if (isPlaying) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                                    .border(1.dp, Color(0xFF10B981), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(5.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF10B981))
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "PLAYING",
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.Black,
                                                    color = Color(0xFF10B981)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Hint for Google TV remote
                Text(
                    text = "💡 Remote: Press OK to Play | ◀ Left for Categories | ▶ Right for Channels | Back to Close",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
