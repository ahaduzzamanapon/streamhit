package com.streamhit.app.ui.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhit.app.R
import com.streamhit.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit
) {
    // Progress animation from 0% to 100%
    var startAnimation by remember { mutableStateOf(false) }
    val progressAnimation by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(
            durationMillis = 2000,
            easing = FastOutSlowInEasing
        ),
        label = "splashProgress"
    )

    // Pulsing logo glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "splashGlow")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoPulse"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        // Keep splash on screen for ~2.3 seconds
        delay(2300)
        onSplashFinished()
    }

    val statusText = when {
        progressAnimation < 0.35f -> "Initializing NXLY Engine..."
        progressAnimation < 0.75f -> "Loading Latest Streams & Channels..."
        progressAnimation < 0.98f -> "Connecting to High-Speed CDN..."
        else -> "Ready to Stream!"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark),
        contentAlignment = Alignment.Center
    ) {
        // Ambient background glow behind logo
        Box(
            modifier = Modifier
                .size(280.dp)
                .scale(pulseScale)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentCyan.copy(alpha = glowAlpha * 0.35f),
                            AccentBlue.copy(alpha = glowAlpha * 0.15f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            // App Logo with glowing ring
            Box(
                modifier = Modifier
                    .size(118.dp)
                    .scale(pulseScale),
                contentAlignment = Alignment.Center
            ) {
                // Outer glowing neon border ring
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            width = 2.5.dp,
                            brush = Brush.sweepGradient(
                                listOf(
                                    AccentCyan,
                                    AccentBlue,
                                    AccentPink,
                                    AccentCyan
                                )
                            ),
                            shape = RoundedCornerShape(28.dp)
                        )
                )

                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "NXLY Stream Logo",
                    modifier = Modifier
                        .size(106.dp)
                        .clip(RoundedCornerShape(24.dp))
                )
            }

            Spacer(modifier = Modifier.height(26.dp))

            // App Brand Name with sleek gradient
            Text(
                text = "NXLY STREAM",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.5.sp,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Tagline
            Text(
                text = "Movies, Series & Live TV",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                color = AccentCyan.copy(alpha = 0.85f)
            )

            Spacer(modifier = Modifier.height(42.dp))

            // ── ANIMATED PROGRESS BAR WITH GLOW EFFECT ───────────────────────
            Box(
                modifier = Modifier.width(230.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                // Glow Aura underneath the progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progressAnimation.coerceAtLeast(0.05f))
                        .height(18.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    AccentCyan.copy(alpha = 0.45f),
                                    AccentBlue.copy(alpha = 0.35f),
                                    Color(0xFF7928CA).copy(alpha = 0.25f)
                                )
                            ),
                            shape = RoundedCornerShape(9.dp)
                        )
                )

                // Progress Bar Background Track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.5.dp)
                        .background(
                            Color.White.copy(alpha = 0.08f),
                            shape = CircleShape
                        )
                        .border(
                            0.5.dp,
                            Color.White.copy(alpha = 0.12f),
                            shape = CircleShape
                        )
                )

                // Active Animated Gradient Fill
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = progressAnimation)
                        .height(6.5.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF00F5D4),
                                    AccentCyan,
                                    AccentBlue,
                                    Color(0xFF7928CA)
                                )
                            ),
                            shape = CircleShape
                        )
                )

                // Glowing Leading Tip Knob
                if (progressAnimation > 0.03f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progressAnimation),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .shadow(8.dp, CircleShape, spotColor = AccentCyan)
                                .background(Color.White, CircleShape)
                                .border(1.5.dp, AccentCyan, CircleShape)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Dynamic loading status indicator text
            Text(
                text = statusText,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
        }

        // Bottom footer version tag
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Ultra-Fast Streaming Engine",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.25f),
                letterSpacing = 1.2.sp
            )
        }
    }
}

