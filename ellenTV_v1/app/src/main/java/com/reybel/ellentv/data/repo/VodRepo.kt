package com.reybel.ellentv.data.repo

import android.util.Log
import com.reybel.ellentv.data.api.ApiClient
import com.reybel.ellentv.data.api.ApiService
import com.reybel.ellentv.data.api.CollectionItemsResponse
import com.reybel.ellentv.data.api.CollectionOut
import com.reybel.ellentv.data.api.ProviderCategoryOut
import com.reybel.ellentv.data.api.ProviderOut
import com.reybel.ellentv.data.api.VodListResponse

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
