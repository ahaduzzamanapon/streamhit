package com.streamhit.app.ui.sponsor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.streamhit.app.ui.theme.BgDark
import com.streamhit.app.ui.theme.StreamfitTheme
import com.streamhit.app.util.SponsorAdManager
import kotlinx.coroutines.delay

class SponsorWebViewActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var lastBackPressTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUrl = intent.getStringExtra("EXTRA_TARGET_URL") ?: SponsorAdManager.sponsorUrl
        val isDownloadAd = intent.getBooleanExtra("EXTRA_IS_DOWNLOAD_AD", false)
        val duration = intent.getIntExtra("EXTRA_AD_DURATION", SponsorAdManager.adDurationSeconds).coerceAtLeast(3)

        setContent {
            StreamfitTheme {
                SponsorWebScreen(
                    targetUrl = targetUrl,
                    duration = duration,
                    onTimerFinished = {
                        if (!isDownloadAd) {
                            SponsorAdManager.recordAdUnlock()
                        }
                        SponsorAdManager.recordSponsorEvent(
                            this@SponsorWebViewActivity,
                            "click",
                            if (isDownloadAd) "${duration}s_download_ad_complete" else "${duration}s_unlock_complete"
                        )
                        setResult(Activity.RESULT_OK)
                        finish()
                    },
                    onBackRequested = {
                        handleEarlyBack()
                    },
                    onBindWebView = { wv ->
                        webView = wv
                    }
                )
            }
        }
    }

    private fun handleEarlyBack() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime < 2000L) {
            // User insists on exiting early: don't unlock session
            setResult(Activity.RESULT_CANCELED)
            finish()
        } else {
            lastBackPressTime = now
            Toast.makeText(this, "⚠️ আনলক হতে আর কিছুক্ষণ অপেক্ষা করুন। বের হতে চাইলে আবার ব্যাক চাপুন।", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        handleEarlyBack()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SponsorWebScreen(
    targetUrl: String,
    duration: Int = SponsorAdManager.adDurationSeconds,
    onTimerFinished: () -> Unit,
    onBackRequested: () -> Unit,
    onBindWebView: (WebView) -> Unit
) {
    // Dynamic countdown timer for automated ad center
    var secondsRemaining by remember { mutableIntStateOf(duration) }
    var isComplete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (secondsRemaining > 0) {
            delay(1000L)
            secondsRemaining--
        }
        isComplete = true
        delay(600L) // brief celebration before auto-return
        onTimerFinished()
    }

    // Glow pulse animation for timer
    val infiniteTransition = rememberInfiniteTransition(label = "timerPulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // Sleek floating cyber top bar
        Surface(
            color = Color(0xFF0F1728),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "💖",
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "NXLY Stream • অটোমেটেড অ্যাড সেন্টার",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isComplete) "সফলভাবে সম্পন্ন হয়েছে! চালিয়ে যাচ্ছি..." else "পেজটিতে ${duration} সেকেন্ড অপেক্ষা করুন",
                            color = if (isComplete) Color(0xFF00F5D4) else Color(0xFFA0AEC0),
                            fontSize = 11.sp
                        )
                    }
                }

                // Glowing countdown timer badge
                Box(
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (isComplete) Color(0xFF00F5D4) else Color(0xFFFF2A54).copy(alpha = glowAlpha),
                            RoundedCornerShape(20.dp)
                        )
                        .background(
                            if (isComplete) Color(0x2200F5D4) else Color(0x22FF2A54),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isComplete) "✅ সম্পন্ন!" else "⏳ ${secondsRemaining}s",
                        color = if (isComplete) Color(0xFF00F5D4) else Color(0xFFFF2A54),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Web view displaying the sponsor page
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    }
                    webViewClient = object : WebViewClient() {
                        private fun handleUrl(view: WebView?, url: String): Boolean {
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                return false // Let WebView load HTTP/HTTPS normally
                            }
                            try {
                                val intent = if (url.startsWith("intent://")) {
                                    Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                } else {
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                }
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                view?.context?.startActivity(intent)
                                return true
                            } catch (_: Exception) {
                                try {
                                    if (url.startsWith("intent://")) {
                                        val parsedIntent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                        val fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url")
                                        if (!fallbackUrl.isNullOrBlank()) {
                                            view?.loadUrl(fallbackUrl)
                                            return true
                                        }
                                    } else if (url.startsWith("market://details?id=")) {
                                        val pkgName = url.substringAfter("market://details?id=").substringBefore("&")
                                        view?.loadUrl("https://play.google.com/store/apps/details?id=$pkgName")
                                        return true
                                    }
                                } catch (_: Exception) {}
                            }
                            return true
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return false
                            return handleUrl(view, target)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            if (url.isNullOrBlank()) return false
                            return handleUrl(view, url)
                        }

                        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                            if (failingUrl?.startsWith("market://") == true || failingUrl?.startsWith("intent://") == true) {
                                return // Ignore unsupported scheme error pages
                            }
                            super.onReceivedError(view, errorCode, description, failingUrl)
                        }
                    }
                    webChromeClient = WebChromeClient()
                    onBindWebView(this)
                    loadUrl(targetUrl)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

