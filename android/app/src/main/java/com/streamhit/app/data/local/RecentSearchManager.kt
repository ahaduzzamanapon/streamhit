package com.streamhit.app.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamhit.app.data.model.SubjectSummary

object RecentSearchManager {

    private const val PREFS_NAME = "streamhit_search_history"
    private const val KEY_SEARCH_ITEMS = "recent_search_items"
    private const val MAX_ITEMS = 30
    private val gson = Gson()

    fun getRecentItems(context: Context): List<SubjectSummary> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SEARCH_ITEMS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SubjectSummary>>() {}.type
            gson.fromJson<List<SubjectSummary>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveClickedItem(context: Context, item: SubjectSummary) {
        val current = getRecentItems(context).toMutableList()
        val id = item.subjectId.ifBlank { item.detailPath }
        if (id.isBlank()) return

        // Remove previous instance if exists to bump to front
        current.removeAll { (it.subjectId.ifBlank { it.detailPath }) == id }
        current.add(0, item)

        val trimmed = if (current.size > MAX_ITEMS) current.take(MAX_ITEMS) else current
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SEARCH_ITEMS, gson.toJson(trimmed)).apply()
    }

    fun removeItem(context: Context, item: SubjectSummary) {
        val current = getRecentItems(context).toMutableList()
        val id = item.subjectId.ifBlank { item.detailPath }
        current.removeAll { (it.subjectId.ifBlank { it.detailPath }) == id }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SEARCH_ITEMS, gson.toJson(current)).apply()
    }

    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SEARCH_ITEMS).apply()
    }
}
