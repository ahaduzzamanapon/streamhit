package com.streamhit.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhit.app.data.model.BannerItem
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroBannerSlider(
    banners: List<BannerItem>,
    modifier: Modifier = Modifier,
    onBannerClick: (BannerItem) -> Unit
) {
    if (banners.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { banners.size })

    // Auto rotate every 5 seconds
    LaunchedEffect(banners) {
        while (true) {
            delay(5000)
            if (banners.isNotEmpty()) {
                val nextPage = (pagerState.currentPage + 1) % banners.size
                pagerState.animateScrollToPage(nextPage, animationSpec = tween(700))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = banners[page]
            val sub = item.subject
            val title = item.title.ifEmpty { sub?.title ?: "Trending" }
            val rating = sub?.imdbRatingValue ?: "7.5"
            val genres = sub?.genres ?: "Action"

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onBannerClick(item) }
            ) {
                // Background Poster
                AsyncImage(
                    model = item.image.ifEmpty { sub?.coverUrl },
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Dark Cinematic Gradient Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.5f),
                                    BgDark.copy(alpha = 0.95f),
                                    BgDark
                                ),
                                startY = 50f
                            )
                        )
                )

                // Banner Details & Play Action
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    // Genre badge
                    Box(
                        modifier = Modifier
                            .background(AccentEmerald.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                            .border(1.dp, AccentEmerald.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = genres.split(",").firstOrNull()?.trim() ?: "Trending",
                            color = AccentEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Title
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Meta: rating + Watch Now Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Rating",
                                tint = AccentGold,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = rating,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Watch Now Button
                        Button(
                            onClick = { onBannerClick(item) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentEmerald,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(50.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Watch Now",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }

        // Pager Dots Indicator
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(banners.size.coerceAtMost(8)) { index ->
                val isSelected = pagerState.currentPage == index
                val dotWidth by animateDpAsState(
                    targetValue = if (isSelected) 18.dp else 5.dp,
                    animationSpec = tween(350),
                    label = "dotWidth"
                )
                Box(
                    modifier = Modifier
                        .height(5.dp)
                        .width(dotWidth)
                        .clip(CircleShape)
                        .background(if (isSelected) AccentEmerald else Color.White.copy(alpha = 0.35f))
                )
            }
        }
    }
}
