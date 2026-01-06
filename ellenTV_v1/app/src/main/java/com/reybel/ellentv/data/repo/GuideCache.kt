package com.reybel.ellentv.data.repo

import android.content.Context
import com.reybel.ellentv.data.api.EpgGridResponse
import com.reybel.ellentv.data.api.LiveItem
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GuideCachePayload(
    val providerId: String?,
    val channels: List<LiveItem>,
    val epg: EpgGridResponse?,
    val selectedLiveId: String?,
    val savedAt: Long
)

class GuideCache(context: Context) {
    private val appContext = context.applicationContext
    private val cacheFile: File = File(appContext.cacheDir, "guide_cache.json")

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(GuideCachePayload::class.java)

    suspend fun load(): GuideCachePayload? {
        return runCatching {
            if (!cacheFile.exists()) return null
            val json = withContext(Dispatchers.IO) { cacheFile.readText() }
            withContext(Dispatchers.Default) { adapter.fromJson(json) }
        }.getOrNull()
    }

    suspend fun save(payload: GuideCachePayload) {
        runCatching {
            val json = withContext(Dispatchers.Default) { adapter.toJson(payload) }
            withContext(Dispatchers.IO) { cacheFile.writeText(json) }
        }
    }
}
