package com.reybel.ellentv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reybel.ellentv.data.api.VodItem
import com.reybel.ellentv.ui.components.OptimizedAsyncImage
import com.reybel.ellentv.ui.components.PosterSkeletonCard

// Color de acento
private val AccentColor = Color(0xFF00D9FF)

@Composable
fun MoviesScreen(
    ui: MoviesUiState,
    searchState: SearchState,
    onRequestMore: (collectionId: String, lastVisibleIndex: Int) -> Unit,
    onPlay: (vodId: String) -> Unit,
    onLeftEdgeFocusChanged: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchLoadMore: () -> Unit,
    onSearchDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Error state
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

    // Search overlay state
    var showSearchOverlay by remember { mutableStateOf(false) }
    val searchButtonFocusRequester = remember { FocusRequester() }

    // BackHandler para cerrar búsqueda
    BackHandler(enabled = showSearchOverlay) {
        showSearchOverlay = false
        onSearchDismiss()
    }

    // Selected item state
    var selectedItem by remember { mutableStateOf<VodItem?>(null) }
    BackHandler(enabled = selectedItem != null && !showSearchOverlay) {
        selectedItem = null
    }

    // Show details screen if item selected
    if (selectedItem != null && !showSearchOverlay) {
        MovieDetailsScreen(
            item = selectedItem ?: return,
            onPlay = onPlay,
            modifier = modifier
        )
        return
    }

    // Track focused collection index and item
    var focusedCollectionIndex by remember { mutableIntStateOf(0) }
    var focusedItem by remember { mutableStateOf<VodItem?>(null) }
    val collectionFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    val focusedIndexByCollection = remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val scrollIndexByCollection = remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    // Initialize with first collection's first item
    LaunchedEffect(ui.collections) {
        if (focusedItem == null && ui.collections.isNotEmpty()) {
            focusedItem = ui.collections.firstOrNull()?.items?.firstOrNull()
        }
    }

    // Request focus on first item when screen opens
    LaunchedEffect(ui.collections, initialFocusRequested) {
        if (!initialFocusRequested && ui.collections.isNotEmpty() &&
            ui.collections.firstOrNull()?.items?.isNotEmpty() == true) {
            collectionFocusRequester.requestFocus()
            initialFocusRequested = true
        }
    }

    // Request focus when collection changes (after initial)
    LaunchedEffect(focusedCollectionIndex) {
        if (initialFocusRequested) {
            collectionFocusRequester.requestFocus()
        }
    }

    val focusedCollection = ui.collections.getOrNull(focusedCollectionIndex)
    val nextCollection = ui.collections.getOrNull(focusedCollectionIndex + 1)

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN CONTENT - Wrapped in Box for overlay support
    // ═══════════════════════════════════════════════════════════════════════════
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            // ═══════════════════════════════════════════════════════════════════
            // HEADER CON TÍTULO Y BOTÓN DE BÚSQUEDA
            // ═══════════════════════════════════════════════════════════════════
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Movies",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )

                SearchButton(
                    focusRequester = searchButtonFocusRequester,
                    onClick = { showSearchOverlay = true }
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // FOCUSED COLLECTION - Always on top
            // ═══════════════════════════════════════════════════════════════════
            focusedCollection?.let { collection ->
                val collectionFocusIndex = focusedIndexByCollection.value[collection.collectionId] ?: 0
                val collectionScrollIndex = scrollIndexByCollection.value[collection.collectionId] ?: collectionFocusIndex
                CollectionRow(
                    title = collection.title.ifBlank { "Collection #${focusedCollectionIndex + 1}" },
                    collection = collection,
                    onRequestMore = onRequestMore,
                    onLeftEdgeFocusChanged = onLeftEdgeFocusChanged,
                    onItemFocused = { item -> focusedItem = item },
                    onOpenDetails = { selectedItem = it },
                    onNavigateUp = {
                        if (focusedCollectionIndex > 0) {
                            focusedCollectionIndex--
                        } else {
                            // Si está en la primera colección, ir al botón de búsqueda
                            searchButtonFocusRequester.requestFocus()
                        }
                    },
                    onNavigateDown = {
                        if (focusedCollectionIndex < ui.collections.lastIndex) {
                            focusedCollectionIndex++
                        }
                    },
                    focusRequester = collectionFocusRequester,
                    focusRequesterIndex = collectionFocusIndex,
                    initialFocusedIndex = collectionFocusIndex,
                    initialScrollIndex = collectionScrollIndex,
                    onFocusedIndexChange = { newIndex ->
                        focusedIndexByCollection.value = focusedIndexByCollection.value
                            .toMutableMap()
                            .apply { put(collection.collectionId, newIndex) }
                    },
                    onScrollIndexChange = { newIndex ->
                        scrollIndexByCollection.value = scrollIndexByCollection.value
                            .toMutableMap()
                            .apply { put(collection.collectionId, newIndex) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ═══════════════════════════════════════════════════════════════════
            // INFO PANEL - Fixed position below focused collection
            // ═══════════════════════════════════════════════════════════════════
            MovieMetadataPanel(item = focusedItem)

            Spacer(modifier = Modifier.height(24.dp))

            // ═══════════════════════════════════════════════════════════════════
            // NEXT COLLECTION PREVIEW (dimmed, not focusable yet)
            // ═══════════════════════════════════════════════════════════════════
            nextCollection?.let { collection ->
                Text(
                    text = collection.title.ifBlank { "Collection #${focusedCollectionIndex + 2}" },
                    color = Color.White.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    userScrollEnabled = false
                ) {
                    itemsIndexed(
                        collection.items.take(8),
                        key = { _, item -> item.id }
                    ) { _, item ->
                        // Dimmed preview cards - not focusable
                        Box(
                            modifier = Modifier
                                .width(100.dp)
                                .height(150.dp)
                                .background(Color.White.copy(alpha = 0.05f))
                        ) {
                            item.posterUrl?.let { url ->
                                OptimizedAsyncImage(
                                    url = url,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.5f)),
                                    contentScale = ContentScale.Crop,
                                    targetSizePx = 256
                                )
                            }
                            // Dim overlay
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f))
                            )
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // SEARCH OVERLAY
        // ═══════════════════════════════════════════════════════════════════════
        AnimatedVisibility(
            visible = showSearchOverlay,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SearchOverlay(
                searchState = searchState,
                onQueryChange = onSearchQueryChange,
                onSearch = onSearch,
                onSelectItem = { item ->
                    showSearchOverlay = false
                    selectedItem = item
                },
                onLoadMore = onSearchLoadMore,
                onDismiss = {
                    showSearchOverlay = false
                    onSearchDismiss()
                }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SEARCH BUTTON
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SearchButton(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = interactionSource.collectIsFocusedAsState().value

    val backgroundColor = if (isFocused) AccentColor else Color.White.copy(alpha = 0.1f)
    val contentColor = if (isFocused) Color.Black else Color.White
    val borderColor = if (isFocused) AccentColor else Color.White.copy(alpha = 0.2f)

    Surface(
        onClick = onClick,
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            onClick()
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Search",
                color = contentColor,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// COLLECTION ROW
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CollectionRow(
    title: String,
    collection: MoviesCollectionUi,
    onRequestMore: (collectionId: String, lastVisibleIndex: Int) -> Unit,
    onLeftEdgeFocusChanged: (Boolean) -> Unit,
    onItemFocused: (VodItem) -> Unit,
    onOpenDetails: (VodItem) -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateDown: () -> Unit,
    focusRequester: FocusRequester,
    focusRequesterIndex: Int,
    initialFocusedIndex: Int,
    initialScrollIndex: Int,
    onFocusedIndexChange: (Int) -> Unit,
    onScrollIndexChange: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
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

        val rowState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)
        var focusedIndex by remember(collection.collectionId) { mutableIntStateOf(initialFocusedIndex) }
        val targetFocusIndex = remember(collection.items.size, focusRequesterIndex) {
            if (collection.items.isEmpty()) 0 else focusRequesterIndex.coerceIn(0, collection.items.lastIndex)
        }

        LaunchedEffect(collection.items.size) {
            if (collection.items.isNotEmpty()) {
                focusedIndex = focusedIndex.coerceIn(0, collection.items.lastIndex)
            }
        }

        LaunchedEffect(focusedIndex, collection.items.size) {
            if (collection.items.isNotEmpty()) {
                rowState.animateScrollToItem(focusedIndex)
            }
        }

        LaunchedEffect(rowState, collection.items.size) {
            snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { lastIdx -> onRequestMore(collection.collectionId, lastIdx) }
        }

        LaunchedEffect(rowState) {
            snapshotFlow { rowState.firstVisibleItemIndex }
                .collect { index -> onScrollIndexChange(index) }
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
                            onFocusedIndexChange(itemIndex)
                            onItemFocused(item)
                        },
                        onNavigateUp = onNavigateUp,
                        onNavigateDown = onNavigateDown,
                        modifier = if (itemIndex == targetFocusIndex) Modifier.focusRequester(focusRequester) else Modifier
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MOVIE POSTER CARD
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MoviePosterCard(
    item: VodItem,
    onOpenDetails: (VodItem) -> Unit,
    onLeftEdgeFocusChanged: (Boolean) -> Unit,
    onFocused: () -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.12f)
    val backgroundColor = if (focused) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f)
    val cardWidth = if (focused) 280.dp else 128.dp
    val cardHeight = 180.dp
    val imageUrl = if (focused) item.backdropUrl ?: item.posterUrl else item.posterUrl

    Surface(
        onClick = { onOpenDetails(item) },
        color = backgroundColor,
        shape = RectangleShape,
        border = BorderStroke(2.dp, borderColor),
        tonalElevation = if (focused) 6.dp else 2.dp,
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionUp -> {
                            onNavigateUp()
                            true
                        }
                        Key.DirectionDown -> {
                            onNavigateDown()
                            true
                        }
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            onOpenDetails(item)
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .onFocusChanged {
                val wasFocused = focused
                focused = it.isFocused
                if (it.isFocused && !wasFocused) {
                    onFocused()
                }
                onLeftEdgeFocusChanged(it.isFocused)
            }
            .focusable()
    ) {
        if (!imageUrl.isNullOrBlank()) {
            OptimizedAsyncImage(
                url = imageUrl,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                targetSizePx = 512
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No image",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MOVIE METADATA PANEL
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MovieMetadataPanel(item: VodItem?) {
    val title = item?.displayTitle ?: ""
    val year = item?.releaseDate?.extractYearFromDate() ?: title.extractYearFromTitle()
    val genre = item?.genreNames
        ?.flatMap { it.split(",") }
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?.joinToString(" • ")
        ?.takeIf { it.isNotBlank() }
    val synopsis = item?.resolvedDescription()?.takeIf { it.isNotBlank() } ?: "No description available."
    val rating = item?.tmdbVoteAverage?.let { String.format("%.1f", it) }
    val cast = item?.resolvedCast()?.take(3)?.joinToString(", ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Title row with rating
        Text(
            text = item?.displayTitle ?: "Select a movie",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Metadata row
        val metaParts = listOfNotNull(
            rating?.let { "★ $it" },
            year,
            genre
        )
        if (metaParts.isNotEmpty()) {
            Text(
                text = metaParts.joinToString(" • "),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Cast
        cast?.let {
            Text(
                text = it,
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Synopsis
        Text(
            text = synopsis,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UTILITY EXTENSIONS
// ═══════════════════════════════════════════════════════════════════════════════

private fun String.extractYearFromTitle(): String? {
    val match = Regex("(19|20)\\d{2}").find(this)
    return match?.value
}

private fun String.extractYearFromDate(): String? {
    return takeIf { length >= 4 }?.substring(0, 4)
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
