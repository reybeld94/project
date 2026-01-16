package com.reybel.ellentv.data.repo

import android.util.Log
import com.reybel.ellentv.data.api.ApiClient
import com.reybel.ellentv.data.api.ApiService
import com.reybel.ellentv.data.api.CollectionItemsResponse
import com.reybel.ellentv.data.api.CollectionOut
import com.reybel.ellentv.data.api.OnDemandSearchResponse
import com.reybel.ellentv.data.api.ProviderCategoryOut
import com.reybel.ellentv.data.api.ProviderOut
import com.reybel.ellentv.data.api.SeriesListResponse
import com.reybel.ellentv.data.api.SeriesSeasonsResponse
import com.reybel.ellentv.data.api.VodListResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class VodRepo {
    private val api = ApiClient.retrofit.create(ApiService::class.java)

    suspend fun fetchProviders(): List<ProviderOut> {
        return api.getProviders()
    }

    suspend fun fetchVodCategories(providerId: String): List<ProviderCategoryOut> {
        return api.getProviderCategories(
            providerId = providerId,
            catType = "vod",
            activeOnly = true
        ).sortedBy { it.name.lowercase() }
    }

    suspend fun fetchSeriesCategories(providerId: String): List<ProviderCategoryOut> {
        return api.getProviderCategories(
            providerId = providerId,
            catType = "series",
            activeOnly = true
        ).sortedBy { it.name.lowercase() }
    }

    suspend fun fetchVodPage(
        providerId: String,
        categoryExtId: Int?,
        limit: Int,
        offset: Int
    ): VodListResponse {
        return api.getVod(
            providerId = providerId,
            categoryExtId = categoryExtId,
            limit = limit,
            offset = offset,
            activeOnly = true,
            approved = true
        )
    }

    suspend fun fetchCollections(
        enabled: Boolean? = true,
        limit: Int = 50,
        offset: Int = 0
    ): List<CollectionOut> {
        return api.getCollections(
            enabled = enabled,
            limit = limit,
            offset = offset
        )
    }

    // Agrega esto dentro de class VodRepo:

    suspend fun searchMovies(
        query: String,
        limit: Int = 30,
        offset: Int = 0
    ): VodListResponse {
        return api.searchVodAll(
            query = query,
            limit = limit,
            offset = offset,
            activeOnly = true,
            synced = true
        )
    }

    suspend fun searchSeries(
        query: String,
        limit: Int = 30,
        offset: Int = 0
    ): SeriesListResponse {
        return api.searchSeriesAll(
            query = query,
            limit = limit,
            offset = offset,
            activeOnly = true
        )
    }

    suspend fun searchAll(
        query: String,
        limit: Int = 30,
        offset: Int = 0
    ): OnDemandSearchResponse = coroutineScope {
        val moviesLimit = limit / 2
        val seriesLimit = limit - moviesLimit
        val moviesOffset = offset / 2
        val seriesOffset = offset / 2

        val moviesDeferred = async { searchMovies(query, moviesLimit, moviesOffset) }
        val seriesDeferred = async { searchSeries(query, seriesLimit, seriesOffset) }

        val movies = moviesDeferred.await()
        val series = seriesDeferred.await()

        OnDemandSearchResponse(
            items = movies.items + series.items,
            totalMovies = movies.total,
            totalSeries = series.total
        )
    }

    suspend fun fetchCollectionItems(
        collectionIdOrSlug: String,
        page: Int = 1,
        staleWhileRevalidate: Boolean = true
    ): CollectionItemsResponse {
        return api.getCollectionItems(
            collectionIdOrSlug = collectionIdOrSlug,
            page = page,
            staleWhileRevalidate = staleWhileRevalidate
        )
    }

    suspend fun fetchSeriesSeasons(seriesId: String): SeriesSeasonsResponse {
        return api.getSeriesSeasons(seriesId)
    }

    suspend fun fetchSeriesEpisodePlayUrl(
        providerId: String,
        episodeId: Int,
        format: String
    ): String {
        return api.getSeriesEpisodePlay(
            providerId = providerId,
            episodeId = episodeId,
            format = format
        ).url
    }

    /**
     * VOD Play URL - SIMPLIFICADO
     *
     * IMPORTANTE: Ya NO hacemos "probe" de la URL porque:
     * 1. El servidor Xtream devuelve 302 redirect con token temporal
     * 2. Cada request consume un token nuevo
     * 3. Si hacemos probe aquí, el token se consume y ExoPlayer recibe uno expirado
     *
     * Solución: Simplemente devolvemos la URL del backend y dejamos que
     * ExoPlayer siga el 302 redirect él mismo (ya lo hace bien con followRedirects=true)
     */
    suspend fun fetchVodPlayUrl(vodId: String): String {
        val response = api.getVodPlayUrl(vodId = vodId, format = null)
        val url = response.url

        Log.d("VOD_REPO", "VOD URL from backend: $url")
        Log.d("VOD_REPO", "ExoPlayer will follow any redirects automatically")

        return url
    }
}
