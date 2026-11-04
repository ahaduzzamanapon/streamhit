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
    private const val KEY_DIALOG_TITLE = "admin_dialog_title"
    private const val KEY_DIALOG_MESSAGE = "admin_dialog_message"
    private const val KEY_BUTTON_TEXT = "admin_button_text"
    private const val KEY_CANCEL_TEXT = "admin_cancel_text"

    // Initial fallback only if phone is completely offline on first launch
    private const val FALLBACK_URL = "https://uplcm.com/4/12189723"

    @Volatile
    var isSessionUnlocked: Boolean = false

    @Volatile
    var sponsorUrl: String = FALLBACK_URL
        private set

    @Volatile
    var isEnabled: Boolean = true
        private set

    @Volatile
    var dialogTitle: String = "আমাদের সাপোর্ট করুন"
        private set

    @Volatile
    var dialogMessage: String = "অ্যাপটি সম্পূর্ণ ফ্রিতে চালাতে এবং সকল কনটেন্ট উপভোগ করতে স্পন্সর পেজটি ১০ সেকেন্ড ভিজিট করে আনলক করুন!"
        private set

    @Volatile
    var buttonText: String = "🚀 স্পন্সর পেজ ভিজিট করুন (১০ সেকেন্ড)"
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
            prefs.getString(KEY_DIALOG_TITLE, null)?.let { if (it.isNotBlank()) dialogTitle = it }
            prefs.getString(KEY_DIALOG_MESSAGE, null)?.let { if (it.isNotBlank()) dialogMessage = it }
            prefs.getString(KEY_BUTTON_TEXT, null)?.let { if (it.isNotBlank()) buttonText = it }
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
                        sponsorUrl = remoteUrl
                    }
                    val remoteEnabled = body?.get("enabled")
                    if (remoteEnabled is Boolean) {
                        isEnabled = remoteEnabled
                    }
                    val rTitle = body?.get("dialog_title")?.toString()?.trim()
                    if (!rTitle.isNullOrBlank()) dialogTitle = rTitle

                    val rMsg = body?.get("dialog_message")?.toString()?.trim()
                    if (!rMsg.isNullOrBlank()) dialogMessage = rMsg

                    val rBtn = body?.get("button_text")?.toString()?.trim()
                    if (!rBtn.isNullOrBlank()) buttonText = rBtn

                    val rCancel = body?.get("cancel_text")?.toString()?.trim()
                    if (!rCancel.isNullOrBlank()) cancelText = rCancel

                    // Save to SharedPreferences so admin link is permanently active on user's device
                    context?.let { ctx ->
                        try {
                            val prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.edit()
                                .putString(KEY_ADMIN_SPONSOR_URL, sponsorUrl)
                                .putBoolean(KEY_IS_ENABLED, isEnabled)
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
