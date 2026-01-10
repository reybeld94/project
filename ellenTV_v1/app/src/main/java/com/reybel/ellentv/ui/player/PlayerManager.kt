package com.reybel.ellentv.ui.player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main)
    private var retryJob: Job? = null
    private var healthMonitorJob: Job? = null

    // URLs alternativas
    private var currentUrls: List<String> = emptyList()
    private var currentUrlIndex = 0
    private var retryCount = 0
    private val maxRetriesPerUrl = 3

    // Track si el contenido actual es VOD o Live
    private var isVodContent = false

    // Health monitoring
    private var lastPosition = 0L
    private var positionStuckCount = 0
    private var lastBitrate = 0L
    private var zeroBitrateCount = 0

    // "Buffer no progresa" watchdog
    private var lastBufferedPos = 0L
    private var lastBufferedProgressAt = 0L
    private var lastNoProgressReconnectAt = 0L
    private var liveStallRecoveryAttempts = 0

    // Buffer adaptativo
    private var rebufferCount = 0
    private var bufferLevel = BufferLevel.NORMAL
    private var lastRebufferTime = 0L
    private var needsBufferUpgrade = false

    // Crossfade
    private var isCrossfading = false

    // Callbacks para UI
    var onBuffering: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onRetrying: ((Int, String) -> Unit)? = null
    var onBitrateChanged: ((Long) -> Unit)? = null
    var onHealthIssue: ((String) -> Unit)? = null
    var onBufferLevelChanged: ((String) -> Unit)? = null

    // Player observable
    private val _player = MutableStateFlow(createPlayer(BufferLevel.NORMAL))
    val playerFlow: StateFlow<ExoPlayer> = _player.asStateFlow()

    private val DEFAULT_LIVE_BUFFER = BufferLevel.HIGH

    val player: ExoPlayer
        get() = _player.value

    init {
        setupPlayerListeners(player)
        startHealthMonitoring()
    }

    /**
     * DataSource que NO envía Range header en la primera petición.
     * Algunos servidores Xtream rechazan Range requests iniciales.
     */
    private class NoInitialRangeDataSource(
        private val upstream: DataSource
    ) : DataSource by upstream {

        override fun open(dataSpec: DataSpec): Long {
            val fixedSpec =
                if (dataSpec.position == 0L && dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    dataSpec.buildUpon()
                        .setLength(C.LENGTH_UNSET.toLong())
                        .build()
                } else {
                    dataSpec
                }
            return upstream.open(fixedSpec)
        }
    }

    private fun createPlayer(level: BufferLevel): ExoPlayer {
        val bandwidthMeter = DefaultBandwidthMeter.Builder(appContext)
            .setInitialBitrateEstimate(1_000_000L)
            .build()

        // ============================================================
        // 🔧 FIX CRÍTICO: Timeouts LARGOS como VLC
        // El servidor Xtream puede tardar 10-30 segundos en responder
        // ============================================================
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")  // User-Agent de VLC
            .setAllowCrossProtocolRedirects(true)      // Permitir http→https y viceversa
            .setConnectTimeoutMs(60_000)               // 60 segundos para conectar (era 8s)
            .setReadTimeoutMs(60_000)                  // 60 segundos para leer (era 8s)
            .setKeepPostFor302Redirects(false)         // Cambiar POST a GET en redirects
            .setDefaultRequestProperties(
                mapOf(
                    "Connection" to "keep-alive",
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Icy-MetaData" to "1"
                )
            )

        val baseFactory = DefaultDataSource.Factory(appContext, httpFactory)

        val dataSourceFactory = object : DataSource.Factory {
            override fun createDataSource(): DataSource {
                return NoInitialRangeDataSource(baseFactory.createDataSource())
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // Buffer según nivel
        val loadControl = when (level) {
            BufferLevel.LOW -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 15000, 1500, 3000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            BufferLevel.NORMAL -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, 30000, 2500, 5000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            BufferLevel.HIGH -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 50000, 5000, 10000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            BufferLevel.MAXIMUM -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 60000, 8000, 15000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }

        Log.i("ELLENTV_BUFFER", "Creating player with buffer level: $level, timeouts: 60s")

        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build().apply {
                playWhenReady = true
                volume = 1f
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    private fun setupPlayerListeners(p: ExoPlayer) {
        p.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (p !== player) return

                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        Log.d("ELLENTV_PLAYER", "Buffering... (isVOD=$isVodContent)")

                        if (!isVodContent) {
                            val now = System.currentTimeMillis()
                            val timeSinceLastRebuffer = now - lastRebufferTime

                            rebufferCount = if (lastRebufferTime > 0 && timeSinceLastRebuffer < 60_000) {
                                rebufferCount + 1
                            } else {
                                1
                            }

                            Log.w("ELLENTV_BUFFER", "Rebuffer #$rebufferCount (${timeSinceLastRebuffer}ms desde último)")

                            lastRebufferTime = now

                            if (rebufferCount >= 2 && bufferLevel != BufferLevel.MAXIMUM) {
                                val skipToHigh = bufferLevel == BufferLevel.LOW || bufferLevel == BufferLevel.NORMAL
                                Log.e(
                                    "ELLENTV_BUFFER",
                                    "Frequent rebuffering! Upgrading buffer (aggressive=$skipToHigh)..."
                                )
                                needsBufferUpgrade = true
                                onHealthIssue?.invoke("Stream inestable - Aumentando buffer")

                                scope.launch {
                                    delay(800)
                                    if (needsBufferUpgrade) upgradeBuffer(skipToHigh)
                                }
                            }
                        }

                        if (!isCrossfading) {
                            onBuffering?.invoke(true)
                        }
                    }

                    Player.STATE_READY -> {
                        Log.d("ELLENTV_PLAYER", "Ready - URL ${currentUrlIndex + 1}/${currentUrls.size} (isVOD=$isVodContent)")

                        if (isCrossfading) {
                            scope.launch {
                                delay(300)
                                isCrossfading = false
                                onBuffering?.invoke(false)
                            }
                        } else {
                            onBuffering?.invoke(false)
                        }

                        retryCount = 0
                        retryJob?.cancel()
                        positionStuckCount = 0
                        zeroBitrateCount = 0

                        if (!isVodContent) {
                            scope.launch {
                                delay(120_000)
                                if (p === player && p.playbackState == Player.STATE_READY) {
                                    Log.i("ELLENTV_BUFFER", "Stream stable for 2min - resetting rebuffer count")
                                    rebufferCount = 0
                                }
                            }
                        }

                        lastBufferedPos = p.bufferedPosition
                        lastBufferedProgressAt = System.currentTimeMillis()
                    }

                    Player.STATE_ENDED -> {
                        Log.w("ELLENTV_PLAYER", "Playback ended (isVOD=$isVodContent)")
                        if (!isVodContent) {
                            attemptReconnect()
                        }
                    }

                    Player.STATE_IDLE -> {
                        Log.d("ELLENTV_PLAYER", "Idle")
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (p !== player) return

                val currentUrl = if (currentUrls.isNotEmpty() && currentUrlIndex < currentUrls.size) {
                    currentUrls[currentUrlIndex]
                } else ""

                Log.e("ELLENTV_PLAYER", "Error on URL ${currentUrlIndex + 1}/${currentUrls.size}: ${error.errorCodeName} (isVOD=$isVodContent)", error)

                val errorMsg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Error de conexión - reintentando..."

                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Stream no disponible"

                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Formato no soportado o contenido no disponible"

                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Error de decodificación"

                    else -> "Error de reproducción"
                }

                onError?.invoke(errorMsg)

                // Para VOD: NO reintentar automáticamente (la URL puede haber expirado)
                // Para Live: SÍ reintentar
                if (!isVodContent && isRecoverableError(error)) {
                    attemptReconnect()
                } else {
                    Log.w("ELLENTV_PLAYER", "Not retrying (isVOD=$isVodContent)")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (p !== player) return
                if (isPlaying) {
                    Log.d("ELLENTV_PLAYER", "Playing")
                }
            }
        })
    }

    private fun upgradeBuffer(skipToHigh: Boolean) {
        val oldLevel = bufferLevel
        bufferLevel = when {
            skipToHigh && bufferLevel == BufferLevel.LOW -> BufferLevel.HIGH
            skipToHigh && bufferLevel == BufferLevel.NORMAL -> BufferLevel.HIGH
            bufferLevel == BufferLevel.LOW -> BufferLevel.NORMAL
            bufferLevel == BufferLevel.NORMAL -> BufferLevel.HIGH
            bufferLevel == BufferLevel.HIGH -> BufferLevel.MAXIMUM
            else -> BufferLevel.MAXIMUM
        }

        if (oldLevel == bufferLevel) {
            Log.w("ELLENTV_BUFFER", "Already at maximum buffer level")
            return
        }

        Log.i("ELLENTV_BUFFER", "Upgrading buffer: $oldLevel → $bufferLevel")
        onBufferLevelChanged?.invoke("Buffer: ${bufferLevel.label}")

        val oldPlayer = player
        val newPlayer = createPlayer(bufferLevel)
        _player.value = newPlayer
        setupPlayerListeners(newPlayer)

        if (currentUrls.isNotEmpty()) {
            setStreamUrlInternal(currentUrls[currentUrlIndex], isRetry = true, seekTo = 0L)
        }

        scope.launch {
            delay(1000)
            oldPlayer.release()
        }

        rebufferCount = 0
        needsBufferUpgrade = false
    }

    private fun recreatePlayerWithLevel(level: BufferLevel) {
        val oldPlayer = player
        val newPlayer = createPlayer(level)
        _player.value = newPlayer
        setupPlayerListeners(newPlayer)

        scope.launch {
            delay(1000)
            oldPlayer.release()
        }
    }

    private fun startHealthMonitoring() {
        healthMonitorJob = scope.launch {
            while (true) {
                delay(3000)

                if (!isVodContent) {
                    checkBufferNotProgressing()

                    if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                        checkStreamHealth()
                    }
                }
            }
        }
    }

    private fun checkBufferNotProgressing() {
        val p = player

        if (!(p.isLoading || p.playbackState == Player.STATE_BUFFERING)) return
        if (p.mediaItemCount == 0) return

        val now = System.currentTimeMillis()
        val bp = p.bufferedPosition

        if (lastBufferedProgressAt == 0L) {
            lastBufferedPos = bp
            lastBufferedProgressAt = now
            return
        }

        val progressed = bp > (lastBufferedPos + 500)

        if (progressed) {
            lastBufferedPos = bp
            lastBufferedProgressAt = now
            liveStallRecoveryAttempts = 0
            return
        }

        val stuckFor = now - lastBufferedProgressAt
        val coolDown = now - lastNoProgressReconnectAt

        // Para VOD: timeout más largo (el servidor puede tardar)
        val STUCK_MS = if (isVodContent) 30_000L else 20_000L
        val COOLDOWN_MS = 20_000L

        if (stuckFor >= STUCK_MS && coolDown >= COOLDOWN_MS) {
            lastNoProgressReconnectAt = now
            Log.e(
                "ELLENTV_HEALTH",
                "Buffer not progressing for ${stuckFor}ms (threshold=${STUCK_MS}ms, cooldown=${COOLDOWN_MS}ms) → reconnect"
            )
            onHealthIssue?.invoke("Buffer no progresa")

            if (!isVodContent) {
                val recovered = trySoftLiveRecovery()

                if (!recovered) {
                    attemptReconnect()
                    liveStallRecoveryAttempts = 0
                }
            }

            lastBufferedProgressAt = now
            lastBufferedPos = bp
        }
    }

    private fun trySoftLiveRecovery(): Boolean {
        val p = player

        if (isVodContent) return false
        if (liveStallRecoveryAttempts >= 2) return false
        if (p.mediaItemCount == 0) return false

        liveStallRecoveryAttempts++
        val target = (p.currentPosition - 1500L).coerceAtLeast(0L)
        val wasPlaying = p.isPlaying

        Log.w(
            "ELLENTV_HEALTH",
            "Soft live recovery #$liveStallRecoveryAttempts (seekTo=${target}ms)"
        )

        scope.launch {
            try {
                p.playWhenReady = false
                p.seekTo(target)
                p.prepare()
                p.playWhenReady = wasPlaying || p.playWhenReady
            } catch (e: Exception) {
                Log.e("ELLENTV_HEALTH", "Soft recovery failed", e)
            }
        }

        return true
    }

    private fun checkStreamHealth() {
        val currentPosition = player.currentPosition
        val currentBitrate = player.currentTracks.groups
            .firstOrNull()?.mediaTrackGroup?.getFormat(0)?.bitrate?.toLong() ?: 0L

        if (currentBitrate != lastBitrate && currentBitrate > 0) {
            lastBitrate = currentBitrate
            onBitrateChanged?.invoke(currentBitrate)
            Log.d("ELLENTV_HEALTH", "Bitrate: ${currentBitrate / 1000} kbps")
        }

        if (currentPosition == lastPosition && currentPosition > 0) {
            positionStuckCount++
            Log.w("ELLENTV_HEALTH", "Position stuck: $positionStuckCount/3")

            if (positionStuckCount >= 3) {
                Log.e("ELLENTV_HEALTH", "Stream frozen! Reconnecting...")
                onHealthIssue?.invoke("Stream congelado")
                positionStuckCount = 0
                attemptReconnect()
            }
        } else {
            positionStuckCount = 0
        }

        if (currentBitrate == 0L && player.isPlaying) {
            zeroBitrateCount++
            Log.w("ELLENTV_HEALTH", "Zero bitrate: $zeroBitrateCount/4")

            if (zeroBitrateCount >= 4) {
                Log.e("ELLENTV_HEALTH", "No data! Reconnecting...")
                onHealthIssue?.invoke("Sin datos del servidor")
                zeroBitrateCount = 0
                attemptReconnect()
            }
        } else {
            zeroBitrateCount = 0
        }

        lastPosition = currentPosition
    }

    private fun isRecoverableError(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> true
            else -> false
        }
    }

    private fun attemptReconnect() {
        if (isVodContent) {
            Log.w("ELLENTV_PLAYER", "attemptReconnect ignored for VOD")
            return
        }
        if (currentUrls.isEmpty()) {
            Log.e("ELLENTV_PLAYER", "No URLs available")
            onError?.invoke("No hay URLs disponibles")
            return
        }

        retryJob?.cancel()
        retryCount++

        if (retryCount > maxRetriesPerUrl) {
            val nextIndex = currentUrlIndex + 1

            if (nextIndex >= currentUrls.size) {
                Log.e("ELLENTV_PLAYER", "All URLs failed, restarting from URL 1")
                currentUrlIndex = 0
                retryCount = 1
            } else {
                Log.w("ELLENTV_PLAYER", "URL ${currentUrlIndex + 1} failed, trying URL ${nextIndex + 1}")
                currentUrlIndex = nextIndex
                retryCount = 1
            }
        }

        val delayMs = (1000L * (1 shl (retryCount - 1))).coerceAtMost(4000L)
        val urlLabel = "URL ${currentUrlIndex + 1}/${currentUrls.size}"

        Log.w("ELLENTV_PLAYER", "Retry $retryCount/$maxRetriesPerUrl for $urlLabel in ${delayMs}ms")
        onRetrying?.invoke(retryCount, urlLabel)

        retryJob = scope.launch {
            delay(delayMs)
            Log.i("ELLENTV_PLAYER", "Retrying $urlLabel")
            setStreamUrlInternal(currentUrls[currentUrlIndex], isRetry = true)
        }
    }

    fun setStreamUrls(urls: List<String>, withCrossfade: Boolean = true) {
        isVodContent = false

        retryJob?.cancel()
        retryCount = 0
        currentUrlIndex = 0
        rebufferCount = 0
        lastRebufferTime = 0L
        needsBufferUpgrade = false
        liveStallRecoveryAttempts = 0

        if (bufferLevel != DEFAULT_LIVE_BUFFER) {
            bufferLevel = DEFAULT_LIVE_BUFFER
            onBufferLevelChanged?.invoke("Buffer inicial LIVE: ${bufferLevel.label}")
            recreatePlayerWithLevel(DEFAULT_LIVE_BUFFER)
        } else {
            bufferLevel = DEFAULT_LIVE_BUFFER
            onBufferLevelChanged?.invoke("Buffer inicial LIVE: ${bufferLevel.label}")
        }

        lastBufferedPos = 0L
        lastBufferedProgressAt = 0L
        lastNoProgressReconnectAt = 0L

        currentUrls = urls.filter { it.isNotBlank() }

        if (currentUrls.isEmpty()) {
            Log.w("ELLENTV_PLAYER", "No valid URLs")
            player.stop()
            player.clearMediaItems()
            return
        }

        Log.i("ELLENTV_PLAYER", "Setting ${currentUrls.size} Live URLs with crossfade=$withCrossfade")
        currentUrls.forEachIndexed { index, url ->
            Log.i("ELLENTV_URL", "URL[$index]: $url")
        }

        if (withCrossfade && player.isPlaying) {
            isCrossfading = true

            scope.launch {
                player.volume = 0.7f
                delay(150)

                setStreamUrlInternal(currentUrls[0], isRetry = false)

                delay(200)
                player.volume = 1f
            }
        } else {
            setStreamUrlInternal(currentUrls[0], isRetry = false)
        }
    }

    fun setVodUrl(url: String) {
        isVodContent = true

        retryJob?.cancel()
        retryCount = 0
        currentUrlIndex = 0
        currentUrls = listOf(url)

        rebufferCount = 0
        lastRebufferTime = 0L
        needsBufferUpgrade = false
        lastBufferedPos = 0L
        lastBufferedProgressAt = 0L
        lastNoProgressReconnectAt = 0L
        liveStallRecoveryAttempts = 0

        Log.i("ELLENTV_PLAYER", "Setting VOD URL (60s timeout): $url")

        if (url.isBlank()) {
            Log.w("ELLENTV_PLAYER", "Empty VOD URL")
            player.stop()
            player.clearMediaItems()
            return
        }

        setStreamUrlInternal(url, isRetry = false)
    }

    fun setStreamUrl(url: String) {
        setStreamUrls(listOf(url))
    }

    private fun setStreamUrlInternal(url: String, isRetry: Boolean, seekTo: Long = 0L) {
        if (url.isBlank()) {
            player.stop()
            player.clearMediaItems()
            return
        }

        try {
            val mediaItemBuilder = MediaItem.Builder().setUri(url)

            if (!isVodContent) {
                mediaItemBuilder.setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(1.05f)
                        .setMinPlaybackSpeed(0.95f)
                        .setTargetOffsetMs(5_000L)
                        .build()
                )
                Log.d("ELLENTV_PLAYER", "Configured as LIVE stream")
            } else {
                Log.d("ELLENTV_PLAYER", "Configured as VOD stream")
            }

            val mediaItem = mediaItemBuilder.build()

            if (!isRetry) {
                player.stop()
                player.setMediaItem(mediaItem)
                player.prepare()
            } else {
                player.setMediaItem(mediaItem)
                if (seekTo > 0) {
                    player.seekTo(seekTo)
                }
                player.prepare()
            }

            player.playWhenReady = true

        } catch (e: Exception) {
            Log.e("ELLENTV_PLAYER", "Error setting stream", e)
            onError?.invoke("Error al cargar stream")
        }
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun resume() {
        player.playWhenReady = true
    }

    fun stop() {
        retryJob?.cancel()
        player.stop()
        player.clearMediaItems()
        currentUrls = emptyList()
        currentUrlIndex = 0
        retryCount = 0
        isCrossfading = false
        rebufferCount = 0
        isVodContent = false
    }

    fun release() {
        retryJob?.cancel()
        healthMonitorJob?.cancel()
        player.release()
        onBuffering = null
        onError = null
        onRetrying = null
        onBitrateChanged = null
        onHealthIssue = null
        onBufferLevelChanged = null
    }

    // Helpers
    fun getCurrentPosition(): Long = player.currentPosition
    fun getDuration(): Long = player.duration
    fun isPlaying(): Boolean = player.isPlaying
    fun getBufferedPercentage(): Int = player.bufferedPercentage
    fun getCurrentUrlIndex(): Int = currentUrlIndex
    fun getTotalUrls(): Int = currentUrls.size
    fun getCurrentBitrate(): Long = lastBitrate
    fun getBufferLevel(): String = bufferLevel.label

    fun getFormattedBitrate(): String {
        return when {
            lastBitrate == 0L -> "N/A"
            lastBitrate < 1_000_000 -> "${lastBitrate / 1000} kbps"
            else -> String.format("%.1f Mbps", lastBitrate / 1_000_000.0)
        }
    }
}

enum class BufferLevel(val label: String) {
    LOW("Bajo (3-15s)"),
    NORMAL("Normal (5-30s)"),
    HIGH("Alto (10-50s)"),
    MAXIMUM("Máximo (15-60s)")
}
