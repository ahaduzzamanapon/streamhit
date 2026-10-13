package com.streamhit.app.data.local

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class DownloadItem(
    val downloadId: Long,
    val subjectId: String,
    val detailPath: String,
    val title: String,
    val coverUrl: String,
    val quality: String,
    val downloadUrl: String,
    val localFilePath: String,
    var totalBytes: Long = 0L,
    var downloadedBytes: Long = 0L,
    var status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val isSeries: Boolean = false,
    val seriesTitle: String = "",
    val seasonNum: Int = 1,
    val episodeNum: Int = 1,
    val addedTime: Long = System.currentTimeMillis()
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

    val formattedSize: String
        get() {
            val bytes = if (totalBytes > 0) totalBytes else downloadedBytes
            return formatFileSize(bytes)
        }

    val playUri: String
        get() {
            val file = File(localFilePath)
            return if (file.exists() && file.length() > 0) {
                Uri.fromFile(file).toString()
            } else {
                downloadUrl
            }
        }

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 MB"
            val df = DecimalFormat("#.0")
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> "${df.format(gb)}GB"
                mb >= 1.0 -> "${df.format(mb)}MB"
                else -> "${df.format(kb)}KB"
            }
        }
    }
}

object MediaDownloadManager {
    private const val PREF_NAME = "streamhit_downloads_pref"
    private const val KEY_DOWNLOADS = "download_items_json"
    private val gson = Gson()

    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<Long, Job>()

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getAllDownloads(context: Context): List<DownloadItem> {
        return try {
            val json = getPrefs(context).getString(KEY_DOWNLOADS, null) ?: return emptyList()
            val type = object : TypeToken<List<DownloadItem>>() {}.type
            gson.fromJson<List<DownloadItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun saveDownloads(context: Context, items: List<DownloadItem>) {
        try {
            val json = gson.toJson(items)
            getPrefs(context).edit().putString(KEY_DOWNLOADS, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun updateItemProgress(context: Context, item: DownloadItem) {
        val list = getAllDownloads(context).toMutableList()
        val idx = list.indexOfFirst { it.downloadId == item.downloadId }
        if (idx >= 0) {
            list[idx].downloadedBytes = item.downloadedBytes
            list[idx].totalBytes = item.totalBytes
            list[idx].status = item.status
            saveDownloads(context, list)
        }
    }

    @Synchronized
    fun updateStatus(context: Context, downloadId: Long, status: DownloadStatus) {
        val list = getAllDownloads(context).toMutableList()
        val idx = list.indexOfFirst { it.downloadId == downloadId }
        if (idx >= 0) {
            list[idx].status = status
            saveDownloads(context, list)
        }
    }

    @Synchronized
    fun startDownload(
        context: Context,
        subjectId: String,
        detailPath: String,
        title: String,
        coverUrl: String,
        quality: String,
        downloadUrl: String,
        isSeries: Boolean = false,
        seriesTitle: String = "",
        seasonNum: Int = 1,
        episodeNum: Int = 1
    ): Long {
        return try {
            val fullDownloadUrl = when {
                downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://") -> downloadUrl
                downloadUrl.startsWith("/") -> "https://nxly.online$downloadUrl"
                else -> "https://nxly.online/$downloadUrl"
            }

            val downloadId = System.currentTimeMillis() + Random.nextLong(1000)
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val fileName = "${cleanTitle}_${quality}_${downloadId}.mp4"

            // Store in app-internal dedicated directory (zero external gallery/device pollution)
            val targetDir = File(context.filesDir, "in_app_downloads")
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, fileName)

            val item = DownloadItem(
                downloadId = downloadId,
                subjectId = subjectId,
                detailPath = detailPath,
                title = title,
                coverUrl = coverUrl,
                quality = quality,
                downloadUrl = fullDownloadUrl,
                localFilePath = targetFile.absolutePath,
                status = DownloadStatus.DOWNLOADING,
                isSeries = isSeries,
                seriesTitle = if (seriesTitle.isNotBlank()) seriesTitle else title,
                seasonNum = seasonNum,
                episodeNum = episodeNum,
                addedTime = System.currentTimeMillis()
            )

            val list = getAllDownloads(context).toMutableList()
            if (isSeries) {
                list.removeAll { it.subjectId == subjectId && it.seasonNum == seasonNum && it.episodeNum == episodeNum && it.quality == quality }
            } else {
                list.removeAll { it.subjectId == subjectId && it.quality == quality }
            }
            list.add(0, item)
            saveDownloads(context, list)

            executeDownload(context.applicationContext, item, targetFile)

            downloadId
        } catch (e: Exception) {
            e.printStackTrace()
            -1L
        }
    }

    private fun executeDownload(context: Context, item: DownloadItem, targetFile: File) {
        val job = downloadScope.launch {
            try {
                var existingLength = if (targetFile.exists()) targetFile.length() else 0L

                val requestBuilder = Request.Builder()
                    .url(item.downloadUrl)
                    .header("User-Agent", "okhttp/4.12.0")
                    .header("Accept", "*/*")

                if (existingLength > 0L) {
                    requestBuilder.header("Range", "bytes=$existingLength-")
                }

                val request = requestBuilder.build()
                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful && response.code != 206) {
                    if (response.code == 416) {
                        targetFile.delete()
                        existingLength = 0L
                        val freshReq = Request.Builder()
                            .url(item.downloadUrl)
                            .header("User-Agent", "okhttp/4.12.0")
                            .header("Accept", "*/*")
                            .build()
                        val freshResp = okHttpClient.newCall(freshReq).execute()
                        if (!freshResp.isSuccessful) {
                            updateStatus(context, item.downloadId, DownloadStatus.FAILED)
                            freshResp.close()
                            return@launch
                        }
                        writeStreamToFile(context, item, targetFile, freshResp, 0L)
                    } else {
                        updateStatus(context, item.downloadId, DownloadStatus.FAILED)
                        response.close()
                        return@launch
                    }
                } else {
                    writeStreamToFile(context, item, targetFile, response, existingLength)
                }
            } catch (e: CancellationException) {
                // Paused or cancelled
            } catch (e: Exception) {
                e.printStackTrace()
                updateStatus(context, item.downloadId, DownloadStatus.FAILED)
            } finally {
                activeJobs.remove(item.downloadId)
            }
        }
        activeJobs[item.downloadId] = job
    }

    private fun writeStreamToFile(
        context: Context,
        item: DownloadItem,
        targetFile: File,
        response: okhttp3.Response,
        existingLength: Long
    ) {
        val body = response.body ?: run {
            updateStatus(context, item.downloadId, DownloadStatus.FAILED)
            return
        }

        val contentLength = body.contentLength()
        val total = if (contentLength > 0) existingLength + contentLength else item.totalBytes
        item.totalBytes = total
        item.downloadedBytes = existingLength
        item.status = DownloadStatus.DOWNLOADING
        updateItemProgress(context, item)

        val buffer = ByteArray(32 * 1024)
        var bytesCopied = existingLength
        var lastUpdateMs = System.currentTimeMillis()

        val inputStream = body.byteStream()
        val outputStream = FileOutputStream(targetFile, existingLength > 0L)

        try {
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                bytesCopied += read
                item.downloadedBytes = bytesCopied

                val now = System.currentTimeMillis()
                if (now - lastUpdateMs >= 350L) {
                    lastUpdateMs = now
                    updateItemProgress(context, item)
                }
            }
            outputStream.flush()

            if (targetFile.exists() && targetFile.length() > 0) {
                item.status = DownloadStatus.COMPLETED
                item.totalBytes = targetFile.length()
                item.downloadedBytes = targetFile.length()
                updateItemProgress(context, item)
            } else {
                updateStatus(context, item.downloadId, DownloadStatus.FAILED)
            }
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
            try { outputStream.close() } catch (_: Exception) {}
            response.close()
        }
    }

    @Synchronized
    fun resumeDownload(context: Context, downloadId: Long) {
        val items = getAllDownloads(context)
        val target = items.firstOrNull { it.downloadId == downloadId } ?: return
        if (target.status == DownloadStatus.COMPLETED) return

        activeJobs[downloadId]?.cancel()
        target.status = DownloadStatus.DOWNLOADING
        updateItemProgress(context, target)

        val targetFile = File(target.localFilePath)
        executeDownload(context.applicationContext, target, targetFile)
    }

    @Synchronized
    fun pauseDownload(context: Context, downloadId: Long) {
        activeJobs[downloadId]?.cancel()
        activeJobs.remove(downloadId)
        updateStatus(context, downloadId, DownloadStatus.PAUSED)
    }

    @Synchronized
    fun refreshDownloadStatuses(context: Context): List<DownloadItem> {
        val items = getAllDownloads(context).toMutableList()
        var changed = false

        for (item in items) {
            val file = File(item.localFilePath)
            if (item.status == DownloadStatus.COMPLETED) {
                if (!file.exists() || file.length() == 0L) {
                    item.status = DownloadStatus.FAILED
                    changed = true
                }
            } else if (item.status == DownloadStatus.DOWNLOADING) {
                if (!activeJobs.containsKey(item.downloadId)) {
                    // Re-attach active job if it was downloading but app restarted
                    executeDownload(context.applicationContext, item, file)
                }
            }
        }

        if (changed) {
            saveDownloads(context, items)
        }
        return items
    }

    @Synchronized
    fun deleteDownload(context: Context, downloadId: Long) {
        try {
            activeJobs[downloadId]?.cancel()
            activeJobs.remove(downloadId)

            val items = getAllDownloads(context).toMutableList()
            val target = items.firstOrNull { it.downloadId == downloadId }
            if (target != null) {
                val file = File(target.localFilePath)
                if (file.exists()) file.delete()
                items.remove(target)
                saveDownloads(context, items)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAvailableStorageFormatted(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            val gb = bytesAvailable / (1024.0 * 1024.0 * 1024.0)
            val df = DecimalFormat("#.0")
            "${df.format(gb)}GB available"
        } catch (e: Exception) {
            "Storage available"
        }
    }
}
