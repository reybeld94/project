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
import com.reybel.ellentv.data.repo.PlaybackProgress
import com.reybel.ellentv.data.repo.PlaybackProgressCache
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
    private var progressSaveJob: Job? = null
    private var performanceMonitorJob: Job? = null  // 🔍 DIAGNÓSTICO: Monitor de rendimiento

    // Progress tracking
    private val progressCache = PlaybackProgressCache(context)
    private var currentContentId: String? = null
    private var currentContentType: String? = null
    private var currentSeasonNumber: Int? = null
    private var currentEpisodeNumber: Int? = null
    private var currentTitle: String? = null
    private var currentPosterUrl: String? = null
    private var currentBackdropUrl: String? = null

    // URLs alternativas
    private var currentUrls: List<String> = emptyList()
    private var currentUrlIndex = 0
    private var retryCount = 0
    private val maxRetriesPerUrl = 10  // Aumentado de 3 a 10 para mayor persistencia

    // Track si el contenido actual es VOD o Live
    private var isVodContent = false

    // Health monitoring
    private var lastPosition = 0L
    private var positionStuckCount = 0
    private var lastBitrate = 0L
    private var zeroBitrateCount = 0

    // 🔍 DIAGNÓSTICO: Tracking detallado de frames para identificar freezes
    private var lastRenderedFrames = 0L
    private var lastDroppedFrames = 0L
    private var lastBufferCheckTime = 0L
    private var consecutiveLowBuffer = 0
    private var lastEventTimestamp = 0L

    // "Buffer no progresa" watchdog
    private var lastBufferedPos = 0L
    private var lastBufferedProgressAt = 0L
    private var lastNoProgressReconnectAt = 0L
    private var liveStallRecoveryAttempts = 0

    // Buffer adaptativo
    private var rebufferCount = 0
    private var bufferLevel = BufferLevel.NORMAL
    private var lastRebufferTime = 0L
    private var lastBufferUpgradeAt = 0L
    private var needsBufferUpgrade = false

    private val rebufferUpgradeThreshold = 2  // Reducido de 3 a 2 para respuesta más rápida
    private val minBufferUpgradeIntervalMs = 45_000L  // Reducido de 120s a 45s

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

    // 🔧 FIX: Usar buffer LOW para dispositivos limitados como FireTV
    // HIGH/MAXIMUM causan freezes de 1-2s por garbage collection en dispositivos con poca RAM
    // LOW (3-15s) es suficiente y evita sobrecarga de memoria
    // VLC probablemente usa buffers pequeños similares
    private val DEFAULT_LIVE_BUFFER = BufferLevel.LOW

    val player: ExoPlayer
        get() = _player.value

    init {
        setupPlayerListeners(player)
        startHealthMonitoring()
        startPerformanceMonitoring()  // 🔍 DIAGNÓSTICO: Iniciar monitoreo de rendimiento
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
                // 🔧 OPTIMIZADO para FireTV/dispositivos limitados
                // Parámetros: minBuffer, maxBuffer, playbackBuffer, playbackRebuffer
                // Valores conservadores para minimizar uso de memoria
                .setBufferDurationsMs(
                    2000,   // minBufferMs: 2s (reducido de 3s)
                    10000,  // maxBufferMs: 10s (reducido de 15s)
                    1500,   // bufferForPlaybackMs: 1.5s
                    2000    // bufferForPlaybackAfterRebufferMs: 2s
                )
                // FALSE = prioriza tamaño sobre tiempo (menos RAM)
                .setPrioritizeTimeOverSizeThresholds(false)
                // Limitar allocators para reducir fragmentación de memoria
                .setAllocator(
                    androidx.media3.exoplayer.upstream.DefaultAllocator(
                        true,   // trimOnReset
                        16 * 1024  // individualAllocationSize: 16KB (pequeño)
                    )
                )
                .build()

            BufferLevel.NORMAL -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, 30000, 2000, 5000)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()

            BufferLevel.HIGH -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(25000, 60000, 3000, 15000)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()

            BufferLevel.MAXIMUM -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(30000, 90000, 3000, 20000)
                .setPrioritizeTimeOverSizeThresholds(false)
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
        // 🔍 DIAGNÓSTICO: Listener de analytics para tracking de frames y rendimiento
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                if (droppedFrames > 0) {
                    val droppedRate = (droppedFrames.toFloat() / elapsedMs * 1000).toInt()
                    Log.w(
                        "ELLENTV_FRAMES",
                        "🎬 DROPPED FRAMES: $droppedFrames frames in ${elapsedMs}ms (~$droppedRate fps dropped) - POSIBLE FREEZE"
                    )
                }
            }

            override fun onVideoFrameProcessingOffset(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int
            ) {
                if (frameCount > 0) {
                    val avgOffsetMs = totalProcessingOffsetUs / 1000 / frameCount
                    if (avgOffsetMs > 50) {  // Más de 50ms de lag es preocupante
                        Log.w(
                            "ELLENTV_FRAMES",
                            "⏱️ FRAME PROCESSING SLOW: avg ${avgOffsetMs}ms per frame ($frameCount frames) - DECODER STRUGGLING"
                        )
                    }
                }
            }
        })

        p.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (p !== player) return

                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        Log.d("ELLENTV_PLAYER", "Buffering... (isVOD=$isVodContent)")

                        // 🔧 FIX: Deshabilitar buffer adaptativo para live
                        // El cambio dinámico de buffer recreaba el player y causaba freezes
                        // VLC usa un buffer fijo y no lo cambia durante la reproducción
                        // Ahora usamos buffer HIGH fijo desde el inicio
                        /*
                        if (!isVodContent) {
                            val now = System.currentTimeMillis()
                            val timeSinceLastRebuffer = now - lastRebufferTime

                            rebufferCount = if (lastRebufferTime > 0 && timeSinceLastRebuffer < 60_000) {
                                rebufferCount + 1
                            } else {
                                1
                            }

                            Log.w(
                                "ELLENTV_BUFFER",
                                "Rebuffer #$rebufferCount (${timeSinceLastRebuffer}ms desde último, threshold=$rebufferUpgradeThreshold)"
                            )

                            lastRebufferTime = now

                            if (rebufferCount >= rebufferUpgradeThreshold && bufferLevel != BufferLevel.MAXIMUM) {
                                val timeSinceUpgrade = now - lastBufferUpgradeAt
                                if (timeSinceUpgrade >= minBufferUpgradeIntervalMs) {
                                    val skipToHigh = bufferLevel == BufferLevel.LOW || bufferLevel == BufferLevel.NORMAL
                                    Log.e(
                                        "ELLENTV_BUFFER",
                                        "Frequent rebuffering! Upgrading buffer (aggressive=$skipToHigh, threshold=$rebufferUpgradeThreshold)..."
                                    )
                                    needsBufferUpgrade = true
                                    onHealthIssue?.invoke("Stream inestable - Aumentando buffer")
                                    lastBufferUpgradeAt = now

                                    scope.launch {
                                        delay(800)
                                        if (needsBufferUpgrade) upgradeBuffer(skipToHigh)
                                    }
                                } else {
                                    Log.w(
                                        "ELLENTV_BUFFER",
                                        "Upgrade skipped (cooldown=${minBufferUpgradeIntervalMs}ms, since=${timeSinceUpgrade}ms)"
                                    )
                                }
                            }
                        }
                        */

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
                                delay(180_000)  // Aumentado de 2min a 3min
                                if (p === player && p.playbackState == Player.STATE_READY) {
                                    Log.i("ELLENTV_BUFFER", "Stream stable for 3min - resetting rebuffer count")
                                    rebufferCount = 0
                                }
                            }
                        }

                        lastBufferedPos = p.bufferedPosition
                        lastBufferedProgressAt = System.currentTimeMillis()
                    }

                    Player.STATE_ENDED -> {
                        Log.w("ELLENTV_PLAYER", "Playback ended (isVOD=$isVodContent)")
                        if (isVodContent) {
                            // Mark as completed when video ends
                            saveProgressNow(markCompleted = true)
                        } else {
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
                delay(5000)  // Aumentado de 3s a 5s para ser menos agresivo

                // 🔧 FIX: Deshabilitar health monitoring para live streams
                // Este monitoreo constante estaba causando interrupciones y freezes
                // VLC no hace este tipo de verificaciones agresivas
                if (isVodContent) {
                    // Solo para VOD mantenemos verificación básica
                    checkBufferNotProgressing()
                }
                // DESHABILITADO para LIVE: checkStreamHealth() - causaba freezes
            }
        }
    }

    // 🔍 DIAGNÓSTICO: Monitor de rendimiento para identificar causa exacta de freezes
    private fun startPerformanceMonitoring() {
        performanceMonitorJob = scope.launch {
            delay(5000)  // Esperar 5s antes de empezar a monitorear

            while (true) {
                delay(2000)  // Check cada 2 segundos

                if (!isVodContent && player.isPlaying) {
                    val p = player
                    val now = System.currentTimeMillis()

                    // Analizar estado del buffer
                    val bufferedPercent = p.bufferedPercentage
                    val bufferedPos = p.bufferedPosition
                    val currentPos = p.currentPosition
                    val bufferedAheadMs = bufferedPos - currentPos

                    // Analizar frames (si están disponibles)
                    val videoFormat = p.videoFormat
                    val droppedFrames = try {
                        p.analyticsCollector.videoFormat?.let {
                            // Intentar obtener estadísticas de frames
                            0L  // Placeholder
                        } ?: 0L
                    } catch (e: Exception) {
                        0L
                    }

                    // ALERTA: Buffer peligrosamente bajo
                    if (bufferedAheadMs < 3000) {  // Menos de 3 segundos
                        consecutiveLowBuffer++
                        Log.e(
                            "ELLENTV_PERF",
                            "⚠️ LOW BUFFER: ${bufferedAheadMs}ms ahead (${bufferedPercent}%) - count: $consecutiveLowBuffer - FREEZE RISK!"
                        )

                        if (consecutiveLowBuffer >= 3) {  // 3 checks consecutivos = 6 segundos
                            Log.e(
                                "ELLENTV_PERF",
                                "🔴 BUFFER CRITICALLY LOW for 6s - FREEZE LIKELY OCCURRING"
                            )
                        }
                    } else {
                        if (consecutiveLowBuffer > 0) {
                            Log.i(
                                "ELLENTV_PERF",
                                "✅ Buffer recovered: ${bufferedAheadMs}ms ahead (${bufferedPercent}%)"
                            )
                        }
                        consecutiveLowBuffer = 0
                    }

                    // Log periódico de estado general (cada 10 segundos)
                    if (now - lastBufferCheckTime > 10000) {
                        val bitrate = getCurrentBitrate()
                        val bitrateStr = if (bitrate > 0) "${bitrate / 1000} kbps" else "N/A"

                        Log.d(
                            "ELLENTV_PERF",
                            "📊 Status: Buffer=${bufferedAheadMs}ms (${bufferedPercent}%), " +
                            "Bitrate=$bitrateStr, " +
                            "Format=${videoFormat?.width}x${videoFormat?.height}@${videoFormat?.frameRate}fps, " +
                            "Codec=${videoFormat?.sampleMimeType}"
                        )

                        lastBufferCheckTime = now
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

        // 🔧 FIX: Aumentar timeouts significativamente para evitar reconexiones innecesarias
        // VLC espera mucho más antes de decidir que algo está mal
        val STUCK_MS = if (isVodContent) 60_000L else 60_000L  // 60 segundos para ambos
        val COOLDOWN_MS = 60_000L  // 60 segundos de cooldown

        if (stuckFor >= STUCK_MS && coolDown >= COOLDOWN_MS) {
            lastNoProgressReconnectAt = now
            Log.e(
                "ELLENTV_HEALTH",
                "Buffer not progressing for ${stuckFor}ms (threshold=${STUCK_MS}ms, cooldown=${COOLDOWN_MS}ms) → reconnect"
            )
            onHealthIssue?.invoke("Buffer no progresa")

            if (!isVodContent) {
                // 🔧 FIX: Deshabilitar soft live recovery - causaba micro-pausas
                // VLC no hace estos seeks hacia atrás que interrumpen la reproducción
                // Simplemente reconectamos si es absolutamente necesario
                // val recovered = trySoftLiveRecovery()
                // if (!recovered) {
                attemptReconnect()
                liveStallRecoveryAttempts = 0
                // }
            }

            lastBufferedProgressAt = now
            lastBufferedPos = bp
        }
    }

    private fun trySoftLiveRecovery(): Boolean {
        val p = player

        if (isVodContent) return false
        if (liveStallRecoveryAttempts >= 5) return false  // Aumentado de 2 a 5 intentos
        if (p.mediaItemCount == 0) return false

        liveStallRecoveryAttempts++
        val target = (p.currentPosition - 2000L).coerceAtLeast(0L) // Aumentado de -400ms a -2000ms
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

    // 🔧 FIX: Función DESHABILITADA - causaba freezes en live streams
    // Esta verificación constante de posición y bitrate interrumpía la reproducción
    // VLC confía en que el player maneje el buffering automáticamente
    private fun checkStreamHealth() {
        // DESHABILITADO COMPLETAMENTE para evitar interrupciones
        // El health monitoring agresivo causaba más problemas que soluciones

        // Solo registramos el bitrate actual si cambia (sin acciones)
        val currentBitrate = player.currentTracks.groups
            .firstOrNull()?.mediaTrackGroup?.getFormat(0)?.bitrate?.toLong() ?: 0L

        if (currentBitrate != lastBitrate && currentBitrate > 0) {
            lastBitrate = currentBitrate
            onBitrateChanged?.invoke(currentBitrate)
            Log.d("ELLENTV_HEALTH", "Bitrate: ${currentBitrate / 1000} kbps")
        }

        /*
        // DESHABILITADO: Position stuck check
        val currentPosition = player.currentPosition
        if (currentPosition == lastPosition && currentPosition > 0) {
            positionStuckCount++
            Log.w("ELLENTV_HEALTH", "Position stuck: $positionStuckCount/10")

            if (positionStuckCount >= 10) {  // Aumentado de 5 a 10 (50 segundos)
                Log.e("ELLENTV_HEALTH", "Stream frozen! Reconnecting...")
                onHealthIssue?.invoke("Stream congelado")
                positionStuckCount = 0
                if (player.playbackState == Player.STATE_BUFFERING) {
                    attemptReconnect()
                } else {
                    Log.w("ELLENTV_HEALTH", "Skipping reconnect; player not buffering")
                }
            }
        } else {
            positionStuckCount = 0
        }

        // DESHABILITADO: Zero bitrate check
        if (currentBitrate == 0L && player.isPlaying) {
            zeroBitrateCount++
            Log.w("ELLENTV_HEALTH", "Zero bitrate: $zeroBitrateCount/12")

            if (zeroBitrateCount >= 12) {  // Aumentado de 6 a 12 (60 segundos)
                Log.e("ELLENTV_HEALTH", "No data! Reconnecting...")
                onHealthIssue?.invoke("Sin datos del servidor")
                zeroBitrateCount = 0
                if (player.playbackState == Player.STATE_BUFFERING) {
                    attemptReconnect()
                } else {
                    Log.w("ELLENTV_HEALTH", "Skipping reconnect; player not buffering")
                }
            }
        } else {
            zeroBitrateCount = 0
        }

        lastPosition = currentPosition
        */
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

        val delayMs = (1000L * (1 shl (retryCount - 1))).coerceAtMost(12000L)  // Aumentado de 4s a 12s
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
        lastBufferUpgradeAt = 0L
        needsBufferUpgrade = false
        liveStallRecoveryAttempts = 0

        if (bufferLevel != DEFAULT_LIVE_BUFFER) {
            bufferLevel = DEFAULT_LIVE_BUFFER
            onBufferLevelChanged?.invoke("Buffer LIVE optimizado para FireTV: ${bufferLevel.label}")
            recreatePlayerWithLevel(DEFAULT_LIVE_BUFFER)
        } else {
            bufferLevel = DEFAULT_LIVE_BUFFER
            onBufferLevelChanged?.invoke("Buffer LIVE optimizado para FireTV: ${bufferLevel.label}")
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
        lastBufferUpgradeAt = 0L
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
                // 🔧 FIX: Configuración minimalista para FireTV
                // Reducir ajustes de velocidad al mínimo
                // Target offset bajo para buffer pequeño (2-10s)
                mediaItemBuilder.setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxPlaybackSpeed(1.01f)      // Casi sin aceleración (reducido de 1.02)
                        .setMinPlaybackSpeed(0.99f)      // Casi sin desaceleración (reducido de 0.98)
                        .setTargetOffsetMs(5_000L)       // 5s target (ajustado a buffer bajo)
                        .build()
                )
                Log.d("ELLENTV_PLAYER", "Configured as LIVE stream (FireTV optimized: minimal buffer & adjustments)")
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
        if (isVodContent) {
            saveProgressNow()
        }
    }

    fun resume() {
        player.playWhenReady = true
    }

    fun stop() {
        if (isVodContent) {
            saveProgressNow()
        }
        progressSaveJob?.cancel()
        retryJob?.cancel()
        player.stop()
        player.clearMediaItems()
        currentUrls = emptyList()
        currentUrlIndex = 0
        retryCount = 0
        isCrossfading = false
        rebufferCount = 0
        isVodContent = false
        lastBufferUpgradeAt = 0L
        currentContentId = null
        currentContentType = null
        currentSeasonNumber = null
        currentEpisodeNumber = null
        currentTitle = null
        currentPosterUrl = null
        currentBackdropUrl = null
    }

    fun release() {
        saveProgressNow() // Save before releasing
        retryJob?.cancel()
        healthMonitorJob?.cancel()
        progressSaveJob?.cancel()
        performanceMonitorJob?.cancel()  // 🔍 DIAGNÓSTICO: Cancelar monitor de rendimiento
        player.release()
        onBuffering = null
        onError = null
        onRetrying = null
        onBitrateChanged = null
        onHealthIssue = null
        onBufferLevelChanged = null
    }

    // Progress tracking methods
    fun setContentMetadata(
        contentId: String,
        contentType: String,
        title: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        posterUrl: String? = null,
        backdropUrl: String? = null
    ) {
        currentContentId = contentId
        currentContentType = contentType
        currentTitle = title
        currentSeasonNumber = seasonNumber
        currentEpisodeNumber = episodeNumber
        currentPosterUrl = posterUrl
        currentBackdropUrl = backdropUrl

        if (isVodContent) {
            startProgressTracking()
        }
    }

    private fun startProgressTracking() {
        progressSaveJob?.cancel()
        progressSaveJob = scope.launch {
            delay(10_000) // Wait 10s before first save
            while (true) {
                if (isPlaying() && currentContentId != null) {
                    saveProgressNow()
                }
                delay(10_000) // Save every 10 seconds
            }
        }
    }

    private fun saveProgressNow(markCompleted: Boolean = false) {
        val contentId = currentContentId ?: return
        val contentType = currentContentType ?: return

        if (!isVodContent) return // Only save for VOD

        val position = getCurrentPosition()
        val duration = getDuration()

        // Don't save if duration is not available yet or position is at the very start
        if (duration <= 0 || position < 1000) return

        val progress = PlaybackProgress(
            contentId = contentId,
            contentType = contentType,
            position = if (markCompleted) 0 else position, // Reset if completed
            duration = duration,
            seasonNumber = currentSeasonNumber,
            episodeNumber = currentEpisodeNumber,
            title = currentTitle,
            posterUrl = currentPosterUrl,
            backdropUrl = currentBackdropUrl
        )

        // Only save if progress is meaningful (watched > 5s and < 95%)
        if (progress.shouldResume || markCompleted) {
            scope.launch(Dispatchers.IO) {
                if (markCompleted) {
                    progressCache.clearProgress(contentId)
                    Log.d("ELLENTV_PROGRESS", "Cleared progress for $contentId (completed)")
                } else {
                    progressCache.saveProgress(progress)
                    Log.d("ELLENTV_PROGRESS", "Saved progress: ${position}ms/${duration}ms (${progress.progressPercent.toInt()}%)")
                }
            }
        }
    }

    suspend fun getSavedProgress(contentId: String): PlaybackProgress? {
        return progressCache.getProgress(contentId)
    }

    fun seekToSavedPosition(progress: PlaybackProgress) {
        if (progress.shouldResume && progress.position > 0) {
            player.seekTo(progress.position)
            Log.i("ELLENTV_PROGRESS", "Restored position: ${progress.position}ms (${progress.progressPercent.toInt()}%)")
        }
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
    LOW("Bajo (2-10s) - FireTV"),
    NORMAL("Normal (5-30s)"),
    HIGH("Alto (20-50s)"),
    MAXIMUM("Máximo (25-60s)")
}
