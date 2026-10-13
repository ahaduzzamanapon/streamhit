package com.streamhit.app.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    const val BASE_URL = "https://nxly.online/"

    private val okHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("User-Agent", "StreamfitMobile/1.0.8 (Android)")
                    .header("Accept", "application/json")

                com.streamhit.app.data.analytics.AppAnalyticsManager.cachedDeviceId?.let {
                    builder.header("X-App-Device-Id", it)
                }
                builder.header("X-App-Model", com.streamhit.app.data.analytics.AppAnalyticsManager.getDeviceModel())
                builder.header("X-App-OS", com.streamhit.app.data.analytics.AppAnalyticsManager.getOsVersion())
                builder.header("X-App-Version", com.streamhit.app.data.analytics.AppAnalyticsManager.getAppVersion())

                chain.proceed(builder.build())
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
