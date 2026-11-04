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

    const val DEFAULT_SPONSOR_URL = "https://uplcm.com/4/12189723"

    @Volatile
    var sponsorUrl: String = DEFAULT_SPONSOR_URL
        private set

    @Volatile
    var isEnabled: Boolean = true
        private set

    @Volatile
    var dialogTitle: String = "আমাদের সাপোর্ট করুন"
        private set

    @Volatile
    var dialogMessage: String = "অ্যাপটি সম্পূর্ণ ফ্রিতে চালাতে এবং নতুন কনটেন্ট যুক্ত করতে অনুগ্রহ করে স্পন্সর লিংকে একটি ক্লিক করে আমাদের সাপোর্ট করুন!"
        private set

    @Volatile
    var buttonText: String = "💖 সাপোর্ট করতে লিংকে ক্লিক করুন"
        private set

    @Volatile
    var cancelText: String = "✕ সরাসরি দেখুন"
        private set

    /**
     * Synchronize latest sponsor ad configuration dynamically from backend.
     */
    fun fetchRemoteConfig() {
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
     * Open the sponsor URL in the user's default web browser safely.
     */
    fun openSponsorLink(context: Context): Boolean {
        return try {
            val url = sponsorUrl.ifBlank { DEFAULT_SPONSOR_URL }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

