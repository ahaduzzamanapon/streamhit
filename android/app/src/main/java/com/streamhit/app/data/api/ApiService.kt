package com.streamhit.app.data.api

import com.streamhit.app.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @GET("api/banners")
    suspend fun getBanners(): Response<ApiResponse<BannerList>>

    @GET("api/home")
    suspend fun getHomeFeed(
        @Query("page") page: Int = 1,
        @Query("tabId") tabId: Int = 0
    ): Response<ApiResponse<HomeFeed>>

    @POST("api/filter")
    suspend fun filterContent(
        @Body request: FilterRequest
    ): Response<ApiResponse<FilterResponse>>

    @GET("api/detail")
    suspend fun getDetail(
        @Query("detailPath") detailPath: String,
        @Query("subjectId") subjectId: String = ""
    ): Response<ApiResponse<SubjectDetail>>

    @GET("api/resource")
    suspend fun getResource(
        @Query("subjectId") subjectId: String,
        @Query("detailPath") detailPath: String,
        @Query("se") season: Int = 1,
        @Query("ep") episode: Int = 1,
        @Query("t") timestamp: Long = System.currentTimeMillis()
    ): Response<ApiResponse<ResourceList>>

    @POST("api/search")
    suspend fun search(
        @Body request: SearchRequest
    ): Response<ApiResponse<SearchResponse>>

    @GET("api/sports/live")
    suspend fun getLiveSports(): Response<LiveSportsResponse>

    @GET("api/tv/channels")
    suspend fun getLiveTvChannels(): Response<LiveTvResponse>

    // In-app Version Control
    @GET("api/app/version")
    suspend fun getAppVersion(): Response<AppVersionResponse>

    // Captions & Subtitles
    @GET("api/captions")
    suspend fun getCaptions(
        @Query("subjectId") subjectId: String,
        @Query("detailPath") detailPath: String = "",
        @Query("se") season: Int = 1,
        @Query("ep") episode: Int = 1
    ): Response<CaptionsResponse>

    // Telemetry & Device Heartbeat
    @POST("api/app/heartbeat")
    suspend fun sendHeartbeat(@Body payload: Map<String, String>): Response<Map<String, Any>>

    // FCM Push Notification Token Registration
    @POST("api/fcm/register")
    suspend fun registerFcmToken(@Body payload: Map<String, String>): Response<Map<String, Any>>
}
