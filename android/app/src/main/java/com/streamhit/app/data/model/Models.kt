package com.streamhit.app.data.model

import com.google.gson.annotations.SerializedName

data class ApiResponse<T>(
    @SerializedName("code") val code: Int? = 0,
    @SerializedName("message") val message: String? = null,
    @SerializedName("data") val data: T? = null
)

data class BannerList(
    @SerializedName("list") val list: List<BannerItem> = emptyList()
)

data class BannerItem(
    @SerializedName("subjectId") val subjectIdRaw: String? = null,
    @SerializedName("id") val idRaw: String? = null,
    @SerializedName("title") val title: String = "",
    @SerializedName("content") val content: String? = null,
    @SerializedName("image") val imageRaw: Any? = null,
    @SerializedName("detailPath") val detailPathRaw: String? = null,
    @SerializedName("subjectType") val subjectType: Int = 1,
    @SerializedName("subject") val subject: SubjectDetail? = null
) {
    val subjectId: String
        get() = subjectIdRaw?.ifBlank { null } ?: (idRaw ?: "")

    val detailPath: String
        get() = detailPathRaw?.ifBlank { null } ?: (subject?.detailPath?.ifBlank { null } ?: subjectId)

    val imageUrl: String
        get() = when (imageRaw) {
            is String -> imageRaw
            is Map<*, *> -> (imageRaw["url"] as? String) ?: ""
            else -> ""
        }

    val image: String
        get() = imageUrl
}

data class HomeFeed(
    @SerializedName("items") val itemsRaw: List<HomeSection>? = null,
    @SerializedName("operatingList") val operatingList: List<HomeSection>? = null
) {
    val items: List<HomeSection>
        get() = itemsRaw?.ifEmpty { null } ?: operatingList ?: emptyList()
}

data class HomeSection(
    @SerializedName("type") val type: String = "",
    @SerializedName("title") val title: String? = null,
    @SerializedName("subjects") val subjects: List<SubjectSummary>? = null,
    @SerializedName("banner") val banner: HomeBannerSection? = null
)

data class HomeBannerSection(
    @SerializedName("items") val items: List<BannerItem> = emptyList()
)

data class SubjectSummary(
    @SerializedName("subjectId") val subjectIdRaw: String? = null,
    @SerializedName("id") val idRaw: String? = null,
    @SerializedName("title") val title: String = "",
    @SerializedName("cover") val coverRaw: Any? = null,
    @SerializedName("imdbRatingValue") val imdbRatingValueRaw: Any? = "7.5",
    @SerializedName("releaseDate") val releaseDate: String? = null,
    @SerializedName("genre") val genreRaw: Any? = null,
    @SerializedName("detailPath") val detailPathRaw: String? = null,
    @SerializedName("subjectType") val subjectType: Int = 1,
    @SerializedName("hasResource") val hasResource: Boolean? = true,
    @SerializedName("isCam") val isCam: Boolean? = false
) {
    val subjectId: String
        get() = subjectIdRaw?.ifBlank { null } ?: (idRaw ?: "")

    val detailPath: String
        get() = detailPathRaw?.ifBlank { null } ?: subjectId

    val imdbRatingValue: String
        get() = when (imdbRatingValueRaw) {
            is String -> imdbRatingValueRaw
            is Number -> imdbRatingValueRaw.toString()
            else -> "7.5"
        }

    val coverUrl: String
        get() = when (coverRaw) {
            is String -> coverRaw
            is Map<*, *> -> (coverRaw["url"] as? String) ?: ""
            else -> ""
        }

    val genres: String
        get() = when (genreRaw) {
            is String -> genreRaw
            is List<*> -> genreRaw.filterIsInstance<String>().joinToString(", ")
            else -> "Action"
        }

    val releaseYear: String
        get() = releaseDate?.split("-")?.firstOrNull() ?: "2026"
}

data class FilterRequest(
    @SerializedName("page") val page: Int = 1,
    @SerializedName("pageSize") val pageSize: Int = 18,
    @SerializedName("subjectType") val subjectType: Int = 1,
    @SerializedName("genre") val genre: String = "All",
    @SerializedName("sort") val sort: String = "recommend",
    @SerializedName("year") val year: String = "All"
)

data class FilterResponse(
    @SerializedName("items") val items: List<SubjectSummary>? = null
)

data class SearchRequest(
    @SerializedName("keyword") val keyword: String = "",
    @SerializedName("page") val page: Int = 1,
    @SerializedName("perPage") val perPage: Int = 24,
    @SerializedName("subjectType") val subjectType: Int = 0
)

data class SearchResponse(
    @SerializedName("items") val items: List<SubjectSummary>? = null
)

data class SubjectDetail(
    @SerializedName("subjectId") val subjectIdRaw: String? = null,
    @SerializedName("id") val idRaw: String? = null,
    @SerializedName("title") val title: String = "",
    @SerializedName("cover") val coverRaw: Any? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("genre") val genreRaw: Any? = null,
    @SerializedName("imdbRatingValue") val imdbRatingValueRaw: Any? = "7.5",
    @SerializedName("releaseDate") val releaseDate: String? = null,
    @SerializedName("countryName") val countryName: String? = "USA",
    @SerializedName("duration") val durationRaw: Any? = null,
    @SerializedName("subjectType") val subjectType: Int = 1,
    @SerializedName("detailPath") val detailPathRaw: String? = null,
    @SerializedName("seasons") val seasons: List<SeasonInfo>? = null,
    @SerializedName("dubs") val dubs: List<DubInfo>? = null
) {
    val subjectId: String
        get() = subjectIdRaw?.ifBlank { null } ?: (idRaw ?: "")

    val detailPath: String
        get() = detailPathRaw?.ifBlank { null } ?: subjectId

    val imdbRatingValue: String
        get() = when (imdbRatingValueRaw) {
            is String -> imdbRatingValueRaw
            is Number -> imdbRatingValueRaw.toString()
            else -> "7.5"
        }

    val duration: String
        get() = when (durationRaw) {
            is String -> durationRaw
            is Number -> {
                val sec = durationRaw.toLong()
                val min = sec / 60
                if (min > 0) "${min} min" else "${sec}s"
            }
            else -> "120 min"
        }

    val coverUrl: String
        get() = when (coverRaw) {
            is String -> coverRaw
            is Map<*, *> -> (coverRaw["url"] as? String) ?: ""
            else -> ""
        }

    val genres: String
        get() = when (genreRaw) {
            is String -> genreRaw
            is List<*> -> genreRaw.filterIsInstance<String>().joinToString(", ")
            else -> "Movie"
        }
}

data class SeasonInfo(
    @SerializedName("se") val seRaw: Int? = null,
    @SerializedName("seasonNumber") val seasonNumberRaw: Int? = null,
    @SerializedName("maxEp") val maxEpRaw: Int? = null,
    @SerializedName("episodeCount") val episodeCountRaw: Int? = null,
    @SerializedName("allEp") val allEpRaw: String? = null,
    @SerializedName("episodes") val episodesRaw: List<EpisodeInfo>? = null
) {
    val seasonNumber: Int
        get() = seRaw ?: seasonNumberRaw ?: 1

    val se: Int
        get() = seasonNumber

    val maxEp: Int
        get() = maxEpRaw ?: episodeCountRaw ?: (episodesRaw?.size ?: 1)

    val allEp: String
        get() = allEpRaw ?: (1..maxEp).joinToString(",")

    val episodes: List<EpisodeInfo>
        get() {
            if (!episodesRaw.isNullOrEmpty()) return episodesRaw
            val count = maxEp.coerceAtLeast(1)
            return (1..count).map { epNum ->
                EpisodeInfo(episodeNumberRaw = epNum, title = "Episode $epNum")
            }
        }
}

data class EpisodeInfo(
    @SerializedName("ep") val epRaw: Int? = null,
    @SerializedName("episodeNumber") val episodeNumberRaw: Int? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("detailPath") val detailPath: String? = null
) {
    val episodeNumber: Int
        get() = epRaw ?: episodeNumberRaw ?: 1
}

data class DubInfo(
    @SerializedName("lanName") val lanNameRaw: String? = null,
    @SerializedName("name") val nameRaw: String? = null,
    @SerializedName("lanCode") val lanCodeRaw: String? = null,
    @SerializedName("language") val languageRaw: String? = null,
    @SerializedName("subjectId") val subjectIdRaw: String? = null,
    @SerializedName("detailPath") val detailPathRaw: String? = null,
    @SerializedName("original") val original: Boolean? = false
) {
    val name: String
        get() = lanNameRaw?.ifBlank { null } ?: (nameRaw?.ifBlank { null } ?: "Audio Dub")

    val lanName: String
        get() = name

    val language: String
        get() = lanCodeRaw?.ifBlank { null } ?: (languageRaw?.ifBlank { null } ?: name)

    val subjectId: String
        get() = subjectIdRaw ?: ""

    val detailPath: String
        get() = detailPathRaw ?: ""
}

data class ResourceList(
    @SerializedName("list") val list: List<StreamResource> = emptyList()
)

data class StreamResource(
    @SerializedName("resourceId") val resourceId: String = "",
    @SerializedName("resourceLink") val resourceLink: String = "",
    @SerializedName("url") val rawUrl: String? = null,
    @SerializedName("resolution") val resolution: Int? = 720,
    @SerializedName("size") val sizeRaw: Any? = null,
    @SerializedName("quality") val quality: String? = "HD",
    @SerializedName("format") val format: String? = "mp4"
) {
    val url: String
        get() {
            val link = if (resourceLink.isNotBlank()) resourceLink else (rawUrl ?: "")
            return when {
                link.startsWith("http://") || link.startsWith("https://") -> link
                link.startsWith("/") -> com.streamhit.app.data.api.RetrofitClient.BASE_URL.trimEnd('/') + link
                link.isNotBlank() -> com.streamhit.app.data.api.RetrofitClient.BASE_URL.trimEnd('/') + "/" + link
                else -> ""
            }
        }

    val displayResolution: String
        get() = when (resolution) {
            1080 -> "1080p FHD"
            720 -> "720p HD"
            480 -> "480p SD"
            360 -> "360p"
            else -> if (resolution != null && resolution > 0) "${resolution}p" else "HD"
        }

    val displaySize: String
        get() {
            val bytes = when (sizeRaw) {
                is Number -> sizeRaw.toLong()
                is String -> sizeRaw.toLongOrNull() ?: 0L
                else -> 0L
            }
            return if (bytes > 0) {
                val mb = bytes / (1024 * 1024)
                if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else "$mb MB"
            } else ""
        }
}

data class LiveSportsResponse(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("list") val list: List<LiveSportItem> = emptyList()
)

data class LiveSportItem(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("match_title") val matchTitleRaw: String? = null,
    @SerializedName("league") val league: String = "",
    @SerializedName("match_time") val matchTime: String = "",
    @SerializedName("logo") val logo: String = "",
    @SerializedName("team1_name") val team1NameRaw: String? = null,
    @SerializedName("team1Name") val team1NameCamel: String? = null,
    @SerializedName("team2_name") val team2NameRaw: String? = null,
    @SerializedName("team2Name") val team2NameCamel: String? = null,
    @SerializedName("team1_logo") val team1LogoRaw: String? = null,
    @SerializedName("team1Logo") val team1LogoCamel: String? = null,
    @SerializedName("team2_logo") val team2LogoRaw: String? = null,
    @SerializedName("team2Logo") val team2LogoCamel: String? = null,
    @SerializedName("stream_links") val streamLinksRaw: Any? = null,
    @SerializedName("streamLinks") val streamLinksList: List<StreamLinkItem>? = null
) {
    val matchTitle: String
        get() = title.ifBlank { matchTitleRaw ?: "Live Match" }

    val team1Name: String
        get() = team1NameCamel ?: team1NameRaw ?: ""

    val team2Name: String
        get() = team2NameCamel ?: team2NameRaw ?: ""

    val team1Logo: String
        get() = team1LogoCamel ?: team1LogoRaw ?: ""

    val team2Logo: String
        get() = team2LogoCamel ?: team2LogoRaw ?: ""

    val streamLinks: List<StreamLinkItem>
        get() {
            if (!streamLinksList.isNullOrEmpty()) return streamLinksList
            return when (streamLinksRaw) {
                is List<*> -> streamLinksRaw.mapNotNull { it as? Map<*, *> }.map {
                    StreamLinkItem(
                        label = (it["label"] as? String) ?: "Server",
                        url = (it["url"] as? String) ?: ""
                    )
                }
                else -> emptyList()
            }
        }
}

data class LiveTvResponse(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("list") val list: List<LiveTvChannel> = emptyList()
)

data class LiveTvChannel(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("logo") val logo: String = "",
    @SerializedName("category") val category: String = "General",
    @SerializedName("servers") val servers: List<StreamLinkItem> = emptyList(),
    @SerializedName("streamLinks") val streamLinks: List<StreamLinkItem> = emptyList()
) {
    val activeServers: List<StreamLinkItem>
        get() = if (servers.isNotEmpty()) servers else streamLinks
}

data class StreamLinkItem(
    @SerializedName("label") val label: String = "Server 1",
    @SerializedName("url") val url: String = ""
)

// Version Control model
data class AppVersionResponse(
    @SerializedName("version") val version: AppVersionInfo? = null
)

data class AppVersionInfo(
    @SerializedName("versionCode") val versionCode: Int = 1,
    @SerializedName("versionName") val versionName: String = "1.0.0",
    @SerializedName("apkUrl") val apkUrl: String = "",
    @SerializedName("mustUpdate") val mustUpdate: Boolean = false,
    @SerializedName("releaseNotes") val releaseNotes: String? = null
)
