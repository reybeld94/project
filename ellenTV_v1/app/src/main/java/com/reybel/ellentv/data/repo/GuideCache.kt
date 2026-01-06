package com.reybel.ellentv.data.repo

import android.content.Context
import com.reybel.ellentv.data.api.EpgGridResponse
import com.reybel.ellentv.data.api.LiveItem
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

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

    fun load(): GuideCachePayload? {
        return runCatching {
            if (!cacheFile.exists()) return null
            val json = cacheFile.readText()
            adapter.fromJson(json)
        }.getOrNull()
    }

    fun save(payload: GuideCachePayload) {
        runCatching {
            val json = adapter.toJson(payload)
            cacheFile.writeText(json)
        }
    }
}
