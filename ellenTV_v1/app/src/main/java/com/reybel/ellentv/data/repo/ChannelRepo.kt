package com.reybel.ellentv.data.repo

import com.reybel.ellentv.data.api.ApiClient
import com.reybel.ellentv.data.api.ApiService
import com.reybel.ellentv.data.api.LiveItem
import com.reybel.ellentv.data.api.PlayResponse

class ChannelRepo {

    private val api = ApiClient.retrofit.create(ApiService::class.java)

    suspend fun getDefaultProviderId(): String {
        val providers = api.getProviders()
        val active = providers.firstOrNull { it.isActive } ?: providers.firstOrNull()
        require(active != null) { "No providers found. Create one with POST /providers" }
        return active.id
    }

    suspend fun fetchLive(providerId: String): List<LiveItem> {
        return api.getLive(providerId = providerId, approved = true, activeOnly = true, limit = 100, offset = 0).items
    }

    suspend fun fetchPlayInfo(liveId: String): PlayResponse {
        return api.getPlayUrl(liveId = liveId, format = "m3u8")
    }

    suspend fun fetchPlayUrl(liveId: String): String {
        return fetchPlayInfo(liveId).url
    }
    suspend fun fetchEpgGrid(
        providerId: String,
        hours: Int = 3,
        categoryExtId: Int? = null,
        limitChannels: Int = 80,
        offsetChannels: Int = 0
    ) = api.getEpgGrid(
        providerId = providerId,
        categoryExtId = categoryExtId,
        hours = hours,
        limitChannels = limitChannels,
        offsetChannels = offsetChannels
    )
    suspend fun fetchEpgGridRaw(
        providerId: String,
        hours: Int = 3,
        categoryExtId: Int? = null,
        limitChannels: Int = 80,
        offsetChannels: Int = 0
    ) = api.getEpgGridRaw(
        providerId = providerId,
        categoryExtId = categoryExtId,
        hours = hours,
        limitChannels = limitChannels,
        offsetChannels = offsetChannels
    )
    suspend fun fetchLiveRaw(providerId: String): String {
        return api.getLiveRaw(providerId = providerId, approved = true, activeOnly = true, limit = 100, offset = 0)
    }



}
