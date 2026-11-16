package com.streamhit.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamhit.app.ui.theme.BgCard
import com.streamhit.app.ui.theme.TextMuted
import com.streamhit.app.ui.theme.TextSecondary
import com.streamhit.app.util.SponsorAdManager
import kotlinx.coroutines.delay

@Composable
fun SponsorSupportDialog(
    videoTitle: String = "",
    cancelButtonText: String? = null,
    onSupportClick: () -> Unit,
    onCancel: () -> Unit
) {
    // If user is VIP Premium or Ads are disabled by Master Switch, bypass immediately!
    if (com.streamhit.app.util.ReferralManager.isUserPremium() || !SponsorAdManager.isEnabled) {
        LaunchedEffect(Unit) {
            onCancel()
        }
        return
    }

    // 3-second automatic redirect countdown (no click required)
    var countdown by remember { mutableIntStateOf(3) }
    var hasTriggered by remember { mutableStateOf(false) }

    val triggerSupport = {
        if (!hasTriggered) {
            hasTriggered = true
            onSupportClick()
        }
    }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000L)
            countdown -= 1
        }
        triggerSupport()
    }

    // Subtle heart pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "heartPulse")
    val heartScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartScale"
    )

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = BgCard,
            border = BorderStroke(1.2.dp, Brush.linearGradient(listOf(Color(0xFFFF2A54), Color(0xFF00F5D4)))),
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Glowing animated pulse heart badge
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .scale(heartScale)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFFFF2A54), Color(0xFFFF7A00))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Support Heart",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Title
                Text(
                    text = SponsorAdManager.dialogTitle,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Optional Video Title Pill
                if (videoTitle.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "▶ $videoTitle",
                            color = Color(0xFF00F5D4),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Subtitle / Message
                Text(
                    text = SponsorAdManager.dialogMessage,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Auto-countdown linear progress
                LinearProgressIndicator(
                    progress = { ((3 - countdown) / 3f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF00F5D4),
                    trackColor = Color.White.copy(alpha = 0.12f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Primary Mandatory Action Button: auto-navigates in 3 seconds or on immediate click
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFFFF2A54), Color(0xFFFF5252), Color(0xFFFF7A00))
                            )
                        )
                        .clickable { triggerSupport() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open Link",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (countdown > 0) "🚀 Opening Sponsor (${countdown}s)..." else "🚀 Opening...",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
