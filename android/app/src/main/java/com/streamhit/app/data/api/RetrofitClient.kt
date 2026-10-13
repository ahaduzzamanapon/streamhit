package com.streamhit.app.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    const val BASE_URL = "https://nxly.online/"

    private fun safeHeader(builder: okhttp3.Request.Builder, name: String, value: String?) {
        if (value.isNullOrBlank()) return
        try {
            val sanitized = value.filter { it in ' '..'~' }.trim()
            if (sanitized.isNotEmpty()) {
                builder.header(name, sanitized)
            }
        } catch (_: Exception) {}
    }

    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                    .header("User-Agent", "StreamfitMobile/1.0.8 (Android)")
                    .header("Accept", "application/json")

                safeHeader(requestBuilder, "X-App-Device-Id", com.streamhit.app.data.analytics.AppAnalyticsManager.cachedDeviceId)
                safeHeader(requestBuilder, "X-App-Model", com.streamhit.app.data.analytics.AppAnalyticsManager.getDeviceModel())
                safeHeader(requestBuilder, "X-App-OS", com.streamhit.app.data.analytics.AppAnalyticsManager.getOsVersion())
                safeHeader(requestBuilder, "X-App-Version", com.streamhit.app.data.analytics.AppAnalyticsManager.getAppVersion())

                chain.proceed(requestBuilder.build())
            }
            .addInterceptor(logging)
            .build()
    }

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}


