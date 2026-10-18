package com.streamhit.app.data.analytics

import android.content.Context
import android.os.Build
import com.streamhit.app.BuildConfig
import com.streamhit.app.data.api.RetrofitClient
import kotlinx.coroutines.*
import java.util.UUID

object AppAnalyticsManager {

    private const val PREFS_NAME = "streamhit_analytics"
    private const val KEY_DEVICE_ID = "device_id"
    private var heartbeatJob: Job? = null

    @Volatile
    var cachedDeviceId: String? = null

    @Volatile
    var currentWatchingTitle: String? = null

    @Volatile
    var currentWatchingDetailPath: String? = null

    @Volatile
    var isCurrentlyPlaying: Boolean = false

    fun setWatching(title: String?, detailPath: String?, isPlaying: Boolean) {
        currentWatchingTitle = title
        currentWatchingDetailPath = detailPath
        isCurrentlyPlaying = isPlaying
    }

    fun clearWatching() {
        currentWatchingTitle = null
        currentWatchingDetailPath = null
        isCurrentlyPlaying = false
    }

    fun sendImmediateWatchUpdate(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dId = getDeviceId(context)
                val model = getDeviceModel()
                val os = getOsVersion()
                val ver = getAppVersion()
                val payload = mapOf(
                    "device_id" to dId,
                    "device_model" to model,
                    "os_version" to os,
                    "app_version" to ver,
                    "current_watching" to (currentWatchingTitle ?: ""),
                    "detail_path" to (currentWatchingDetailPath ?: ""),
                    "is_watching" to (if (isCurrentlyPlaying) "1" else "0")
                )
                RetrofitClient.apiService.sendHeartbeat(payload)
            } catch (_: Exception) {}
        }
    }

    fun getDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        cachedDeviceId = id
        return id
    }

    fun getDeviceModel(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    fun getOsVersion(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    fun getAppVersion(): String {
        return "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    fun startHeartbeat(context: Context, scope: CoroutineScope) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            val dId = getDeviceId(context)
            val model = getDeviceModel()
            val os = getOsVersion()
            val ver = getAppVersion()

            while (isActive) {
                try {
                    val payload = mapOf(
                        "device_id" to dId,
                        "device_model" to model,
                        "os_version" to os,
                        "app_version" to ver,
                        "current_watching" to (currentWatchingTitle ?: ""),
                        "detail_path" to (currentWatchingDetailPath ?: ""),
                        "is_watching" to (if (isCurrentlyPlaying) "1" else "0")
                    )
                    RetrofitClient.apiService.sendHeartbeat(payload)
                } catch (_: Exception) {
                }
                delay(30_000L) // Pulse every 30 seconds
            }
        }
    }
}
