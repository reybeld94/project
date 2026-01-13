package com.reybel.ellentv.data.api
import com.reybel.ellentv.data.api.EpgGridResponse

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("providers")
    suspend fun getProviders(): List<ProviderOut>

    @GET("live")
    suspend fun getLive(
        @Query("provider_id") providerId: String,
        @Query("approved") approved: Boolean? = true,
        @Query("active_only") activeOnly: Boolean = true,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): LiveListResponse

    @GET("live/{live_id}/play")
    suspend fun getPlayUrl(
        @Path("live_id") liveId: String,
        @Query("format") format: String = "m3u8"
    ): PlayResponse

    @GET("epg/grid")
    suspend fun getEpgGridRaw(
        @Query("provider_id") providerId: String,
        @Query("category_ext_id") categoryExtId: Int? = null,
        @Query("hours") hours: Int = 3,
        @Query("limit_channels") limitChannels: Int = 80,
        @Query("offset_channels") offsetChannels: Int = 0
    ): String


    @GET("epg/grid")
    suspend fun getEpgGrid(
        @Query("provider_id") providerId: String,
        @Query("category_ext_id") categoryExtId: Int? = null,
        @Query("hours") hours: Int = 3,
        @Query("limit_channels") limitChannels: Int = 80,
        @Query("offset_channels") offsetChannels: Int = 0
    ): EpgGridResponse

    @GET("live")
    suspend fun getLiveRaw(
        @Query("provider_id") providerId: String,
        @Query("approved") approved: Boolean? = true,
        @Query("active_only") activeOnly: Boolean = true,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): String

    // ---------- VOD ----------

    @GET("providers/{provider_id}/categories")
    suspend fun getProviderCategories(
        @Path("provider_id") providerId: String,
        @Query("cat_type") catType: String,
        @Query("active_only") activeOnly: Boolean = true
    ): List<ProviderCategoryOut>

    @GET("vod")
    suspend fun getVod(
        @Query("provider_id") providerId: String,
        @Query("category_ext_id") categoryExtId: Int? = null,
        @Query("q") q: String? = null,
        @Query("limit") limit: Int = 60,
        @Query("offset") offset: Int = 0,
        @Query("active_only") activeOnly: Boolean = true,
        @Query("approved") approved: Boolean? = null
    ): VodListResponse

    // 🔧 CORREGIDO: format ahora es nullable - el servidor decide
    @GET("vod/{vod_id}/play")
    suspend fun getVodPlayUrl(
        @Path("vod_id") vodId: String,
        @Query("format") format: String? = null
    ): PlayResponse

    @GET("vod/all")
    suspend fun searchVodAll(
        @Query("q") query: String,
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
        @Query("active_only") activeOnly: Boolean = true,
        @Query("synced") synced: Boolean? = true
    ): VodListResponse  // Ya tienes este modelo, reutilízalo

    // ---------- COLLECTIONS ----------

    @GET("collections")
    suspend fun getCollections(
        @Query("enabled") enabled: Boolean? = true,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): List<CollectionOut>

    @GET("collections/{collection_id_or_slug}/items")
    suspend fun getCollectionItems(
        @Path("collection_id_or_slug") collectionIdOrSlug: String,
        @Query("page") page: Int = 1,
        @Query("stale_while_revalidate") staleWhileRevalidate: Boolean = true
    ): CollectionItemsResponse
}
