package com.streamhit.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.streamhit.app.ui.theme.*
import com.streamhit.app.util.AppNoticePopupManager

@Composable
fun AppNoticeDialog(
    title: String = AppNoticePopupManager.title,
    message: String = AppNoticePopupManager.message,
    imageUrl: String = AppNoticePopupManager.imageUrl,
    btn1Text: String = AppNoticePopupManager.btn1Text,
    btn1Url: String = AppNoticePopupManager.btn1Url,
    btn2Enabled: Boolean = AppNoticePopupManager.btn2Enabled,
    btn2Text: String = AppNoticePopupManager.btn2Text,
    btn2Url: String = AppNoticePopupManager.btn2Url,
    showCancel: Boolean = AppNoticePopupManager.showCancel,
    cancelText: String = AppNoticePopupManager.cancelText,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = {
            if (showCancel) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = showCancel,
            dismissOnClickOutside = showCancel
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = BgSurface,
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        AccentCyan.copy(alpha = 0.5f),
                        AccentBlue.copy(alpha = 0.3f),
                        Color.White.copy(alpha = 0.08f)
                    )
                )
            ),
            shadowElevation = 24.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Close button (if cancel allowed)
                if (showCancel) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Optional Banner Image
                if (imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Announcement Banner",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                } else {
                    // Glowing Icon header
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        AccentCyan.copy(alpha = 0.25f),
                                        AccentBlue.copy(alpha = 0.05f)
                                    )
                                )
                            )
                            .border(1.5.dp, AccentCyan.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = "Announcement",
                            tint = AccentCyan,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Title
                Text(
                    text = title.ifBlank { "গুরুত্বপূর্ণ নোটিশ" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Message Text
                if (message.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(weight = 1f, fill = false)
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = message,
                            fontSize = 13.5.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Action Buttons Column
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Button 1: Primary Action Button
                    Button(
                        onClick = {
                            if (btn1Url.isNotBlank()) {
                                AppNoticePopupManager.openExternalUrl(context, btn1Url)
                            }
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(AccentEmerald, AccentEmeraldDark)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = btn1Text.ifBlank { "যুক্ত হন" },
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (btn1Url.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Button 2: Secondary Action Button (if enabled)
                    if (btn2Enabled && btn2Text.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                if (btn2Url.isNotBlank()) {
                                    AppNoticePopupManager.openExternalUrl(context, btn2Url)
                                }
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White.copy(alpha = 0.04f)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = btn2Text,
                                    color = AccentCyan,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (btn2Url.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Cancel / Dismiss button
                    if (showCancel) {
                        TextButton(
                            onClick = { onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = cancelText.ifBlank { "বন্ধ করুন" },
                                color = TextMuted,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

