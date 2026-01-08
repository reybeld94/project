package com.reybel.ellentv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reybel.ellentv.data.api.VodItem
import com.reybel.ellentv.data.repo.VodRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val DEFAULT_PAGE_LIMIT = 30

data class MoviesCollectionUi(
    val providerId: String,
    val title: String,
    val categoryExtId: Int? = null,
    val isLoading: Boolean = false,
    val items: List<VodItem> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
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

    fun open() {
        if (_ui.value.collections.isNotEmpty()) return

        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            try {
                val providers = repo.fetchProviders()
                val activeProviders = providers.filter { it.isActive }.ifEmpty { providers }
                val selected = activeProviders.take(2)
                val collections = selected.map { provider ->
                    MoviesCollectionUi(
                        providerId = provider.id,
                        title = provider.name,
                        isLoading = true
                    )
                }

                _ui.update { it.copy(isLoading = false, collections = collections) }

                selected.forEach { provider ->
                    loadProviderCollection(provider.id)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoading = false, error = e.message ?: "Error loading providers") }
            }
        }
    }

    private fun loadProviderCollection(providerId: String) {
        viewModelScope.launch {
            updateCollection(providerId) { it.copy(isLoading = true, error = null, offset = 0) }
            try {
                val categories = repo.fetchVodCategories(providerId)
                val firstCategory = categories.firstOrNull()
                val categoryExtId = firstCategory?.extId
                val title = firstCategory?.name ?: _ui.value.collections.firstOrNull { it.providerId == providerId }?.title.orEmpty()
                updateCollection(providerId) { it.copy(title = title, categoryExtId = categoryExtId) }
                loadFirstPage(providerId, categoryExtId)
            } catch (e: Exception) {
                updateCollection(providerId) { it.copy(isLoading = false, error = e.message ?: "Error loading collection") }
            }
        }
    }

    private fun loadFirstPage(providerId: String, categoryExtId: Int?) {
        val limit = _ui.value.limit

        viewModelScope.launch {
            updateCollection(providerId) { it.copy(isLoading = true, error = null, offset = 0) }
            try {
                val resp = repo.fetchVodPage(providerId, categoryExtId = categoryExtId, limit = limit, offset = 0)
                updateCollection(providerId) {
                    it.copy(
                        isLoading = false,
                        items = resp.items,
                        total = resp.total,
                        offset = resp.items.size
                    )
                }
            } catch (e: Exception) {
                updateCollection(providerId) { it.copy(isLoading = false, error = e.message ?: "Error loading collection") }
            }
        }
    }

    fun loadMoreIfNeeded(providerId: String, lastVisibleIndex: Int) {
        val collection = _ui.value.collections.firstOrNull { it.providerId == providerId } ?: return
        if (collection.isLoading) return
        if (collection.items.isEmpty()) return
        if (collection.items.size >= collection.total) return
        if (lastVisibleIndex < collection.items.size - 6) return

        val limit = _ui.value.limit
        viewModelScope.launch {
            updateCollection(providerId) { it.copy(isLoading = true, error = null) }
            try {
                val resp = repo.fetchVodPage(
                    providerId,
                    categoryExtId = collection.categoryExtId,
                    limit = limit,
                    offset = collection.offset
                )
                updateCollection(providerId) {
                    it.copy(
                        isLoading = false,
                        items = it.items + resp.items,
                        total = resp.total,
                        offset = it.items.size + resp.items.size
                    )
                }
            } catch (e: Exception) {
                updateCollection(providerId) { it.copy(isLoading = false, error = e.message ?: "Error loading more") }
            }
        }
    }

    suspend fun getPlayUrl(vodId: String): String {
        return repo.fetchVodPlayUrl(vodId)
    }

    private fun updateCollection(
        providerId: String,
        updater: (MoviesCollectionUi) -> MoviesCollectionUi
    ) {
        _ui.update { state ->
            state.copy(
                collections = state.collections.map { collection ->
                    if (collection.providerId == providerId) updater(collection) else collection
                }
            )
        }
    }
}
