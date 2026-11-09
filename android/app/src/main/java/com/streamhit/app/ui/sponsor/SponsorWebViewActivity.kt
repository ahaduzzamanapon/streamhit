package com.streamhit.app.ui.sponsor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    private var hasClickedState = false
    private var isCompletedState = false
    private var remainingSecondsState = 30
    private var durationClickedState = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUrl = intent.getStringExtra("EXTRA_TARGET_URL") ?: SponsorAdManager.sponsorUrl
        val isDownloadAd = intent.getBooleanExtra("EXTRA_IS_DOWNLOAD_AD", false)
        val durationClicked = intent.getIntExtra("EXTRA_DURATION_CLICKED", SponsorAdManager.durationClickedSeconds).coerceAtLeast(3)
        val durationNoClick = intent.getIntExtra("EXTRA_DURATION_NO_CLICK", SponsorAdManager.durationNoClickSeconds).coerceAtLeast(5)

        durationClickedState = durationClicked
        remainingSecondsState = durationNoClick

        // Strict back press handler: back is blocked until click+10s or 30s timeout finishes
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isCompletedState) {
                    finish()
                } else if (!hasClickedState) {
                    Toast.makeText(
                        this@SponsorWebViewActivity,
                        "⚠️ যেকোনো কিছুতে ক্লিক করুন (${durationClickedState}s) অথবা অপেক্ষা করুন (${remainingSecondsState}s)!",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@SponsorWebViewActivity,
                        "⚠️ ক্লিক সনাক্ত হয়েছে! আর মাত্র ${remainingSecondsState} সেকেন্ড অপেক্ষা করুন।",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        setContent {
            StreamfitTheme {
                SponsorWebScreen(
                    targetUrl = targetUrl,
                    durationClicked = durationClicked,
                    durationNoClick = durationNoClick,
                    onStatusUpdate = { clicked, remaining, complete ->
                        hasClickedState = clicked
                        remainingSecondsState = remaining
                        isCompletedState = complete
                    },
                    onTimerFinished = { wasClicked ->
                        if (!isDownloadAd) {
                            SponsorAdManager.recordAdUnlock()
                        }
                        val eventName = if (wasClicked) {
                            if (isDownloadAd) "download_ad_clicked_${durationClicked}s" else "ad_clicked_${durationClicked}s"
                        } else {
                            if (isDownloadAd) "download_ad_timeout_${durationNoClick}s" else "ad_timeout_${durationNoClick}s"
                        }
                        SponsorAdManager.recordSponsorEvent(
                            this@SponsorWebViewActivity,
                            "click",
                            eventName
                        )
                        setResult(Activity.RESULT_OK)
                        finish()
                    },
                    onBindWebView = { wv ->
                        webView = wv
                    }
                )
            }
        }
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

// Javascript bridge for multi-layer web click detection
class WebClickBridge(private val onUserClick: () -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onAdClicked() {
        mainHandler.post {
            onUserClick()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun SponsorWebScreen(
    targetUrl: String,
    durationClicked: Int = 10,
    durationNoClick: Int = 30,
    onStatusUpdate: (Boolean, Int, Boolean) -> Unit,
    onTimerFinished: (Boolean) -> Unit,
    onBindWebView: (WebView) -> Unit
) {
    var hasClicked by remember { mutableStateOf(false) }
    var secondsRemaining by remember { mutableIntStateOf(durationNoClick) }
    var isComplete by remember { mutableStateOf(false) }

    // Report status to Activity for back press handling
    LaunchedEffect(hasClicked, secondsRemaining, isComplete) {
        onStatusUpdate(hasClicked, secondsRemaining, isComplete)
    }

    // Function called whenever user clicks on ad
    val registerClick: () -> Unit = {
        if (!hasClicked && !isComplete) {
            hasClicked = true
            // If user clicked, switch timer to durationClicked (e.g. 10s)
            if (secondsRemaining > durationClicked) {
                secondsRemaining = durationClicked
            }
        }
    }

    // Countdown timer loop
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            if (secondsRemaining > 1) {
                secondsRemaining--
            } else {
                secondsRemaining = 0
                isComplete = true
                delay(500L)
                onTimerFinished(hasClicked)
                break
            }
        }
    }

    // Glow pulse animation for timer badge
    val infiniteTransition = rememberInfiniteTransition(label = "timerPulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.95f,
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
        // Floating cyber top status bar
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
                        text = if (hasClicked) "⚡" else "💖",
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "NXLY Stream • স্পন্সর সেন্টার",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                isComplete -> "সফলভাবে সম্পন্ন হয়েছে! চালিয়ে যাচ্ছি..."
                                hasClicked -> "✅ ক্লিক সনাক্ত হয়েছে! ${secondsRemaining}s অপেক্ষা করুন..."
                                else -> "💡 যেকোনো কিছুতে ক্লিক করুন (${durationClicked}s এ শেষ হবে)"
                            },
                            color = when {
                                isComplete || hasClicked -> Color(0xFF00F5D4)
                                else -> Color(0xFFA0AEC0)
                            },
                            fontSize = 11.sp
                        )
                    }
                }

                // Glowing countdown timer badge
                val badgeColor = when {
                    isComplete -> Color(0xFF00F5D4)
                    hasClicked -> Color(0xFF00F5D4)
                    else -> Color(0xFFFF2A54)
                }

                Box(
                    modifier = Modifier
                        .border(
                            1.dp,
                            badgeColor.copy(alpha = glowAlpha),
                            RoundedCornerShape(20.dp)
                        )
                        .background(
                            badgeColor.copy(alpha = 0.15f),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            isComplete -> "✅ সম্পন্ন!"
                            hasClicked -> "⚡ ${secondsRemaining}s"
                            else -> "⏳ ${secondsRemaining}s"
                        },
                        color = badgeColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // WebView with multi-layered Click Detector
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

                    // 1. JavaScript click listener bridge
                    addJavascriptInterface(WebClickBridge { registerClick() }, "AndroidBridge")

                    val injectScript = """
                        (function() {
                            if (window.__clickDetectorAttached) return;
                            window.__clickDetectorAttached = true;
                            function report() {
                                try {
                                    if (window.AndroidBridge && window.AndroidBridge.onAdClicked) {
                                        window.AndroidBridge.onAdClicked();
                                    }
                                } catch(e){}
                            }
                            window.addEventListener('click', report, true);
                            window.addEventListener('touchstart', report, {passive: true});
                            window.addEventListener('mousedown', report, true);
                            window.addEventListener('blur', function() {
                                report();
                            });
                        })();
                    """.trimIndent()

                    webViewClient = object : WebViewClient() {
                        private fun handleUrl(view: WebView?, url: String): Boolean {
                            // Any navigation attempt indicates user clicked on link/action
                            registerClick()

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

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            view?.evaluateJavascript(injectScript, null)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(injectScript, null)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
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
                                return
                            }
                            super.onReceivedError(view, errorCode, description, failingUrl)
                        }
                    }

                    // 2. WebChromeClient for popup window clicks (window.open)
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            registerClick()
                            return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
                        }
                    }

                    // 3. Native touch detector for raw touch/tap interactions
                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            registerClick()
                        }
                        false
                    }

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


