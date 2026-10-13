package com.streamhit.app.data.local

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
object MediaCacheManager {

    private const val MAX_CACHE_SIZE_BYTES = 250L * 1024L * 1024L // 250 MB LRU budget
    private const val CACHE_DIR_NAME = "stream_media_cache"

    @Volatile
    private var simpleCache: SimpleCache? = null

    @Volatile
    private var cacheDataSourceFactory: DataSource.Factory? = null

    @Volatile
    private var okHttpClient: OkHttpClient? = null

    @Synchronized
    fun getOkHttpClient(): OkHttpClient {
        if (okHttpClient == null) {
            val builder = OkHttpClient.Builder()
                .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    val req = chain.request()
                    val host = req.url.host.lowercase()
                    val reqBuilder = req.newBuilder()
                        .header("User-Agent", "okhttp/4.12.0")
                        .header("Accept", "*/*")
                        .header("Accept-Encoding", "identity")

                    // bcdnxw servers require 123movienow.cc Origin & Referer headers
                    if (host.contains("bcdnxw")) {
                        reqBuilder.header("Origin", "https://123movienow.cc")
                        reqBuilder.header("Referer", "https://123movienow.cc/")
                    }
                    chain.proceed(reqBuilder.build())
                }

            com.streamhit.app.data.api.RetrofitClient.getCustomSslSocketFactory()?.let { (socketFactory, trustManager) ->
                builder.sslSocketFactory(socketFactory, trustManager)
            }

            okHttpClient = builder.build()
        }
        return okHttpClient!!
    }

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
            val httpDataSourceFactory = OkHttpDataSource.Factory(getOkHttpClient())

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
