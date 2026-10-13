package com.streamhit.app.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamhit.app.ui.theme.*
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun AudioSyncDialog(
    currentOffsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(AccentEmerald.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Audio Sync",
                                tint = AccentEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Audio Sync",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Adjust audio & video synchronization",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Current Sync Badge Display
                val statusText: String
                val statusColor: Color
                val badgeText: String

                when {
                    currentOffsetMs == 0L -> {
                        badgeText = "0 ms"
                        statusText = "Audio and video are in sync"
                        statusColor = AccentEmerald
                    }
                    currentOffsetMs > 0L -> {
                        badgeText = "+${currentOffsetMs} ms"
                        statusText = "Audio delayed • Sound was playing too early"
                        statusColor = AccentCyan
                    }
                    else -> {
                        badgeText = "${currentOffsetMs} ms"
                        statusText = "Audio advanced • Sound was lagging behind"
                        statusColor = AccentGold
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(statusColor.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = badgeText,
                            color = statusColor,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = statusText,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stepper Buttons Section Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Quick Adjustments",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Live during playback",
                        color = AccentEmerald.copy(alpha = 0.75f),
                        fontSize = 10.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Steppers Grid
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Fine Adjust (±50 ms)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AudioSyncStepButton(
                            label = "-50 ms",
                            subLabel = "Audio earlier",
                            modifier = Modifier.weight(1f),
                            onClick = { onOffsetChange(currentOffsetMs - 50L) }
                        )
                        AudioSyncStepButton(
                            label = "+50 ms",
                            subLabel = "Audio later",
                            modifier = Modifier.weight(1f),
                            onClick = { onOffsetChange(currentOffsetMs + 50L) }
                        )
                    }

                    // Medium Adjust (±250 ms)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AudioSyncStepButton(
                            label = "-250 ms",
                            subLabel = "-1/4 sec",
                            modifier = Modifier.weight(1f),
                            onClick = { onOffsetChange(currentOffsetMs - 250L) }
                        )
                        AudioSyncStepButton(
                            label = "+250 ms",
                            subLabel = "+1/4 sec",
                            modifier = Modifier.weight(1f),
                            onClick = { onOffsetChange(currentOffsetMs + 250L) }
                        )
                    }

                    // Coarse Adjust (±500 ms)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AudioSyncStepButton(
                            label = "-500 ms",
                            subLabel = "-1/2 sec",
                            modifier = Modifier.weight(1f),
                            onClick = { onOffsetChange(currentOffsetMs - 500L) }
                        )
                        AudioSyncStepButton(
                            label = "+500 ms",
                            subLabel = "+1/2 sec",
                            modifier = Modifier.weight(1f),
                            onClick = { onOffsetChange(currentOffsetMs + 500L) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Continuous Slider
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Fine Slider Control",
                            color = TextSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${currentOffsetMs} ms",
                            color = statusColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = currentOffsetMs.toFloat().coerceIn(-3000f, 3000f),
                        onValueChange = { newVal ->
                            // Snap to nearest 25ms step for crisp precision
                            val snapped = (newVal / 25f).roundToLong() * 25L
                            onOffsetChange(snapped)
                        },
                        valueRange = -3000f..3000f,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentEmerald,
                            activeTrackColor = AccentEmerald,
                            inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("-3.0s (Earlier)", color = TextMuted, fontSize = 9.5.sp)
                        Text("0s (Default)", color = TextMuted, fontSize = 9.5.sp)
                        Text("+3.0s (Later)", color = TextMuted, fontSize = 9.5.sp)
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Bottom Action Buttons: Reset & Done
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onReset,
                        enabled = currentOffsetMs != 0L,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (currentOffsetMs != 0L) AccentGold.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.1f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (currentOffsetMs != 0L) AccentGold else TextMuted
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Reset (0 ms)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Text(
                            text = "Done",
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioSyncStepButton(
    label: String,
    subLabel: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = BgDark,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier.height(42.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subLabel,
                color = TextMuted,
                fontSize = 9.sp
            )
        }
    }
}
