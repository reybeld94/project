package com.reybel.ellentv.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reybel.ellentv.ui.components.OptimizedAsyncImage
import com.reybel.ellentv.ui.vod.VodUiState

@Composable
fun SeriesScreen(
    ui: VodUiState,
    onSelectCategory: (Int?) -> Unit,
    onRequestMore: (lastVisibleIndex: Int) -> Unit,
    onPlay: (vodId: String) -> Unit,
    onLeftEdgeFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()

    // infinite scroll trigger
    LaunchedEffect(gridState, ui.items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastIdx -> onRequestMore(lastIdx) }
    }

    Row(modifier = modifier.fillMaxSize()) {

        // Left: categories panel
        Box(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.78f),
                            Color.Black.copy(alpha = 0.55f),
                            Color.Transparent
                        )
                    )
                )
                .padding(12.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    text = "Series On Demand",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(10.dp))

                if (ui.isLoadingCats) {
                    Text("Loading categories…", color = Color.White.copy(alpha = 0.75f))
                } else if (ui.categories.isEmpty()) {
                    Text("No categories", color = Color.White.copy(alpha = 0.75f))
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(ui.categories, key = { _, c -> c.id }) { _, cat ->
                            val selected = ui.selectedCatExtId == cat.extId
                            // 🔧 MEJORADO: Estado de foco para cada categoría
                            var focused by remember { mutableStateOf(false) }

                            val backgroundColor = when {
                                focused -> Color(0xFF42A5F5).copy(alpha = 0.30f)
                                selected -> Color.White.copy(alpha = 0.16f)
                                else -> Color.Transparent
                            }

                            val borderColor = when {
                                focused -> Color(0xFF64B5F6)
                                selected -> Color.White.copy(alpha = 0.20f)
                                else -> Color.Transparent
                            }

                            Surface(
                                onClick = { onSelectCategory(cat.extId) },
                                color = backgroundColor,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(2.dp, borderColor),
                                tonalElevation = if (focused) 4.dp else 0.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .onFocusChanged {
                                        focused = it.isFocused
                                        onLeftEdgeFocusChanged(it.isFocused)
                                    }
                                    .focusable()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = cat.name,
                                        color = Color.White.copy(alpha = if (focused || selected) 0.95f else 0.80f),
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Right: poster grid
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 10.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
        ) {

            if (ui.error != null) {
                Text(
                    text = ui.error,
                    color = Color.Red.copy(alpha = 0.9f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (ui.items.isEmpty() && ui.isLoadingPage) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading series…", color = Color.White.copy(alpha = 0.8f))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    gridItemsIndexed(ui.items, key = { _, it -> it.id }) { _, item ->
                        // 🔧 MEJORADO: Estado de foco individual para cada poster
                        var focused by remember { mutableStateOf(false) }

                        val borderColor = if (focused) {
                            Color(0xFF64B5F6)
                        } else {
                            Color.White.copy(alpha = 0.12f)
                        }

                        val backgroundColor = if (focused) {
                            Color.White.copy(alpha = 0.10f)
                        } else {
                            Color.White.copy(alpha = 0.06f)
                        }

                        Surface(
                            onClick = { onPlay(item.id) },
                            color = backgroundColor,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(3.dp, borderColor),
                            tonalElevation = if (focused) 8.dp else 2.dp,
                            modifier = Modifier
                                .height(220.dp)
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (it.isFocused) onLeftEdgeFocusChanged(false)
                                }
                                .focusable()
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                val poster = item.posterUrl
                                if (!poster.isNullOrBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(170.dp)
                                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                    ) {
                                        OptimizedAsyncImage(
                                            url = poster,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                } else {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(170.dp)
                                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                            .background(Color.Black.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No poster", color = Color.White.copy(alpha = 0.6f))
                                    }
                                }

                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = item.displayTitle,
                                    color = Color.White.copy(alpha = if (focused) 1.0f else 0.9f),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = if (focused) 14.sp else 13.sp
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }
                    }

                    if (ui.isLoadingPage && ui.items.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Loading more…", color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
        }
    }
}
