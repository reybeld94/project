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
import androidx.media3.common.Player
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.reybel.ellentv.ui.epg.EpgSection
import androidx.activity.viewModels
import com.reybel.ellentv.ui.home.HomeViewModel
import com.reybel.ellentv.ui.ondemand.OnDemandScreen
import com.reybel.ellentv.ui.ondemand.OnDemandViewModel

import com.reybel.ellentv.ui.player.VideoPlayerView
import com.reybel.ellentv.ui.home.PreviewSection

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import com.reybel.ellentv.ui.home.InfoPanel
import com.reybel.ellentv.ui.home.FullscreenInfoBar

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
import androidx.compose.ui.platform.LocalContext

import coil.compose.AsyncImage
import coil.request.ImageRequest

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

import com.reybel.ellentv.R
import com.reybel.ellentv.BuildConfig
import com.reybel.ellentv.data.api.EpgGridResponse
import com.reybel.ellentv.data.api.EpgProgram
import com.reybel.ellentv.data.api.LiveItem
import com.reybel.ellentv.data.repo.ChannelRepo
import com.reybel.ellentv.data.repo.GuideCache
import com.reybel.ellentv.data.repo.GuideCachePayload

import kotlinx.coroutines.delay

import java.time.Instant
import androidx.compose.ui.Alignment
import kotlin.math.ceil
import kotlin.math.max

private const val INITIAL_EPG_HOURS = 4
private const val INITIAL_EPG_CHANNELS = 30
private const val FULL_EPG_HOURS = 8
private const val FULL_EPG_CHANNELS = 80
private val DEBUG_LOGS_ENABLED = BuildConfig.DEBUG

private enum class EpgFocusArea {
    CHANNELS,
    PROGRAMS
}

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private lateinit var playerManager: com.reybel.ellentv.ui.player.PlayerManager

    override fun onCreate(savedInstanceState: Bundle?) {

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        super.onCreate(savedInstanceState)
        val homeVm: HomeViewModel by viewModels()
        val onDemandVm: OnDemandViewModel by viewModels {
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return OnDemandViewModel(applicationContext) as T
                }
            }
        }

        // Fullscreen (Fire TV feel)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        playerManager = com.reybel.ellentv.ui.player.PlayerManager(this)

        setContent {
            MaterialTheme(typography = AppTypography) {
                TvHomeScreen(playerManager, homeVm, onDemandVm)
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
    onDemandVm: OnDemandViewModel
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current.applicationContext
    val guideCache = remember { GuideCache(context) }

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

    val onDemandUi by onDemandVm.ui.collectAsState()
    val onDemandSearchState by onDemandVm.searchState.collectAsState()

    var vodLeftEdgeFocused by remember { mutableStateOf(false) }
    var vodActiveFullscreen by remember { mutableStateOf(false) }
    var lastLiveUrls by remember { mutableStateOf<List<String>>(emptyList()) }

    // 🔧 NUEVO: Track si estamos en el borde izquierdo del EPG (columna de canales)
    var epgOnChannelColumn by remember { mutableStateOf(false) }
    var epgSuppressDrawerOpen by remember { mutableStateOf(false) }
    var epgLastFocusArea by remember { mutableStateOf(EpgFocusArea.PROGRAMS) }

    var channels by remember { mutableStateOf(emptyList<LiveItem>()) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    var error by remember { mutableStateOf<String?>(null) }

    var streamUrl by remember { mutableStateOf("") }
    var streamAlt1 by remember { mutableStateOf("") }
    var streamAlt2 by remember { mutableStateOf("") }
    var streamAlt3 by remember { mutableStateOf("") }
    var isFullscreen by remember { mutableStateOf(false) }
    var fullscreenInfoVisible by remember { mutableStateOf(false) }
    var fullscreenInfoJob by remember { mutableStateOf<Job?>(null) }

    val fullscreenFocus = remember { FocusRequester() }

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

    fun showFullscreenInfoBar() {
        fullscreenInfoJob?.cancel()
        fullscreenInfoVisible = true
        fullscreenInfoJob = scope.launch {
            delay(5_000)
            fullscreenInfoVisible = false
        }
    }

    val repo = remember { ChannelRepo() }
    fun sortChannels(items: List<LiveItem>): List<LiveItem> {
        return items.sortedWith { a, b ->
            val an = a.channelNumber ?: Int.MAX_VALUE
            val bn = b.channelNumber ?: Int.MAX_VALUE
            val c = an.compareTo(bn)
            if (c != 0) c else a.name.compareTo(b.name, ignoreCase = true)
        }
    }

    fun tuneToChannel(liveId: String, showInfo: Boolean = false) {
        if (liveId == selectedId) {
            if (showInfo) showFullscreenInfoBar()
            return
        }

        selectedId = liveId
        browseLiveId = null
        browseProgram = null
        if (showInfo) showFullscreenInfoBar()

        scope.launch {
            try {
                val playInfo = repo.fetchPlayInfo(liveId)
                streamUrl = playInfo.url
                streamAlt1 = playInfo.alt1.orEmpty()
                streamAlt2 = playInfo.alt2.orEmpty()
                streamAlt3 = playInfo.alt3.orEmpty()
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Error al abrir canal"
                Log.e("ELLENTV_API", "fetchPlayUrl error: ${e.message}", e)
            }
        }

        vm.selectChannel(liveId)
    }

    fun changeChannelByOffset(offset: Int) {
        if (channels.isEmpty()) return
        val currentIndex = channels.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
        val targetIndex = (currentIndex + offset).coerceIn(0, channels.lastIndex)
        val targetId = channels[targetIndex].id
        tuneToChannel(targetId, showInfo = true)
    }

    fun persistGuideSnapshot(grid: EpgGridResponse? = epgGrid) {
        val pid = providerId ?: return
        val currentGrid = grid ?: return
        if (channels.isEmpty()) return

        val payload = GuideCachePayload(
            providerId = pid,
            channels = channels,
            epg = currentGrid,
            selectedLiveId = selectedId,
            savedAt = System.currentTimeMillis()
        )

        scope.launch {
            guideCache.save(payload)
        }
    }

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
            persistGuideSnapshot(merged)

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

    LaunchedEffect(isFullscreen) {
        if (isFullscreen) fullscreenFocus.requestFocus()
    }

    val player by playerManager.playerFlow.collectAsState()

    LaunchedEffect(Unit) {
        val cached = guideCache.load()
        if (cached != null) {
            providerId = cached.providerId
            channels = sortChannels(cached.channels)
            epgGrid = cached.epg
            lastEpgFetchAt = cached.savedAt

            val restoredSelected = cached.selectedLiveId?.takeIf { id ->
                cached.channels.any { it.id == id }
            }
            selectedId = restoredSelected ?: cached.channels.firstOrNull()?.id
            browseLiveId = selectedId
        }
    }

    // ===== Boot / First-load overlay =====
    var showBoot by remember { mutableStateOf(true) }
    var bootTitle by remember { mutableStateOf("Preparing Live TV, Guide & On-Demand…") }
    var bootProgress by remember { mutableStateOf(0.06f) }
    var isPlayerReady by remember { mutableStateOf(false) }

    var savedVolume by remember { mutableStateOf(1f) }
    var mutedForConnect by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isPlayerReady = playbackState == Player.STATE_READY
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    isPlayerReady = true
                }
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(isBuffering, isPlayerReady, streamUrl, streamAlt1, streamAlt2, streamAlt3) {
        val hasStreamRequest = streamUrl.isNotBlank() || streamAlt1.isNotBlank() || streamAlt2.isNotBlank() || streamAlt3.isNotBlank()
        val shouldMute = isBuffering || (!isPlayerReady && hasStreamRequest)

        if (shouldMute) {
            if (!mutedForConnect) {
                savedVolume = player.volume
                player.volume = 0f
                mutedForConnect = true
            }
        } else if (mutedForConnect) {
            player.volume = savedVolume
            mutedForConnect = false
        }
    }

    LaunchedEffect(showBoot, isFullscreen) {
        if (showBoot || isFullscreen) drawerOpen = false
    }

    val bootDataReady = remember(channels, epgGrid) {
        val hasChannels = channels.isNotEmpty()
        val hasEpgData = epgGrid?.items?.isNotEmpty() == true
        hasChannels && hasEpgData
    }

    LaunchedEffect(bootDataReady) {
        if (showBoot && bootDataReady) {
            bootProgress = 1f
            delay(350)
            showBoot = false
        }
    }

    LaunchedEffect(showBoot) {
        if (!showBoot) return@LaunchedEffect
        delay(12_000)
        if (showBoot) showBoot = false
    }

    // 🎬 Abrir On Demand según la sección actual
    LaunchedEffect(section, providerId) {
        val pid = providerId ?: return@LaunchedEffect
        when (section) {
            AppSection.ON_DEMAND -> onDemandVm.open(pid)
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

    // 🔧 MEJORADO: Cuando cambiamos de Live a On Demand, detenemos el player completamente
    LaunchedEffect(section) {
        if (section == AppSection.ON_DEMAND) {
            // 🔧 CRÍTICO: Detener completamente para evitar audio de fondo
            playerManager.stop()
        } else if (section == AppSection.LIVE) {
            // Restauramos el stream de Live
            if (!vodActiveFullscreen && lastLiveUrls.isNotEmpty()) {
                playerManager.setStreamUrls(lastLiveUrls)
            }
        }
    }

    LaunchedEffect(isFullscreen) {
        if (!isFullscreen) {
            fullscreenInfoJob?.cancel()
            fullscreenInfoVisible = false
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
                val sortedChannels = sortChannels(items)

                channels = sortedChannels

                val mergedGrid = mergeEpgGrids(epgGrid, gridResp, preserveExistingWindow = true)
                epgGrid = mergedGrid
                lastEpgFetchAt = System.currentTimeMillis()
                bootTitle = "Preparing video preview…"
                bootProgress = 0.80f
                epgError = null

                val allowed = sortedChannels.map { it.id }.toSet()

                val preferredId =
                    mergedGrid.items.firstOrNull { it.liveId in allowed && it.epgSourceId != null && it.programs.isNotEmpty() }?.liveId
                        ?: sortedChannels.firstOrNull()?.id

                val keepSelected = selectedId?.takeIf { it in allowed }
                val chosenId = keepSelected ?: preferredId

                selectedId = chosenId
                browseLiveId = browseLiveId ?: chosenId
                error = if (sortedChannels.isEmpty()) "No hay canales. ¿Tienes approved=true?" else null

                if (!chosenId.isNullOrBlank()) {
                    bootProgress = 0.92f
                    bootTitle = "Almost ready…"
                    val playInfo = withContext(Dispatchers.IO) { repo.fetchPlayInfo(chosenId) }
                    streamUrl = playInfo.url
                    streamAlt1 = playInfo.alt1.orEmpty()
                    streamAlt2 = playInfo.alt2.orEmpty()
                    streamAlt3 = playInfo.alt3.orEmpty()
                } else {
                    streamUrl = ""
                    streamAlt1 = ""
                    streamAlt2 = ""
                    streamAlt3 = ""
                }

                persistGuideSnapshot(mergedGrid)

                scope.launch {
                    fetchAndMergeEpg(
                        pid = pid,
                        hours = FULL_EPG_HOURS,
                        limitChannels = FULL_EPG_CHANNELS,
                        reason = "expand-epg",
                        preserveWindow = true
                    )
                }

                if (DEBUG_LOGS_ENABLED && Log.isLoggable("EPG_RAW", Log.DEBUG)) {
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

    // 🔧 BackHandler para Movies: abrir sidebar solo con back
    BackHandler(enabled = !showBoot && !isFullscreen && section == AppSection.ON_DEMAND) {
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
                if (isFullscreen) return@onPreviewKeyEvent false

                val ne = event.nativeKeyEvent
                if (ne.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

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
                        AppSection.ON_DEMAND -> false // Solo se abre con Back en On Demand
                        else -> false
                    }

                    if (ne.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && canOpen) {
                        if (section == AppSection.LIVE && epgSuppressDrawerOpen) {
                            epgSuppressDrawerOpen = false
                            return@onPreviewKeyEvent false
                        }
                        drawerOpen = true
                        true
                    } else {
                        false
                    }
                }
            }
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
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

                            tuneToChannel(liveId)
                        },
                        onHover = { liveId, program ->
                            setBrowseDebounced(liveId, program)
                        },
                        // 🔧 NUEVO: Callback para saber si estamos en la columna de canales
                        onChannelColumnFocusChanged = { isOnChannelColumn ->
                            if (isOnChannelColumn) {
                                if (epgLastFocusArea == EpgFocusArea.PROGRAMS) {
                                    epgSuppressDrawerOpen = true
                                }
                                epgLastFocusArea = EpgFocusArea.CHANNELS
                            } else {
                                epgLastFocusArea = EpgFocusArea.PROGRAMS
                                epgSuppressDrawerOpen = false
                            }
                            epgOnChannelColumn = isOnChannelColumn
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }

            AppSection.ON_DEMAND -> {
                OnDemandScreen(
                    ui = onDemandUi,
                    searchState = onDemandSearchState,
                    onRequestMore = { collectionId, lastIdx ->
                        onDemandVm.loadMoreIfNeeded(collectionId, lastIdx)
                    },
                    onPlayMovie = { item ->
                        scope.launch {
                            try {
                                // Check for saved progress
                                val savedProgress = playerManager.getSavedProgress(item.id)

                                val url = onDemandVm.getMoviePlayUrl(item.id)
                                vodActiveFullscreen = true
                                playerManager.setVodUrl(url)

                                // Set metadata for progress tracking
                                playerManager.setContentMetadata(
                                    contentId = item.id,
                                    contentType = "movie",
                                    title = item.displayTitle,
                                    posterUrl = item.poster ?: item.customPosterUrl,
                                    backdropUrl = item.backdropUrl
                                )

                                // Restore saved position if exists
                                savedProgress?.let { progress ->
                                    playerManager.seekToSavedPosition(progress)
                                }

                                isFullscreen = true
                            } catch (e: Exception) {
                                error = e.message ?: "Error playing VOD"
                            }
                        }
                    },
                    onPlayEpisode = { providerId, episodeId, format, title, seasonNum, episodeNum ->
                        scope.launch {
                            try {
                                val contentId = "$providerId:$episodeId"

                                // Check for saved progress
                                val savedProgress = playerManager.getSavedProgress(contentId)

                                val url = onDemandVm.getSeriesEpisodePlayUrl(providerId, episodeId, format)
                                vodActiveFullscreen = true
                                playerManager.setVodUrl(url)

                                // Set metadata for progress tracking
                                playerManager.setContentMetadata(
                                    contentId = contentId,
                                    contentType = "episode",
                                    title = title,
                                    seasonNumber = seasonNum,
                                    episodeNumber = episodeNum
                                )

                                // Restore saved position if exists
                                savedProgress?.let { progress ->
                                    playerManager.seekToSavedPosition(progress)
                                }

                                isFullscreen = true
                            } catch (e: Exception) {
                                error = e.message ?: "Error playing episode"
                            }
                        }
                    },
                    onSearchQueryChange = { query -> onDemandVm.updateSearchQuery(query) },
                    onSearch = { onDemandVm.performSearch() },
                    onSearchLoadMore = { onDemandVm.loadMoreSearchResults() },
                    onSearchDismiss = { onDemandVm.clearSearch() },
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
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(R.drawable.background)
                        .crossfade(true)
                        .build(),
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
                fullscreenInfoVisible = false
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
                        if (vodActiveFullscreen) {
                            return@onPreviewKeyEvent false
                        }

                        val ne = event.nativeKeyEvent

                        if (ne.action != android.view.KeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent true
                        }

                        when (ne.keyCode) {
                            android.view.KeyEvent.KEYCODE_BACK,
                            android.view.KeyEvent.KEYCODE_ESCAPE -> {
                                isFullscreen = false
                                fullscreenInfoVisible = false
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
                            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                            android.view.KeyEvent.KEYCODE_ENTER -> {
                                if (section == AppSection.LIVE && !vodActiveFullscreen) {
                                    showFullscreenInfoBar()
                                }
                                true
                            }
                            android.view.KeyEvent.KEYCODE_CHANNEL_UP,
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                if (section == AppSection.LIVE && !vodActiveFullscreen) {
                                    changeChannelByOffset(1)
                                }
                                true
                            }
                            android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (section == AppSection.LIVE && !vodActiveFullscreen) {
                                    changeChannelByOffset(-1)
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
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                    showVodControls = vodActiveFullscreen
                )

                if (fullscreenInfoVisible && section == AppSection.LIVE && !vodActiveFullscreen) {
                    FullscreenInfoBar(
                        selectedId = selectedId,
                        browseProgram = browseProgram,
                        epgGrid = epgGrid,
                        channels = channels,
                        now = now,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(160.dp)
                    )
                }
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
