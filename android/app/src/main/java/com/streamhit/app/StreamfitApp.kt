package com.streamhit.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.streamhit.app.data.api.RetrofitClient

class StreamfitApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { RetrofitClient.okHttpClient }
            .crossfade(true)
            .build()
    }
}

