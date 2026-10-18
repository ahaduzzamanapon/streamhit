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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder

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

    private val _downloadsFlow = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloadsFlow: StateFlow<List<DownloadItem>> = _downloadsFlow.asStateFlow()

    private var isInitialized = false

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getAllDownloads(context: Context): List<DownloadItem> {
        val list = try {
            val json = getPrefs(context).getString(KEY_DOWNLOADS, null) ?: return emptyList()
            val type = object : TypeToken<List<DownloadItem>>() {}.type
            gson.fromJson<List<DownloadItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (!isInitialized) {
            _downloadsFlow.value = list
            isInitialized = true
        }
        return list
    }

    @Synchronized
    private fun saveDownloads(context: Context, items: List<DownloadItem>) {
        try {
            val json = gson.toJson(items)
            getPrefs(context).edit().putString(KEY_DOWNLOADS, json).apply()
            _downloadsFlow.value = items.toList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var lastPrefSaveTimeMs = 0L

    @Synchronized
    private fun updateItemProgress(context: Context, item: DownloadItem, forcePersist: Boolean = false) {
        val list = (_downloadsFlow.value.ifEmpty { getAllDownloads(context) }).toMutableList()
        val idx = list.indexOfFirst { it.downloadId == item.downloadId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(
                downloadedBytes = item.downloadedBytes,
                totalBytes = item.totalBytes,
                status = item.status
            )
            _downloadsFlow.value = list

            val now = System.currentTimeMillis()
            if (forcePersist || (now - lastPrefSaveTimeMs > 2500L) || item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.FAILED) {
                lastPrefSaveTimeMs = now
                saveDownloads(context, list)
            }
        }
    }

    @Synchronized
    fun updateStatus(context: Context, downloadId: Long, status: DownloadStatus) {
        val list = (_downloadsFlow.value.ifEmpty { getAllDownloads(context) }).toMutableList()
        val idx = list.indexOfFirst { it.downloadId == downloadId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(status = status)
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

            // Store in app-internal dedicated directory
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

            val list = (_downloadsFlow.value.ifEmpty { getAllDownloads(context) }).toMutableList()
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

                val candidateHeaders = listOf(
                    mapOf("Origin" to "https://123movienow.cc", "Referer" to "https://123movienow.cc/", "User-Agent" to "okhttp/4.12.0", "Accept" to "*/*"),
                    mapOf("Origin" to "https://123movienow.cc", "Referer" to "https://123movienow.cc/", "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36", "Accept" to "*/*"),
                    mapOf("User-Agent" to "okhttp/4.12.0", "Accept" to "*/*")
                )

                var response: okhttp3.Response? = null
                var finalExistingLength = existingLength

                // Tier 1: Try candidate headers on direct URL
                for (hdrs in candidateHeaders) {
                    try {
                        val reqBuilder = Request.Builder().url(item.downloadUrl)
                        hdrs.forEach { (k, v) -> reqBuilder.header(k, v) }
                        if (finalExistingLength > 0L) {
                            reqBuilder.header("Range", "bytes=$finalExistingLength-")
                        }
                        val candidateResp = okHttpClient.newCall(reqBuilder.build()).execute()
                        if (candidateResp.isSuccessful || candidateResp.code == 206) {
                            response = candidateResp
                            break
                        } else if (candidateResp.code == 416) {
                            targetFile.delete()
                            finalExistingLength = 0L
                            candidateResp.close()
                            val freshReq = Request.Builder().url(item.downloadUrl)
                            hdrs.forEach { (k, v) -> freshReq.header(k, v) }
                            val freshResp = okHttpClient.newCall(freshReq.build()).execute()
                            if (freshResp.isSuccessful) {
                                response = freshResp
                                break
                            } else {
                                freshResp.close()
                            }
                        } else {
                            candidateResp.close()
                        }
                    } catch (_: Exception) {}
                }

                // Tier 2: Fallback to server streaming proxy /fetch?source_url=...
                if (response == null && !item.downloadUrl.contains("/fetch?source_url=")) {
                    try {
                        val proxyUrl = "https://nxly.online/fetch?source_url=" + URLEncoder.encode(item.downloadUrl, "UTF-8")
                        val reqBuilder = Request.Builder()
                            .url(proxyUrl)
                            .header("User-Agent", "okhttp/4.12.0")
                            .header("Accept", "*/*")
                        if (finalExistingLength > 0L) {
                            reqBuilder.header("Range", "bytes=$finalExistingLength-")
                        }
                        val proxyResp = okHttpClient.newCall(reqBuilder.build()).execute()
                        if (proxyResp.isSuccessful || proxyResp.code == 206) {
                            response = proxyResp
                        } else {
                            proxyResp.close()
                        }
                    } catch (_: Exception) {}
                }

                if (response != null && (response.isSuccessful || response.code == 206)) {
                    writeStreamToFile(context, item, targetFile, response, finalExistingLength)
                } else {
                    updateStatus(context, item.downloadId, DownloadStatus.FAILED)
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
        updateItemProgress(context, item, forcePersist = true)

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
                if (now - lastUpdateMs >= 200L) {
                    lastUpdateMs = now
                    updateItemProgress(context, item, forcePersist = false)
                }
            }
            outputStream.flush()

            if (targetFile.exists() && targetFile.length() > 0) {
                item.status = DownloadStatus.COMPLETED
                item.totalBytes = targetFile.length()
                item.downloadedBytes = targetFile.length()
                updateItemProgress(context, item, forcePersist = true)
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
        val items = _downloadsFlow.value.ifEmpty { getAllDownloads(context) }
        val target = items.firstOrNull { it.downloadId == downloadId } ?: return
        if (target.status == DownloadStatus.COMPLETED) return

        activeJobs[downloadId]?.cancel()
        target.status = DownloadStatus.DOWNLOADING
        updateItemProgress(context, target, forcePersist = true)

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
        val items = (_downloadsFlow.value.ifEmpty { getAllDownloads(context) }).toMutableList()
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
        } else {
            _downloadsFlow.value = items
        }
        return items
    }

    @Synchronized
    fun deleteDownload(context: Context, downloadId: Long) {
        try {
            activeJobs[downloadId]?.cancel()
            activeJobs.remove(downloadId)

            val items = (_downloadsFlow.value.ifEmpty { getAllDownloads(context) }).toMutableList()
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
