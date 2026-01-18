package com.reybel.ellentv.ui.epg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.produceState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reybel.ellentv.data.api.EpgGridResponse
import com.reybel.ellentv.data.api.EpgProgram
import com.reybel.ellentv.data.api.LiveItem
import com.reybel.ellentv.ui.ChannelData
import com.reybel.ellentv.ui.ClampedProgram
import com.reybel.ellentv.ui.EpgRowData
import com.reybel.ellentv.ui.TimeWindow
import com.reybel.ellentv.ui.absUrl
import com.reybel.ellentv.ui.clampProgramsStatic
import com.reybel.ellentv.ui.components.OPTIMIZED_PLACEHOLDER_MEMORY_KEY
import com.reybel.ellentv.ui.components.OptimizedAsyncImage
import com.reybel.ellentv.ui.drawNowLine
import com.reybel.ellentv.ui.maxInstant
import com.reybel.ellentv.ui.minutesToWidth
import com.reybel.ellentv.ui.minInstant
import com.reybel.ellentv.ui.parseInstantFlexible
import com.reybel.ellentv.ui.roundDownToHalfHour
import com.reybel.ellentv.ui.roundUpToHalfHour
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DEFAULT_VISIBLE_WINDOW_MINUTES = 180L // 3 horas
private const val EXPANSION_MINUTES = 60L
private const val MIN_WINDOW_MINUTES = 60L
private const val PREFETCH_CHANNEL_COUNT = 12

private fun buildTimeWindow(start: Instant, end: Instant, hourWidth: Dp): TimeWindow {
    val safeEnd = if (end.isAfter(start)) end else start.plus(Duration.ofMinutes(1))
    val durationMinutes = Duration.between(start, safeEnd).toMinutes().coerceAtLeast(1)
    val totalWidth = minutesToWidth(durationMinutes, hourWidth)
    return TimeWindow(start, safeEnd, durationMinutes, totalWidth)
}

private fun limitTimeWindow(base: TimeWindow, hourWidth: Dp, desiredMinutes: Long): TimeWindow {
    val target = desiredMinutes
        .coerceAtLeast(MIN_WINDOW_MINUTES)
        .coerceAtMost(base.durationMinutes)

    val newEnd = minInstant(base.start.plus(Duration.ofMinutes(target)), base.end)
    return buildTimeWindow(base.start, newEnd, hourWidth)
}

@Composable
fun EpgSection(
    error: String?,
    epgError: String?,
    epgGrid: EpgGridResponse?,
    channels: List<LiveItem>,
    selectedId: String?,
    now: Instant,
    onSelectLive: (String) -> Unit,
    onHover: (String, EpgProgram?) -> Unit,
    onChannelColumnFocusChanged: (Boolean) -> Unit = {}, // 🔧 NUEVO callback
    modifier: Modifier = Modifier
) {
    var cachedGrid by remember { mutableStateOf<EpgGridResponse?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageLoader = remember(context) { context.imageLoader }

    val logosToPrefetch by remember(channels) {
        derivedStateOf {
            channels
                .sortedBy { it.channelNumber ?: Int.MAX_VALUE }
                .take(PREFETCH_CHANNEL_COUNT)
                .mapNotNull { ch ->
                    absUrl(
                        ch.customLogoUrl
                            ?: ch.logo
                            ?: ch.streamIcon
                    )
                }
        }
    }

    LaunchedEffect(logosToPrefetch, lifecycleOwner) {
        if (logosToPrefetch.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            logosToPrefetch.forEach { logoUrl ->
                val request = ImageRequest.Builder(context)
                    .data(logoUrl)
                    .size(96)
                    .memoryCacheKey(logoUrl)
                    .diskCacheKey(logoUrl)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .placeholderMemoryCacheKey(OPTIMIZED_PLACEHOLDER_MEMORY_KEY)
                    .allowHardware(true)
                    .lifecycle(lifecycleOwner.lifecycle)
                    .build()
                imageLoader.enqueue(request)
            }
        }
    }

    LaunchedEffect(epgGrid) {
        if (epgGrid != null) {
            cachedGrid = epgGrid
        }
    }

    val grid = epgGrid ?: cachedGrid

    Box(
        modifier = modifier
            .clip(
                RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp
                )
            )
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.45f),
                        Color.Black.copy(alpha = 0.30f)
                    )
                )
            )
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            if (error != null) {
                Text("ERROR: $error", color = Color.White)
                Spacer(Modifier.height(8.dp))
            }
            if (epgError != null) {
                Text("EPG ERROR: $epgError", color = Color.White)
                Spacer(Modifier.height(8.dp))
            }

            if (grid == null) {
                EpgSkeleton(channels)
            } else {
                // Pre-procesar datos UNA VEZ
                val channelMap by remember(channels) {
                    derivedStateOf { channels.associateBy { it.id } }
                }

                val allowedIds by remember(channels) {
                    derivedStateOf { channels.map { it.id }.toSet() }
                }

                val filteredItems by remember(grid, allowedIds, channelMap) {
                    derivedStateOf {
                        grid.items
                            .filter { it.liveId in allowedIds }
                            .sortedWith { a, b ->
                                val an = channelMap[a.liveId]?.channelNumber ?: Int.MAX_VALUE
                                val bn = channelMap[b.liveId]?.channelNumber ?: Int.MAX_VALUE
                                val c = an.compareTo(bn)
                                if (c != 0) c else a.name.compareTo(b.name, ignoreCase = true)
                            }
                    }
                }

                val filteredGrid by remember(grid, filteredItems) {
                    derivedStateOf {
                        grid.copy(
                            count = filteredItems.size,
                            items = filteredItems
                        )
                    }
                }

                EpgGridView(
                    grid = filteredGrid,
                    channelMap = channelMap,
                    selectedLiveId = selectedId,
                    now = now,
                    onSelectLive = onSelectLive,
                    onHover = onHover,
                    onChannelColumnFocusChanged = onChannelColumnFocusChanged // 🔧 NUEVO
                )
            }
        }
    }
}

@Composable
fun EpgSkeleton(channels: List<LiveItem>) {
    val channelColWidth = 200.dp
    val rowHeight = 64.dp
    val placeholderRows = if (channels.isEmpty()) 6 else channels.size.coerceAtMost(8)
    val barColor = Color.White.copy(alpha = 0.08f)

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.30f))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(
                    modifier = Modifier
                        .width(channelColWidth)
                        .height(16.dp),
                    color = barColor
                )
                Spacer(Modifier.width(12.dp))
                SkeletonBlock(
                    modifier = Modifier
                        .height(16.dp)
                        .fillMaxWidth(),
                    color = barColor
                )
            }
        }

        repeat(placeholderRows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBlock(
                    modifier = Modifier
                        .width(channelColWidth)
                        .height(rowHeight)
                        .clip(RoundedCornerShape(10.dp)),
                    color = barColor
                )
                Spacer(Modifier.width(12.dp))
                SkeletonBlock(
                    modifier = Modifier
                        .height(56.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)),
                    color = barColor
                )
            }
        }
    }
}

@Composable
private fun SkeletonBlock(modifier: Modifier, color: Color) {
    Box(
        modifier = modifier.background(color)
    )
}

@OptIn(UnstableApi::class)
@Composable
fun EpgGridView(
    grid: EpgGridResponse,
    channelMap: Map<String, LiveItem>,
    selectedLiveId: String?,
    now: Instant,
    onSelectLive: (String) -> Unit,
    onHover: (String, EpgProgram?) -> Unit,
    onChannelColumnFocusChanged: (Boolean) -> Unit = {} // 🔧 NUEVO
) {
    val listState = rememberLazyListState()

    val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }

    fun frFor(id: String): FocusRequester =
        focusRequesters.getOrPut(id) { FocusRequester() }

    LaunchedEffect(selectedLiveId, grid.items) {
        val id = selectedLiveId ?: return@LaunchedEffect
        val idx = grid.items.indexOfFirst { it.liveId == id }
        if (idx < 0) return@LaunchedEffect

        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .first { it.isNotEmpty() }

        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val firstVisible = visibleItems.first().index
        val lastVisible = visibleItems.last().index

        if (idx < firstVisible || idx > lastVisible) {
            listState.scrollToItem(idx)
        }
    }

    val horizontalListState = rememberLazyListState()
    val zone = remember { ZoneId.systemDefault() }
    val fmt = remember { DateTimeFormatter.ofPattern("h:mm a", Locale.US) }
    val hourWidth: Dp = 340.dp

    val baseTimeWindow by remember(grid.window) {
        derivedStateOf {
            val apiStart = parseInstantFlexible(grid.window.start)
            val apiEnd = parseInstantFlexible(grid.window.end)
            val windowStart = roundDownToHalfHour(apiStart)
            val windowEnd = roundUpToHalfHour(apiEnd)
            val windowMinutes = Duration.between(windowStart, windowEnd).toMinutes().coerceAtLeast(1)
            val totalWidth = minutesToWidth(windowMinutes, hourWidth)

            TimeWindow(windowStart, windowEnd, windowMinutes, totalWidth)
        }
    }

    var visibleTimeWindow by remember(baseTimeWindow) {
        mutableStateOf(limitTimeWindow(baseTimeWindow, hourWidth, DEFAULT_VISIBLE_WINDOW_MINUTES))
    }

    LaunchedEffect(baseTimeWindow) {
        visibleTimeWindow = limitTimeWindow(baseTimeWindow, hourWidth, DEFAULT_VISIBLE_WINDOW_MINUTES)
    }

    LaunchedEffect(horizontalListState, baseTimeWindow, hourWidth) {
        snapshotFlow {
            val info = horizontalListState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            Triple(first, last, total)
        }.collect { (first, last, total) ->
            if (total == 0) return@collect

            var updated = visibleTimeWindow

            if (last >= total - 2 && updated.end.isBefore(baseTimeWindow.end)) {
                val newEnd = minInstant(
                    baseTimeWindow.end,
                    updated.end.plus(Duration.ofMinutes(EXPANSION_MINUTES))
                )
                updated = buildTimeWindow(updated.start, newEnd, hourWidth)
            }

            if (first <= 1 && updated.start.isAfter(baseTimeWindow.start)) {
                val newStart = maxInstant(
                    baseTimeWindow.start,
                    updated.start.minus(Duration.ofMinutes(EXPANSION_MINUTES))
                )
                updated = buildTimeWindow(newStart, updated.end, hourWidth)
            }

            if (updated != visibleTimeWindow) {
                visibleTimeWindow = updated
            }
        }
    }

    // Pre-procesar filas en BACKGROUND con caché
    val epgRowsData by produceState<List<EpgRowData>>(
        initialValue = emptyList(),
        key1 = grid.items,
        key2 = channelMap,
        key3 = visibleTimeWindow
    ) {
        value = withContext(Dispatchers.Default) {
            grid.items.map { row ->
                val ch = channelMap[row.liveId]
                val channelData = ChannelData(
                    id = row.liveId,
                    displayName = buildString {
                        if (ch?.channelNumber != null) append("${ch.channelNumber}  ")
                        append(ch?.normalizedName ?: ch?.name ?: row.name)
                    },
                    logoUrl = absUrl(
                        ch?.customLogoUrl
                            ?: ch?.logo
                            ?: ch?.streamIcon
                            ?: row.logo
                    ),
                    channelNumber = ch?.channelNumber
                )

                val clampedPrograms = clampProgramsStatic(
                    row.programs ?: emptyList(),
                    visibleTimeWindow.start,
                    visibleTimeWindow.end
                )

                EpgRowData(row.liveId, channelData, clampedPrograms)
            }
        }
    }

    var cachedRows by remember { mutableStateOf<List<EpgRowData>>(emptyList()) }

    LaunchedEffect(epgRowsData) {
        if (epgRowsData.isNotEmpty()) {
            cachedRows = epgRowsData
        }
    }

    val rowsToRender = if (epgRowsData.isNotEmpty()) epgRowsData else cachedRows

    if (rowsToRender.isEmpty()) {
        EpgSkeleton(channelMap.values.toList())
        return
    }

    val channelColWidth = 200.dp
    val headerHeight = 36.dp
    val rowHeight = 64.dp

    // Header timeline
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.30f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "CHANNELS",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.7f),
                letterSpacing = 1.2.sp,
                modifier = Modifier.width(channelColWidth)
            )

            Spacer(Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .width(visibleTimeWindow.totalWidth)
                    .fillMaxHeight()
            ) {
                LazyRow(
                    state = horizontalListState,
                    modifier = Modifier.fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val startZ = visibleTimeWindow.start.atZone(zone)
                    val halfHourWidth = hourWidth / 2
                    val steps = (visibleTimeWindow.durationMinutes / 30).toInt()

                    items(count = steps + 1, key = { it }) { i ->
                        val t = startZ.plusMinutes((i * 30).toLong())
                        Box(
                            modifier = Modifier.width(halfHourWidth),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (i % 2 == 0) {
                                Text(
                                    t.format(fmt),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawNowLine(now, visibleTimeWindow.start, visibleTimeWindow.end)
                )
            }
        }
    }

    Spacer(Modifier.height(6.dp))

    // OPTIMIZACIONES CRÍTICAS PARA SCROLLING FLUIDO
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = true
    ) {
        items(
            items = rowsToRender,
            key = { it.liveId },
            contentType = { "epg_row" }
        ) { rowData ->
            val isSelected = rowData.liveId == selectedLiveId
            EpgRow(
                rowData = rowData,
                isSelected = isSelected,
                timeWindow = visibleTimeWindow,
                now = now,
                horizontalState = horizontalListState,
                channelColWidth = channelColWidth,
                rowHeight = rowHeight,
                hourWidth = hourWidth,
                focusRequester = frFor(rowData.liveId),
                onSelectLive = onSelectLive,
                onHover = onHover,
                onChannelColumnFocusChanged = onChannelColumnFocusChanged // 🔧 NUEVO
            )
        }
    }
}

@Composable
fun EpgRow(
    rowData: EpgRowData,
    isSelected: Boolean,
    timeWindow: TimeWindow,
    now: Instant,
    horizontalState: LazyListState,
    channelColWidth: Dp,
    rowHeight: Dp,
    hourWidth: Dp,
    focusRequester: FocusRequester,
    onSelectLive: (String) -> Unit,
    onHover: (String, EpgProgram?) -> Unit,
    onChannelColumnFocusChanged: (Boolean) -> Unit = {} // 🔧 NUEVO
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelCellModern(
            channelData = rowData.channelData,
            selected = isSelected,
            onFocused = {
                onHover(rowData.liveId, null)
                onChannelColumnFocusChanged(true) // 🔧 NUEVO: notificar que estamos en la columna
            },
            focusRequester = focusRequester,
            onSelect = { onSelectLive(it) },
            modifier = Modifier
                .width(channelColWidth)
                .height(rowHeight)
        )

        Spacer(Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .width(timeWindow.totalWidth)
                .height(rowHeight)
        ) {
            ProgramsRow(
                liveId = rowData.liveId,
                programs = rowData.clampedPrograms,
                timeWindow = timeWindow,
                hourWidth = hourWidth,
                horizontalState = horizontalState,
                isSelectedChannel = isSelected,
                onFocused = { liveId, program ->
                    onHover(liveId, program)
                    onChannelColumnFocusChanged(false) // 🔧 NUEVO: ya no estamos en columna de canales
                },
                onClick = { onSelectLive(rowData.liveId) }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawNowLine(now, timeWindow.start, timeWindow.end)
            )
        }
    }
}

@Composable
fun ChannelCellModern(
    channelData: ChannelData,
    selected: Boolean,
    onFocused: () -> Unit,
    focusRequester: FocusRequester,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }

    val backgroundColor = when {
        selected -> Color(0xFF1E88E5).copy(alpha = 0.20f)
        focused -> Color.White.copy(alpha = 0.15f)
        else -> Color.Black.copy(alpha = 0.20f)
    }

    val borderColor = when {
        focused -> Color(0xFF42A5F5)
        else -> Color.White.copy(alpha = 0.08f)
    }

    Surface(
        onClick = { onSelect(channelData.id) },
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(2.dp, borderColor),
        tonalElevation = if (focused || selected) 4.dp else 0.dp,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                val was = focused
                focused = it.isFocused
                if (focused && !was) onFocused()
            }
            .focusable()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!channelData.logoUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF8C8C8C))
                ) {
                    OptimizedAsyncImage(
                        url = channelData.logoUrl,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF8C8C8C))
                )
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = channelData.displayName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = Color.White,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun ProgramsRow(
    liveId: String,
    programs: List<ClampedProgram>,
    timeWindow: TimeWindow,
    hourWidth: Dp,
    horizontalState: LazyListState,
    isSelectedChannel: Boolean,
    onFocused: (String, EpgProgram?) -> Unit,
    onClick: () -> Unit
) {
    val segments = remember(programs, timeWindow, hourWidth) {
        buildProgramSegments(programs, timeWindow, hourWidth)
    }

    LazyRow(
        state = horizontalState,
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = segments.contentPadding
    ) {
        items(
            items = segments.items,
            key = { it.key }
        ) { segment ->
            when (segment) {
                is ProgramSegment.Gap -> Spacer(Modifier.width(segment.width))
                is ProgramSegment.Content -> ProgramCellModern(
                    liveId = liveId,
                    program = segment.program,
                    title = segment.title,
                    width = segment.programWidth,
                    isCurrent = segment.isCurrent,
                    isSelectedChannel = isSelectedChannel,
                    onFocused = onFocused,
                    onClick = onClick
                )
            }
        }
    }
}

private data class ProgramSegments(
    val items: List<ProgramSegment>,
    val contentPadding: PaddingValues
)

private sealed interface ProgramSegment {
    val key: String

    data class Gap(
        override val key: String,
        val width: Dp
    ) : ProgramSegment

    data class Content(
        override val key: String,
        val program: EpgProgram?,
        val title: String,
        val isCurrent: Boolean,
        val programWidth: Dp
    ) : ProgramSegment
}

private fun buildProgramSegments(
    programs: List<ClampedProgram>,
    timeWindow: TimeWindow,
    hourWidth: Dp
): ProgramSegments {
    if (programs.isEmpty()) {
        return ProgramSegments(
            items = listOf(
                ProgramSegment.Content(
                    key = "placeholder",
                    program = null,
                    title = "Sin información",
                    isCurrent = false,
                    programWidth = timeWindow.totalWidth
                )
            ),
            contentPadding = PaddingValues()
        )
    }

    val startPaddingMinutes = Duration.between(timeWindow.start, programs.first().startInstant)
        .toMinutes()
        .coerceAtLeast(0)
    val items = mutableListOf<ProgramSegment>()
    var cursor = programs.first().startInstant

    programs.forEachIndexed { index, clamped ->
        if (index > 0) {
            val gapMs = Duration.between(cursor, clamped.startInstant).toMillis()
            if (gapMs > 0) {
                val gapMin = (gapMs / 60_000).coerceAtLeast(0)
                if (gapMin > 0) {
                    val gapWidth = minutesToWidth(gapMin, hourWidth)
                    items.add(
                        ProgramSegment.Gap(
                            key = "gap-$index-${clamped.startInstant.toEpochMilli()}",
                            width = gapWidth
                        )
                    )
                }
            }
        }

        val durMin = Duration.between(clamped.startInstant, clamped.endInstant)
            .toMinutes()
            .coerceAtLeast(1)
        val w = minutesToWidth(durMin, hourWidth)

        items.add(
            ProgramSegment.Content(
                key = "program-$index-${clamped.startInstant.toEpochMilli()}",
                program = clamped.program,
                title = clamped.title,
                isCurrent = clamped.isCurrent,
                programWidth = w
            )
        )

        cursor = clamped.endInstant
    }

    val tailMs = Duration.between(cursor, timeWindow.end).toMillis()
    val endPaddingMinutes = (tailMs / 60_000).coerceAtLeast(0)

    return ProgramSegments(
        items = items,
        contentPadding = PaddingValues(
            start = minutesToWidth(startPaddingMinutes, hourWidth),
            end = minutesToWidth(endPaddingMinutes.toLong(), hourWidth)
        )
    )
}

@Composable
fun ProgramCellModern(
    liveId: String,
    program: EpgProgram?,
    title: String,
    width: Dp,
    isCurrent: Boolean,
    isSelectedChannel: Boolean,
    onFocused: (String, EpgProgram?) -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val backgroundColor = if (focused) {
        Color.White.copy(alpha = 0.15f)
    } else {
        Color.Black.copy(alpha = 0.20f)
    }

    val borderColor = if (focused) {
        Color(0xFF42A5F5)
    } else {
        Color.White.copy(alpha = 0.08f)
    }

    val displayTitle = remember(title) {
        if (title.isBlank()) "(sin título)" else title
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, borderColor),
        tonalElevation = if (focused) 4.dp else 0.dp,
        modifier = Modifier
            .padding(end = 8.dp)
            .width(width)
            .height(56.dp)
            .onFocusChanged {
                val was = focused
                focused = it.isFocused
                if (focused && !was) onFocused(liveId, program)
            }
            .focusable()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = displayTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp
                ),
                lineHeight = 16.sp
            )
        }
    }
}
