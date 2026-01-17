package com.reybel.ellentv.ui.ondemand

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.reybel.ellentv.ui.vod.MovieDetailsScreen
import com.reybel.ellentv.ui.vod.SearchOverlay
import com.reybel.ellentv.ui.vod.SearchState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

// Color de acento
private val AccentColor = Color(0xFF00D9FF)

@Composable
fun OnDemandScreen(
    ui: OnDemandUiState,
    searchState: SearchState,
    onFilterChange: (ContentFilter) -> Unit,
    onRequestMore: (collectionId: String, lastVisibleIndex: Int) -> Unit,
    onPlayMovie: (item: VodItem) -> Unit,
    onPlayEpisode: (providerId: String, episodeId: Int, format: String, title: String?, seasonNum: Int?, episodeNum: Int?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchLoadMore: () -> Unit,
    onSearchDismiss: () -> Unit,
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

    var showSearchOverlay by remember { mutableStateOf(false) }
    val searchButtonFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = showSearchOverlay) {
        showSearchOverlay = false
        onSearchDismiss()
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedItem by remember { mutableStateOf<VodItem?>(null) }
    var savedProgress by remember { mutableStateOf<com.reybel.ellentv.data.repo.PlaybackProgress?>(null) }

    var progressMap by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }

    LaunchedEffect(ui.collections) {
        val progressCache = com.reybel.ellentv.data.repo.PlaybackProgressCache(context)
        val allProgress = progressCache.getAllResumable()
        progressMap = allProgress.associate { progress ->
            progress.contentId to (progress.progressPercent / 100f)
        }
    }

    BackHandler(enabled = selectedItem != null && !showSearchOverlay) {
        selectedItem = null
    }

    // Fetch saved progress when item is selected
    LaunchedEffect(selectedItem) {
        savedProgress = selectedItem?.let { item ->
            val progressCache = com.reybel.ellentv.data.repo.PlaybackProgressCache(context)
            progressCache.getProgress(item.id)
        }
    }

    if (selectedItem != null && !showSearchOverlay) {
        val item = selectedItem ?: return
        val isSeries = item.contentType?.lowercase() == "series"
        if (isSeries) {
            SeriesDetailsScreen(
                item = item,
                onPlay = onPlayEpisode,
                onBack = { selectedItem = null },
                modifier = modifier
            )
        } else {
            MovieDetailsScreen(
                item = item,
                onPlay = onPlayMovie,
                savedProgress = savedProgress,
                modifier = modifier
            )
        }
        return
    }

    val filteredCollections = when (ui.currentFilter) {
        ContentFilter.ALL -> ui.collections
        ContentFilter.MOVIES -> ui.collections.filter { it.contentType == ContentFilter.MOVIES }
        ContentFilter.SERIES -> ui.collections.filter { it.contentType == ContentFilter.SERIES }
    }.filter { collection ->
        // Only show collections that are either loading or have items
        collection.isLoading || collection.items.isNotEmpty()
    }

    var focusedCollectionIndex by remember { mutableIntStateOf(0) }
    var focusedItem by remember { mutableStateOf<VodItem?>(null) }
    val focusedIndexByCollection = remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val scrollIndexByCollection = remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    LaunchedEffect(filteredCollections) {
        val firstFocusableIndex = filteredCollections.indexOfFirst { it.items.isNotEmpty() }
        if (firstFocusableIndex >= 0) {
            if (filteredCollections.getOrNull(focusedCollectionIndex)?.items?.isNotEmpty() != true) {
                focusedCollectionIndex = firstFocusableIndex
            }
            if (focusedItem == null) {
                focusedItem = filteredCollections[firstFocusableIndex].items.firstOrNull()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            // Header with filters and search
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "On Demand",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                    FilterChip(
                        selected = ui.currentFilter == ContentFilter.ALL,
                        onClick = { onFilterChange(ContentFilter.ALL) },
                        label = { Text("All") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentColor.copy(alpha = 0.3f),
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = ui.currentFilter == ContentFilter.MOVIES,
                        onClick = { onFilterChange(ContentFilter.MOVIES) },
                        label = { Text("Movies") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentColor.copy(alpha = 0.3f),
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = ui.currentFilter == ContentFilter.SERIES,
                        onClick = { onFilterChange(ContentFilter.SERIES) },
                        label = { Text("Series") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentColor.copy(alpha = 0.3f),
                            selectedLabelColor = Color.White
                        )
                    )
                }

                SearchButton(
                    focusRequester = searchButtonFocusRequester,
                    onClick = { showSearchOverlay = true }
                )
            }

            // Collections with vertical scroll
            val collectionsListState = rememberLazyListState()

            LaunchedEffect(focusedCollectionIndex) {
                if (focusedCollectionIndex in filteredCollections.indices) {
                    collectionsListState.animateScrollToItem(focusedCollectionIndex)
                }
            }

            LazyColumn(
                state = collectionsListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp)
            ) {
                itemsIndexed(filteredCollections, key = { _, col -> col.collectionId }) { colIndex, collection ->
                    val collectionFocusRequester = remember { FocusRequester() }
                    val collectionFocusIndex = focusedIndexByCollection.value[collection.collectionId] ?: 0
                    val collectionScrollIndex = scrollIndexByCollection.value[collection.collectionId] ?: collectionFocusIndex
                    val isFocusedCollection = colIndex == focusedCollectionIndex

                    LaunchedEffect(isFocusedCollection) {
                        if (isFocusedCollection && collection.items.isNotEmpty()) {
                            collectionFocusRequester.requestFocus()
                        }
                    }

                    CollectionRowModern(
                        title = collection.title.ifBlank { "Collection #${colIndex + 1}" },
                        collection = collection,
                        isFocused = isFocusedCollection,
                        onRequestMore = onRequestMore,
                        onLeftEdgeFocusChanged = onLeftEdgeFocusChanged,
                        onItemFocused = { item ->
                            focusedItem = item
                            if (colIndex != focusedCollectionIndex) {
                                focusedCollectionIndex = colIndex
                            }
                        },
                        onOpenDetails = { selectedItem = it },
                        onNavigateUp = {
                            if (colIndex > 0) {
                                focusedCollectionIndex = colIndex - 1
                            } else {
                                searchButtonFocusRequester.requestFocus()
                            }
                        },
                        onNavigateDown = {
                            if (colIndex < filteredCollections.lastIndex) {
                                focusedCollectionIndex = colIndex + 1
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
                        },
                        progressMap = if (collection.collectionId == "continue_watching") progressMap else emptyMap(),
                        metadataItem = if (isFocusedCollection) focusedItem else null
                    )
                }
            }
        }

        if (showSearchOverlay) {
            SearchOverlay(
                searchState = searchState,
                onQueryChange = onSearchQueryChange,
                onSearch = onSearch,
                onSelectItem = { item ->
                    selectedItem = item
                    showSearchOverlay = false
                },
                onLoadMore = onSearchLoadMore,
                onDismiss = {
                    showSearchOverlay = false
                    onSearchDismiss()
                },
                title = "Search On Demand"
            )
        }
    }
}

@Composable
private fun SearchButton(
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val backgroundColor = if (focused) AccentColor.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f)
    val borderColor = if (focused) AccentColor else Color.White.copy(alpha = 0.25f)
    val contentColor = if (focused) AccentColor else Color.White.copy(alpha = 0.8f)

    Surface(
        onClick = onClick,
        color = backgroundColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, borderColor),
        tonalElevation = if (focused) 6.dp else 2.dp,
        interactionSource = interactionSource,
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .height(46.dp)
            .padding(horizontal = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp)
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

@Composable
private fun CollectionRowModern(
    title: String,
    collection: OnDemandCollectionUi,
    isFocused: Boolean,
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
    onScrollIndexChange: (Int) -> Unit,
    progressMap: Map<String, Float> = emptyMap(),
    metadataItem: VodItem? = null
) {
    // Animated alpha for non-focused collections
    val collectionAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.5f,
        animationSpec = tween(durationMillis = 300),
        label = "collectionAlpha"
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isFocused) Color.Black.copy(alpha = 0.4f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(vertical = if (isFocused) 16.dp else 0.dp)
    ) {
        val rowState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)

        // Collection header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isFocused) 12.dp else 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Indicator for focused collection
                if (isFocused) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(24.dp)
                            .background(AccentColor, RoundedCornerShape(2.dp))
                    )
                }

                Text(
                    text = title,
                    color = Color.White.copy(alpha = collectionAlpha),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = if (isFocused) 24.sp else 20.sp
                    )
                )
            }

            // Position indicator
            if (collection.items.isNotEmpty()) {
                val visibleStart = (rowState.firstVisibleItemIndex + 1).coerceAtMost(collection.total)
                val visibleEnd = (rowState.firstVisibleItemIndex +
                    rowState.layoutInfo.visibleItemsInfo.size).coerceAtMost(collection.total)

                Text(
                    text = "$visibleStart-$visibleEnd / ${collection.total}",
                    color = AccentColor.copy(alpha = collectionAlpha * 0.8f),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }

        if (collection.error != null) {
            Text(
                text = collection.error,
                color = Color.Red.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = if (isFocused) 12.dp else 0.dp)
            )
        }

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

        LaunchedEffect(collection.collectionId, targetFocusIndex, collection.items.size) {
            if (collection.items.isNotEmpty()) {
                snapshotFlow {
                    rowState.layoutInfo.visibleItemsInfo.any { it.index == targetFocusIndex }
                }
                    .filter { it }
                    .first()
                focusRequester.requestFocus()
            }
        }

        LaunchedEffect(rowState) {
            snapshotFlow { rowState.firstVisibleItemIndex }
                .collect { index -> onScrollIndexChange(index) }
        }

        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (isFocused) 12.dp else 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (collection.items.isEmpty() && collection.isLoading) {
                items(8) { _ ->
                    PosterSkeletonCard(modifier = Modifier.width(160.dp).height(240.dp))
                }
            } else {
                itemsIndexed(collection.items, key = { _, item -> item.id }) { itemIndex, item ->
                    OnDemandPosterCardModern(
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
                        progressPercentage = progressMap[item.id],
                        collectionAlpha = collectionAlpha,
                        modifier = if (itemIndex == targetFocusIndex) Modifier.focusRequester(focusRequester) else Modifier
                    )
                }
            }
        }

        // Metadata panel for focused collection
        if (isFocused && metadataItem != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OnDemandMetadataPanelCompact(
                item = metadataItem,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

@Composable
private fun OnDemandPosterCardModern(
    item: VodItem,
    onOpenDetails: (VodItem) -> Unit,
    onLeftEdgeFocusChanged: (Boolean) -> Unit,
    onFocused: () -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateDown: () -> Unit,
    progressPercentage: Float? = null,
    collectionAlpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    // Smooth subtle animations - NO crazy width changes
    val animatedBorderColor by animateColorAsState(
        targetValue = if (focused) AccentColor else Color.White.copy(alpha = 0.15f),
        animationSpec = tween(durationMillis = 250),
        label = "borderColor"
    )
    val animatedElevation by animateDpAsState(
        targetValue = if (focused) 12.dp else 2.dp,
        animationSpec = tween(durationMillis = 250),
        label = "elevation"
    )
    val animatedBorderWidth by animateDpAsState(
        targetValue = if (focused) 4.dp else 2.dp,
        animationSpec = tween(durationMillis = 250),
        label = "borderWidth"
    )
    val animatedScale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f),
        label = "scale"
    )

    val cardWidth = 160.dp
    val cardHeight = 240.dp

    Surface(
        onClick = { onOpenDetails(item) },
        color = Color.Black.copy(alpha = 0.3f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(animatedBorderWidth, animatedBorderColor),
        tonalElevation = animatedElevation,
        shadowElevation = if (focused) 16.dp else 0.dp,
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
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
        Box(modifier = Modifier.fillMaxSize()) {
            val posterUrl = item.posterUrl
            if (!posterUrl.isNullOrBlank()) {
                OptimizedAsyncImage(
                    url = posterUrl,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = collectionAlpha },
                    contentScale = ContentScale.Crop,
                    targetSizePx = 512
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .graphicsLayer { alpha = collectionAlpha },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No image",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
            }

            // Gradient overlay for better text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = if (focused) 0.7f else 0.5f)
                            ),
                            startY = 100f
                        )
                    )
            )

            // Content type badge
            item.contentType?.let { contentType ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    color = when (contentType.lowercase()) {
                        "movie" -> Color(0xFF00BCD4).copy(alpha = 0.95f)
                        "series" -> Color(0xFFE91E63).copy(alpha = 0.95f)
                        else -> Color.Gray.copy(alpha = 0.95f)
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = when (contentType.lowercase()) {
                            "movie" -> "MOVIE"
                            "series" -> "SERIES"
                            else -> contentType.uppercase()
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            // Title overlay at bottom
            if (focused) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Text(
                        text = item.displayTitle ?: "Unknown",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Progress bar overlay
            progressPercentage?.let { progress ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                ) {
                    // Background
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.5f))
                    )
                    // Progress
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(AccentColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun OnDemandMetadataPanel(item: VodItem?) {
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

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Background with gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    )
            )

            // Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            // Title with gradient background
            Text(
                text = item?.displayTitle ?: "Select a title",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Rating, Year, Genre in a Row with better styling
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rating?.let {
                    Surface(
                        color = AccentColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, AccentColor.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "★",
                                color = Color(0xFFFFD700),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = it,
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                }

                year?.let {
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )
                }

                genre?.let {
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            cast?.let {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cast:",
                        color = AccentColor.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = synopsis,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 20.sp
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            }
        }
    }
}

@Composable
private fun OnDemandMetadataPanelCompact(
    item: VodItem,
    modifier: Modifier = Modifier
) {
    val year = item.releaseDate?.extractYearFromDate() ?: item.displayTitle?.extractYearFromTitle()
    val rating = item.tmdbVoteAverage?.let { String.format("%.1f", it) }
    val genre = item.genreNames
        ?.flatMap { it.split(",") }
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?.take(2)
        ?.joinToString(" • ")
    val synopsis = item.resolvedDescription()?.takeIf { it.isNotBlank() }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp)
    ) {
        // Title
        Text(
            text = item.displayTitle ?: "Unknown",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Metadata row
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            rating?.let {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "★",
                        color = Color(0xFFFFD700),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }

            year?.let {
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            genre?.let {
                Text(
                    text = it,
                    color = AccentColor.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Synopsis
        synopsis?.let {
            Text(
                text = it,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall.copy(
                    lineHeight = 16.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CollectionRowPreview(
    title: String,
    items: List<VodItem>
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(items.take(10), key = { _, item -> item.id }) { _, item ->
                Surface(
                    color = Color.White.copy(alpha = 0.06f),
                    shape = RectangleShape,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .width(128.dp)
                        .height(180.dp)
                ) {
                    val poster = item.posterUrl
                    if (!poster.isNullOrBlank()) {
                        OptimizedAsyncImage(
                            url = poster,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            targetSizePx = 256
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
        }
    }
}

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
