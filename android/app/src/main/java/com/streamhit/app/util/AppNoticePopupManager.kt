package com.streamhit.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.streamhit.app.data.api.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AppNoticePopupManager {

    private const val PREFS_NAME = "streamhit_app_popup_prefs"
    private const val KEY_ENABLED = "popup_enabled"
    private const val KEY_TITLE = "popup_title"
    private const val KEY_MESSAGE = "popup_message"
    private const val KEY_IMAGE_URL = "popup_image_url"
    private const val KEY_SHOW_CANCEL = "popup_show_cancel"
    private const val KEY_CANCEL_TEXT = "popup_cancel_text"
    private const val KEY_BTN1_TEXT = "popup_btn1_text"
    private const val KEY_BTN1_URL = "popup_btn1_url"
    private const val KEY_BTN2_ENABLED = "popup_btn2_enabled"
    private const val KEY_BTN2_TEXT = "popup_btn2_text"
    private const val KEY_BTN2_URL = "popup_btn2_url"

    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var title: String = "গুরুত্বপূর্ণ নোটিশ"
        private set

    @Volatile
    var message: String = ""
        private set

    @Volatile
    var imageUrl: String = ""
        private set

    @Volatile
    var showCancel: Boolean = true
        private set

    @Volatile
    var cancelText: String = "বন্ধ করুন"
        private set

    @Volatile
    var btn1Text: String = "টেলিগ্রামে যুক্ত হন"
        private set

    @Volatile
    var btn1Url: String = ""
        private set

    @Volatile
    var btn2Enabled: Boolean = false
        private set

    @Volatile
    var btn2Text: String = ""
        private set

    @Volatile
    var btn2Url: String = ""
        private set

    @Volatile
    var hasShownInCurrentSession: Boolean = false

    fun init(context: Context) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            isEnabled = prefs.getBoolean(KEY_ENABLED, false)
            title = prefs.getString(KEY_TITLE, "গুরুত্বপূর্ণ নোটিশ") ?: "গুরুত্বপূর্ণ নোটিশ"
            message = prefs.getString(KEY_MESSAGE, "") ?: ""
            imageUrl = prefs.getString(KEY_IMAGE_URL, "") ?: ""
            showCancel = prefs.getBoolean(KEY_SHOW_CANCEL, true)
            cancelText = prefs.getString(KEY_CANCEL_TEXT, "বন্ধ করুন") ?: "বন্ধ করুন"
            btn1Text = prefs.getString(KEY_BTN1_TEXT, "টেলিগ্রামে যুক্ত হন") ?: "টেলিগ্রামে যুক্ত হন"
            btn1Url = prefs.getString(KEY_BTN1_URL, "") ?: ""
            btn2Enabled = prefs.getBoolean(KEY_BTN2_ENABLED, false)
            btn2Text = prefs.getString(KEY_BTN2_TEXT, "") ?: ""
            btn2Url = prefs.getString(KEY_BTN2_URL, "") ?: ""
        } catch (_: Exception) {}

        fetchRemoteConfig(context)
    }

    fun fetchRemoteConfig(context: Context? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val res = RetrofitClient.apiService.getAppPopupConfig()
                if (res.isSuccessful) {
                    val body = res.body() ?: return@launch
                    val remoteEnabled = body["enabled"]
                    isEnabled = when (remoteEnabled) {
                        is Boolean -> remoteEnabled
                        is Number -> remoteEnabled.toInt() == 1
                        is String -> remoteEnabled.equals("true", ignoreCase = true) || remoteEnabled == "1"
                        else -> false
                    }

                    title = body["title"]?.toString()?.trim() ?: "গুরুত্বপূর্ণ নোটিশ"
                    message = body["message"]?.toString()?.trim() ?: ""
                    imageUrl = body["image_url"]?.toString()?.trim() ?: ""

                    val remoteShowCancel = body["show_cancel"]
                    showCancel = when (remoteShowCancel) {
                        is Boolean -> remoteShowCancel
                        is Number -> remoteShowCancel.toInt() == 1
                        is String -> remoteShowCancel.equals("true", ignoreCase = true) || remoteShowCancel == "1"
                        else -> true
                    }

                    cancelText = body["cancel_text"]?.toString()?.trim()?.ifBlank { "বন্ধ করুন" } ?: "বন্ধ করুন"
                    btn1Text = body["btn1_text"]?.toString()?.trim()?.ifBlank { "টেলিগ্রামে যুক্ত হন" } ?: "টেলিগ্রামে যুক্ত হন"
                    btn1Url = body["btn1_url"]?.toString()?.trim() ?: ""

                    val remoteBtn2Enabled = body["btn2_enabled"]
                    btn2Enabled = when (remoteBtn2Enabled) {
                        is Boolean -> remoteBtn2Enabled
                        is Number -> remoteBtn2Enabled.toInt() == 1
                        is String -> remoteBtn2Enabled.equals("true", ignoreCase = true) || remoteBtn2Enabled == "1"
                        else -> false
                    }

                    btn2Text = body["btn2_text"]?.toString()?.trim() ?: ""
                    btn2Url = body["btn2_url"]?.toString()?.trim() ?: ""

                    context?.let { ctx ->
                        try {
                            val prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.edit()
                                .putBoolean(KEY_ENABLED, isEnabled)
                                .putString(KEY_TITLE, title)
                                .putString(KEY_MESSAGE, message)
                                .putString(KEY_IMAGE_URL, imageUrl)
                                .putBoolean(KEY_SHOW_CANCEL, showCancel)
                                .putString(KEY_CANCEL_TEXT, cancelText)
                                .putString(KEY_BTN1_TEXT, btn1Text)
                                .putString(KEY_BTN1_URL, btn1Url)
                                .putBoolean(KEY_BTN2_ENABLED, btn2Enabled)
                                .putString(KEY_BTN2_TEXT, btn2Text)
                                .putString(KEY_BTN2_URL, btn2Url)
                                .apply()
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Opens the action URL in user's external web browser or target application.
     */
    fun openExternalUrl(context: Context, rawUrl: String): Boolean {
        return try {
            var url = rawUrl.trim()
            if (url.isBlank()) return false
            if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("tg://") && !url.startsWith("intent://")) {
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

