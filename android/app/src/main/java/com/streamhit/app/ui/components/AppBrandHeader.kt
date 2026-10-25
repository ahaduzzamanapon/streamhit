package com.streamhit.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhit.app.R
import com.streamhit.app.ui.theme.AccentCyan
import com.streamhit.app.ui.theme.AccentEmerald
import com.streamhit.app.ui.theme.TextMuted

/**
 * Minimal, ultra-modern luxury app header without bloated badges.
 */
@Composable
fun AppBrandHeader(
    title: String? = null,
    subtitle: String? = null,
    showBackButton: Boolean = false,
    onBackClick: () -> Unit = {},
    showSearchAction: Boolean = false,
    onSearchClick: () -> Unit = {},
    showShareAction: Boolean = true,
    tagText: String? = null,
    tagColor: Color = AccentEmerald,
    modifier: Modifier = Modifier
) {
    var showShareModal by remember { mutableStateOf(false) }

    Surface(
        color = Color(0xFF070B14).copy(alpha = 0.98f),
        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.05f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Back button or Logo + Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (showBackButton) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        modifier = Modifier
                            .size(30.dp)
                            .clickable(onClick = onBackClick)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Official NXLY Stream Logo - Sleek & Minimal
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color.White)
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.linearGradient(listOf(AccentEmerald, AccentCyan))
                            ),
                            RoundedCornerShape(7.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "NXLY Stream Logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Brand Name & Subtitle (WITHOUT FREE HD / LIVE TV badge)
                Column(
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (title.isNullOrBlank()) {
                            Text(
                                text = "NXLY",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "STREAM",
                                color = AccentEmerald,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp
                            )
                        } else {
                            Text(
                                text = title,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            color = TextMuted,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            // Right Actions: Search & Share - Sleek minimal buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (showSearchAction) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .size(30.dp)
                            .clickable(onClick = onSearchClick)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                if (showShareAction) {
                    Surface(
                        shape = CircleShape,
                        color = AccentEmerald.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.40f)),
                        modifier = Modifier
                            .size(30.dp)
                            .clickable { showShareModal = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = AccentEmerald,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showShareModal) {
        AppShareDialog(onDismiss = { showShareModal = false })
    }
}
