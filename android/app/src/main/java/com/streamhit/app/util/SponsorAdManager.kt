package com.streamhit.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.streamhit.app.data.analytics.AppAnalyticsManager
import com.streamhit.app.data.api.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SponsorAdManager {

    private const val PREFS_NAME = "streamhit_sponsor_ad_prefs"
    private const val KEY_ADMIN_SPONSOR_URL = "admin_sponsor_url"
    private const val KEY_IS_ENABLED = "admin_ad_enabled"
    private const val KEY_AD_DURATION = "admin_ad_duration"
    private const val KEY_DURATION_CLICKED = "admin_duration_clicked"
    private const val KEY_DURATION_NO_CLICK = "admin_duration_no_click"
    private const val KEY_DIALOG_TITLE = "admin_dialog_title"
    private const val KEY_DIALOG_MESSAGE = "admin_dialog_message"
    private const val KEY_BUTTON_TEXT = "admin_button_text"
    private const val KEY_CANCEL_TEXT = "admin_cancel_text"

    // Initial fallback only if phone is completely offline on first launch
    private const val FALLBACK_URL = "https://uplcm.com/4/12189723"

    @Volatile
    var isSessionUnlocked: Boolean = false

    @Volatile
    var lastAdUnlockTimeMs: Long = 0L

    @Volatile
    var adDurationSeconds: Int = 10
        private set

    @Volatile
    var durationClickedSeconds: Int = 10
        private set

    @Volatile
    var durationNoClickSeconds: Int = 30
        private set

    /**
     * Checks if ad is currently unlocked.
     * When unlocked, allows 30 minutes of uninterrupted playback before prompting again.
     */
    fun isAdUnlocked(): Boolean {
        if (!isEnabled) return true
        if (!isSessionUnlocked) return false
        val elapsed = System.currentTimeMillis() - lastAdUnlockTimeMs
        val intervalMs = 30 * 60 * 1000L // 30 minutes
        return elapsed < intervalMs
    }

    /**
     * Call when user finishes the sponsor visit.
     */
    fun recordAdUnlock() {
        lastAdUnlockTimeMs = System.currentTimeMillis()
        isSessionUnlocked = true
    }

    @Volatile
    var sponsorUrl: String = FALLBACK_URL
        private set

    @Volatile
    var isEnabled: Boolean = true
        private set

    @Volatile
    var dialogTitle: String = "অটোমেটেড অ্যাড সেন্টার"
        private set

    @Volatile
    var dialogMessage: String = "যেকোনো কিছুতে ক্লিক করলে ১০ সেকেন্ডে শেষ হবে, আর ক্লিক না করলে ৩০ সেকেন্ড অপেক্ষা করতে হবে।"
        private set

    @Volatile
    var buttonText: String = "🚀 অ্যাড দেখুন (১০ সেকেন্ড)"
        private set

    @Volatile
    var cancelText: String = "✕ সরাসরি দেখুন"
        private set

    /**
     * Initialize dynamic settings from local cache and fetch latest from Admin Panel.
     */
    fun init(context: Context) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedUrl = prefs.getString(KEY_ADMIN_SPONSOR_URL, null)
            if (!savedUrl.isNullOrBlank()) {
                sponsorUrl = savedUrl
            }
            isEnabled = prefs.getBoolean(KEY_IS_ENABLED, true)
            durationClickedSeconds = prefs.getInt(KEY_DURATION_CLICKED, prefs.getInt(KEY_AD_DURATION, 10))
            durationNoClickSeconds = prefs.getInt(KEY_DURATION_NO_CLICK, 30)
            adDurationSeconds = durationClickedSeconds
            prefs.getString(KEY_DIALOG_TITLE, null)?.let { if (it.isNotBlank()) dialogTitle = it }
            prefs.getString(KEY_DIALOG_MESSAGE, null)?.let { if (it.isNotBlank()) dialogMessage = it }
            prefs.getString(KEY_BUTTON_TEXT, null)?.let { if (it.isNotBlank()) buttonText = it }
                ?: run { buttonText = "🚀 অ্যাড দেখুন ($durationClickedSeconds সেকেন্ড)" }
            prefs.getString(KEY_CANCEL_TEXT, null)?.let { if (it.isNotBlank()) cancelText = it }
        } catch (_: Exception) {}

        // Fetch latest dynamic configuration from Admin Panel API
        fetchRemoteConfig(context)
    }

    /**
     * Synchronize latest sponsor ad configuration dynamically from Admin Panel / Backend.
     */
    fun fetchRemoteConfig(context: Context? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val res = RetrofitClient.apiService.getAdConfig()
                if (res.isSuccessful) {
                    val body = res.body()
                    val remoteUrl = body?.get("sponsor_url")?.toString()?.trim()
                    if (!remoteUrl.isNullOrBlank() && remoteUrl.startsWith("http")) {
                        if (remoteUrl != sponsorUrl) {
                            sponsorUrl = remoteUrl
                            // When admin updates sponsor link, instantly reset session unlock so user gets the new ad!
                            isSessionUnlocked = false
                        }
                    }
                    val remoteEnabled = body?.get("enabled")
                    if (remoteEnabled != null) {
                        val parsedEnabled = when (remoteEnabled) {
                            is Boolean -> remoteEnabled
                            is Number -> remoteEnabled.toInt() == 1
                            is String -> remoteEnabled.equals("true", ignoreCase = true) || remoteEnabled == "1"
                            else -> true
                        }
                        if (parsedEnabled != isEnabled) {
                            isEnabled = parsedEnabled
                            if (parsedEnabled) {
                                isSessionUnlocked = false
                            }
                        }
                    }
                    val remoteDuration = body?.get("duration_clicked") ?: body?.get("duration")
                    if (remoteDuration != null) {
                        val parsedDuration = when (remoteDuration) {
                            is Number -> remoteDuration.toInt()
                            is String -> remoteDuration.toDoubleOrNull()?.toInt() ?: 10
                            else -> 10
                        }
                        if (parsedDuration in 3..300) {
                            durationClickedSeconds = parsedDuration
                            adDurationSeconds = parsedDuration
                        }
                    }

                    val remoteNoClick = body?.get("duration_no_click")
                    if (remoteNoClick != null) {
                        val parsedNoClick = when (remoteNoClick) {
                            is Number -> remoteNoClick.toInt()
                            is String -> remoteNoClick.toDoubleOrNull()?.toInt() ?: 30
                            else -> 30
                        }
                        if (parsedNoClick in 5..300) {
                            durationNoClickSeconds = parsedNoClick
                        }
                    }

                    val rTitle = body?.get("dialog_title")?.toString()?.trim()
                    if (!rTitle.isNullOrBlank()) dialogTitle = rTitle

                    val rMsg = body?.get("dialog_message")?.toString()?.trim()
                    if (!rMsg.isNullOrBlank()) dialogMessage = rMsg

                    val rBtn = body?.get("button_text")?.toString()?.trim()
                    if (!rBtn.isNullOrBlank()) {
                        buttonText = rBtn
                    } else {
                        buttonText = "🚀 অ্যাড দেখুন ($durationClickedSeconds সেকেন্ড)"
                    }

                    val rCancel = body?.get("cancel_text")?.toString()?.trim()
                    if (!rCancel.isNullOrBlank()) cancelText = rCancel

                    // Save to SharedPreferences so admin link is permanently active on user's device
                    context?.let { ctx ->
                        try {
                            val prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.edit()
                                .putString(KEY_ADMIN_SPONSOR_URL, sponsorUrl)
                                .putBoolean(KEY_IS_ENABLED, isEnabled)
                                .putInt(KEY_AD_DURATION, durationClickedSeconds)
                                .putInt(KEY_DURATION_CLICKED, durationClickedSeconds)
                                .putInt(KEY_DURATION_NO_CLICK, durationNoClickSeconds)
                                .putString(KEY_DIALOG_TITLE, dialogTitle)
                                .putString(KEY_DIALOG_MESSAGE, dialogMessage)
                                .putString(KEY_BUTTON_TEXT, buttonText)
                                .putString(KEY_CANCEL_TEXT, cancelText)
                                .apply()
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Record an ad event (click, impression, cancel) to the backend for Admin Panel reporting.
     */
    fun recordSponsorEvent(context: Context, eventType: String, videoTitle: String = "") {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val payload = mapOf(
                    "event" to eventType, // "click", "impression", "cancel"
                    "device_id" to AppAnalyticsManager.getDeviceId(context),
                    "device_model" to AppAnalyticsManager.getDeviceModel(),
                    "video_title" to videoTitle,
                    "app_version" to AppAnalyticsManager.getAppVersion()
                )
                RetrofitClient.apiService.recordAdEvent(payload)
            } catch (_: Exception) {}
        }
    }

    /**
     * Open the sponsor URL (dynamically retrieved from Admin Panel) in user's browser.
     */
    fun openSponsorLink(context: Context): Boolean {
        return try {
            var url = sponsorUrl.trim().ifBlank { FALLBACK_URL }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
