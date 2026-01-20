package com.reybel.ellentv.data.repo

import android.content.Context
import android.util.Log
import com.reybel.ellentv.data.PreferencesManager
import com.reybel.ellentv.data.api.ApiClient
import com.reybel.ellentv.data.api.ApiService
import com.reybel.ellentv.data.api.FavoriteCreate
import com.reybel.ellentv.data.api.FavoriteOut
import com.reybel.ellentv.data.api.MyListItemCreate
import com.reybel.ellentv.data.api.MyListItemOut
import com.reybel.ellentv.data.api.PlaybackProgressCreate
import com.reybel.ellentv.data.api.PlaybackProgressOut
import com.reybel.ellentv.data.api.WatchedItemCreate
import com.reybel.ellentv.data.api.WatchedItemOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UserDataRepo(private val context: Context) {
    private val apiService = ApiClient.retrofit.create(ApiService::class.java)
    private val preferencesManager = PreferencesManager(context)

    private fun getUniqueCode(): String? {
        return preferencesManager.getUniqueCode()
    }

    // ========================================
    // PLAYBACK PROGRESS
    // ========================================

    suspend fun savePlaybackProgress(
        contentType: String,
        contentId: String,
        positionMs: Long,
        durationMs: Long,
        title: String? = null,
        posterUrl: String? = null,
        backdropUrl: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): Result<PlaybackProgressOut> = withContext(Dispatchers.IO) {
        try {
            val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                Exception("No unique code found")
            )

            val progress = PlaybackProgressCreate(
                contentType = contentType,
                contentId = contentId,
                positionMs = positionMs,
                durationMs = durationMs,
                title = title,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber
            )

            val result = apiService.savePlaybackProgress(progress, uniqueCode)
            Result.success(result)
        } catch (e: Exception) {
            Log.e("UserDataRepo", "Error saving playback progress", e)
            Result.failure(e)
        }
    }

    suspend fun getAllPlaybackProgress(): Result<List<PlaybackProgressOut>> = withContext(Dispatchers.IO) {
        try {
            val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                Exception("No unique code found")
            )

            val result = apiService.getAllPlaybackProgress(uniqueCode)
            Result.success(result)
        } catch (e: Exception) {
            Log.e("UserDataRepo", "Error getting all playback progress", e)
            Result.failure(e)
        }
    }

    suspend fun getPlaybackProgress(contentType: String, contentId: String): Result<PlaybackProgressOut?> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                val result = apiService.getPlaybackProgress(contentType, contentId, uniqueCode)
                Result.success(result)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error getting playback progress", e)
                Result.failure(e)
            }
        }

    suspend fun deletePlaybackProgress(contentType: String, contentId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                apiService.deletePlaybackProgress(contentType, contentId, uniqueCode)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error deleting playback progress", e)
                Result.failure(e)
            }
        }

    // ========================================
    // WATCHED ITEMS
    // ========================================

    suspend fun markAsWatched(contentType: String, contentId: String): Result<WatchedItemOut> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                val watched = WatchedItemCreate(contentType, contentId)
                val result = apiService.markAsWatched(watched, uniqueCode)
                Result.success(result)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error marking as watched", e)
                Result.failure(e)
            }
        }

    suspend fun getWatchedItems(contentType: String? = null): Result<List<WatchedItemOut>> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                val result = apiService.getWatchedItems(uniqueCode, contentType)
                Result.success(result)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error getting watched items", e)
                Result.failure(e)
            }
        }

    suspend fun checkIsWatched(contentType: String, contentId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                val result = apiService.checkIsWatched(contentType, contentId, uniqueCode)
                Result.success(result != null)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error checking is watched", e)
                Result.failure(e)
            }
        }

    // ========================================
    // FAVORITES
    // ========================================

    suspend fun addToFavorites(contentType: String, contentId: String): Result<FavoriteOut> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                val favorite = FavoriteCreate(contentType, contentId)
                val result = apiService.addToFavorites(favorite, uniqueCode)
                Result.success(result)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error adding to favorites", e)
                Result.failure(e)
            }
        }

    suspend fun removeFromFavorites(contentType: String, contentId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                apiService.removeFromFavorites(contentType, contentId, uniqueCode)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error removing from favorites", e)
                Result.failure(e)
            }
        }

    suspend fun getFavorites(contentType: String? = null): Result<List<FavoriteOut>> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                val result = apiService.getFavorites(uniqueCode, contentType)
                Result.success(result)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error getting favorites", e)
                Result.failure(e)
            }
        }

    suspend fun checkIsFavorite(contentType: String, contentId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                val result = apiService.checkIsFavorite(contentType, contentId, uniqueCode)
                Result.success(result != null)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error checking is favorite", e)
                Result.failure(e)
            }
        }

    // ========================================
    // MY LIST
    // ========================================

    suspend fun addToMyList(contentType: String, contentId: String): Result<MyListItemOut> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                val myListItem = MyListItemCreate(contentType, contentId)
                val result = apiService.addToMyList(myListItem, uniqueCode)
                Result.success(result)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error adding to my list", e)
                Result.failure(e)
            }
        }

    suspend fun removeFromMyList(contentType: String, contentId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                apiService.removeFromMyList(contentType, contentId, uniqueCode)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error removing from my list", e)
                Result.failure(e)
            }
        }

    suspend fun getMyList(contentType: String? = null): Result<List<MyListItemOut>> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                val result = apiService.getMyList(uniqueCode, contentType)
                Result.success(result)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error getting my list", e)
                Result.failure(e)
            }
        }

    suspend fun checkIsInMyList(contentType: String, contentId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val uniqueCode = getUniqueCode() ?: return@withContext Result.failure(
                    Exception("No unique code found")
                )

                val result = apiService.checkIsInMyList(contentType, contentId, uniqueCode)
                Result.success(result != null)
            } catch (e: Exception) {
                Log.e("UserDataRepo", "Error checking is in my list", e)
                Result.failure(e)
            }
        }
}
