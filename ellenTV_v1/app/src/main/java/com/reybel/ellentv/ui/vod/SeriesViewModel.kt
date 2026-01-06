package com.reybel.ellentv.ui.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reybel.ellentv.data.repo.VodRepo
import com.reybel.ellentv.ui.vod.VodUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel para Series - Reutiliza VodUiState porque la estructura es idéntica
 * Solo cambia el cat_type que se pide al backend
 */
class SeriesViewModel(
    private val repo: VodRepo = VodRepo()
) : ViewModel() {

    private val _ui = MutableStateFlow(VodUiState())
    val ui: StateFlow<VodUiState> = _ui.asStateFlow()

    fun open(providerId: String) {
        val currentPid = _ui.value.providerId
        if (currentPid == providerId && _ui.value.categories.isNotEmpty()) return

        viewModelScope.launch {
            _ui.update { it.copy(providerId = providerId, isLoadingCats = true, error = null) }
            try {
                // 🎯 DIFERENCIA CLAVE: cat_type = "series" en vez de "vod"
                val cats = repo.fetchSeriesCategories(providerId)
                val first = cats.firstOrNull()?.extId
                _ui.update {
                    it.copy(
                        isLoadingCats = false,
                        categories = cats,
                        selectedCatExtId = first,
                        items = emptyList(),
                        total = 0,
                        offset = 0
                    )
                }
                loadFirstPage()
            } catch (e: Exception) {
                _ui.update { it.copy(isLoadingCats = false, error = e.message ?: "Error loading series categories") }
            }
        }
    }

    fun selectCategory(extId: Int?) {
        _ui.update { it.copy(selectedCatExtId = extId, items = emptyList(), total = 0, offset = 0) }
        loadFirstPage()
    }

    private fun loadFirstPage() {
        val pid = _ui.value.providerId ?: return
        val cat = _ui.value.selectedCatExtId
        val limit = _ui.value.limit

        viewModelScope.launch {
            _ui.update { it.copy(isLoadingPage = true, error = null, offset = 0) }
            try {
                // Usa el mismo endpoint /vod pero con las categorías de series
                val resp = repo.fetchVodPage(pid, cat, limit, 0)
                _ui.update {
                    it.copy(
                        isLoadingPage = false,
                        items = resp.items,
                        total = resp.total,
                        offset = resp.items.size
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoadingPage = false, error = e.message ?: "Error loading series") }
            }
        }
    }

    fun loadMoreIfNeeded(lastVisibleIndex: Int) {
        val st = _ui.value
        val pid = st.providerId ?: return
        if (st.isLoadingPage) return
        if (st.items.isEmpty()) return
        if (st.items.size >= st.total) return

        if (lastVisibleIndex < st.items.size - 12) return

        viewModelScope.launch {
            _ui.update { it.copy(isLoadingPage = true, error = null) }
            try {
                val resp = repo.fetchVodPage(pid, st.selectedCatExtId, st.limit, st.offset)
                _ui.update {
                    it.copy(
                        isLoadingPage = false,
                        items = it.items + resp.items,
                        total = resp.total,
                        offset = it.items.size + resp.items.size
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(isLoadingPage = false, error = e.message ?: "Error loading more") }
            }
        }
    }

    // 🔧 CORREGIDO: El servidor decide el formato basado en container_extension
    suspend fun getPlayUrl(vodId: String): String {
        return repo.fetchVodPlayUrl(vodId)
    }
}