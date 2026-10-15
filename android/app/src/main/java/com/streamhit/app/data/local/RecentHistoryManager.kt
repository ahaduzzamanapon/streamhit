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
    val subjectType: Int = 1,
    val seasonNum: Int = 1,
    val episodeNum: Int = 1,
    val dubLanguage: String? = null,
    val dubSubjectId: String? = null,
    val dubDetailPath: String? = null,
    val dubName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val progressPercent: Int
        get() = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0

    val episodeBadge: String
        get() {
            val epStr = if (subjectType == 2) "S${seasonNum} E${episodeNum}" else ""
            val dStr = dubBadge
            return when {
                epStr.isNotBlank() && dStr.isNotBlank() -> "$epStr • $dStr"
                epStr.isNotBlank() -> epStr
                dStr.isNotBlank() -> dStr
                else -> ""
            }
        }

    val dubBadge: String
        get() {
            val lang = dubLanguage?.ifBlank { null } ?: (dubName?.ifBlank { null } ?: "")
            return if (lang.isNotBlank() && !lang.equals("original", ignoreCase = true)) lang else ""
        }
}

object RecentHistoryManager {
    private const val PREF_NAME = "streamhit_recent_history"
    private const val PREF_EPISODES = "streamhit_episode_progress"
    private const val KEY_HISTORY = "watch_history_list"
    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun getEpisodePrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_EPISODES, Context.MODE_PRIVATE)
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
    fun getHistoryForSubject(context: Context, subjectId: String, detailPath: String = ""): WatchHistoryItem? {
        val list = getRecentHistory(context)
        return list.firstOrNull {
            (subjectId.isNotBlank() && it.subjectId == subjectId) ||
            (detailPath.isNotBlank() && it.detailPath == detailPath)
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
        durationMs: Long,
        subjectType: Int = 1,
        seasonNum: Int = 1,
        episodeNum: Int = 1,
        dubLanguage: String? = null,
        dubSubjectId: String? = null,
        dubDetailPath: String? = null,
        dubName: String? = null
    ) {
        if (title.isBlank() && subjectId.isBlank() && detailPath.isBlank()) return

        try {
            val safePos = positionMs.coerceAtLeast(0L)

            // Save episode-level progress for Series
            if (subjectType == 2) {
                val epEditor = getEpisodePrefs(context).edit()
                if (subjectId.isNotBlank()) {
                    epEditor.putLong("${subjectId}_s${seasonNum}_e${episodeNum}", safePos)
                }
                if (detailPath.isNotBlank()) {
                    epEditor.putLong("${detailPath}_s${seasonNum}_e${episodeNum}", safePos)
                }
                epEditor.apply()
            }

            val list = getRecentHistory(context).toMutableList()
            // Find any existing entry to preserve duration, metadata or dubbing if incoming is empty
            val existing = list.firstOrNull {
                (subjectId.isNotBlank() && it.subjectId == subjectId) ||
                (detailPath.isNotBlank() && it.detailPath == detailPath) ||
                (subjectId.isEmpty() && streamUrl.isNotBlank() && it.streamUrl == streamUrl)
            }

            // Remove existing entry for same subject or path or stream
            list.removeAll {
                (subjectId.isNotBlank() && it.subjectId == subjectId) ||
                (detailPath.isNotBlank() && it.detailPath == detailPath) ||
                (subjectId.isEmpty() && streamUrl.isNotBlank() && it.streamUrl == streamUrl)
            }

            val finalDuration = if (durationMs > 0L) durationMs else (existing?.durationMs ?: 0L)
            val finalCover = coverUrl.ifBlank { existing?.coverUrl ?: "" }
            val finalTitle = title.ifBlank { existing?.title ?: "Watching" }
            val finalStream = streamUrl.ifBlank { existing?.streamUrl ?: "" }
            val finalDubLang = dubLanguage?.ifBlank { null } ?: existing?.dubLanguage
            val finalDubSubId = dubSubjectId?.ifBlank { null } ?: existing?.dubSubjectId
            val finalDubPath = dubDetailPath?.ifBlank { null } ?: existing?.dubDetailPath
            val finalDubName = dubName?.ifBlank { null } ?: existing?.dubName

            // Add updated/new entry at the top of history
            list.add(
                0,
                WatchHistoryItem(
                    subjectId = subjectId,
                    detailPath = detailPath,
                    title = finalTitle,
                    coverUrl = finalCover,
                    streamUrl = finalStream,
                    positionMs = safePos,
                    durationMs = finalDuration,
                    subjectType = subjectType,
                    seasonNum = seasonNum,
                    episodeNum = episodeNum,
                    dubLanguage = finalDubLang,
                    dubSubjectId = finalDubSubId,
                    dubDetailPath = finalDubPath,
                    dubName = finalDubName,
                    timestamp = System.currentTimeMillis()
                )
            )

            // Keep max 25 items
            val trimmed = list.take(25)
            val json = gson.toJson(trimmed)
            getPrefs(context).edit().putString(KEY_HISTORY, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getSavedPosition(
        context: Context,
        streamUrl: String = "",
        subjectId: String = "",
        detailPath: String = "",
        seasonNum: Int = 1,
        episodeNum: Int = 1,
        subjectType: Int = 1
    ): Long {
        if (subjectType == 2) {
            val epPrefs = getEpisodePrefs(context)
            val key1 = "${subjectId}_s${seasonNum}_e${episodeNum}"
            val key2 = "${detailPath}_s${seasonNum}_e${episodeNum}"
            if (subjectId.isNotBlank() && epPrefs.contains(key1)) {
                return epPrefs.getLong(key1, 0L)
            }
            if (detailPath.isNotBlank() && epPrefs.contains(key2)) {
                return epPrefs.getLong(key2, 0L)
            }
            // Check if recent history entry is for THIS exact episode
            val list = getRecentHistory(context)
            val item = list.firstOrNull {
                ((subjectId.isNotBlank() && it.subjectId == subjectId) || (detailPath.isNotBlank() && it.detailPath == detailPath)) &&
                it.seasonNum == seasonNum && it.episodeNum == episodeNum
            }
            return item?.positionMs ?: 0L
        } else {
            val list = getRecentHistory(context)
            val item = list.firstOrNull {
                (subjectId.isNotBlank() && it.subjectId == subjectId) ||
                (detailPath.isNotBlank() && it.detailPath == detailPath) ||
                (subjectId.isEmpty() && streamUrl.isNotBlank() && it.streamUrl == streamUrl)
            }
            return item?.positionMs ?: 0L
        }
    }

    @Synchronized
    fun getSavedPosition(context: Context, streamUrl: String, subjectId: String, detailPath: String = ""): Long {
        return getSavedPosition(context, streamUrl, subjectId, detailPath, 1, 1, 1)
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
        getEpisodePrefs(context).edit().clear().apply()
    }
}
