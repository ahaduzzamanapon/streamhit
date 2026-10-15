package com.streamhit.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.streamhit.app.BuildConfig
import com.streamhit.app.data.api.RetrofitClient
import com.streamhit.app.data.model.AppVersionInfo
import com.streamhit.app.data.repository.MovieRepository

object AppUpdateManager {

    private val repository = MovieRepository()

    suspend fun checkForUpdate(): AppVersionInfo? {
        val serverVersion = repository.checkAppVersion() ?: return null
        return if (serverVersion.versionCode > BuildConfig.VERSION_CODE) {
            serverVersion
        } else {
            null
        }
    }

    fun getResolvedUrl(versionInfo: AppVersionInfo): String {
        val rawUrl = versionInfo.apkUrl.trim()
        return if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            RetrofitClient.BASE_URL.trimEnd('/') + "/" + rawUrl.trimStart('/')
        }
    }

    fun openBrowserDownload(context: Context, versionInfo: AppVersionInfo) {
        val fullUrl = getResolvedUrl(versionInfo)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(context, "Opening download in browser...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open browser: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun startDownloadAndInstall(context: Context, versionInfo: AppVersionInfo) {
        openBrowserDownload(context, versionInfo)
    }
}
