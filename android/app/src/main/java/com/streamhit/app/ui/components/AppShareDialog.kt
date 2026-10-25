package com.streamhit.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamhit.app.ui.theme.AccentEmerald
import com.streamhit.app.ui.theme.BgCard
import com.streamhit.app.ui.theme.TextMuted
import com.streamhit.app.ui.theme.TextSecondary

const val APP_SHARE_URL = "https://nxly.online/download"
const val APP_SHARE_TEXT = "🎬 Watch thousands of Movies, TV Shows & Live TV channels in HD for FREE on NXLY Stream!\n\nDownload the app now: https://nxly.online/download"

@Composable
fun AppShareDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = BgCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
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
                // Top Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Vibrant Share Icon
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(AccentEmerald, Color(0xFF00B0FF))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share App",
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = "Share NXLY Stream",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Description
                Text(
                    text = "Love streaming free movies, series & live TV? Share NXLY Stream with your friends and family so everyone can enjoy! 🍿",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Feature Highlights
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("✨ 100% Free", "⚡ Fast Streaming", "📺 Live TV").forEach { pill ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.06f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = pill,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Primary Share Button (Android Chooser - WhatsApp, Messenger, etc.)
                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "NXLY Stream - Free Movies & TV Shows")
                            putExtra(Intent.EXTRA_TEXT, APP_SHARE_TEXT)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share NXLY Stream via"))
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Share with Friends",
                        color = Color.Black,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Copy Link Button
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("NXLY Stream App Link", APP_SHARE_URL)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "App download link copied to clipboard!", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Copy Download Link",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Mandatory 20-minute watch lock dialog.
 * Has only ONE button: Share to Continue. Absolutely NO cancel button.
 * Dialog cannot be dismissed by back button or clicking outside.
 */
@Composable
fun WatchLockShareDialog(
    movieTitle: String,
    onShareSuccess: () -> Unit
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = { /* Cannot dismiss without sharing */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Color(0xFF0F1728),
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                Brush.linearGradient(listOf(Color(0xFF00C6FF), Color(0xFF0164FF)))
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Official NXLY Logo badge
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .border(
                            1.5.dp,
                            Brush.linearGradient(listOf(Color(0xFF00C6FF), Color(0xFF0164FF))),
                            RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = com.streamhit.app.R.drawable.app_logo),
                        contentDescription = "NXLY Stream Logo",
                        modifier = Modifier.size(54.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "মুভিটি কি ভালো লাগছে? 🍿",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "২০ মিনিট ফ্রি দেখা সম্পন্ন হয়েছে! বাকি অংশ সম্পূর্ণ ফ্রিতে দেখতে বন্ধুদের সাথে অ্যাপটি শেয়ার করুন।",
                    color = Color(0xFFB0C4DE),
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Highlights Pill Row
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF131B2E),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp, horizontal = 14.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("✨ ফুল HD", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("•", color = Color(0xFF00C6FF))
                        Text("⚡ বাফারিং ছাড়া", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("•", color = Color(0xFF00C6FF))
                        Text("🎁 ১০০% ফ্রি", color = Color(0xFF00C6FF), fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // SINGLE BUTTON - NO CANCEL BUTTON
                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "NXLY Stream - Free Movies & TV Shows")
                            val text = "🔥 $movieTitle এবং হাজারো HD মুভি ও সিরিজ সম্পূর্ণ ফ্রিতে দেখুন NXLY Stream অ্যাপে!\n\nএখনই ডাউনলোড করুন: $APP_SHARE_URL"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "বন্ধুদের সাথে শেয়ার করুন"))
                        onShareSuccess()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00C6FF)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "বন্ধুদের শেয়ার করে দেখা চালিয়ে যান 🚀",
                        color = Color.Black,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

/**
 * Prominent & responsive "Share This App With Friends" banner card below player & details.
 */
@Composable
fun ShareAppBannerCard(
    modifier: Modifier = Modifier,
    onShareClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF0D1424),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(listOf(Color(0xFF00C6FF).copy(alpha = 0.45f), Color(0xFF0164FF).copy(alpha = 0.20f)))
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onShareClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF00C6FF), Color(0xFF0164FF)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = Color.Black,
                        modifier = Modifier.size(19.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "বন্ধুদের সাথে অ্যাপটি শেয়ার করুন",
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "বন্ধুদের সাথে শেয়ার করে ফ্রি দেখা উপভোগ করুন 🍿",
                        color = Color(0xFF8FA3BF),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF00C6FF),
                modifier = Modifier.clickable(onClick = onShareClick)
            ) {
                Text(
                    text = "Share",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

