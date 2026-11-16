package com.streamhit.app.ui.account

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamhit.app.MainActivity
import com.streamhit.app.data.analytics.AppAnalyticsManager
import com.streamhit.app.ui.theme.*
import com.streamhit.app.util.DeviceUtils
import com.streamhit.app.util.ReferralManager

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

    var inputClaimCode by remember { mutableStateOf("") }
    var isSubmittingClaim by remember { mutableStateOf(false) }
    var claimError by remember { mutableStateOf("") }

    val handleClaimSubmit = {
        val trimmed = inputClaimCode.trim().uppercase()
        if (trimmed.length != 6) {
            claimError = "Referral code must be 6 characters."
        } else {
            isSubmittingClaim = true
            claimError = ""
            ReferralManager.claimReferral(context, trimmed) { success, msg ->
                isSubmittingClaim = false
                if (success) {
                    inputClaimCode = ""
                    Toast.makeText(context, "🎉 $msg", Toast.LENGTH_LONG).show()
                } else {
                    claimError = msg
                }
            }
        }
    }

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
            text = "Account & Community",
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
                        text = if (ReferralManager.isUserPremium()) "VIP Member • 100% Ad-Free Access" else "Standard Member • All Content Unlocked",
                        color = if (ReferralManager.isUserPremium()) Color(0xFFFFD700) else AccentEmerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ─── VIP Premium Status Banner ───
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BgSurfaceElevated,
            border = BorderStroke(
                1.2.dp,
                if (ReferralManager.isUserPremium()) {
                    Brush.linearGradient(listOf(Color(0xFFFFD700), Color(0xFF00F5D4)))
                } else {
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.05f)))
                }
            ),
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
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            if (ReferralManager.isUserPremium()) Color(0xFFFFD700).copy(alpha = 0.18f)
                            else Color.White.copy(alpha = 0.08f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (ReferralManager.isUserPremium()) "👑" else "🛡️",
                        fontSize = 22.sp
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (ReferralManager.isUserPremium()) "VIP Ad-Free Active" else "Ad-Free VIP Pass",
                        color = if (ReferralManager.isUserPremium()) Color(0xFFFFD700) else Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (ReferralManager.isUserPremium()) {
                            "${ReferralManager.premiumDaysLeft} Days Remaining • Zero Ads & Turbo VIP Downloads"
                        } else {
                            "Invite friends or enter a referral code to get 3 Days VIP"
                        },
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ─── Referral Program Card ───
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BgSurface,
            border = BorderStroke(1.dp, Color(0xFF00F5D4).copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF00F5D4).copy(alpha = 0.15f),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = "🎁", fontSize = 16.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Refer Friends & Earn VIP",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFFFD700).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color(0xFFFFD700).copy(alpha = 0.40f))
                    ) {
                        Text(
                            text = "+3 Days / Friend",
                            color = Color(0xFFFFD700),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Share your code with friends. When they use it, both of you get 3 Days of 100% Ad-Free VIP Premium (Stacks: 3+3+3 Days)!",
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Referral Code Display Capsule
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = BgCard,
                    border = BorderStroke(1.dp, Color(0xFFFFD700).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "YOUR REFERRAL CODE",
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = ReferralManager.myReferralCode.ifBlank { "LOADING" },
                                color = Color(0xFFFFD700),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 3.sp
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Copy Button
                            Button(
                                onClick = {
                                    val code = ReferralManager.myReferralCode
                                    if (code.isNotBlank()) {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("NXLY Referral Code", code)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Referral code copied!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.10f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            // Share Button
                            Button(
                                onClick = {
                                    val code = ReferralManager.myReferralCode
                                    val shareText = "🔥 Watch thousands of HD Movies, Series & Live Sports 100% Free on NXLY Stream!\n\nUse my Referral Code: $code to get 3 Days Ad-Free VIP Premium instantly!\n\nDownload App: https://ads.nxly.online"
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Referral Code"))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F5D4)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "👥 Total Friends Invited: ${ReferralManager.totalReferrals}",
                        color = AccentEmerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Reward: 3 Days VIP Each",
                        color = TextMuted,
                        fontSize = 11.5.sp
                    )
                }

                // If user has not claimed a referral code yet, allow them to claim right here
                if (!ReferralManager.hasClaimed) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Have a Friend's Referral Code?",
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputClaimCode,
                            onValueChange = {
                                if (it.length <= 6) {
                                    inputClaimCode = it.uppercase()
                                    claimError = ""
                                }
                            },
                            placeholder = { Text("Enter 6-char code", color = TextMuted, fontSize = 12.sp) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                color = Color(0xFFFFD700),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFFD700),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedContainerColor = BgCard,
                                unfocusedContainerColor = BgCard
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { handleClaimSubmit() }),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = { handleClaimSubmit() },
                            enabled = !isSubmittingClaim && inputClaimCode.trim().length == 6,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD700),
                                disabledContainerColor = Color(0xFFFFD700).copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            if (isSubmittingClaim) {
                                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            } else {
                                Text("Submit", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (claimError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = claimError, color = Color(0xFFFF5252), fontSize = 11.5.sp)
                    }
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
                            text = "Share Feedback & Experience",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Request new movies or series, report playback issues, or chat with fellow cinema lovers. Our team is always here to listen!",
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ─── Community Link Cards ───
        Text(
            text = "Official Platforms",
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
            subtitle = "Direct support, movie requests & community chat",
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
            subtitle = "Reviews, discussion & movie suggestions",
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
            subtitle = "New releases, official updates & announcements",
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
                        text = "Share with Friends",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Share NXLY Stream app with your friends & family",
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
                        text = "Android TV / Google TV Preview",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Test TV remote navigation and TV Live Hub on mobile",
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
        Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
    }
}
