package com.reybel.ellentv.data.repo

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PlaybackProgress(
    val contentId: String,
    val contentType: String, // "movie" or "episode"
    val position: Long, // milliseconds
    val duration: Long, // milliseconds
    val seasonNumber: Int? = null, // for episodes
    val episodeNumber: Int? = null, // for episodes
    val title: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val progressPercent: Float
        get() = if (duration > 0) (position.toFloat() / duration.toFloat()) * 100 else 0f

    val shouldResume: Boolean
        get() = position > 5000 && progressPercent < 95 // Resume if watched > 5s and < 95%
}

data class PlaybackProgressPayload(
    val entries: Map<String, PlaybackProgress>
)

class PlaybackProgressCache(context: Context) {
    private val appContext = context.applicationContext
    private val cacheFile: File = File(appContext.filesDir, "playback_progress.json")

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(PlaybackProgressPayload::class.java)

    suspend fun load(): PlaybackProgressPayload {
        return runCatching {
            if (!cacheFile.exists()) return PlaybackProgressPayload(emptyMap())
            val json = withContext(Dispatchers.IO) { cacheFile.readText() }
            withContext(Dispatchers.Default) {
                adapter.fromJson(json) ?: PlaybackProgressPayload(emptyMap())
            }
        }.getOrElse { PlaybackProgressPayload(emptyMap()) }
    }

    suspend fun save(payload: PlaybackProgressPayload) {
        runCatching {
            val json = withContext(Dispatchers.Default) { adapter.toJson(payload) }
            withContext(Dispatchers.IO) { cacheFile.writeText(json) }
        }
    }

    suspend fun saveProgress(progress: PlaybackProgress) {
        val current = load()
        val updated = current.entries.toMutableMap()
        updated[progress.contentId] = progress
        save(PlaybackProgressPayload(updated))
    }

    suspend fun getProgress(contentId: String): PlaybackProgress? {
        return load().entries[contentId]
    }

    suspend fun clearProgress(contentId: String) {
        val current = load()
        val updated = current.entries.toMutableMap()
        updated.remove(contentId)
        save(PlaybackProgressPayload(updated))
    }
}
