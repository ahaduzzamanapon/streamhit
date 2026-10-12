package com.streamhit.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamhit.app.data.model.LiveTvChannel

object LiveTvCacheManager {

    private const val PREF_NAME = "streamhit_livetv_cache"
    private const val KEY_CHANNELS = "cached_livetv_channels"
    private const val KEY_TIMESTAMP = "cached_livetv_timestamp"
    private const val CACHE_EXPIRY_MS = 5 * 60 * 60 * 1000L // 5 Hours (User requirement)

    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isCacheExpired(context: Context): Boolean {
        val prefs = getPrefs(context)
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        val hasData = prefs.contains(KEY_CHANNELS)
        if (!hasData || timestamp == 0L) return true
        return (System.currentTimeMillis() - timestamp) > CACHE_EXPIRY_MS
    }

    fun getCachedChannels(context: Context): List<LiveTvChannel> {
        val json = getPrefs(context).getString(KEY_CHANNELS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<LiveTvChannel>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveChannelsCache(context: Context, channels: List<LiveTvChannel>) {
        if (channels.isEmpty()) return
        val prefs = getPrefs(context)
        try {
            val json = gson.toJson(channels)
            prefs.edit()
                .putString(KEY_CHANNELS, json)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {}
    }
}
