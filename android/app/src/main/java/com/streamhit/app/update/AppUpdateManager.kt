package com.streamhit.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.streamhit.app.BuildConfig
import com.streamhit.app.data.api.RetrofitClient
import com.streamhit.app.data.model.AppVersionInfo
import com.streamhit.app.data.repository.MovieRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

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
        val fullUrl = getResolvedUrl(versionInfo)

        // If the URL is an external web link or Google Drive link that is not a direct .apk file
        if (fullUrl.contains("drive.google.com") || fullUrl.contains("/view") || !fullUrl.endsWith(".apk")) {
            openBrowserDownload(context, versionInfo)
            return
        }

        // On Android 8.0+, prompt permission if install unknown apps is disabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                try {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    Toast.makeText(context, "Please allow Streamfit to install updates", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        }

        Toast.makeText(context, "Downloading update v${versionInfo.versionName}...", Toast.LENGTH_SHORT).show()

        // 1. Direct high-speed OkHttp in-app download (bypasses DownloadManager restrictions)
        CoroutineScope(Dispatchers.IO).launch {
            var directDownloadSuccess = false
            val targetDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
            val destinationFile = File(targetDir, "Streamfit-v${versionInfo.versionName}.apk")

            try {
                if (destinationFile.exists()) {
                    destinationFile.delete()
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()

                val request = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", "StreamfitMobile/1.0.10 (Android)")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful && response.body != null) {
                    val body = response.body!!
                    body.byteStream().use { input ->
                        destinationFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (destinationFile.exists() && destinationFile.length() > 500_000) {
                        directDownloadSuccess = true
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Download complete. Starting installation...", Toast.LENGTH_SHORT).show()
                            installApk(context, destinationFile)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AppUpdateManager", "Direct OkHttp download failed: ${e.message}")
            }

            // 2. Fallback to system DownloadManager if direct OkHttp did not succeed
            if (!directDownloadSuccess) {
                withContext(Dispatchers.Main) {
                    startDownloadManagerDownload(context, versionInfo)
                }
            }
        }
    }

    private fun startDownloadManagerDownload(context: Context, versionInfo: AppVersionInfo) {
        val fullUrl = getResolvedUrl(versionInfo)
        try {
            val fileName = "Streamfit-v${versionInfo.versionName}.apk"
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (downloadManager == null) {
                openBrowserDownload(context, versionInfo)
                return
            }

            // Remove any old downloaded version file
            val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val destinationFile = File(targetDir, fileName)
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            val uri = Uri.parse(fullUrl)
            val request = DownloadManager.Request(uri).apply {
                setTitle("Downloading Update v${versionInfo.versionName}")
                setDescription("NXLY Streamhit Update")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadId = downloadManager.enqueue(request)

            // Broadcast receiver for completion
            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(ctxt: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        try {
                            ctxt.unregisterReceiver(this)
                        } catch (_: Exception) {}

                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        var success = false
                        if (cursor != null && cursor.moveToFirst()) {
                            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            if (statusIdx != -1) {
                                val status = cursor.getInt(statusIdx)
                                success = (status == DownloadManager.STATUS_SUCCESSFUL)
                            }
                            cursor.close()
                        }

                        if (success && destinationFile.exists() && destinationFile.length() > 0) {
                            Toast.makeText(ctxt, "Download complete. Starting installation...", Toast.LENGTH_SHORT).show()
                            installApk(ctxt, destinationFile)
                        } else {
                            openBrowserDownload(ctxt, versionInfo)
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }

        } catch (e: Exception) {
            openBrowserDownload(context, versionInfo)
        }
    }

    private fun installApk(context: Context, file: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
