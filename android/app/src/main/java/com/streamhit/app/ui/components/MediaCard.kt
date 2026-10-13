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
                .aspectRatio(2f / 2.85f)
                .clip(RoundedCornerShape(10.dp))
                .background(BgCard)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
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
                                Color.Black.copy(alpha = 0.75f)
                            )
                        )
                    )
            )

            // Top Badges (Language / Dub)
            val titleLower = item.title.lowercase()
            val lang = when {
                titleLower.contains("[hindi]") -> "Hindi"
                titleLower.contains("[bengali]") -> "Bengali"
                else -> null
            }

            if (lang != null) {
                Box(
                    modifier = Modifier
                        .padding(5.dp)
                        .align(Alignment.TopStart)
                        .background(AccentEmerald.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = lang,
                        color = Color.Black,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Rating Badge bottom right (Frosted Glass)
            Box(
                modifier = Modifier
                    .padding(5.dp)
                    .align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Rating",
                        tint = AccentGold,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
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
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = 4.dp, y = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Title
        Text(
            text = item.title,
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
