package com.streamhit.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class WatchHistoryItem(
    val subjectId: String,
    val detailPath: String,
    val title: String,
    val coverUrl: String,
    val streamUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    val progressPercent: Int
        get() = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0
}

object RecentHistoryManager {
    private const val PREF_NAME = "streamhit_recent_history"
    private const val KEY_HISTORY = "watch_history_list"
    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getRecentHistory(context: Context): List<WatchHistoryItem> {
        return try {
            val json = getPrefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
            val type = object : TypeToken<List<WatchHistoryItem>>() {}.type
            gson.fromJson<List<WatchHistoryItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveProgress(
        context: Context,
        subjectId: String,
        detailPath: String,
        title: String,
        coverUrl: String,
        streamUrl: String,
        positionMs: Long,
        durationMs: Long
    ) {
        if (positionMs <= 1000L || durationMs <= 0L) return

        try {
            val list = getRecentHistory(context).toMutableList()
            // Remove existing entry for same subject or stream
            list.removeAll { it.subjectId == subjectId || (subjectId.isEmpty() && it.streamUrl == streamUrl) }

            // Add new entry at top
            list.add(
                0,
                WatchHistoryItem(
                    subjectId = subjectId,
                    detailPath = detailPath,
                    title = title,
                    coverUrl = coverUrl,
                    streamUrl = streamUrl,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    timestamp = System.currentTimeMillis()
                )
            )

            // Keep max 20 items
            val trimmed = list.take(20)
            val json = gson.toJson(trimmed)
            getPrefs(context).edit().putString(KEY_HISTORY, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getSavedPosition(context: Context, streamUrl: String, subjectId: String): Long {
        val list = getRecentHistory(context)
        val item = list.firstOrNull { it.subjectId == subjectId || (subjectId.isEmpty() && it.streamUrl == streamUrl) }
        return item?.positionMs ?: 0L
    }

    @Synchronized
    fun removeHistoryItem(context: Context, subjectId: String) {
        try {
            val list = getRecentHistory(context).toMutableList()
            list.removeAll { it.subjectId == subjectId }
            getPrefs(context).edit().putString(KEY_HISTORY, gson.toJson(list)).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun clearHistory(context: Context) {
        getPrefs(context).edit().remove(KEY_HISTORY).apply()
    }
}
