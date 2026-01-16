package com.reybel.ellentv.ui.ondemand

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import com.reybel.ellentv.data.api.EpisodeInfo
import com.reybel.ellentv.data.api.SeasonInfo
import com.reybel.ellentv.data.api.VodItem
import com.reybel.ellentv.data.repo.VodRepo
import com.reybel.ellentv.ui.components.OptimizedAsyncImage

private val BackgroundColor = Color(0xFF0A0A0A)
private val CyanAccent = Color(0xFF00D9FF)

data class SeriesDetailsUiState(
    val isLoading: Boolean = true,
    val seriesId: String = "",
    val title: String = "",
    val overview: String? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val voteAverage: Double? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
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
    val focusRequester = remember { FocusRequester() }
    var playFocused by remember { mutableStateOf(false) }
    var trailerFocused by remember { mutableStateOf(false) }
    var listFocused by remember { mutableStateOf(false) }

    var uiState by remember {
        mutableStateOf(
            SeriesDetailsUiState(
                seriesId = item.id,
                title = item.displayTitle,
                overview = item.resolvedDescription(),
                backdropUrl = item.backdropUrl,
                posterUrl = item.posterUrl,
                voteAverage = item.tmdbVoteAverage,
                genres = item.genreNames ?: emptyList(),
                cast = item.resolvedCast() ?: emptyList()
            )
        )
    }
    val repo = remember { VodRepo() }

    var visible by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(500),
        label = "contentAlpha"
    )

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
        visible = true
        focusRequester.requestFocus()
    }

    if (uiState.isLoading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundColor)
    ) {
        val backdropUrl = uiState.backdropUrl
        if (!backdropUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(0.72f)
                    .fillMaxHeight(0.85f)
            ) {
                OptimizedAsyncImage(
                    url = backdropUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetSizePx = 1920
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0.0f to BackgroundColor,
                                    0.05f to BackgroundColor.copy(alpha = 0.98f),
                                    0.15f to BackgroundColor.copy(alpha = 0.85f),
                                    0.30f to BackgroundColor.copy(alpha = 0.5f),
                                    0.50f to BackgroundColor.copy(alpha = 0.1f),
                                    0.65f to Color.Transparent,
                                    1.0f to Color.Transparent
                                )
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    0.70f to BackgroundColor.copy(alpha = 0.4f),
                                    0.85f to BackgroundColor.copy(alpha = 0.85f),
                                    0.95f to BackgroundColor.copy(alpha = 0.98f),
                                    1.0f to BackgroundColor
                                )
                            )
                        )
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 32.dp)
                .graphicsLayer { alpha = contentAlpha }
        ) {
            val year = item.releaseDate?.extractYearFromDate() ?: item.displayTitle.extractYearFromTitle()
            val rating = uiState.voteAverage?.let { String.format("%.1f", it) }
            val language = item.tmdbOriginalLanguage?.uppercase()
            val castText = uiState.cast.take(4).joinToString(", ")
            val genreText = uiState.genres.joinToString(" • ")
            val selectedSeason = uiState.seasons.getOrNull(uiState.selectedSeasonIndex)
            val episodes = selectedSeason?.episodes ?: emptyList()
            val playEpisode = episodes.firstOrNull()
            val providerId = uiState.providerId

            Column(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "SERIES",
                    color = CyanAccent.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium.copy(
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )

                Text(
                    text = uiState.title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rating?.let {
                        Surface(
                            color = Color(0xFFFBBF24).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFFBBF24),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = it,
                                    color = Color(0xFFFBBF24),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                    year?.let { MetadataChip(text = it) }
                    language?.let { MetadataChip(text = it) }
                    if (item.tmdbStatus == "synced") {
                        MetadataChip(
                            text = "TMDB",
                            backgroundColor = Color(0xFF01B4E4).copy(alpha = 0.2f),
                            textColor = Color(0xFF01B4E4)
                        )
                    }
                }

                if (genreText.isNotBlank()) {
                    Text(
                        text = genreText,
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (castText.isNotBlank()) {
                    Text(
                        text = "Cast: $castText",
                        color = Color.White.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                uiState.overview?.let { overview ->
                    Text(
                        text = overview,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 22.sp
                        ),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val playScale by animateFloatAsState(
                        targetValue = if (playFocused) 1.03f else 1f,
                        animationSpec = tween(150),
                        label = "playScale"
                    )
                    val canPlay = providerId != null && playEpisode != null

                    Surface(
                        onClick = {
                            if (canPlay) {
                                onPlay(
                                    providerId!!,
                                    playEpisode!!.episodeId,
                                    playEpisode.containerExtension ?: "mp4"
                                )
                            }
                        },
                        color = if (playFocused) CyanAccent else CyanAccent.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .width(160.dp)
                            .scale(playScale)
                            .focusRequester(focusRequester)
                            .onFocusChanged { playFocused = it.isFocused }
                            .focusable()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Play S${selectedSeason?.seasonNumber ?: 1}E1",
                                color = Color.Black,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }

                    ActionButton(
                        icon = Icons.Outlined.PlayCircleOutline,
                        label = "Trailer",
                        isFocused = trailerFocused,
                        onFocusChanged = { trailerFocused = it },
                        onClick = { }
                    )

                    ActionButton(
                        icon = Icons.Outlined.Add,
                        label = "My List",
                        isFocused = listFocused,
                        onFocusChanged = { listFocused = it },
                        onClick = { }
                    )
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SEASONS",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${uiState.seasons.size} seasons",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(uiState.seasons) { index, season ->
                        val isSelected = index == uiState.selectedSeasonIndex
                        SeasonTab(
                            season = season,
                            isSelected = isSelected,
                            onSelect = { uiState = uiState.copy(selectedSeasonIndex = index) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Season ${selectedSeason?.seasonNumber ?: 1} • ${episodes.size} Episodes",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(episodes) { index, episode ->
                        EpisodeRow(
                            episode = episode,
                            episodeNumber = index + 1,
                            onPlay = {
                                val provider = providerId ?: return@EpisodeRow
                                onPlay(
                                    provider,
                                    episode.episodeId,
                                    episode.containerExtension ?: "mp4"
                                )
                            }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "BACK",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
            Text(
                text = "to go back",
                color = Color.White.copy(alpha = 0.35f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun MetadataChip(
    text: String,
    backgroundColor: Color = Color.White.copy(alpha = 0.1f),
    textColor: Color = Color.White.copy(alpha = 0.85f)
) {
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1f,
        animationSpec = tween(150),
        label = "actionScale"
    )

    Surface(
        onClick = onClick,
        color = if (isFocused) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isFocused) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.12f)
        ),
        modifier = Modifier
            .height(48.dp)
            .scale(scale)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .focusable()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

@Composable
private fun SeasonTab(
    season: SeasonInfo,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) {
        CyanAccent.copy(alpha = 0.2f)
    } else {
        Color.White.copy(alpha = 0.06f)
    }

    val borderColor = if (isSelected) {
        CyanAccent.copy(alpha = 0.5f)
    } else {
        Color.White.copy(alpha = 0.1f)
    }

    Surface(
        onClick = onSelect,
        color = backgroundColor,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Text(
            text = season.name ?: "S${season.seasonNumber}",
            color = if (isSelected) CyanAccent else Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeInfo,
    episodeNumber: Int,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1f,
        animationSpec = tween(150),
        label = "episodeScale"
    )

    val backgroundColor = if (isFocused) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.05f)
    }

    val borderColor = if (isFocused) {
        CyanAccent.copy(alpha = 0.5f)
    } else {
        Color.White.copy(alpha = 0.08f)
    }

    Surface(
        onClick = onPlay,
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                color = if (isFocused) {
                    CyanAccent.copy(alpha = 0.2f)
                } else {
                    Color.White.copy(alpha = 0.1f)
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "$episodeNumber",
                    color = if (isFocused) CyanAccent else Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title ?: "Episode $episodeNumber",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                episode.durationSecs?.let { secs ->
                    val minutes = secs / 60
                    Text(
                        text = "${minutes} min",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = if (isFocused) CyanAccent else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

private fun VodItem.resolvedDescription(): String? {
    return listOfNotNull(tmdbOverview, overview, description, desc, shortDesc, longDesc)
        .firstOrNull { it.isNotBlank() }
        ?.trim()
}

private fun VodItem.resolvedCast(): List<String>? {
    return (tmdbCast ?: cast)
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.ifEmpty { null }
}

private fun String.extractYearFromTitle(): String? {
    val match = Regex("(19|20)\\d{2}").find(this)
    return match?.value
}

private fun String.extractYearFromDate(): String? {
    return takeIf { length >= 4 }?.substring(0, 4)
}
