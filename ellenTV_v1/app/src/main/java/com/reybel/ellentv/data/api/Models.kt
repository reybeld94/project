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

    // tu API dijo que devuelve “poster calculado”
    val poster: String? = null,

    @Json(name = "custom_poster_url") val customPosterUrl: String? = null,
    @Json(name = "stream_icon") val streamIcon: String? = null,

    @Json(name = "category_ext_id") val categoryExtId: Int? = null,
    val approved: Boolean = false,
    @Json(name = "is_active") val isActive: Boolean = true,

    @Json(name = "container_extension") val containerExtension: String? = null
) {
    val posterUrl: String? get() = customPosterUrl ?: poster ?: streamIcon
}