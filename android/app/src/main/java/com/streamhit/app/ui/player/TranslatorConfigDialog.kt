package com.streamhit.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhit.app.ui.theme.*
import com.streamhit.app.util.SubtitleTtsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslatorConfigDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedLang by remember { mutableStateOf(SubtitleTtsManager.targetLanguage) }
    var isEnabledLocal by remember { mutableStateOf(SubtitleTtsManager.isEnabled) }
    var selectedRate by remember { mutableFloatStateOf(SubtitleTtsManager.speechRate) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = null
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(AccentEmerald.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = "AI Dubbing",
                            tint = AccentEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Real-Time Audio Translator",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = AccentEmerald.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "Google TTS",
                                    color = AccentEmerald,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Speaks dialogues in selected language with zero lag",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Enable Toggle Switch Row
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BgCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Enable Live AI Dubbing",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isEnabledLocal) "Active • Video audio ducks while voice speaks" else "Disabled",
                            color = if (isEnabledLocal) AccentEmerald else TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = isEnabledLocal,
                        onCheckedChange = { isEnabledLocal = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = AccentEmerald,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Translate From row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Translate From",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentGold, modifier = Modifier.size(13.dp))
                        Text("Auto Detect (Original Audio)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Translate To Grid
            Text(
                text = "Translate To (Select Voice Language)",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SubtitleTtsManager.SUPPORTED_LANGUAGES) { lang ->
                    val isSelected = selectedLang == lang.code
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) AccentEmerald.copy(alpha = 0.18f) else BgCard,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) AccentEmerald else Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedLang = lang.code }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = lang.flag, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = lang.nativeName,
                                color = if (isSelected) AccentEmerald else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = lang.name,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Speech Rate Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Voice Speed",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.95f to "0.95x", 1.05f to "1.05x (Best)", 1.15f to "1.15x").forEach { (r, label) ->
                        val isSelected = selectedRate == r
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) AccentEmerald else Color.White.copy(alpha = 0.08f),
                            modifier = Modifier.clickable { selectedRate = r }
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Pre-translation Status Box
            if (SubtitleTtsManager.totalCuesCount > 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = SubtitleTtsManager.statusMessage,
                            color = if (SubtitleTtsManager.isPretranslating) AccentGold else AccentEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${SubtitleTtsManager.pretranslatedCount}/${SubtitleTtsManager.totalCuesCount}",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    if (SubtitleTtsManager.isPretranslating) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val progress = if (SubtitleTtsManager.totalCuesCount > 0) {
                            SubtitleTtsManager.pretranslatedCount.toFloat() / SubtitleTtsManager.totalCuesCount.toFloat()
                        } else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = AccentGold,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Apply & Play Button
            Button(
                onClick = {
                    SubtitleTtsManager.setTargetLang(selectedLang)
                    SubtitleTtsManager.setRate(selectedRate)
                    SubtitleTtsManager.toggleEnabled(isEnabledLocal)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Apply & Play",
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isEnabledLocal) "Apply & Play with AI Voice" else "Save Settings",
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
