package com.streamhit.app.data.repository

import android.content.Context
import com.streamhit.app.data.local.LiveTvCacheManager
import com.streamhit.app.data.model.LiveTvChannel
import com.streamhit.app.data.model.StreamLinkItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class LiveTvRepository {

    private val m3uUrl = "https://raw.githubusercontent.com/saeidrahmanbd/BDIX-IPTV/main/IPTV-Playlist.m3u"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun getChannels(context: Context): List<LiveTvChannel> = withContext(Dispatchers.IO) {
        val cached = LiveTvCacheManager.getCachedChannels(context)
        val isExpired = LiveTvCacheManager.isCacheExpired(context)

        // If cache is fresh and not empty, return cached directly
        if (!isExpired && cached.isNotEmpty()) {
            return@withContext cached
        }

        // Try fetching fresh data from GitHub M3U
        try {
            val request = Request.Builder()
                .url(m3uUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val content = response.body?.string() ?: ""
                val parsed = parseM3u(content)
                if (parsed.isNotEmpty()) {
                    LiveTvCacheManager.saveChannelsCache(context, parsed)
                    return@withContext parsed
                }
            }
        } catch (_: Exception) {
            // In case of network error, timeout, or link failure: keep previous cache intact!
        }

        // Resilient fallback: return whatever was in cache
        return@withContext cached
    }

    private fun parseM3u(m3uContent: String): List<LiveTvChannel> {
        val lines = m3uContent.lines()
        val rawChannels = mutableListOf<RawChannel>()
        var i = 0

        val groupRegex = Regex("""group-title="([^"]*)"""")
        val logoRegex = Regex("""tvg-logo="([^"]*)"""")
        val nameRegex = Regex("""tvg-name="([^"]*)"""")

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val category = groupRegex.find(line)?.groupValues?.get(1)?.trim()?.ifBlank { "General" } ?: "General"
                val logo = logoRegex.find(line)?.groupValues?.get(1)?.trim() ?: ""

                // Extract channel name
                val tvgName = nameRegex.find(line)?.groupValues?.get(1)?.trim()
                val commaName = line.substringAfterLast(",").trim()
                val channelName = when {
                    !tvgName.isNullOrBlank() -> tvgName
                    commaName.isNotBlank() -> commaName
                    else -> "Live Channel"
                }

                // Next non-empty, non-comment line is the stream URL
                var streamUrl = ""
                var j = i + 1
                while (j < lines.size) {
                    val nextLine = lines[j].trim()
                    if (nextLine.isNotBlank() && !nextLine.startsWith("#")) {
                        streamUrl = nextLine
                        i = j
                        break
                    }
                    j++
                }

                if (streamUrl.isNotBlank()) {
                    rawChannels.add(RawChannel(channelName, logo, category, streamUrl))
                }
            }
            i++
        }

        // Group duplicate channel names as multiple servers
        // User requirement: "ek name e 2 ta chanel thakle ota alada server bole gonno hobe"
        val groupedMap = linkedMapOf<String, MutableList<RawChannel>>()
        for (item in rawChannels) {
            val normKey = item.name.lowercase().trim()
            groupedMap.getOrPut(normKey) { mutableListOf() }.add(item)
        }

        return groupedMap.values.map { list ->
            val first = list.first()
            val bestLogo = list.firstOrNull { it.logoUrl.isNotBlank() }?.logoUrl ?: ""
            val bestCategory = list.firstOrNull { 
                it.category.isNotBlank() && !it.category.equals("Backup", ignoreCase = true) && !it.category.equals("General", ignoreCase = true) 
            }?.category ?: first.category

            val uniqueServers = list.distinctBy { it.streamUrl.trim() }
            val servers = uniqueServers.mapIndexed { index, raw ->
                StreamLinkItem(
                    label = "Server ${index + 1}",
                    url = raw.streamUrl
                )
            }
            LiveTvChannel(
                id = first.name.hashCode().toString(),
                name = first.name,
                logo = bestLogo,
                category = bestCategory,
                servers = servers
            )
        }
    }

    private data class RawChannel(
        val name: String,
        val logoUrl: String,
        val category: String,
        val streamUrl: String
    )
}
