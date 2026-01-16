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
    }

    var focusedCollectionIndex by remember { mutableIntStateOf(0) }
    var focusedItem by remember { mutableStateOf<VodItem?>(null) }
    val collectionFocusRequester = remember { FocusRequester() }
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

    val focusedCollection = filteredCollections.getOrNull(focusedCollectionIndex)
    val nextCollection = filteredCollections.getOrNull(focusedCollectionIndex + 1)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
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
                            fontWeight = FontWeight.Bold
                        )
                    )
                    FilterChip(
                        selected = ui.currentFilter == ContentFilter.ALL,
                        onClick = { onFilterChange(ContentFilter.ALL) },
                        label = { Text("All") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentColor.copy(alpha = 0.3f)
                        )
                    )
                    FilterChip(
                        selected = ui.currentFilter == ContentFilter.MOVIES,
                        onClick = { onFilterChange(ContentFilter.MOVIES) },
                        label = { Text("Movies") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentColor.copy(alpha = 0.3f)
                        )
                    )
                    FilterChip(
                        selected = ui.currentFilter == ContentFilter.SERIES,
                        onClick = { onFilterChange(ContentFilter.SERIES) },
                        label = { Text("Series") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentColor.copy(alpha = 0.3f)
                        )
                    )
                }

                SearchButton(
                    focusRequester = searchButtonFocusRequester,
                    onClick = { showSearchOverlay = true }
                )
            }

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
                            searchButtonFocusRequester.requestFocus()
                        }
                    },
                    onNavigateDown = {
                        if (focusedCollectionIndex < filteredCollections.lastIndex) {
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
                    },
                    progressMap = if (collection.collectionId == "continue_watching") progressMap else emptyMap()
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            OnDemandMetadataPanel(item = focusedItem)

            Spacer(modifier = Modifier.height(28.dp))

            AnimatedVisibility(
                visible = nextCollection != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                nextCollection?.let { collection ->
                    CollectionRowPreview(
                        title = collection.title.ifBlank { "Next collection" },
                        items = collection.items
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
private fun CollectionRow(
    title: String,
    collection: OnDemandCollectionUi,
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
    progressMap: Map<String, Float> = emptyMap()
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        val rowState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )

            // Position indicator
            if (collection.items.isNotEmpty()) {
                val visibleStart = (rowState.firstVisibleItemIndex + 1).coerceAtMost(collection.total)
                val visibleEnd = (rowState.firstVisibleItemIndex +
                    rowState.layoutInfo.visibleItemsInfo.size).coerceAtMost(collection.total)

                Text(
                    text = "$visibleStart-$visibleEnd of ${collection.total}",
                    color = AccentColor.copy(alpha = 0.8f),
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
                style = MaterialTheme.typography.bodyMedium
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
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (collection.items.isEmpty() && collection.isLoading) {
                items(8) { _ ->
                    PosterSkeletonCard(modifier = Modifier.width(128.dp))
                }
            } else {
                itemsIndexed(collection.items, key = { _, item -> item.id }) { itemIndex, item ->
                    OnDemandPosterCard(
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
                        modifier = if (itemIndex == targetFocusIndex) Modifier.focusRequester(focusRequester) else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun OnDemandPosterCard(
    item: VodItem,
    onOpenDetails: (VodItem) -> Unit,
    onLeftEdgeFocusChanged: (Boolean) -> Unit,
    onFocused: () -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateDown: () -> Unit,
    progressPercentage: Float? = null,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    // Animated properties for smooth transitions
    val animatedBorderColor by animateColorAsState(
        targetValue = if (focused) AccentColor else Color.White.copy(alpha = 0.12f),
        animationSpec = tween(durationMillis = 200),
        label = "borderColor"
    )
    val animatedBackgroundColor by animateColorAsState(
        targetValue = if (focused) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
        animationSpec = tween(durationMillis = 200),
        label = "backgroundColor"
    )
    val animatedCardWidth by animateDpAsState(
        targetValue = if (focused) 280.dp else 128.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "cardWidth"
    )
    val animatedElevation by animateDpAsState(
        targetValue = if (focused) 8.dp else 2.dp,
        animationSpec = tween(durationMillis = 200),
        label = "elevation"
    )
    val animatedBorderWidth by animateDpAsState(
        targetValue = if (focused) 3.dp else 1.dp,
        animationSpec = tween(durationMillis = 200),
        label = "borderWidth"
    )

    val cardHeight = 180.dp
    // Use backdrop for focused cards if available, otherwise poster
    // This prevents image reload when focusing
    val imageUrl = if (focused && item.backdropUrl != null) {
        item.backdropUrl
    } else {
        item.posterUrl
    }

    Surface(
        onClick = { onOpenDetails(item) },
        color = animatedBackgroundColor,
        shape = RoundedCornerShape(if (focused) 8.dp else 4.dp),
        border = BorderStroke(animatedBorderWidth, animatedBorderColor),
        tonalElevation = animatedElevation,
        shadowElevation = if (focused) 12.dp else 0.dp,
        modifier = modifier
            .width(animatedCardWidth)
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
        Box(modifier = Modifier.fillMaxSize()) {
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

            // Content type badge (top-right corner)
            item.contentType?.let { contentType ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    color = when (contentType.lowercase()) {
                        "movie" -> Color(0xFF2196F3).copy(alpha = 0.9f)
                        "series" -> Color(0xFF9C27B0).copy(alpha = 0.9f)
                        else -> Color.Gray.copy(alpha = 0.9f)
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = when (contentType.lowercase()) {
                            "movie" -> "MOVIE"
                            "series" -> "SERIES"
                            else -> contentType.uppercase()
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Progress bar overlay
            progressPercentage?.let { progress ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                ) {
                    // Progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(6.dp)
                                .background(AccentColor)
                        )
                    }
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
