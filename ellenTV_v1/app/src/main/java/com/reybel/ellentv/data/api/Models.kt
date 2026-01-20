package com.reybel.ellentv.data.api

import com.squareup.moshi.Json

// Helper function to resolve TMDB image paths
private fun resolveTmdbImagePath(path: String?, size: String): String? {
    if (path.isNullOrBlank()) return null
    return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/$size$path"
}

data class ProviderOut(
    val id: String,
    val name: String,
    @Json(name = "base_url") val baseUrl: String? = null,
    val username: String? = null,
    @Json(name = "is_active") val isActive: Boolean = true
)

data class LiveListResponse(
    val total: Int,
    val items: List<LiveItem>
)

data class LiveItem(
    val id: String,
    @Json(name = "provider_stream_id") val providerStreamId: Int? = null,
    @Json(name = "channel_number") val channelNumber: Int? = null,
    val name: String,
    @Json(name = "normalized_name") val normalizedName: String? = null,

    // lo viejo (puede venir null)
    @Json(name = "stream_icon") val streamIcon: String? = null,

    // lo que TU API está mandando ahora (según LIVE_RAW)
    @Json(name = "custom_logo_url") val customLogoUrl: String? = null,
    val logo: String? = null,

    val approved: Boolean = false,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "category_name") val categoryName: String? = null,
    @Json(name = "category_ext_id") val categoryExtId: Int? = null
)


data class PlayResponse(
    val id: String,
    val name: String,
    val url: String,
    @Json(name = "alt1") val alt1: String? = null,
    @Json(name = "alt2") val alt2: String? = null,
    @Json(name = "alt3") val alt3: String? = null
)

data class CollectionOut(
    val id: String,
    val name: String,
    val slug: String,
    @Json(name = "source_type") val sourceType: String,
    @Json(name = "source_id") val sourceId: Int? = null,
    @Json(name = "filters") val filters: Map<String, Any>? = null,
    val enabled: Boolean = true,
    @Json(name = "order_index") val orderIndex: Int = 0
)

data class CollectionItemsResponse(
    @Json(name = "collection_id") val collectionId: String,
    val page: Int,
    val payload: Map<String, Any> = emptyMap(),
    val cached: Boolean = false,
    val stale: Boolean = false
)

data class TmdbPagedResponse(
    val page: Int? = null,
    @Json(name = "total_pages") val totalPages: Int? = null,
    val results: List<TmdbItem> = emptyList()
)

data class TmdbGenre(
    val id: Int,
    val name: String
)

data class TmdbItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    val cast: List<String>? = null,
    @Json(name = "tmdb_vote_average") val tmdbVoteAverage: Double? = null,
    @Json(name = "tmdb_original_language") val tmdbOriginalLanguage: String? = null,
    @Json(name = "tmdb_cast") val tmdbCast: List<String>? = null,
    @Json(name = "genre_names") val genreNames: List<String>? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null,
    val genres: List<TmdbGenre>? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "vod_id") val vodId: String? = null,
    @Json(name = "stream_url") val streamUrl: String? = null
) {
    val displayTitle: String
        get() = title ?: name ?: ""
}

// -------------------- EPG --------------------

data class EpgGridResponse(
    val ok: Boolean = true,
    val window: EpgWindow,
    val count: Int,
    val items: List<EpgGridItem>
)

data class EpgWindow(
    val start: String,
    val end: String
)

data class EpgGridItem(
    @Json(name = "live_id") val liveId: String,
    val name: String,
    val logo: String? = null,
    @Json(name = "epg_source_id") val epgSourceId: String? = null,
    val programs: List<EpgProgram> = emptyList()
)

data class EpgProgram(
    val title: String,
    val start: String,
    val end: String,
    val category: String? = null,

    // Soporta varios nombres posibles que suelen venir en EPGs
    val description: String? = null,
    @Json(name = "desc") val desc: String? = null,
    @Json(name = "short_desc") val shortDesc: String? = null,
    @Json(name = "long_desc") val longDesc: String? = null,
)

data class ProviderCategoryOut(
    val id: String,
    val name: String,
    @Json(name = "cat_type") val catType: String? = null,

    // Algunos backends lo llaman provider_category_id, otros category_ext_id
    @Json(name = "provider_category_id") val providerCategoryId: Int? = null,
    @Json(name = "category_ext_id") val categoryExtId: Int? = null,

    @Json(name = "is_active") val isActive: Boolean = true,
) {
    val extId: Int get() = providerCategoryId ?: categoryExtId ?: 0
}

data class VodListResponse(
    val total: Int = 0,
    val items: List<VodItem> = emptyList()
)

data class VodItem(
    val id: String,
    val name: String,
    @Json(name = "normalized_name") val normalizedName: String? = null,

    val poster: String? = null,
    @Json(name = "cover") val cover: String? = null,

    @Json(name = "custom_poster_url") val customPosterUrl: String? = null,
    @Json(name = "stream_icon") val streamIcon: String? = null,

    @Json(name = "category_ext_id") val categoryExtId: Int? = null,
    val approved: Boolean = false,
    @Json(name = "is_active") val isActive: Boolean = true,

    @Json(name = "tmdb_status") val tmdbStatus: String? = null,
    @Json(name = "tmdb_title") val tmdbTitle: String? = null,
    @Json(name = "tmdb_id") val tmdbId: Int? = null,
    @Json(name = "tmdb_vote_average") val tmdbVoteAverage: Double? = null,
    @Json(name = "tmdb_original_language") val tmdbOriginalLanguage: String? = null,
    @Json(name = "tmdb_cast") val tmdbCast: List<String>? = null,
    val cast: List<String>? = null,

    // ══════ NUEVOS CAMPOS TMDB ══════
    @Json(name = "tmdb_overview") val tmdbOverview: String? = null,
    @Json(name = "tmdb_poster_path") val tmdbPosterPath: String? = null,
    @Json(name = "tmdb_backdrop_path") val tmdbBackdropPath: String? = null,
    @Json(name = "tmdb_genres") val tmdbGenres: List<String>? = null,
    @Json(name = "tmdb_release_date") val tmdbReleaseDate: String? = null,
    // ════════════════════════════════

    @Json(name = "container_extension") val containerExtension: String? = null,
    val overview: String? = null,
    val description: String? = null,
    @Json(name = "desc") val desc: String? = null,
    @Json(name = "short_desc") val shortDesc: String? = null,
    @Json(name = "long_desc") val longDesc: String? = null,
    @Json(name = "genre_names") val genreNames: List<String>? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    val backdrop: String? = null,
    @Json(name = "stream_url") val streamUrl: String? = null,
    @Json(name = "content_type") val contentType: String? = null
) {
    // ══════ PROPIEDADES ACTUALIZADAS ══════
    val posterUrl: String?
        get() = customPosterUrl
            ?: poster
            ?: cover
            ?: resolveTmdbImagePath(tmdbPosterPath, "w500")
            ?: streamIcon

    val backdropUrl: String?
        get() = backdrop
            ?: resolveTmdbImagePath(tmdbBackdropPath, "w1280")
            ?: resolveTmdbImagePath(backdropPath, "w1280")
            ?: posterUrl

    val displayTitle: String
        get() = when {
            tmdbStatus?.lowercase() in listOf("synced", "sync") && !tmdbTitle.isNullOrBlank() -> tmdbTitle
            !normalizedName.isNullOrBlank() -> normalizedName
            else -> name
        }
    // ══════════════════════════════════════
}

data class SeasonInfo(
    @Json(name = "season_number") val seasonNumber: Int,
    val name: String? = null,
    val cover: String? = null,
    @Json(name = "episode_count") val episodeCount: Int = 0,
    val episodes: List<EpisodeInfo> = emptyList()
)

data class EpisodeInfo(
    @Json(name = "episode_id") val episodeId: Int,
    @Json(name = "episode_number") val episodeNumber: Int,
    val title: String? = null,
    @Json(name = "container_extension") val containerExtension: String? = null,
    @Json(name = "duration_secs") val durationSecs: Int? = null
)

data class SeriesListResponse(
    val total: Int,
    val items: List<VodItem> = emptyList()
)

data class SeriesSeasonsResponse(
    @Json(name = "series_id") val seriesId: String,
    @Json(name = "provider_id") val providerId: String,
    val seasons: List<SeasonInfo> = emptyList(),
    @Json(name = "tmdb_status") val tmdbStatus: String? = null,
    @Json(name = "tmdb_title") val tmdbTitle: String? = null,
    @Json(name = "tmdb_id") val tmdbId: Int? = null,
    @Json(name = "tmdb_vote_average") val tmdbVoteAverage: Double? = null,
    @Json(name = "tmdb_original_language") val tmdbOriginalLanguage: String? = null,
    @Json(name = "tmdb_cast") val tmdbCast: List<String>? = null,
    @Json(name = "tmdb_overview") val tmdbOverview: String? = null,
    @Json(name = "tmdb_poster_path") val tmdbPosterPath: String? = null,
    @Json(name = "tmdb_backdrop_path") val tmdbBackdropPath: String? = null,
    @Json(name = "tmdb_genres") val tmdbGenres: List<String>? = null,
    @Json(name = "tmdb_release_date") val tmdbReleaseDate: String? = null
)

data class OnDemandSearchResponse(
    val items: List<VodItem> = emptyList(),
    @Json(name = "total_movies") val totalMovies: Int = 0,
    @Json(name = "total_series") val totalSeries: Int = 0
) {
    val total: Int
        get() = totalMovies + totalSeries
}

// -------------------- User Data Models --------------------

data class PlaybackProgressOut(
    val id: String,
    @Json(name = "provider_user_id") val providerUserId: String,
    @Json(name = "content_type") val contentType: String,
    @Json(name = "content_id") val contentId: String,
    @Json(name = "position_ms") val positionMs: Long,
    @Json(name = "duration_ms") val durationMs: Long,
    val title: String? = null,
    @Json(name = "poster_url") val posterUrl: String? = null,
    @Json(name = "backdrop_url") val backdropUrl: String? = null,
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "episode_number") val episodeNumber: Int? = null,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "created_at") val createdAt: String
) {
    val progressPercent: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()) * 100 else 0f

    val shouldResume: Boolean
        get() = positionMs > 5000 && progressPercent < 95
}

data class PlaybackProgressCreate(
    @Json(name = "content_type") val contentType: String,
    @Json(name = "content_id") val contentId: String,
    @Json(name = "position_ms") val positionMs: Long,
    @Json(name = "duration_ms") val durationMs: Long,
    val title: String? = null,
    @Json(name = "poster_url") val posterUrl: String? = null,
    @Json(name = "backdrop_url") val backdropUrl: String? = null,
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "episode_number") val episodeNumber: Int? = null
)

data class WatchedItemOut(
    val id: String,
    @Json(name = "provider_user_id") val providerUserId: String,
    @Json(name = "content_type") val contentType: String,
    @Json(name = "content_id") val contentId: String,
    @Json(name = "watched_at") val watchedAt: String,
    @Json(name = "created_at") val createdAt: String
)

data class WatchedItemCreate(
    @Json(name = "content_type") val contentType: String,
    @Json(name = "content_id") val contentId: String
)

data class FavoriteOut(
    val id: String,
    @Json(name = "provider_user_id") val providerUserId: String,
    @Json(name = "content_type") val contentType: String,
    @Json(name = "content_id") val contentId: String,
    @Json(name = "created_at") val createdAt: String
)

data class FavoriteCreate(
    @Json(name = "content_type") val contentType: String,
    @Json(name = "content_id") val contentId: String
)

data class MyListItemOut(
    val id: String,
    @Json(name = "provider_user_id") val providerUserId: String,
    @Json(name = "content_type") val contentType: String,
    @Json(name = "content_id") val contentId: String,
    @Json(name = "created_at") val createdAt: String
)

data class MyListItemCreate(
    @Json(name = "content_type") val contentType: String,
    @Json(name = "content_id") val contentId: String
)
