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

@Composable
fun AppBrandHeader(
    title: String? = null,
    subtitle: String? = null,
    showBackButton: Boolean = false,
    onBackClick: () -> Unit = {},
    showSearchAction: Boolean = false,
    onSearchClick: () -> Unit = {},
    showShareAction: Boolean = true,
    tagText: String = "FREE HD",
    tagColor: Color = AccentEmerald,
    modifier: Modifier = Modifier
) {
    var showShareModal by remember { mutableStateOf(false) }

    Surface(
        color = Color(0xFF080C16).copy(alpha = 0.98f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Back button or Logo + Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (showBackButton) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier
                            .size(34.dp)
                            .clickable(onClick = onBackClick)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Official NXLY Stream Logo
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White)
                        .border(
                            BorderStroke(
                                1.dp,
                                Brush.linearGradient(listOf(AccentEmerald, AccentCyan))
                            ),
                            RoundedCornerShape(9.dp)
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

                // Brand Name & Subtitle / Tag
                Column(
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (title.isNullOrBlank()) {
                            Text(
                                text = "NXLY",
                                color = Color.White,
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "STREAM",
                                color = AccentEmerald,
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp
                            )
                        } else {
                            Text(
                                text = title,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        // Pulsing Live / Free HD Tag Pill
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = tagColor.copy(alpha = 0.14f),
                            border = BorderStroke(0.8.dp, tagColor.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .background(tagColor, CircleShape)
                                )
                                Text(
                                    text = tagText,
                                    color = tagColor,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.4.sp
                                )
                            }
                        }
                    }

                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            // Right Actions: Search & Share
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
                            .size(34.dp)
                            .clickable(onClick = onSearchClick)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (showShareAction) {
                    Surface(
                        shape = CircleShape,
                        color = AccentEmerald.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .size(34.dp)
                            .clickable { showShareModal = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = AccentEmerald,
                                modifier = Modifier.size(16.dp)
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
