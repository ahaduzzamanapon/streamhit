package com.streamhit.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamhit.app.data.model.SubjectSummary
import com.streamhit.app.ui.theme.*

@Composable
fun MediaCard(
    item: SubjectSummary,
    modifier: Modifier = Modifier.width(118.dp),
    rankNumber: Int? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "mediaCardScale"
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        // Poster Box with Aspect Ratio
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 2.9f)
                .clip(RoundedCornerShape(12.dp))
                .background(BgCard)
                .border(0.8.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(12.dp))
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Subtle dark bottom gradient for text & rating contrast
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.80f)
                            )
                        )
                    )
            )

            // Top Badges (Language / Dub / Quality)
            val titleLower = item.title.lowercase()
            val genresLower = item.genres.lowercase()
            val isBengali = titleLower.contains("[bengali]") || titleLower.contains("(bengali)") || titleLower.contains("bengali") || titleLower.contains("bangla")
            val isHindi = titleLower.contains("[hindi]") || titleLower.contains("(hindi)") || titleLower.contains("hindi")
            val isAnime = genresLower.contains("anime") || genresLower.contains("animation") || titleLower.contains("anime")
            val isCam = (item.isCam == true) || titleLower.contains("[cam]") || titleLower.contains("(cam)")

            when {
                isBengali -> {
                    Box(
                        modifier = Modifier
                            .padding(5.dp)
                            .align(Alignment.TopStart)
                            .background(
                                Brush.horizontalGradient(listOf(Color(0xFF00C853), Color(0xFF00E676))),
                                RoundedCornerShape(5.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "🇧🇩 Bangla",
                            color = Color.Black,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                isHindi -> {
                    Box(
                        modifier = Modifier
                            .padding(5.dp)
                            .align(Alignment.TopStart)
                            .background(
                                Brush.horizontalGradient(listOf(Color(0xFFFF9100), Color(0xFFFFD600))),
                                RoundedCornerShape(5.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "🇮🇳 Hindi",
                            color = Color.Black,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                isAnime -> {
                    Box(
                        modifier = Modifier
                            .padding(5.dp)
                            .align(Alignment.TopStart)
                            .background(
                                Brush.horizontalGradient(listOf(Color(0xFF8E24AA), Color(0xFFD81B60))),
                                RoundedCornerShape(5.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "⚡ Anime",
                            color = Color.White,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                isCam -> {
                    Box(
                        modifier = Modifier
                            .padding(5.dp)
                            .align(Alignment.TopStart)
                            .background(Color(0xFFE53935), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "CAM",
                            color = Color.White,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                else -> {
                    // Subtle HD badge
                    Box(
                        modifier = Modifier
                            .padding(5.dp)
                            .align(Alignment.TopStart)
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "HD",
                            color = AccentCyan,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Rating Badge bottom right (Frosted Glass)
            Box(
                modifier = Modifier
                    .padding(5.dp)
                    .align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(6.dp))
                    .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Rating",
                        tint = AccentGold,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = item.imdbRatingValue ?: "7.5",
                        color = Color.White,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Rank Number overlay (1, 2, 3...)
            if (rankNumber != null) {
                Text(
                    text = "$rankNumber",
                    color = Color.White.copy(alpha = 0.95f),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = 5.dp, y = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        // Cleaned Title (removes redundant bracket tags for a cleaner look)
        val cleanTitle = item.title
            .replace(Regex("\\[(bengali|hindi|cam|bangla|dual audio|multi|english|dubbed|720p|1080p|480p).*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\((bengali|hindi|cam|bangla|dual audio|multi|english|dubbed|720p|1080p|480p).*?\\)", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { item.title }

        Text(
            text = cleanTitle,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Meta info (Year & Type)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.releaseYear,
                color = TextMuted,
                fontSize = 10.sp
            )
            Text(
                text = if (item.subjectType == 2) "Series" else "Movie",
                color = AccentCyan.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
