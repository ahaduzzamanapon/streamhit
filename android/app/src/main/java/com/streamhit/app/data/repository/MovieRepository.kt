package com.streamhit.app.data.repository

import com.streamhit.app.data.api.RetrofitClient
import com.streamhit.app.data.model.*

class MovieRepository {

    private val api = RetrofitClient.apiService

    suspend fun getBanners(): List<BannerItem> {
        return try {
            val response = api.getBanners()
            if (response.isSuccessful) {
                val list = response.body()?.data?.list ?: emptyList()
                list.filter { !com.streamhit.app.util.AdultContentFilter.isAdult(it.title, it.subject?.genres, it.detailPath) }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getHomeFeed(): List<HomeSection> {
        return try {
            val response = api.getHomeFeed()
            if (response.isSuccessful) {
                val sections = response.body()?.data?.items ?: emptyList()
                sections.mapNotNull { section ->
                    val cleanSubjects = section.subjects?.filter { 
                        !com.streamhit.app.util.AdultContentFilter.isAdult(it.title, it.genres, it.detailPath) 
                    }
                    val cleanBanner = section.banner?.items?.filter {
                        !com.streamhit.app.util.AdultContentFilter.isAdult(it.title, it.subject?.genres, it.detailPath)
                    }
                    if ((cleanSubjects != null && cleanSubjects.isNotEmpty()) || (cleanBanner != null && cleanBanner.isNotEmpty())) {
                        section.copy(
                            subjects = cleanSubjects,
                            banner = if (cleanBanner != null) section.banner.copy(items = cleanBanner) else section.banner
                        )
                    } else null
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun filterContent(
        page: Int = 1,
        subjectType: Int = 1,
        genre: String = "All",
        sort: String = "recommend",
        year: String = "All"
    ): List<SubjectSummary> {
        return try {
            val response = api.filterContent(FilterRequest(page, 24, subjectType, genre, sort, year))
            if (response.isSuccessful) {
                val items = response.body()?.data?.items ?: emptyList()
                items.filter { !com.streamhit.app.util.AdultContentFilter.isAdult(it.title, it.genres, it.detailPath) }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getDetail(detailPath: String, subjectId: String = ""): SubjectDetail? {
        return try {
            val response = api.getDetail(detailPath, subjectId)
            if (response.isSuccessful) response.body()?.data else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getStreamingResources(
        subjectId: String,
        detailPath: String,
        season: Int = 1,
        episode: Int = 1
    ): List<StreamResource> {
        return try {
            val response = api.getResource(subjectId, detailPath, season, episode)
            if (response.isSuccessful) {
                response.body()?.data?.list ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun search(query: String, page: Int = 1): List<SubjectSummary> {
        return try {
            val response = api.search(SearchRequest(keyword = query, page = page))
            if (response.isSuccessful) {
                val items = response.body()?.data?.items ?: emptyList()
                items.filter { !com.streamhit.app.util.AdultContentFilter.isAdult(it.title, it.genres, it.detailPath) }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getLiveSports(): List<LiveSportItem> {
        return try {
            val response = api.getLiveSports()
            if (response.isSuccessful) response.body()?.list ?: emptyList() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getLiveTvChannels(): List<LiveTvChannel> {
        return try {
            val response = api.getLiveTvChannels()
            if (response.isSuccessful) response.body()?.list ?: emptyList() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun checkAppVersion(): AppVersionInfo? {
        return try {
            val response = api.getAppVersion()
            if (response.isSuccessful) response.body()?.version else null
        } catch (e: Exception) {
            null
        }
    }
}
