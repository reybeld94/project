package com.reybel.ellentv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.onFocusChanged
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
        border = BorderStroke(3.dp, borderColor),
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