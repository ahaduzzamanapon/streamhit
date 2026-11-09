package com.streamhit.app.ui.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhit.app.MainActivity
import com.streamhit.app.data.analytics.AppAnalyticsManager
import com.streamhit.app.ui.theme.*
import com.streamhit.app.util.DeviceUtils

private const val TELEGRAM_GROUP_URL = "https://t.me/+c_X9PS6rX2ViNzk9"
private const val FACEBOOK_GROUP_URL = "https://www.facebook.com/share/g/1EDZZTxryj/"
private const val FACEBOOK_PAGE_URL = "https://www.facebook.com/share/1Dvwj5sTWw/"

@Composable
fun AccountScreen(
    onShareApp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .verticalScroll(scrollState)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = 76.dp), // Extra space for floating bottom bar
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ─── Header ───
        Text(
            text = "অ্যাকাউন্ট ও কমিউনিটি",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            textAlign = TextAlign.Start
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ─── User Profile Capsule ───
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BgSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(AccentEmerald, AccentBlue)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "User Avatar",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NXLY Stream User",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "Verified",
                            tint = AccentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "VIP Free Access • All Content Unlocked",
                        color = AccentEmerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ─── Community Feedback Banner ───
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BgSurfaceElevated,
            border = BorderStroke(
                1.2.dp,
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFF00C6FF).copy(alpha = 0.4f),
                        Color(0xFF0072FF).copy(alpha = 0.4f)
                    )
                )
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AccentEmerald.copy(alpha = 0.15f),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = "💬", fontSize = 18.sp)
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Join Our Community",
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "ফিডব্যাক এবং অভিজ্ঞতা শেয়ার করুন",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "অ্যাপ ব্যবহারের অভিজ্ঞতা, নতুন মুভি বা নাটকের রিকোয়েস্ট, অথবা কোনো সমস্যা হলে আমাদের গ্রুপ ও পেজে যুক্ত হয়ে জানান। আমরা সবসময় আপনাদের কথা শুনি!",
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ─── Community Link Cards ───
        Text(
            text = "আমাদের অফিসিয়াল প্ল্যাটফর্ম",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            textAlign = TextAlign.Start
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 1. Telegram Group
        CommunityLinkCard(
            title = "Telegram Group",
            subtitle = "সরাসরি সাপোর্ট, মুভি রিকোয়েস্ট ও আড্ডা",
            badgeText = "Join Group",
            icon = Icons.AutoMirrored.Filled.Send,
            iconTint = Color(0xFF229ED9),
            iconBgColor = Color(0xFF229ED9).copy(alpha = 0.15f),
            onClick = {
                openCustomUrl(context, TELEGRAM_GROUP_URL)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Facebook Group
        CommunityLinkCard(
            title = "Facebook Group",
            subtitle = "রিভিউ, আড্ডা ও অভিজ্ঞতা শেয়ার করুন",
            badgeText = "Join Group",
            icon = Icons.Default.Groups,
            iconTint = Color(0xFF1877F2),
            iconBgColor = Color(0xFF1877F2).copy(alpha = 0.15f),
            onClick = {
                openCustomUrl(context, FACEBOOK_GROUP_URL)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Facebook Page
        CommunityLinkCard(
            title = "Facebook Page",
            subtitle = "নতুন রিলিজ, আপডেট ও অফিসিয়াল নোটিশ",
            badgeText = "Follow Page",
            icon = Icons.Default.ThumbUp,
            iconTint = Color(0xFF1877F2),
            iconBgColor = Color(0xFF1877F2).copy(alpha = 0.15f),
            onClick = {
                openCustomUrl(context, FACEBOOK_PAGE_URL)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ─── Share App Card ───
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = BgSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onShareApp() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(AccentEmerald.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "বন্ধুদের সাথে শেয়ার করুন",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "বন্ধুদের সাথে NXLY Stream অ্যাপ শেয়ার করে সাপোর্ট করুন",
                        color = TextMuted,
                        fontSize = 11.5.sp
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Go",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ─── TV Mode Preview Card (Developer / Testing Switch) ───
        var isTvPreviewActive by remember { mutableStateOf(DeviceUtils.isForcedTvMode(context)) }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = BgSurface,
            border = BorderStroke(1.dp, Color(0xFF00F0FF).copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00F0FF).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = "TV Mode",
                        tint = Color(0xFF00F0FF),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Android TV / Google TV প্রিভিউ",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "মোবাইলে TV ইন্টারফেস ও লাইভ টিভি হাব টেস্ট করুন",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                Switch(
                    checked = isTvPreviewActive,
                    onCheckedChange = { checked ->
                        isTvPreviewActive = checked
                        DeviceUtils.setForceTvMode(context, checked)
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        context.startActivity(intent)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00F0FF),
                        checkedTrackColor = Color(0xFF00F0FF).copy(alpha = 0.35f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ─── App Version Footer ───
        Text(
            text = "NXLY Stream • Version ${AppAnalyticsManager.getAppVersion()}",
            color = TextMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "High Quality Streaming & Cinema Experience",
            color = TextMuted.copy(alpha = 0.6f),
            fontSize = 10.5.sp
        )
    }
}

@Composable
private fun CommunityLinkCard(
    title: String,
    subtitle: String,
    badgeText: String,
    icon: ImageVector,
    iconTint: Color,
    iconBgColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = BgCard,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Glowing Action Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = iconTint.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, iconTint.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = badgeText,
                        color = iconTint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open",
                        tint = iconTint,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

private fun openCustomUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "লিংক ওপেন করা সম্ভব হয়নি", Toast.LENGTH_SHORT).show()
    }
}

