package com.streamhit.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.streamhit.app.data.analytics.AppAnalyticsManager
import com.streamhit.app.data.api.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object SponsorAdManager {

    const val PLACEMENT_DOWNLOAD_TURBO = "download_turbo"
    const val PLACEMENT_PLAYER_START = "player_start"
    const val PLACEMENT_PLAYER_INTERVAL = "player_interval"
    const val PLACEMENT_DOWNLOAD_SINGLE = "download_single"
    const val PLACEMENT_LIVETV = "livetv"
    const val PLACEMENT_APP_LAUNCH = "app_launch"

    private const val PREFS_NAME = "streamhit_sponsor_ad_prefs"
    private const val KEY_ADMIN_SPONSOR_URL = "admin_sponsor_url"
    private const val KEY_IS_ENABLED = "admin_ad_enabled"
    private const val KEY_AD_DURATION = "admin_ad_duration"
    private const val KEY_DURATION_CLICKED = "admin_duration_clicked"
    private const val KEY_DURATION_NO_CLICK = "admin_duration_no_click"
    private const val KEY_PLAYBACK_INTERVAL_MINUTES = "admin_playback_interval_minutes"
    private const val KEY_PLACEMENTS_JSON = "admin_placements_json"
    private const val KEY_DIALOG_TITLE = "admin_dialog_title"
    private const val KEY_DIALOG_MESSAGE = "admin_dialog_message"
    private const val KEY_BUTTON_TEXT = "admin_button_text"
    private const val KEY_CANCEL_TEXT = "admin_cancel_text"

    // Initial fallback only if phone is completely offline on first launch
    private const val FALLBACK_URL = "https://uplcm.com/4/12189723"

    data class PlacementConfig(
        val enabled: Boolean = true,
        val url: String = "",
        val intervalMinutes: Int = 25
    )

    private val placementsMap = ConcurrentHashMap<String, PlacementConfig>().apply {
        put(PLACEMENT_DOWNLOAD_TURBO, PlacementConfig(enabled = true))
        put(PLACEMENT_PLAYER_START, PlacementConfig(enabled = true))
        put(PLACEMENT_PLAYER_INTERVAL, PlacementConfig(enabled = true, intervalMinutes = 25))
        put(PLACEMENT_DOWNLOAD_SINGLE, PlacementConfig(enabled = true))
        put(PLACEMENT_LIVETV, PlacementConfig(enabled = true))
        put(PLACEMENT_APP_LAUNCH, PlacementConfig(enabled = true))
    }

    @Volatile
    var hasShownLaunchAdInSession: Boolean = false

    var configVersion by mutableIntStateOf(0)
        private set

    @Volatile
    var isSessionUnlocked: Boolean = false

    @Volatile
    var lastAdUnlockTimeMs: Long = 0L

    @Volatile
    var playbackIntervalMinutes: Int = 25
        private set

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
     * Checks if ad is currently unlocked for playback intervals.
     */
    fun isAdUnlocked(): Boolean {
        return isPlaybackIntervalUnlocked()
    }

    fun isPlaybackIntervalUnlocked(): Boolean {
        if (!isEnabled) return true
        if (!isPlacementEnabled(PLACEMENT_PLAYER_INTERVAL)) return true
        if (!isSessionUnlocked) return false
        val elapsed = System.currentTimeMillis() - lastAdUnlockTimeMs
        val intervalMs = getPlaybackIntervalMinutes(PLACEMENT_PLAYER_INTERVAL) * 60 * 1000L
        return elapsed < intervalMs
    }

    fun isPlacementEnabled(placement: String): Boolean {
        if (!isEnabled) return false
        val cfg = placementsMap[placement]
        return cfg?.enabled ?: true
    }

    fun getPlacementUrl(placement: String): String {
        val customUrl = placementsMap[placement]?.url?.trim()
        if (!customUrl.isNullOrBlank() && (customUrl.startsWith("http://") || customUrl.startsWith("https://"))) {
            return customUrl
        }
        return sponsorUrl.ifBlank { FALLBACK_URL }
    }

    fun getPlaybackIntervalMinutes(placement: String = PLACEMENT_PLAYER_INTERVAL): Int {
        val cfg = placementsMap[placement]
        if (cfg != null && cfg.intervalMinutes in 1..180) {
            return cfg.intervalMinutes
        }
        return playbackIntervalMinutes.coerceIn(1, 180)
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

    private var syncJob: Job? = null

    /**
     * Starts background real-time sync polling (every 15 seconds) while app is active.
     */
    fun startRealtimeSync(context: Context) {
        if (syncJob?.isActive == true) return
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(15000) // Poll every 15s for instant admin responsiveness
                try {
                    fetchRemoteConfig(context)
                } catch (_: Exception) {}
            }
        }
    }

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
            playbackIntervalMinutes = prefs.getInt(KEY_PLAYBACK_INTERVAL_MINUTES, 25).coerceIn(1, 180)

            val savedPlacementsJson = prefs.getString(KEY_PLACEMENTS_JSON, null)
            if (!savedPlacementsJson.isNullOrBlank()) {
                try {
                    val rootObj = JSONObject(savedPlacementsJson)
                    val keys = rootObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val obj = rootObj.optJSONObject(key)
                        if (obj != null) {
                            val enabled = obj.optBoolean("enabled", true)
                            val url = obj.optString("url", "")
                            val interval = obj.optInt("interval_minutes", 25)
                            placementsMap[key] = PlacementConfig(enabled, url, interval)
                        }
                    }
                } catch (_: Exception) {}
            }

            prefs.getString(KEY_DIALOG_TITLE, null)?.let { if (it.isNotBlank()) dialogTitle = it }
            prefs.getString(KEY_DIALOG_MESSAGE, null)?.let { if (it.isNotBlank()) dialogMessage = it }
            prefs.getString(KEY_BUTTON_TEXT, null)?.let { if (it.isNotBlank()) buttonText = it }
                ?: run { buttonText = "🚀 অ্যাড দেখুন ($durationClickedSeconds সেকেন্ড)" }
            prefs.getString(KEY_CANCEL_TEXT, null)?.let { if (it.isNotBlank()) cancelText = it }
        } catch (_: Exception) {}

        // Fetch latest dynamic configuration from Admin Panel API immediately
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

                    val remotePlaybackInterval = body?.get("playback_interval_minutes")
                    if (remotePlaybackInterval != null) {
                        val parsedInterval = when (remotePlaybackInterval) {
                            is Number -> remotePlaybackInterval.toInt()
                            is String -> remotePlaybackInterval.toDoubleOrNull()?.toInt() ?: 25
                            else -> 25
                        }
                        if (parsedInterval in 1..180) {
                            playbackIntervalMinutes = parsedInterval
                        }
                    }

                    var placementsJsonStr: String? = null
                    val remotePlacements = body?.get("placements")
                    if (remotePlacements is Map<*, *>) {
                        val jsonPlacements = JSONObject()
                        for ((k, v) in remotePlacements) {
                            val key = k?.toString() ?: continue
                            if (v is Map<*, *>) {
                                val enabled = when (val en = v["enabled"]) {
                                    is Boolean -> en
                                    is Number -> en.toInt() == 1
                                    is String -> en.equals("true", ignoreCase = true) || en == "1"
                                    else -> true
                                }
                                val url = v["url"]?.toString()?.trim() ?: ""
                                val interval = when (val iv = v["interval_minutes"]) {
                                    is Number -> iv.toInt()
                                    is String -> iv.toDoubleOrNull()?.toInt() ?: 25
                                    else -> 25
                                }
                                placementsMap[key] = PlacementConfig(enabled, url, interval)
                                val obj = JSONObject()
                                obj.put("enabled", enabled)
                                obj.put("url", url)
                                obj.put("interval_minutes", interval)
                                jsonPlacements.put(key, obj)
                            }
                        }
                        placementsJsonStr = jsonPlacements.toString()
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

                    // Trigger Compose UI update in real time
                    CoroutineScope(Dispatchers.Main).launch {
                        configVersion++
                    }

                    // Save to SharedPreferences
                    context?.let { ctx ->
                        try {
                            val prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            val editor = prefs.edit()
                                .putString(KEY_ADMIN_SPONSOR_URL, sponsorUrl)
                                .putBoolean(KEY_IS_ENABLED, isEnabled)
                                .putInt(KEY_AD_DURATION, durationClickedSeconds)
                                .putInt(KEY_DURATION_CLICKED, durationClickedSeconds)
                                .putInt(KEY_DURATION_NO_CLICK, durationNoClickSeconds)
                                .putInt(KEY_PLAYBACK_INTERVAL_MINUTES, playbackIntervalMinutes)
                                .putString(KEY_DIALOG_TITLE, dialogTitle)
                                .putString(KEY_DIALOG_MESSAGE, dialogMessage)
                                .putString(KEY_BUTTON_TEXT, buttonText)
                                .putString(KEY_CANCEL_TEXT, cancelText)
                            if (placementsJsonStr != null) {
                                editor.putString(KEY_PLACEMENTS_JSON, placementsJsonStr)
                            }
                            editor.apply()
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
     * Open the sponsor URL in user's browser.
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
