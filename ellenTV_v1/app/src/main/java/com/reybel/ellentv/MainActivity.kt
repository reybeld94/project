package com.reybel.ellentv.ui
import android.view.WindowManager

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

import android.view.KeyEvent
import androidx.compose.runtime.saveable.rememberSaveable
import com.reybel.ellentv.ui.components.AppSection
import com.reybel.ellentv.ui.components.SideMenuDrawer

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.Player
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.reybel.ellentv.ui.epg.EpgSection
import androidx.activity.viewModels
import com.reybel.ellentv.ui.home.HomeViewModel

import com.reybel.ellentv.ui.player.VideoPlayerView
import com.reybel.ellentv.ui.home.PreviewSection

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import com.reybel.ellentv.ui.home.InfoPanel

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

import androidx.compose.ui.unit.dp

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

import com.reybel.ellentv.R
import com.reybel.ellentv.data.api.EpgGridResponse
import com.reybel.ellentv.data.api.EpgProgram
import com.reybel.ellentv.data.api.LiveItem
import com.reybel.ellentv.data.repo.ChannelRepo

import kotlinx.coroutines.delay

import java.time.Instant
import androidx.compose.ui.Alignment
import kotlin.math.ceil
import kotlin.math.max

private const val INITIAL_EPG_HOURS = 4
private const val INITIAL_EPG_CHANNELS = 30
private const val FULL_EPG_HOURS = 8
private const val FULL_EPG_CHANNELS = 80


@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var playerManager: com.reybel.ellentv.ui.player.PlayerManager

    override fun onCreate(savedInstanceState: Bundle?) {

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        super.onCreate(savedInstanceState)
        val homeVm: HomeViewModel by viewModels()
        val vodVm: com.reybel.ellentv.ui.vod.VodViewModel by viewModels()
        val seriesVm: com.reybel.ellentv.ui.series.SeriesViewModel by viewModels()

        // Fullscreen (Fire TV feel)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        playerManager = com.reybel.ellentv.ui.player.PlayerManager(this)

        setContent {
            MaterialTheme(typography = AppTypography) {
                TvHomeScreen(playerManager, homeVm, vodVm, seriesVm)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        playerManager.pause()
    }

    override fun onResume() {
        super.onResume()
        playerManager.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerManager.release()
    }
}

@Composable
fun TvHomeScreen(
    playerManager: com.reybel.ellentv.ui.player.PlayerManager,
    vm: HomeViewModel,
    vodVm: com.reybel.ellentv.ui.vod.VodViewModel,
    seriesVm: com.reybel.ellentv.ui.series.SeriesViewModel
) {
    val ui by vm.ui.collectAsState()

    var epgGrid by remember { mutableStateOf<EpgGridResponse?>(null) }
    var epgError by remember { mutableStateOf<String?>(null) }

    var providerId by remember { mutableStateOf<String?>(null) }
    var lastEpgFetchAt by remember { mutableStateOf(0L) }
    var isEpgFetchInFlight by remember { mutableStateOf(false) }

    var drawerOpen by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(AppSection.LIVE) }

    var browseLiveId by remember { mutableStateOf<String?>(null) }
    var browseProgram by remember { mutableStateOf<EpgProgram?>(null) }
    val scope = rememberCoroutineScope()
    var hoverJob by remember { mutableStateOf<Job?>(null) }

    // Estados del player para UI feedback
    var isBuffering by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableStateOf(0) }
    var currentUrlLabel by remember { mutableStateOf("") }
    var currentBitrate by remember { mutableStateOf("N/A") }
    var healthIssue by remember { mutableStateOf<String?>(null) }
    var bufferLevel by remember { mutableStateOf("Normal") }

    val vodUi by vodVm.ui.collectAsState()
    val seriesUi by seriesVm.ui.collectAsState()

    var vodLeftEdgeFocused by remember { mutableStateOf(false) }
    var vodActiveFullscreen by remember { mutableStateOf(false) }
    var lastLiveUrls by remember { mutableStateOf<List<String>>(emptyList()) }

    // 🔧 NUEVO: Track si estamos en el borde izquierdo del EPG (columna de canales)
    var epgOnChannelColumn by remember { mutableStateOf(false) }

    fun setBrowseDebounced(liveId: String?, program: EpgProgram?) {
        if (liveId == browseLiveId && program == browseProgram) return

        hoverJob?.cancel()
        hoverJob = scope.launch {
            delay(50)
            browseLiveId = liveId
            browseProgram = program
            vm.setBrowse(liveId, program)
        }
    }

    val repo = remember { ChannelRepo() }

    fun requestedEpgHours(): Int {
        val minutes = epgGrid?.let { epgWindowDurationMinutes(it) } ?: (FULL_EPG_HOURS * 60)
        val currentHours = ceil(minutes.toDouble() / 60.0).toInt().coerceAtLeast(INITIAL_EPG_HOURS)
        return max(currentHours, FULL_EPG_HOURS)
    }

    fun requestedChannelLimit(): Int {
        val currentCount = epgGrid?.count ?: INITIAL_EPG_CHANNELS
        return max(currentCount, FULL_EPG_CHANNELS)
    }

    suspend fun fetchAndMergeEpg(
        pid: String,
        hours: Int,
        limitChannels: Int,
        offsetChannels: Int = 0,
        reason: String,
        preserveWindow: Boolean = true
    ) {
        if (isEpgFetchInFlight) return
        isEpgFetchInFlight = true

        try {
            val incoming = withContext(Dispatchers.IO) {
                repo.fetchEpgGrid(
                    providerId = pid,
                    hours = hours,
                    categoryExtId = null,
                    limitChannels = limitChannels,
                    offsetChannels = offsetChannels
                )
            }

            val merged = mergeEpgGrids(epgGrid, incoming, preserveExistingWindow = preserveWindow)
            val changed = merged != epgGrid
            epgGrid = merged
            lastEpgFetchAt = System.currentTimeMillis()
            epgError = null

            Log.i(
                "ELLENTV_EPG",
                "EPG updated ($reason). changed=$changed window=${merged.window.start}..${merged.window.end} items=${merged.count}"
            )
        } catch (e: Exception) {
            Log.e("ELLENTV_EPG", "EPG fetch failed ($reason): ${e.message}", e)
            epgError = "EPG refresh: ${e.message ?: "error"}"
        } finally {
            isEpgFetchInFlight = false
        }
    }

    var channels by remember { mutableStateOf(emptyList<LiveItem>()) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    var error by remember { mutableStateOf<String?>(null) }

    var streamUrl by remember { mutableStateOf("") }
    var streamAlt1 by remember { mutableStateOf("") }
    var streamAlt2 by remember { mutableStateOf("") }
    var streamAlt3 by remember { mutableStateOf("") }
    var isFullscreen by remember { mutableStateOf(false) }

    val fullscreenFocus = remember { FocusRequester() }

    LaunchedEffect(isFullscreen) {
        if (isFullscreen) fullscreenFocus.requestFocus()
    }

    val player by playerManager.playerFlow.collectAsState()

    // ===== Boot / First-load overlay =====
    var showBoot by remember { mutableStateOf(true) }
    var bootTitle by remember { mutableStateOf("Preparing Live TV, Guide & On-Demand…") }
    var bootProgress by remember { mutableStateOf(0.06f) }
    var isPlayerReady by remember { mutableStateOf(false) }

    var savedVolume by remember { mutableStateOf(1f) }

    LaunchedEffect(showBoot, player) {
        if (showBoot) {
            savedVolume = player.volume
            player.volume = 0f
        } else {
            player.volume = savedVolume
        }
    }

    LaunchedEffect(showBoot, streamUrl) {
        if (!showBoot) {
            player.volume = savedVolume
        }
    }

    LaunchedEffect(player) {
        isPlayerReady = false
        snapshotFlow { player.playbackState }
            .distinctUntilChanged()
            .collect { st ->
                if (st == Player.STATE_READY) isPlayerReady = true
            }
    }

    LaunchedEffect(showBoot, isFullscreen) {
        if (showBoot || isFullscreen) drawerOpen = false
    }

    val bootDataReady = remember(channels, epgGrid) {
        channels.isNotEmpty() && epgGrid != null
    }

    LaunchedEffect(bootDataReady, isPlayerReady) {
        if (showBoot && bootDataReady && isPlayerReady) {
            bootProgress = 1f
            delay(350)
            showBoot = false
        }
    }

    LaunchedEffect(bootDataReady) {
        if (showBoot && bootDataReady) {
            delay(12_000)
            if (showBoot) showBoot = false
        }
    }

    // 🎬 Abrir VOD o Series según la sección actual
    LaunchedEffect(section, providerId) {
        val pid = providerId ?: return@LaunchedEffect
        when (section) {
            AppSection.MOVIES -> vodVm.open(pid)
            AppSection.SERIES -> seriesVm.open(pid)
            else -> {}
        }
    }

    // Setup player callbacks
    DisposableEffect(playerManager) {
        playerManager.onBuffering = { buffering ->
            isBuffering = buffering
        }

        playerManager.onError = { errorMsg ->
            playerError = errorMsg
            scope.launch {
                delay(5000)
                if (playerError == errorMsg) {
                    playerError = null
                }
            }
        }

        playerManager.onRetrying = { count, urlLabel ->
            retryCount = count
            currentUrlLabel = urlLabel
        }

        playerManager.onBitrateChanged = { bitrate ->
            currentBitrate = playerManager.getFormattedBitrate()
        }

        playerManager.onHealthIssue = { issue ->
            healthIssue = issue
            scope.launch {
                delay(3000)
                if (healthIssue == issue) {
                    healthIssue = null
                }
            }
        }

        playerManager.onBufferLevelChanged = { level ->
            bufferLevel = level
        }

        onDispose {
            playerManager.onBuffering = null
            playerManager.onError = null
            playerManager.onRetrying = null
            playerManager.onBitrateChanged = null
            playerManager.onHealthIssue = null
            playerManager.onBufferLevelChanged = null
        }
    }

    // 🔧 MEJORADO: Cuando cambiamos de Live a VOD, detenemos el player completamente
    LaunchedEffect(section) {
        if (section == AppSection.MOVIES || section == AppSection.SERIES) {
            // 🔧 CRÍTICO: Detener completamente para evitar audio de fondo
            playerManager.stop()
        } else if (section == AppSection.LIVE) {
            // Restauramos el stream de Live
            if (!vodActiveFullscreen && lastLiveUrls.isNotEmpty()) {
                playerManager.setStreamUrls(lastLiveUrls)
            }
        }
    }

    LaunchedEffect(streamUrl, streamAlt1, streamAlt2, streamAlt3) {
        if (streamUrl.isNotBlank()) {
            playerError = null
            retryCount = 0
            currentUrlLabel = ""

            val urls = listOfNotNull(
                streamUrl.takeIf { it.isNotBlank() },
                streamAlt1.takeIf { it.isNotBlank() },
                streamAlt2.takeIf { it.isNotBlank() },
                streamAlt3.takeIf { it.isNotBlank() }
            )

            Log.d("ELLENTV_STREAM", "Loading stream with ${urls.size} URLs")
            if (!vodActiveFullscreen && section == AppSection.LIVE) {
                lastLiveUrls = urls
                playerManager.setStreamUrls(urls)
            }
        }
    }

    // Ticker optimizado
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(30_000)
        }
    }

    // ===== Auto-refresh EPG =====
    LaunchedEffect(providerId) {
        val pid = providerId ?: return@LaunchedEffect

        while (true) {
            delay(5 * 60_000L)

            val currentGrid = epgGrid ?: continue
            val windowEnd = runCatching { parseInstantFlexible(currentGrid.window.end) }.getOrNull()

            val staleByTime =
                (System.currentTimeMillis() - lastEpgFetchAt) >= 55 * 60_000L

            val nearWindowEnd =
                windowEnd != null && Duration.between(now, windowEnd).toMinutes() <= 20

            if (!staleByTime && !nearWindowEnd) continue

            val hours = requestedEpgHours()
            val channelsLimit = requestedChannelLimit()

            fetchAndMergeEpg(
                pid = pid,
                hours = hours,
                limitChannels = channelsLimit,
                reason = if (nearWindowEnd) "auto-refresh-window-edge" else "auto-refresh-stale",
                preserveWindow = true
            )
        }
    }

    // Carga inicial
    LaunchedEffect(Unit) {
        try {
            bootTitle = "Loading Live TV channels…"
            bootProgress = 0.15f

            val pid = repo.getDefaultProviderId()
            providerId = pid

            coroutineScope {
                val channelsDeferred = async(Dispatchers.IO) { repo.fetchLive(pid) }
                val epgDeferred = async(Dispatchers.IO) {
                    repo.fetchEpgGrid(
                        providerId = pid,
                        hours = INITIAL_EPG_HOURS,
                        categoryExtId = null,
                        limitChannels = INITIAL_EPG_CHANNELS,
                        offsetChannels = 0
                    )
                }

                bootTitle = "Loading program guide (EPG)…"
                bootProgress = 0.45f

                val items = channelsDeferred.await()
                val gridResp = epgDeferred.await()

                channels = items.sortedWith { a, b ->
                    val an = a.channelNumber ?: Int.MAX_VALUE
                    val bn = b.channelNumber ?: Int.MAX_VALUE
                    val c = an.compareTo(bn)
                    if (c != 0) c else a.name.compareTo(b.name, ignoreCase = true)
                }

                epgGrid = gridResp
                lastEpgFetchAt = System.currentTimeMillis()
                bootTitle = "Preparing video preview…"
                bootProgress = 0.80f
                epgError = null

                val allowed = items.map { it.id }.toSet()

                val preferredId =
                    gridResp.items.firstOrNull { it.liveId in allowed && it.epgSourceId != null && it.programs.isNotEmpty() }?.liveId
                        ?: items.firstOrNull()?.id

                selectedId = preferredId
                error = if (items.isEmpty()) "No hay canales. ¿Tienes approved=true?" else null

                if (!preferredId.isNullOrBlank()) {
                    bootProgress = 0.92f
                    bootTitle = "Almost ready…"
                    streamUrl = withContext(Dispatchers.IO) { repo.fetchPlayUrl(preferredId) }
                } else {
                    streamUrl = ""
                }

                scope.launch {
                    fetchAndMergeEpg(
                        pid = pid,
                        hours = FULL_EPG_HOURS,
                        limitChannels = FULL_EPG_CHANNELS,
                        reason = "expand-epg",
                        preserveWindow = true
                    )
                }

                if (Log.isLoggable("EPG_RAW", Log.DEBUG)) {
                    launch(Dispatchers.IO) {
                        runCatching {
                            val liveRaw = repo.fetchLiveRaw(pid)
                            val raw = repo.fetchEpgGridRaw(
                                providerId = pid,
                                hours = FULL_EPG_HOURS,
                                limitChannels = FULL_EPG_CHANNELS
                            )
                            Log.d("ELLENTV_LIVE_RAW", liveRaw)
                            Log.d("EPG_RAW", raw)
                        }.onFailure { e ->
                            Log.w("ELLENTV_RAW", "Raw fetch failed: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            epgError = e.message ?: "Error loading EPG"
            error = e.message ?: "Error loading channels"
            Log.e("ELLENTV_API", "ERROR: ${e.message}", e)
        }
    }

    // 🔧 NUEVO: BackHandler para VOD/Series
    BackHandler(enabled = !showBoot && !isFullscreen && (section == AppSection.MOVIES || section == AppSection.SERIES)) {
        drawerOpen = true
    }

    // 🔧 NUEVO: BackHandler para fullscreen VOD
    BackHandler(enabled = isFullscreen && vodActiveFullscreen) {
        isFullscreen = false
        vodActiveFullscreen = false
        // 🔧 CRÍTICO: Solo restaurar Live TV si volvemos a la sección LIVE
        // Si estamos en Movies/Series, detener el player completamente
        if (section == AppSection.LIVE && lastLiveUrls.isNotEmpty()) {
            playerManager.setStreamUrls(lastLiveUrls)
        } else {
            // Detener audio para evitar que se escuche el canal de fondo
            playerManager.stop()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (showBoot || isFullscreen) return@onPreviewKeyEvent false

                val ne = event.nativeKeyEvent
                if (ne.action != KeyEvent.ACTION_UP) return@onPreviewKeyEvent false

                if (drawerOpen) {
                    when (ne.keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            drawerOpen = false
                            true
                        }
                        KeyEvent.KEYCODE_BACK,
                        KeyEvent.KEYCODE_ESCAPE -> {
                            drawerOpen = false
                            true
                        }
                        else -> false
                    }
                } else {
                    // 🔧 CORREGIDO: Lógica mejorada para abrir el menú
                    val canOpen = when (section) {
                        AppSection.LIVE -> epgOnChannelColumn // Solo si estamos en la columna de canales
                        AppSection.MOVIES, AppSection.SERIES -> vodLeftEdgeFocused // Solo en borde izquierdo
                        else -> false
                    }

                    if (ne.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && canOpen) {
                        drawerOpen = true
                        true
                    } else {
                        false
                    }
                }
            }
    ) {

        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.45f
        )

        when (section) {
            AppSection.LIVE -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PreviewSection(
                            player = player,
                            attachPlayer = !isFullscreen,
                            modifier = Modifier
                                .fillMaxWidth(0.35f)
                                .height(190.dp)
                        )

                        InfoPanel(
                            browseLiveId = browseLiveId,
                            selectedId = selectedId,
                            browseProgram = browseProgram,
                            epgGrid = epgGrid,
                            channels = channels,
                            now = now
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    EpgSection(
                        error = error,
                        epgError = epgError,
                        epgGrid = epgGrid,
                        channels = channels,
                        selectedId = selectedId,
                        now = now,
                        onSelectLive = { liveId ->
                            if (liveId == selectedId) {
                                isFullscreen = true
                                return@EpgSection
                            }

                            selectedId = liveId
                            browseLiveId = null
                            browseProgram = null

                            scope.launch {
                                try {
                                    streamUrl = repo.fetchPlayUrl(liveId)
                                    error = null
                                } catch (e: Exception) {
                                    error = e.message ?: "Error al abrir canal"
                                    Log.e("ELLENTV_API", "fetchPlayUrl error: ${e.message}", e)
                                }
                            }

                            vm.selectChannel(liveId)
                        },
                        onHover = { liveId, program ->
                            setBrowseDebounced(liveId, program)
                        },
                        // 🔧 NUEVO: Callback para saber si estamos en la columna de canales
                        onChannelColumnFocusChanged = { isOnChannelColumn ->
                            epgOnChannelColumn = isOnChannelColumn
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }

            AppSection.MOVIES -> {
                com.reybel.ellentv.ui.vod.VodScreen(
                    ui = vodUi,
                    onSelectCategory = { vodVm.selectCategory(it) },
                    onRequestMore = { lastIdx -> vodVm.loadMoreIfNeeded(lastIdx) },
                    onPlay = { vodId ->
                        scope.launch {
                            try {
                                val url = vodVm.getPlayUrl(vodId)
                                vodActiveFullscreen = true
                                playerManager.setVodUrl(url)
                                isFullscreen = true
                            } catch (e: Exception) {
                                error = e.message ?: "Error playing VOD"
                            }
                        }
                    },
                    onLeftEdgeFocusChanged = { vodLeftEdgeFocused = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            AppSection.SERIES -> {
                com.reybel.ellentv.ui.series.SeriesScreen(
                    ui = seriesUi,
                    onSelectCategory = { seriesVm.selectCategory(it) },
                    onRequestMore = { lastIdx -> seriesVm.loadMoreIfNeeded(lastIdx) },
                    onPlay = { vodId ->
                        scope.launch {
                            try {
                                val url = seriesVm.getPlayUrl(vodId)
                                vodActiveFullscreen = true
                                playerManager.setStreamUrls(listOf(url))
                                isFullscreen = true
                            } catch (e: Exception) {
                                error = e.message ?: "Error playing series"
                            }
                        }
                    },
                    onLeftEdgeFocusChanged = { vodLeftEdgeFocused = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${section.label}\n(coming soon)",
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (showBoot) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusable()
                    .onPreviewKeyEvent { true }
            ) {
                Image(
                    painter = painterResource(id = R.drawable.background),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f))
                )

                BootLoadingScreen(
                    title = bootTitle,
                    progress = bootProgress
                )
            }
        }

        SideMenuDrawer(
            open = drawerOpen,
            current = section,
            onSelect = { picked ->
                section = picked
                drawerOpen = false
            },
            onClose = { drawerOpen = false }
        )

        if (isFullscreen) {
            BackHandler(enabled = true) {
                isFullscreen = false
                if (vodActiveFullscreen) {
                    vodActiveFullscreen = false
                    // 🔧 CRÍTICO: Solo restaurar Live TV si volvemos a la sección LIVE
                    if (section == AppSection.LIVE && lastLiveUrls.isNotEmpty()) {
                        playerManager.setStreamUrls(lastLiveUrls)
                    } else {
                        playerManager.stop()
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .focusRequester(fullscreenFocus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val ne = event.nativeKeyEvent

                        if (ne.action != android.view.KeyEvent.ACTION_UP) {
                            return@onPreviewKeyEvent true
                        }

                        when (ne.keyCode) {
                            android.view.KeyEvent.KEYCODE_BACK,
                            android.view.KeyEvent.KEYCODE_ESCAPE -> {
                                isFullscreen = false
                                if (vodActiveFullscreen) {
                                    vodActiveFullscreen = false
                                    // 🔧 CRÍTICO: Solo restaurar Live TV si volvemos a la sección LIVE
                                    if (section == AppSection.LIVE && lastLiveUrls.isNotEmpty()) {
                                        playerManager.setStreamUrls(lastLiveUrls)
                                    } else {
                                        playerManager.stop()
                                    }
                                }
                                true
                            }
                            else -> true
                        }
                    }
            ) {
                VideoPlayerView(
                    player = player,
                    modifier = Modifier.fillMaxSize(),
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                )
            }
        }
    }
}

@Composable
private fun BootLoadingScreen(
    title: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(7.dp)
                    .background(Color.White.copy(alpha = 0.22f), shape = RoundedCornerShape(99.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(Color.White, shape = RoundedCornerShape(99.dp))
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Please wait a moment…",
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
