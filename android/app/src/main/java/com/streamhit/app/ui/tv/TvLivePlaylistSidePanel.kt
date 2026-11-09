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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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

        val channelListState = rememberLazyListState()

        // Auto-scroll to current playing channel when panel opens
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
                .width(360.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFA060A13),
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
                .padding(top = 18.dp, bottom = 18.dp, start = 16.dp, end = 16.dp)
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
                            text = "লাইভ টিভি চ্যানেল",
                            fontSize = 17.sp,
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

                Spacer(modifier = Modifier.height(14.dp))

                // ── Category Row (Horizontal Filter) ──
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories) { cat ->
                        val isCatSelected = cat == selectedCategory
                        val interactionSource = remember { MutableInteractionSource() }
                        val isCatFocused by interactionSource.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    when {
                                        isCatSelected -> Color(0xFF10B981)
                                        isCatFocused -> Color.White.copy(alpha = 0.22f)
                                        else -> Color.White.copy(alpha = 0.07f)
                                    }
                                )
                                .border(
                                    width = if (isCatFocused) 2.dp else 1.dp,
                                    color = if (isCatFocused) Color(0xFF10B981) else Color.Transparent,
                                    shape = RoundedCornerShape(999.dp)
                                )
                                .clickable(interactionSource = interactionSource, indication = null) {
                                    selectedCategory = cat
                                    onUserInteracted()
                                }
                                .focusable(interactionSource = interactionSource)
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cat,
                                fontSize = 12.sp,
                                fontWeight = if (isCatSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isCatSelected) Color(0xFF022C22) else Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ── Channel List ──
                Text(
                    text = "${displayedChannels.size}টি চ্যানেল উপলব্ধ",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    state = channelListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(displayedChannels, key = { _, ch -> ch.name + ch.id }) { index, ch ->
                        val isPlaying = currentChannel?.name.equals(ch.name, ignoreCase = true)
                        val interactionSource = remember { MutableInteractionSource() }
                        val isItemFocused by interactionSource.collectIsFocusedAsState()

                        val scale by animateFloatAsState(
                            targetValue = if (isItemFocused) 1.03f else 1.0f,
                            label = "chScale"
                        )

                        val itemBg = when {
                            isPlaying -> Brush.horizontalGradient(listOf(Color(0xFF064E3B), Color(0xFF0F1E2A)))
                            isItemFocused -> Brush.horizontalGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
                            else -> Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.04f), Color.Transparent))
                        }

                        val itemBorderColor = when {
                            isItemFocused -> Color(0xFF10B981)
                            isPlaying -> Color(0xFF10B981).copy(alpha = 0.5f)
                            else -> Color.White.copy(alpha = 0.05f)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .scale(scale)
                                .clip(RoundedCornerShape(12.dp))
                                .background(itemBg)
                                .border(
                                    width = if (isItemFocused) 2.dp else 1.dp,
                                    color = itemBorderColor,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(interactionSource = interactionSource, indication = null) {
                                    onUserInteracted()
                                    onChannelSelected(ch)
                                }
                                .focusable(interactionSource = interactionSource)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
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
                                    modifier = Modifier.width(24.dp)
                                )

                                // Channel Logo
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (ch.logo.isNotBlank()) {
                                        AsyncImage(
                                            model = ch.logo,
                                            contentDescription = ch.name,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Tv,
                                            contentDescription = null,
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Channel Name & Category
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ch.name,
                                        fontSize = 14.sp,
                                        fontWeight = if (isPlaying || isItemFocused) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isPlaying) Color(0xFF10B981) else Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = ch.category.ifBlank { "General" },
                                        fontSize = 11.sp,
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
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF10B981))
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "PLAYING",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color(0xFF10B981)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Hint
                Text(
                    text = "💡 ৬ সেকেন্ডে নিজে বন্ধ হবে | রিমোটের OK চাপুন প্লে করতে",
                    fontSize = 10.5.sp,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

