package com.reybel.ellentv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reybel.ellentv.data.api.EpgGridResponse
import com.reybel.ellentv.data.api.EpgProgram
import com.reybel.ellentv.data.api.LiveItem
import com.reybel.ellentv.data.repo.ChannelRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val providerId: String? = null,

    val channels: List<LiveItem> = emptyList(),
    val selectedLiveId: String? = null,
    val streamUrl: String = "",

    val epgGrid: EpgGridResponse? = null,

    // “Browse” = lo que estás enfocando con el mando (sin cambiar canal aún)
    val browseLiveId: String? = null,
    val browseProgram: EpgProgram? = null,

    val error: String? = null
)

class HomeViewModel(
    private val repo: ChannelRepo = ChannelRepo()
) : ViewModel() {

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    init {
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }

            try {
                val providerId = repo.getDefaultProviderId()
                val channels = repo.fetchLive(providerId)

                // Mantén el canal seleccionado si todavía existe
                val keepSelected = _ui.value.selectedLiveId?.takeIf { old ->
                    channels.any { it.id == old }
                }

                val selectedId = keepSelected ?: channels.firstOrNull()?.id

                val streamUrl = if (selectedId != null) repo.fetchPlayUrl(selectedId) else ""
                val epg = repo.fetchEpgGrid(providerId = providerId, hours = 8)

                _ui.update {
                    it.copy(
                        isLoading = false,
                        providerId = providerId,
                        channels = channels,
                        selectedLiveId = selectedId,
                        streamUrl = streamUrl,
                        epgGrid = epg,

                        // Si no había browse, lo alineamos al seleccionado
                        browseLiveId = it.browseLiveId ?: selectedId,
                        browseProgram = it.browseProgram
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Error cargando datos"
                    )
                }
            }
        }
    }

    fun selectChannel(liveId: String) {
        // Cambia el canal SOLO cuando el usuario presiona Enter
        _ui.update { it.copy(selectedLiveId = liveId, error = null) }

        viewModelScope.launch {
            try {
                val url = repo.fetchPlayUrl(liveId)
                _ui.update { it.copy(streamUrl = url) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: "Error obteniendo stream url") }
            }
        }
    }

    fun setBrowse(liveId: String?, program: EpgProgram?) {
        _ui.update { it.copy(browseLiveId = liveId, browseProgram = program) }
    }
}
