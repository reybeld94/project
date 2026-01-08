package com.reybel.ellentv.data.api

import com.squareup.moshi.Json

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
    val url: String
)

data class CollectionOut(
    val id: String,
    val name: String,
    val slug: String,
    @Json(name = "source_type") val sourceType: String,
    @Json(name = "source_id") val sourceId: Int? = null,
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
    @Json(name = "genre_names") val genreNames: List<String>? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null,
    val genres: List<TmdbGenre>? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null
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

    // tu API dijo que devuelve “poster calculado”
    val poster: String? = null,

    @Json(name = "custom_poster_url") val customPosterUrl: String? = null,
    @Json(name = "stream_icon") val streamIcon: String? = null,

    @Json(name = "category_ext_id") val categoryExtId: Int? = null,
    val approved: Boolean = false,
    @Json(name = "is_active") val isActive: Boolean = true,

    @Json(name = "tmdb_status") val tmdbStatus: String? = null,
    @Json(name = "tmdb_title") val tmdbTitle: String? = null,
    @Json(name = "tmdb_id") val tmdbId: Int? = null,

    @Json(name = "container_extension") val containerExtension: String? = null,
    val overview: String? = null,
    @Json(name = "genre_names") val genreNames: List<String>? = null,
    @Json(name = "release_date") val releaseDate: String? = null
) {
    val posterUrl: String? get() = customPosterUrl ?: poster ?: streamIcon
    val displayTitle: String
        get() = when {
            tmdbStatus == "synced" && !tmdbTitle.isNullOrBlank() -> tmdbTitle
            !normalizedName.isNullOrBlank() -> normalizedName
            else -> name
        }
}
