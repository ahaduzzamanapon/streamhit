package com.streamhit.app.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.google.gson.Gson
import com.streamhit.app.data.model.LiveTvChannel

object DeviceUtils {

    private const val PREFS_NAME = "streamhit_tv_prefs"
    private const val KEY_LAST_LIVE_CHANNEL = "key_last_live_channel"
    private const val KEY_LAST_LIVE_SERVER_IDX = "key_last_live_server_idx"

    private val gson = Gson()

    /**
     * Accurately detects whether the running environment is Google TV / Android TV
     * or a standard Android Phone / Tablet.
     */
    fun isTvDevice(context: Context): Boolean {
        try {
            // Check developer preview toggle first
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean("force_tv_mode", false)) {
                return true
            }

            // 1. Check UI Mode (Recommended Android way)
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
                return true
            }

            // 2. Check Leanback & Television System Features
            val pm = context.packageManager
            if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                pm.hasSystemFeature("android.hardware.type.television") ||
                pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
            ) {
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    fun setForceTvMode(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("force_tv_mode", enabled).apply()
    }

    fun isForcedTvMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("force_tv_mode", false)
    }

    /**
     * Saves the last played Live TV Channel to SharedPreferences
     * so that on TV launch, it automatically starts playing without manual selection.
     */
    fun saveLastPlayedLiveChannel(context: Context, channel: LiveTvChannel, serverIdx: Int = 0) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = gson.toJson(channel)
            prefs.edit()
                .putString(KEY_LAST_LIVE_CHANNEL, json)
                .putInt(KEY_LAST_LIVE_SERVER_IDX, serverIdx)
                .apply()
        } catch (_: Exception) {}
    }

    /**
     * Retrieves the last played Live TV channel and server index.
     */
    fun getLastPlayedLiveChannel(context: Context): Pair<LiveTvChannel?, Int> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_LAST_LIVE_CHANNEL, null)
            val serverIdx = prefs.getInt(KEY_LAST_LIVE_SERVER_IDX, 0)
            if (!json.isNullOrBlank()) {
                val ch = gson.fromJson(json, LiveTvChannel::class.java)
                Pair(ch, serverIdx)
            } else {
                Pair(null, 0)
            }
        } catch (_: Exception) {
            Pair(null, 0)
        }
    }
}

