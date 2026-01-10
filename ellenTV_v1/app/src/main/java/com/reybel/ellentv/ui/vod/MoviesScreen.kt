package com.reybel.ellentv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reybel.ellentv.data.api.VodItem
import com.reybel.ellentv.ui.components.OptimizedAsyncImage
import com.reybel.ellentv.ui.components.PosterSkeletonCard

@Composable
fun MoviesScreen(
    ui: MoviesUiState,
    onRequestMore: (collectionId: String, lastVisibleIndex: Int) -> Unit,
    onPlay: (vodId: String) -> Unit,
    onLeftEdgeFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (ui.error != null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = ui.error,
                color = Color.Red.copy(alpha = 0.9f),
                style = MaterialTheme.typography.titleMedium
            )
        }
        return
    }

    var selectedItem by remember { mutableStateOf<VodItem?>(null) }
    BackHandler(enabled = selectedItem != null) {
        selectedItem = null
    }

    if (selectedItem != null) {
        MovieDetailsScreen(
            item = selectedItem ?: return,
            onPlay = onPlay,
            modifier = modifier
        )
        return
    }

    val initialCollection = ui.collections.firstOrNull { it.items.isNotEmpty() }
    var focusedItem by remember(ui.collections) {
        mutableStateOf(initialCollection?.items?.firstOrNull())
    }
    var focusedCollectionId by remember(ui.collections) {
        mutableStateOf(initialCollection?.collectionId)
    }

    LaunchedEffect(ui.collections) {
        if (focusedItem == null) {
            focusedItem = initialCollection?.items?.firstOrNull()
            focusedCollectionId = initialCollection?.collectionId
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(30.dp),
            modifier = Modifier.weight(1f)
        ) {
            itemsIndexed(ui.collections, key = { _, collection -> collection.collectionId }) { index, collection ->
                MoviesCollectionSection(
                    title = collection.title.ifBlank { "Colección #${index + 1}" },
                    collection = collection,
                    onRequestMore = onRequestMore,
                    onPlay = onPlay,
                    onLeftEdgeFocusChanged = onLeftEdgeFocusChanged,
                    onItemFocused = { collectionId, item ->
                        focusedCollectionId = collectionId
                        focusedItem = item
                    },
                    onOpenDetails = { selectedItem = it },
                    showMetadata = collection.collectionId == focusedCollectionId,
                    metadataItem = focusedItem
                )
            }
        }
    }
}

@Composable
private fun MoviesCollectionSection(
    title: String,
    collection: MoviesCollectionUi,
    onRequestMore: (collectionId: String, lastVisibleIndex: Int) -> Unit,
    onPlay: (vodId: String) -> Unit,
    onLeftEdgeFocusChanged: (Boolean) -> Unit,
    onItemFocused: (collectionId: String, item: VodItem) -> Unit,
    onOpenDetails: (VodItem) -> Unit,
    showMetadata: Boolean,
    metadataItem: VodItem?
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
        )

        if (collection.error != null) {
            Text(
                text = collection.error,
                color = Color.Red.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        val rowState = rememberLazyListState()
        var focusedIndex by remember(collection.collectionId) { mutableStateOf(0) }
        val focusedItem = collection.items.getOrNull(focusedIndex) ?: collection.items.firstOrNull()

        LaunchedEffect(focusedIndex, collection.items.size) {
            if (collection.items.isNotEmpty()) {
                rowState.animateScrollToItem(focusedIndex)
            }
        }

        LaunchedEffect(rowState, collection.items.size) {
            snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { lastIdx -> onRequestMore(collection.collectionId, lastIdx) }
        }

        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (collection.items.isEmpty() && collection.isLoading) {
                items(8) { _ ->
                    PosterSkeletonCard(modifier = Modifier.width(128.dp))
                }
            } else {
                itemsIndexed(collection.items, key = { _, item -> item.id }) { itemIndex, item ->
                    MoviePosterCard(
                        item = item,
                        onOpenDetails = onOpenDetails,
                        onLeftEdgeFocusChanged = onLeftEdgeFocusChanged,
                        onFocused = {
                            focusedIndex = itemIndex
                            onItemFocused(collection.collectionId, item)
                        }
                    )
                }
            }
        }

        if (showMetadata) {
            MovieMetadataPanel(item = metadataItem)
        }
    }
}

@Composable
private fun MoviePosterCard(
    item: VodItem,
    onOpenDetails: (VodItem) -> Unit,
    onLeftEdgeFocusChanged: (Boolean) -> Unit,
    onFocused: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.12f)
    val backgroundColor = if (focused) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f)
    val cardWidth = if (focused) 320.dp else 128.dp
    val cardHeight = 200.dp
    val imageUrl = if (focused) item.backdropUrl ?: item.posterUrl else item.posterUrl

    Surface(
        onClick = { onOpenDetails(item) },
        color = backgroundColor,
        shape = RectangleShape,
        border = androidx.compose.foundation.BorderStroke(3.dp, borderColor),
        tonalElevation = if (focused) 6.dp else 2.dp,
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    onFocused()
                }
                onLeftEdgeFocusChanged(it.isFocused)
            }
            .focusable()
    ) {
        if (!imageUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                OptimizedAsyncImage(
                    url = imageUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetSizePx = 512
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Sin imagen",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun MovieDetailsScreen(
    item: VodItem,
    onPlay: (vodId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    var playFocused by remember { mutableStateOf(false) }
    val backdropUrl = item.backdropUrl
    val genre = item.genreNames
        ?.flatMap { it.split(",") }
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?.joinToString(", ")
    val year = item.releaseDate?.extractYearFromDate() ?: item.displayTitle.extractYearFromTitle()
    val infoPairs = listOfNotNull(
        "ID" to item.id,
        "Nombre" to item.name.takeIf { it.isNotBlank() },
        "Título TMDB" to item.tmdbTitle?.takeIf { it.isNotBlank() },
        "Estado TMDB" to item.tmdbStatus?.takeIf { it.isNotBlank() },
        "TMDB ID" to item.tmdbId?.toString(),
        "Categoría" to item.categoryExtId?.toString(),
        "Géneros" to genre?.takeIf { it.isNotBlank() },
        "Fecha" to item.releaseDate?.takeIf { it.isNotBlank() },
        "Aprobado" to item.approved.toString(),
        "Activo" to item.isActive.toString(),
        "Extensión" to item.containerExtension?.takeIf { it.isNotBlank() },
        "Stream URL" to item.streamUrl?.takeIf { it.isNotBlank() }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        if (!backdropUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(0.62f)
                    .heightIn(min = 240.dp)
                    .fillMaxHeight(0.45f)
            ) {
                OptimizedAsyncImage(
                    url = backdropUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetSizePx = 1024
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.9f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.85f)
                                )
                            )
                        )
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .align(Alignment.TopStart),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = item.displayTitle,
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = listOfNotNull("Movie", genre, year).joinToString(" • "),
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = item.overview?.takeIf { it.isNotBlank() } ?: "Sin sinopsis disponible.",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium
            )
            Surface(
                onClick = { onPlay(item.id) },
                color = if (playFocused) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.18f),
                border = androidx.compose.foundation.BorderStroke(
                    2.dp,
                    if (playFocused) Color.White else Color.White.copy(alpha = 0.4f)
                ),
                shape = RectangleShape,
                modifier = Modifier
                    .width(200.dp)
                    .height(56.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { playFocused = it.isFocused }
                    .focusable()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 16.dp)
                    )
                    Text(
                        text = "Play",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .align(Alignment.BottomStart),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Detalles",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            infoPairs.forEach { (label, value) ->
                Text(
                    text = "$label: $value",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "Presiona atrás para volver",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun MovieMetadataPanel(item: VodItem?) {
    val title = item?.displayTitle ?: ""
    val year = item?.releaseDate?.extractYearFromDate() ?: title.extractYearFromTitle()
    val genre = item?.genreNames
        ?.flatMap { it.split(",") }
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?.joinToString(", ")
        ?.takeIf { it.isNotBlank() }
        ?: "Sin género"
    val typeLabel = "Movie"
    val synopsis = item?.overview?.takeIf { it.isNotBlank() } ?: "Sin sinopsis disponible."

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "$typeLabel • $genre • ${year ?: "N/A"}",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = item?.displayTitle ?: "Selecciona una película",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = synopsis,
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun String.extractYearFromTitle(): String? {
    val match = Regex("(19|20)\\d{2}").find(this)
    return match?.value
}

private fun String.extractYearFromDate(): String? {
    return takeIf { length >= 4 }?.substring(0, 4)
}
