package com.reybel.ellentv.ui.vod

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reybel.ellentv.data.api.VodItem
import com.reybel.ellentv.ui.components.OptimizedAsyncImage
import com.reybel.ellentv.ui.components.PosterSkeletonCard
import com.reybel.ellentv.ui.components.TVKeyboard

// ═══════════════════════════════════════════════════════════════════════════════
// COLORES
// ═══════════════════════════════════════════════════════════════════════════════
private val OverlayBackground = Color(0xF0000000)
private val AccentColor = Color(0xFF00D9FF)
private val CardFocusedBorder = Color(0xFF64B5F6)

/**
 * Estado de la búsqueda
 */
data class SearchState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<VodItem> = emptyList(),
    val total: Int = 0,
    val hasSearched: Boolean = false,
    val error: String? = null
)

/**
 * Overlay de búsqueda para Movies.
 *
 * Muestra:
 * - Teclado virtual arriba
 * - Resultados de búsqueda abajo
 * - Navegación completa con D-pad
 */
@Composable
fun SearchOverlay(
    searchState: SearchState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectItem: (VodItem) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // BackHandler para cerrar
    BackHandler(onBack = onDismiss)

    // Estado de foco (teclado vs resultados)
    var focusArea by remember { mutableStateOf(FocusArea.KEYBOARD) }

    // Focus requesters
    val closeButtonFocusRequester = remember { FocusRequester() }
    val firstResultFocusRequester = remember { FocusRequester() }
    val keyboardFocusRequester = remember { FocusRequester() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OverlayBackground)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (event.key == Key.Escape || event.key == Key.Back) {
                    onDismiss()
                    true
                } else {
                    false
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            // ═══════════════════════════════════════════════════════════════════
            // HEADER
            // ═══════════════════════════════════════════════════════════════════
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = AccentColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Search Movies",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                // Botón cerrar
                CloseButton(
                    focusRequester = closeButtonFocusRequester,
                    onClick = onDismiss
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════════════════
            // CONTENIDO PRINCIPAL (Teclado + Resultados)
            // ═══════════════════════════════════════════════════════════════════
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ═══════════════════════════════════════════════════════════════
                // TECLADO (lado izquierdo)
                // ═══════════════════════════════════════════════════════════════
                Box(
                    modifier = Modifier
                        .width(420.dp)
                        .fillMaxHeight()
                ) {
                    TVKeyboard(
                        value = searchState.query,
                        onValueChange = onQueryChange,
                        onSearch = {
                            onSearch()
                            // Mover foco a resultados si hay query
                            if (searchState.query.isNotEmpty()) {
                                focusArea = FocusArea.RESULTS
                            }
                        },
                        placeholder = "Type to search...",
                        useQwertyLayout = true,
                        onNavigateUp = { closeButtonFocusRequester.requestFocus() },
                        onNavigateDown = {
                            // Si hay resultados, ir a ellos
                            if (searchState.results.isNotEmpty()) {
                                focusArea = FocusArea.RESULTS
                                firstResultFocusRequester.requestFocus()
                            }
                        }
                    )
                }

                // ═══════════════════════════════════════════════════════════════
                // RESULTADOS (lado derecho)
                // ═══════════════════════════════════════════════════════════════
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0A0A0A))
                ) {
                    when {
                        searchState.isSearching -> {
                            // Loading
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = AccentColor,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = "Searching...",
                                        color = Color.White.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }

                        searchState.error != null -> {
                            // Error
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = searchState.error,
                                    color = Color.Red.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        !searchState.hasSearched -> {
                            // Estado inicial
                            EmptySearchState(
                                message = "Start typing to search",
                                submessage = "Search across your entire movie catalog"
                            )
                        }

                        searchState.results.isEmpty() -> {
                            // Sin resultados
                            EmptySearchState(
                                message = "No results found",
                                submessage = "Try a different search term"
                            )
                        }

                        else -> {
                            // Mostrar resultados
                            SearchResultsGrid(
                                results = searchState.results,
                                total = searchState.total,
                                onSelectItem = onSelectItem,
                                onLoadMore = onLoadMore,
                                firstItemFocusRequester = firstResultFocusRequester,
                                onNavigateLeft = {
                                    // Volver al teclado
                                    focusArea = FocusArea.KEYBOARD
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMPONENTES
// ═══════════════════════════════════════════════════════════════════════════════

private enum class FocusArea {
    KEYBOARD, RESULTS
}

@Composable
private fun CloseButton(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = interactionSource.collectIsFocusedAsState().value

    val backgroundColor = if (isFocused) AccentColor else Color.White.copy(alpha = 0.1f)
    val iconTint = if (isFocused) Color.Black else Color.White.copy(alpha = 0.7f)

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close",
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun EmptySearchState(
    message: String,
    submessage: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Movie,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = submessage,
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SearchResultsGrid(
    results: List<VodItem>,
    total: Int,
    onSelectItem: (VodItem) -> Unit,
    onLoadMore: () -> Unit,
    firstItemFocusRequester: FocusRequester,
    onNavigateLeft: () -> Unit
) {
    val gridState = rememberLazyGridState()

    // Infinite scroll
    LaunchedEffect(gridState, results.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastIdx ->
                if (lastIdx >= results.size - 6 && results.size < total) {
                    onLoadMore()
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header con conteo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Results",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
            Text(
                text = "$total movies found",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Grid de resultados
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = results,
                key = { _, item -> item.id }
            ) { index, item ->
                SearchResultCard(
                    item = item,
                    onClick = { onSelectItem(item) },
                    focusRequester = if (index == 0) firstItemFocusRequester else null,
                    onNavigateLeft = if (index % 4 == 0) onNavigateLeft else null // Solo en primera columna
                )
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    item: VodItem,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onNavigateLeft: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor = if (isFocused) CardFocusedBorder else Color.Transparent
    val cardScale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(150),
        label = "cardScale"
    )

    val imageUrl = item.customPosterUrl
        ?: item.poster
        ?: item.streamIcon

    Surface(
        onClick = onClick,
        color = if (isFocused) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, borderColor),
        modifier = modifier
            .scale(cardScale)
            .height(190.dp)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (event.key == Key.DirectionLeft && onNavigateLeft != null) {
                    onNavigateLeft()
                    true
                } else {
                    false
                }
            }
            .focusable()
    ) {
        Column {
            // Poster
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(Color.Black)
            ) {
                if (!imageUrl.isNullOrBlank()) {
                    OptimizedAsyncImage(
                        url = imageUrl,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        targetSizePx = 240
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A1A1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                // Rating badge
                item.tmdbVoteAverage?.let { rating ->
                    if (rating > 0) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD700),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = String.format("%.1f", rating),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            // Info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = item.displayTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Año si está disponible
                val year = item.releaseDate?.take(4)
                if (!year.isNullOrBlank()) {
                    Text(
                        text = year,
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
