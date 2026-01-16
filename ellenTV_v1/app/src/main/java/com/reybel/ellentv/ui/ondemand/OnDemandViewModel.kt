package com.reybel.ellentv.ui.ondemand

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reybel.ellentv.data.api.TmdbItem
import com.reybel.ellentv.data.api.TmdbPagedResponse
import com.reybel.ellentv.data.api.VodItem
import com.reybel.ellentv.data.repo.PlaybackProgressCache
import com.reybel.ellentv.data.repo.VodRepo
import com.reybel.ellentv.ui.vod.SearchState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.math.ceil
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_PAGE_LIMIT = 30
private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w780"
private const val SEARCH_LIMIT = 30

private const val CONTENT_TYPE_MOVIE = "movie"
private const val CONTENT_TYPE_SERIES = "series"

enum class ContentFilter {
    ALL,
    MOVIES,
    SERIES
}

data class OnDemandCollectionUi(
    val collectionId: String,
    val title: String,
    val contentType: ContentFilter,
    val categoryExtId: Int? = null,
    val isLoading: Boolean = false,
    val items: List<VodItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val totalPages: Int = 0,
    val limit: Int = DEFAULT_PAGE_LIMIT,
    val offset: Int = 0,
    val error: String? = null
)

data class OnDemandUiState(
    val providerId: String? = null,
    val isLoading: Boolean = false,
    val currentFilter: ContentFilter = ContentFilter.ALL,
    val collections: List<OnDemandCollectionUi> = emptyList(),
    val error: String? = null
)

class OnDemandViewModel(
    private val context: Context? = null,
    private val repo: VodRepo = VodRepo()
) : ViewModel() {

    private val _ui = MutableStateFlow(OnDemandUiState())
    val ui: StateFlow<OnDemandUiState> = _ui.asStateFlow()

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private var currentSearchQuery: String = ""
    private var movieSearchOffset: Int = 0
    private var seriesSearchOffset: Int = 0

    private val progressCache: PlaybackProgressCache? = context?.let { PlaybackProgressCache(it) }

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    fun open(providerId: String) {
        if (_ui.value.providerId == providerId && _ui.value.collections.isNotEmpty()) return

        viewModelScope.launch {
            _ui.update { it.copy(providerId = providerId, isLoading = true, error = null) }
            try {
                val movieCollections = repo.fetchCollections(enabled = true)
                    .map { collection ->
                        OnDemandCollectionUi(
                            collectionId = collection.id,
                            title = collection.name,
                            contentType = ContentFilter.MOVIES,
                            isLoading = true
                        )
                    }

                val seriesCollections = repo.fetchSeriesCategories(providerId)
                    .map { category ->
                        OnDemandCollectionUi(
                            collectionId = "series:${category.extId}",
                            title = category.name,
                            contentType = ContentFilter.SERIES,
                            categoryExtId = category.extId,
                            isLoading = true
                        )
                    }

                // Create "Continue Watching" collection from saved progress
                val continueWatchingItems = progressCache?.getAllResumable()?.mapNotNull { progress ->
                    // Convert PlaybackProgress to VodItem
                    VodItem(
                        id = progress.contentId,
                        name = progress.title ?: "Unknown",
                        poster = progress.posterUrl,
                        customPosterUrl = progress.posterUrl,
                        backdropUrl = progress.backdropUrl,
                        contentType = progress.contentType,
                        streamIcon = progress.posterUrl
                    )
                } ?: emptyList()

                val continueWatchingCollection = if (continueWatchingItems.isNotEmpty()) {
                    listOf(
                        OnDemandCollectionUi(
                            collectionId = "continue_watching",
                            title = "Continue Watching",
                            contentType = ContentFilter.ALL,
                            isLoading = false,
                            items = continueWatchingItems,
                            total = continueWatchingItems.size,
                            page = 1,
                            totalPages = 1
                        )
                    )
                } else {
                    emptyList()
                }

                val allCollections = continueWatchingCollection + movieCollections + seriesCollections

                _ui.update { it.copy(isLoading = false, collections = allCollections) }

                // Load first page for all collections except Continue Watching
                allCollections.filter { it.collectionId != "continue_watching" }.forEach { collection ->
                    loadFirstPage(collection.collectionId)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message ?: "Error loading collections") }
            }
        }
    }

    fun setFilter(filter: ContentFilter) {
        _ui.update { it.copy(currentFilter = filter) }
    }

    fun updateSearchQuery(query: String) {
        _searchState.update { it.copy(query = query) }
    }

    fun performSearch() {
        val query = _searchState.value.query.trim()
        if (query.isEmpty()) return

        if (query != currentSearchQuery) {
            currentSearchQuery = query
            movieSearchOffset = 0
            seriesSearchOffset = 0
            _searchState.update {
                it.copy(results = emptyList(), total = 0, hasSearched = false)
            }
        }

        viewModelScope.launch {
            _searchState.update { it.copy(isSearching = true, error = null) }
            try {
                val moviesLimit = SEARCH_LIMIT / 2
                val seriesLimit = SEARCH_LIMIT - moviesLimit

                val moviesDeferred = async { repo.searchMovies(query, moviesLimit, movieSearchOffset) }
                val seriesDeferred = async { repo.searchSeries(query, seriesLimit, seriesSearchOffset) }

                val movies = moviesDeferred.await()
                val series = seriesDeferred.await()

                val movieItems = movies.items.map { it.copy(contentType = CONTENT_TYPE_MOVIE) }
                val seriesItems = series.items.map { it.copy(contentType = CONTENT_TYPE_SERIES) }
                val combined = movieItems + seriesItems

                movieSearchOffset += movieItems.size
                seriesSearchOffset += seriesItems.size

                _searchState.update {
                    it.copy(
                        isSearching = false,
                        results = combined,
                        total = movies.total + series.total,
                        hasSearched = true,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _searchState.update {
                    it.copy(isSearching = false, hasSearched = true, error = e.message ?: "Search failed")
                }
            }
        }
    }

    fun loadMoreSearchResults() {
        val state = _searchState.value
        if (state.isSearching) return
        if (state.results.size >= state.total) return
        if (currentSearchQuery.isEmpty()) return

        viewModelScope.launch {
            _searchState.update { it.copy(isSearching = true) }
            try {
                val moviesLimit = SEARCH_LIMIT / 2
                val seriesLimit = SEARCH_LIMIT - moviesLimit

                val moviesDeferred = async { repo.searchMovies(currentSearchQuery, moviesLimit, movieSearchOffset) }
                val seriesDeferred = async { repo.searchSeries(currentSearchQuery, seriesLimit, seriesSearchOffset) }

                val movies = moviesDeferred.await()
                val series = seriesDeferred.await()

                val movieItems = movies.items.map { it.copy(contentType = CONTENT_TYPE_MOVIE) }
                val seriesItems = series.items.map { it.copy(contentType = CONTENT_TYPE_SERIES) }
                val combined = movieItems + seriesItems

                movieSearchOffset += movieItems.size
                seriesSearchOffset += seriesItems.size

                _searchState.update {
                    it.copy(
                        isSearching = false,
                        results = it.results + combined,
                        total = movies.total + series.total
                    )
                }
            } catch (e: Exception) {
                _searchState.update { it.copy(isSearching = false) }
            }
        }
    }

    fun clearSearch() {
        currentSearchQuery = ""
        movieSearchOffset = 0
        seriesSearchOffset = 0
        _searchState.update { SearchState() }
    }

    fun loadMoreIfNeeded(collectionId: String, lastVisibleIndex: Int) {
        val collection = _ui.value.collections.firstOrNull { it.collectionId == collectionId } ?: return
        if (collection.isLoading) return
        if (collection.items.isEmpty()) return
        if (collection.page >= collection.totalPages && collection.contentType == ContentFilter.MOVIES) return
        if (collection.items.size >= collection.total && collection.contentType == ContentFilter.SERIES) return
        if (lastVisibleIndex < collection.items.size - 6) return

        viewModelScope.launch {
            updateCollection(collectionId) { it.copy(isLoading = true, error = null) }
            try {
                when (collection.contentType) {
                    ContentFilter.MOVIES -> {
                        val nextPage = collection.page + 1
                        val resp = repo.fetchCollectionItems(collection.collectionId, page = nextPage)
                        val parsed = parseTmdbPayload(resp.payload)
                        updateCollection(collectionId) {
                            it.copy(
                                isLoading = false,
                                items = it.items + parsed.items.map { item -> item.copy(contentType = CONTENT_TYPE_MOVIE) },
                                total = parsed.totalResults,
                                page = nextPage,
                                totalPages = parsed.totalPages
                            )
                        }
                    }
                    ContentFilter.SERIES -> {
                        val providerId = _ui.value.providerId ?: return@launch
                        val nextOffset = collection.offset + collection.limit
                        val resp = repo.fetchVodPage(providerId, collection.categoryExtId, collection.limit, nextOffset)
                        val totalPages = ceil(resp.total / collection.limit.toDouble()).toInt().coerceAtLeast(1)
                        updateCollection(collectionId) {
                            it.copy(
                                isLoading = false,
                                items = it.items + resp.items.map { item -> item.copy(contentType = CONTENT_TYPE_SERIES) },
                                total = resp.total,
                                offset = nextOffset,
                                page = (nextOffset / collection.limit) + 1,
                                totalPages = totalPages
                            )
                        }
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                updateCollection(collectionId) { it.copy(isLoading = false, error = e.message ?: "Error loading more") }
            }
        }
    }

    suspend fun getMoviePlayUrl(vodId: String): String {
        return repo.fetchVodPlayUrl(vodId)
    }

    suspend fun getSeriesEpisodePlayUrl(providerId: String, episodeId: Int, format: String): String {
        return repo.fetchSeriesEpisodePlayUrl(providerId, episodeId, format)
    }

    private fun loadFirstPage(collectionId: String) {
        viewModelScope.launch {
            updateCollection(collectionId) { it.copy(isLoading = true, error = null, page = 0, totalPages = 0) }
            try {
                val collection = _ui.value.collections.firstOrNull { it.collectionId == collectionId } ?: return@launch
                when (collection.contentType) {
                    ContentFilter.MOVIES -> {
                        val resp = repo.fetchCollectionItems(collection.collectionId, page = 1)
                        val parsed = parseTmdbPayload(resp.payload)
                        updateCollection(collectionId) {
                            it.copy(
                                isLoading = false,
                                items = parsed.items.map { item -> item.copy(contentType = CONTENT_TYPE_MOVIE) },
                                total = parsed.totalResults,
                                page = 1,
                                totalPages = parsed.totalPages
                            )
                        }
                    }
                    ContentFilter.SERIES -> {
                        val providerId = _ui.value.providerId ?: return@launch
                        val resp = repo.fetchVodPage(providerId, collection.categoryExtId, collection.limit, 0)
                        val totalPages = ceil(resp.total / collection.limit.toDouble()).toInt().coerceAtLeast(1)
                        updateCollection(collectionId) {
                            it.copy(
                                isLoading = false,
                                items = resp.items.map { item -> item.copy(contentType = CONTENT_TYPE_SERIES) },
                                total = resp.total,
                                offset = resp.items.size,
                                page = 1,
                                totalPages = totalPages
                            )
                        }
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                updateCollection(collectionId) { it.copy(isLoading = false, error = e.message ?: "Error loading collection") }
            }
        }
    }

    private fun updateCollection(
        collectionId: String,
        updater: (OnDemandCollectionUi) -> OnDemandCollectionUi
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
        val resolvedCast = (tmdbCast ?: cast)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
        return VodItem(
            id = vodId ?: "tmdb:$id",
            name = displayTitle,
            poster = posterUrl,
            streamIcon = posterUrl,
            customPosterUrl = posterUrl,
            backdrop = backdropUrl,
            tmdbStatus = "synced",
            tmdbTitle = displayTitle,
            tmdbId = id,
            tmdbVoteAverage = tmdbVoteAverage ?: voteAverage,
            tmdbOriginalLanguage = tmdbOriginalLanguage?.uppercase() ?: originalLanguage?.uppercase(),
            tmdbCast = resolvedCast?.ifEmpty { null },
            overview = overview,
            genreNames = resolvedGenres.ifEmpty { null },
            releaseDate = release,
            streamUrl = streamUrl,
            contentType = CONTENT_TYPE_MOVIE
        )
    }
}

private data class ParsedTmdbCollection(
    val items: List<VodItem>,
    val totalResults: Int,
    val totalPages: Int
)
