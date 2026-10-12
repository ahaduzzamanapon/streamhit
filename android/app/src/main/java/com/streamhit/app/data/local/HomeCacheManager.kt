package com.streamhit.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamhit.app.data.model.BannerItem
import com.streamhit.app.data.model.HomeSection

object HomeCacheManager {

    private const val PREF_NAME = "streamhit_home_cache"
    private const val KEY_BANNERS = "cached_banners"
    private const val KEY_SECTIONS = "cached_sections"
    private const val KEY_TIMESTAMP = "cached_timestamp"
    private const val CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000L // 24 Hours (Once a day)

    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isCacheExpired(context: Context): Boolean {
        val prefs = getPrefs(context)
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        val hasBanners = prefs.contains(KEY_BANNERS)
        val hasSections = prefs.contains(KEY_SECTIONS)
        if (!hasBanners || !hasSections || timestamp == 0L) return true
        return (System.currentTimeMillis() - timestamp) > CACHE_EXPIRY_MS
    }

    fun getCachedBanners(context: Context): List<BannerItem> {
        val json = getPrefs(context).getString(KEY_BANNERS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<BannerItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getCachedSections(context: Context): List<HomeSection> {
        val json = getPrefs(context).getString(KEY_SECTIONS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<HomeSection>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveHomeCache(context: Context, banners: List<BannerItem>, sections: List<HomeSection>) {
        if (banners.isEmpty() && sections.isEmpty()) return
        val prefs = getPrefs(context)
        try {
            val bannersJson = gson.toJson(banners)
            val sectionsJson = gson.toJson(sections)
            prefs.edit()
                .putString(KEY_BANNERS, bannersJson)
                .putString(KEY_SECTIONS, sectionsJson)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {}
    }

    fun clearCache(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
