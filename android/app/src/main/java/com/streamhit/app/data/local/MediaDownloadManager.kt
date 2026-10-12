package com.streamhit.app.data.local

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.DecimalFormat

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
            return if (file.exists()) {
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
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val fileName = "${cleanTitle}_${quality}_${System.currentTimeMillis()}.mp4"

            val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val targetFile = File(targetDir, fileName)

            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle(title)
                setDescription("Downloading $quality - Streamfit")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationUri(Uri.fromFile(targetFile))
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val id = dm.enqueue(request)

            val item = DownloadItem(
                downloadId = id,
                subjectId = subjectId,
                detailPath = detailPath,
                title = title,
                coverUrl = coverUrl,
                quality = quality,
                downloadUrl = downloadUrl,
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
            id
        } catch (e: Exception) {
            e.printStackTrace()
            -1L
        }
    }

    @Synchronized
    fun refreshDownloadStatuses(context: Context): List<DownloadItem> {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val items = getAllDownloads(context).toMutableList()
        var changed = false

        for (item in items) {
            if (item.status == DownloadStatus.COMPLETED) {
                // Verify local file still exists
                if (!File(item.localFilePath).exists()) {
                    item.status = DownloadStatus.FAILED
                    changed = true
                }
                continue
            }

            try {
                val query = DownloadManager.Query().setFilterById(item.downloadId)
                val cursor = dm.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                    if (bytesCol != -1) item.downloadedBytes = cursor.getLong(bytesCol)
                    if (totalCol != -1) {
                        val total = cursor.getLong(totalCol)
                        if (total > 0) item.totalBytes = total
                    }

                    if (statusCol != -1) {
                        when (cursor.getInt(statusCol)) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                item.status = DownloadStatus.COMPLETED
                                if (item.totalBytes <= 0 && item.downloadedBytes > 0) {
                                    item.totalBytes = item.downloadedBytes
                                }
                                changed = true
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                item.status = DownloadStatus.PAUSED
                                changed = true
                            }
                            DownloadManager.STATUS_FAILED -> {
                                item.status = DownloadStatus.FAILED
                                changed = true
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                item.status = DownloadStatus.DOWNLOADING
                                changed = true
                            }
                        }
                    }
                    cursor.close()
                } else {
                    // Download query returned nothing, check file existence
                    val file = File(item.localFilePath)
                    if (file.exists() && file.length() > 0) {
                        item.status = DownloadStatus.COMPLETED
                        item.downloadedBytes = file.length()
                        item.totalBytes = file.length()
                    } else {
                        item.status = DownloadStatus.FAILED
                    }
                    changed = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(downloadId)

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
