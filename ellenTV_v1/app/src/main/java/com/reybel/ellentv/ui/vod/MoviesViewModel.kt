package com.reybel.ellentv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reybel.ellentv.data.api.TmdbItem
import com.reybel.ellentv.data.api.TmdbPagedResponse
import com.reybel.ellentv.data.api.VodItem
import com.reybel.ellentv.data.repo.VodRepo
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_PAGE_LIMIT = 30
private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w780"

data class MoviesCollectionUi(
    val collectionId: String,
    val title: String,
    val isPlayable: Boolean = true,
    val isLoading: Boolean = false,
    val items: List<VodItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val totalPages: Int = 0,
    val error: String? = null
)

data class MoviesUiState(
    val isLoading: Boolean = false,
    val collections: List<MoviesCollectionUi> = emptyList(),
    val limit: Int = DEFAULT_PAGE_LIMIT,
    val error: String? = null
)

class MoviesViewModel(
    private val repo: VodRepo = VodRepo()
) : ViewModel() {

    private val _ui = MutableStateFlow(MoviesUiState())
    val ui: StateFlow<MoviesUiState> = _ui.asStateFlow()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    fun open() {
        if (_ui.value.collections.isNotEmpty()) return

        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            try {
                val collections = repo.fetchCollections(enabled = true)
                val enabledCollections = collections.ifEmpty {
                    emptyList()
                }
                val collectionUi = enabledCollections.map { collection ->
                    MoviesCollectionUi(
                        collectionId = collection.id,
                        title = collection.name,
                        isPlayable = true,
                        isLoading = true
                    )
                }

                _ui.update { it.copy(isLoading = false, collections = collectionUi) }

                enabledCollections.forEach { collection ->
                    loadFirstPage(collection.id)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message ?: "Error loading collections") }
            }
        }
    }

    private fun loadFirstPage(collectionId: String) {
        viewModelScope.launch {
            updateCollection(collectionId) { it.copy(isLoading = true, error = null, page = 0, totalPages = 0) }
            try {
                val resp = repo.fetchCollectionItems(collectionId, page = 1)
                val parsed = parseTmdbPayload(resp.payload)
                updateCollection(collectionId) {
                    it.copy(
                        isLoading = false,
                        items = parsed.items,
                        total = parsed.totalResults,
                        page = 1,
                        totalPages = parsed.totalPages
                    )
                }
            } catch (e: Exception) {
                updateCollection(collectionId) { it.copy(isLoading = false, error = e.message ?: "Error loading collection") }
            }
        }
    }

    fun loadMoreIfNeeded(collectionId: String, lastVisibleIndex: Int) {
        val collection = _ui.value.collections.firstOrNull { it.collectionId == collectionId } ?: return
        if (collection.isLoading) return
        if (collection.items.isEmpty()) return
        if (collection.page >= collection.totalPages) return
        if (lastVisibleIndex < collection.items.size - 6) return

        viewModelScope.launch {
            updateCollection(collectionId) { it.copy(isLoading = true, error = null) }
            try {
                val nextPage = collection.page + 1
                val resp = repo.fetchCollectionItems(collectionId, page = nextPage)
                val parsed = parseTmdbPayload(resp.payload)
                updateCollection(collectionId) {
                    it.copy(
                        isLoading = false,
                        items = it.items + parsed.items,
                        total = parsed.totalResults,
                        page = nextPage,
                        totalPages = parsed.totalPages
                    )
                }
            } catch (e: Exception) {
                updateCollection(collectionId) { it.copy(isLoading = false, error = e.message ?: "Error loading more") }
            }
        }
    }

    suspend fun getPlayUrl(vodId: String): String {
        return repo.fetchVodPlayUrl(vodId)
    }

    private fun updateCollection(
        collectionId: String,
        updater: (MoviesCollectionUi) -> MoviesCollectionUi
    ) {
        _ui.update { state ->
            state.copy(
                collections = state.collections.map { collection ->
                    if (collection.collectionId == collectionId) updater(collection) else collection
                }
            )
        }
    }

    private fun parseTmdbPayload(payload: Map<String, Any>): ParsedTmdbCollection {
        val adapter = moshi.adapter(TmdbPagedResponse::class.java)
        val parsed = adapter.fromJsonValue(payload)
        val items = parsed?.results?.map { it.toVodItem() } ?: emptyList()
        val totalResults = (payload["total_results"] as? Number)?.toInt() ?: items.size
        val totalPages = parsed?.totalPages ?: (payload["total_pages"] as? Number)?.toInt() ?: 1
        return ParsedTmdbCollection(
            items = items,
            totalResults = totalResults,
            totalPages = totalPages
        )
    }

    private fun TmdbItem.toVodItem(): VodItem {
        val posterPath = posterPath ?: backdropPath
        val posterUrl = posterPath?.let { path -> "${TMDB_IMAGE_BASE}${path}" }
        val backdropUrl = backdropPath?.let { path -> "${TMDB_IMAGE_BASE}${path}" }
        val release = releaseDate ?: firstAirDate
        val resolvedGenres = when {
            !genreNames.isNullOrEmpty() -> genreNames
            !genres.isNullOrEmpty() -> genres.map { it.name }
            else -> emptyList()
        }
        return VodItem(
            id = "tmdb:$id",
            name = displayTitle,
            poster = posterUrl,
            streamIcon = posterUrl,
            customPosterUrl = posterUrl,
            backdrop = backdropUrl,
            overview = overview,
            genreNames = resolvedGenres.ifEmpty { null },
            releaseDate = release
        )
    }
}

private data class ParsedTmdbCollection(
    val items: List<VodItem>,
    val totalResults: Int,
    val totalPages: Int
)
