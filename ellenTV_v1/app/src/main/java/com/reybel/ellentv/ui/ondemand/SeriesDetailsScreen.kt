package com.reybel.ellentv.ui.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reybel.ellentv.data.api.EpisodeInfo
import com.reybel.ellentv.data.api.SeasonInfo
import com.reybel.ellentv.data.api.VodItem
import com.reybel.ellentv.data.repo.VodRepo
import com.reybel.ellentv.ui.components.OptimizedAsyncImage

data class SeriesDetailsUiState(
    val isLoading: Boolean = true,
    val seriesId: String = "",
    val title: String = "",
    val overview: String? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val voteAverage: Double? = null,
    val genres: List<String> = emptyList(),
    val seasons: List<SeasonInfo> = emptyList(),
    val selectedSeasonIndex: Int = 0,
    val providerId: String? = null,
    val error: String? = null
)

@Composable
fun SeriesDetailsScreen(
    item: VodItem,
    onPlay: (providerId: String, episodeId: Int, format: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var uiState by remember {
        mutableStateOf(
            SeriesDetailsUiState(
                seriesId = item.id,
                title = item.displayTitle,
                overview = item.resolvedDescription(),
                backdropUrl = item.backdropUrl,
                posterUrl = item.posterUrl,
                voteAverage = item.tmdbVoteAverage,
                genres = item.genreNames ?: emptyList()
            )
        )
    }
    val repo = remember { VodRepo() }

    LaunchedEffect(item.id) {
        try {
            val response = repo.fetchSeriesSeasons(item.id)
            uiState = uiState.copy(
                isLoading = false,
                seasons = response.seasons,
                providerId = response.providerId,
                error = null
            )
        } catch (e: Exception) {
            uiState = uiState.copy(
                isLoading = false,
                error = e.message ?: "Error loading series"
            )
        }
    }

    if (uiState.isLoading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (uiState.backdropUrl != null) {
            OptimizedAsyncImage(
                url = uiState.backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f),
                                Color.Black
                            )
                        )
                    )
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item { Spacer(Modifier.height(200.dp)) }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Text(
                        text = uiState.title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        uiState.voteAverage?.let { rating ->
                            Surface(
                                color = Color(0xFFFFC107).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "⭐ ${"%.1f".format(rating)}",
                                    color = Color(0xFFFFC107),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        uiState.genres.take(3).forEach { genre ->
                            Surface(
                                color = Color.White.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = genre,
                                    color = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    uiState.overview?.let { overview ->
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = overview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 4
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item {
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        text = "Seasons",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(Modifier.height(12.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(uiState.seasons) { index, season ->
                            val isSelected = index == uiState.selectedSeasonIndex

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    uiState = uiState.copy(selectedSeasonIndex = index)
                                },
                                label = {
                                    Text(season.name ?: "Season ${season.seasonNumber}")
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color.White.copy(alpha = 0.2f),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }

            val selectedSeason = uiState.seasons.getOrNull(uiState.selectedSeasonIndex)
            val episodes = selectedSeason?.episodes ?: emptyList()

            item {
                Text(
                    text = "Episodes (${episodes.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            itemsIndexed(episodes) { index, episode ->
                EpisodeRow(
                    episode = episode,
                    episodeNumber = index + 1,
                    onPlay = {
                        val providerId = uiState.providerId ?: return@EpisodeRow
                        onPlay(
                            providerId,
                            episode.episodeId,
                            episode.containerExtension ?: "mp4"
                        )
                    },
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeInfo,
    episodeNumber: Int,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onPlay,
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "$episodeNumber",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title ?: "Episode $episodeNumber",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )

                episode.durationSecs?.let { secs ->
                    val minutes = secs / 60
                    Text(
                        text = "${minutes} min",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White
                )
            }
        }
    }
}

private fun VodItem.resolvedDescription(): String? {
    return listOfNotNull(tmdbOverview, overview, description, desc, shortDesc, longDesc)
        .firstOrNull { it.isNotBlank() }
        ?.trim()
}
