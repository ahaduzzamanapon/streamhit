package com.streamhit.app.data.local

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@OptIn(UnstableApi::class)
object MediaCacheManager {

    private const val MAX_CACHE_SIZE_BYTES = 250L * 1024L * 1024L // 250 MB LRU budget
    private const val CACHE_DIR_NAME = "stream_media_cache"

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var cacheDataSourceFactory: DataSource.Factory? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        if (simpleCache == null) {
            val cacheDir = File(context.applicationContext.cacheDir, CACHE_DIR_NAME)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE_BYTES)
            val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
            simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return simpleCache!!
    }

    @Synchronized
    fun getDataSourceFactory(context: Context): DataSource.Factory {
        if (cacheDataSourceFactory == null) {
            val cache = getCache(context)
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(15000)

            val upstreamFactory = DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory)

            cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
        return cacheDataSourceFactory!!
    }

    fun release() {
        try {
            simpleCache?.release()
            simpleCache = null
            cacheDataSourceFactory = null
        } catch (_: Exception) {}
    }
}
