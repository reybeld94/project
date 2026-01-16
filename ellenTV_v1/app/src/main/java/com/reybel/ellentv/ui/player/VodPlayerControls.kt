package com.reybel.ellentv.ui.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════════════════════════
// COLORES DEL TEMA
// ═══════════════════════════════════════════════════════════════════════════════
private val AccentColor = Color(0xFF00D9FF)
private val AccentColorDark = Color(0xFF0099B3)
private val ControlBackground = Color(0xFF0D0D0D)
private val ControlBackgroundGradientStart = Color(0x00000000)
private val ControlBackgroundGradientEnd = Color(0xE6000000)
private val FocusedBackground = Color(0xFF1A1A1A)
private val ProgressTrackColor = Color(0xFF333333)
private val BufferedColor = Color(0xFF555555)

// ═══════════════════════════════════════════════════════════════════════════════
// CONSTANTES
// ═══════════════════════════════════════════════════════════════════════════════
private const val SEEK_INCREMENT_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 5000L
private const val POSITION_UPDATE_INTERVAL_MS = 250L

@Composable
fun VodPlayerControls(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    title: String? = null, // Título opcional de la película/episodio
    onBackPressed: (() -> Unit)? = null
) {
    // ═══════════════════════════════════════════════════════════════════════════
    // ESTADOS
    // ═══════════════════════════════════════════════════════════════════════════
    var controlsVisible by remember { mutableStateOf(true) }
    var dialogVisible by remember { mutableStateOf(false) }
    var interactionCounter by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var currentTracks by remember { mutableStateOf(player.currentTracks) }
    var positionMs by remember { mutableLongStateOf(player.currentPosition) }
    var durationMs by remember { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var bufferedPositionMs by remember { mutableLongStateOf(player.bufferedPosition) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }

    // Estado para mostrar indicador de seek
    var seekIndicator by remember { mutableStateOf<SeekIndicatorType?>(null) }

    // Focus requesters para navegación
    val playButtonFocusRequester = remember { FocusRequester() }
    val rewindButtonFocusRequester = remember { FocusRequester() }
    val forwardButtonFocusRequester = remember { FocusRequester() }
    val subtitlesButtonFocusRequester = remember { FocusRequester() }
    val audioButtonFocusRequester = remember { FocusRequester() }
    val sliderFocusRequester = remember { FocusRequester() }
    val controlsFocusRequester = remember { FocusRequester() }

    val focusManager = LocalFocusManager.current

    // ═══════════════════════════════════════════════════════════════════════════
    // LISTENERS DEL PLAYER
    // ═══════════════════════════════════════════════════════════════════════════
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingState: Boolean) {
                isPlaying = isPlayingState
            }
            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Actualizar duración cuando cambia el estado
                durationMs = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Actualización de posición
    LaunchedEffect(player) {
        while (true) {
            if (!isScrubbing) {
                positionMs = player.currentPosition
                bufferedPositionMs = player.bufferedPosition
            }
            durationMs = player.duration.coerceAtLeast(0L)
            delay(POSITION_UPDATE_INTERVAL_MS)
        }
    }

    // Auto-hide de controles
    LaunchedEffect(controlsVisible, interactionCounter, dialogVisible, isPlaying) {
        if (!controlsVisible || dialogVisible || !isPlaying) return@LaunchedEffect
        val snapshot = interactionCounter
        delay(CONTROLS_TIMEOUT_MS)
        if (controlsVisible && interactionCounter == snapshot && !dialogVisible && isPlaying) {
            controlsVisible = false
        }
    }

    // Focus inicial cuando se muestran controles
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(100) // Pequeño delay para que el layout se estabilice
            try {
                playButtonFocusRequester.requestFocus()
            } catch (e: Exception) {
                // Ignorar si el focus requester no está listo
            }
        }
    }

    // Mantener el foco en los controles para capturar teclas del mando
    LaunchedEffect(controlsVisible, dialogVisible) {
        if (!dialogVisible) {
            try {
                controlsFocusRequester.requestFocus()
            } catch (e: Exception) {
                // Ignorar si el focus requester no está listo
            }
        }
    }

    // Auto-hide del indicador de seek
    LaunchedEffect(seekIndicator) {
        if (seekIndicator != null) {
            delay(800)
            seekIndicator = null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FUNCIONES HELPER
    // ═══════════════════════════════════════════════════════════════════════════
    fun registerInteraction(showControls: Boolean = true) {
        interactionCounter += 1
        if (showControls) {
            controlsVisible = true
        }
    }

    fun togglePlayPause() {
        if (isPlaying) {
            player.pause()
        } else {
            player.play()
        }
        registerInteraction()
    }

    fun seekBackward() {
        val newPosition = (player.currentPosition - SEEK_INCREMENT_MS).coerceAtLeast(0L)
        player.seekTo(newPosition)
        positionMs = newPosition
        seekIndicator = SeekIndicatorType.BACKWARD
        registerInteraction()
    }

    fun seekForward() {
        val newPosition = (player.currentPosition + SEEK_INCREMENT_MS).coerceAtMost(durationMs)
        player.seekTo(newPosition)
        positionMs = newPosition
        seekIndicator = SeekIndicatorType.FORWARD
        registerInteraction()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KEY EVENT HANDLER PRINCIPAL
    // ═══════════════════════════════════════════════════════════════════════════
    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(controlsFocusRequester)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures {
                    if (!dialogVisible) {
                        controlsVisible = !controlsVisible
                        interactionCounter += 1
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent

                // Solo procesar ACTION_DOWN para mejor responsividad
                if (native.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }

                // Si el diálogo está visible, solo interceptar BACK
                if (dialogVisible) {
                    if (native.keyCode == KeyEvent.KEYCODE_BACK ||
                        native.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        dialogVisible = false
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }

                when (native.keyCode) {
                    // ═══════════════════════════════════════════════════════════
                    // PLAY/PAUSE con ENTER o DPAD_CENTER
                    // ═══════════════════════════════════════════════════════════
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (!controlsVisible) {
                            // Si los controles no están visibles, mostrarlos y toggle play
                            controlsVisible = true
                            togglePlayPause()
                            return@onPreviewKeyEvent true
                        }
                        // Si están visibles, dejar que el focus maneje la acción
                        registerInteraction()
                        return@onPreviewKeyEvent false
                    }

                    KeyEvent.KEYCODE_DPAD_CENTER -> {
                        if (!controlsVisible) {
                            controlsVisible = true
                            togglePlayPause()
                            return@onPreviewKeyEvent true
                        }
                        registerInteraction()
                        return@onPreviewKeyEvent false
                    }

                    // ═══════════════════════════════════════════════════════════
                    // SEEK con IZQUIERDA/DERECHA
                    // ═══════════════════════════════════════════════════════════
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (!controlsVisible) {
                            // Seek directo sin mostrar controles
                            seekBackward()
                            controlsVisible = true
                            return@onPreviewKeyEvent true
                        }
                        // Si los controles están visibles, permitir navegación
                        registerInteraction()
                        return@onPreviewKeyEvent false
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (!controlsVisible) {
                            seekForward()
                            controlsVisible = true
                            return@onPreviewKeyEvent true
                        }
                        registerInteraction()
                        return@onPreviewKeyEvent false
                    }

                    // ═══════════════════════════════════════════════════════════
                    // NAVEGACIÓN VERTICAL
                    // ═══════════════════════════════════════════════════════════
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!controlsVisible) {
                            controlsVisible = true
                            interactionCounter += 1
                            return@onPreviewKeyEvent true
                        }
                        registerInteraction()
                        return@onPreviewKeyEvent false
                    }

                    // ═══════════════════════════════════════════════════════════
                    // CONTROLES DE MEDIA
                    // ═══════════════════════════════════════════════════════════
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        if (!isPlaying) {
                            player.play()
                            registerInteraction()
                        }
                        return@onPreviewKeyEvent true
                    }

                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (isPlaying) {
                            player.pause()
                            registerInteraction()
                        }
                        return@onPreviewKeyEvent true
                    }

                    KeyEvent.KEYCODE_MEDIA_STOP -> {
                        player.stop()
                        onBackPressed?.invoke()
                        return@onPreviewKeyEvent true
                    }

                    // ═══════════════════════════════════════════════════════════
                    // BACK - Cerrar o volver
                    // ═══════════════════════════════════════════════════════════
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_ESCAPE -> {
                        if (controlsVisible) {
                            controlsVisible = false
                            return@onPreviewKeyEvent true
                        }
                        // Si los controles no están visibles, dejar que el BackHandler lo maneje
                        return@onPreviewKeyEvent false
                    }

                    // ═══════════════════════════════════════════════════════════
                    // SPACE también hace play/pause
                    // ═══════════════════════════════════════════════════════════
                    KeyEvent.KEYCODE_SPACE -> {
                        togglePlayPause()
                        controlsVisible = true
                        return@onPreviewKeyEvent true
                    }

                    else -> {
                        // Cualquier otra tecla muestra los controles
                        if (!controlsVisible) {
                            controlsVisible = true
                            interactionCounter += 1
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                }
            }
    ) {
        // ═══════════════════════════════════════════════════════════════════════
        // INDICADOR DE SEEK (aparece brevemente al adelantar/retroceder)
        // ═══════════════════════════════════════════════════════════════════════
        AnimatedVisibility(
            visible = seekIndicator != null,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            seekIndicator?.let { indicator ->
                SeekIndicator(type = indicator)
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // CONTROLES PRINCIPALES
        // ═══════════════════════════════════════════════════════════════════════
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)) + slideInVertically(
                initialOffsetY = { it / 4 },
                animationSpec = tween(250)
            ),
            exit = fadeOut(tween(200)) + slideOutVertically(
                targetOffsetY = { it / 4 },
                animationSpec = tween(200)
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // ═══════════════════════════════════════════════════════════════
                // GRADIENTE SUPERIOR (título)
                // ═══════════════════════════════════════════════════════════════
                if (title != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.8f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .padding(horizontal = 32.dp, vertical = 24.dp)
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1
                        )
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // PANEL DE CONTROLES INFERIOR
                // ═══════════════════════════════════════════════════════════════
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.6f),
                                    Color.Black.copy(alpha = 0.85f)
                                )
                            )
                        )
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                ) {
                    // ═══════════════════════════════════════════════════════════
                    // BARRA DE PROGRESO CON TIEMPOS
                    // ═══════════════════════════════════════════════════════════
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Tiempo actual
                        Text(
                            text = formatTime(if (isScrubbing) scrubPositionMs else positionMs),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum" // Números tabulares
                            )
                        )

                        // Tiempo restante (opcional) o duración total
                        Text(
                            text = formatTime(durationMs),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFeatureSettings = "tnum"
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ═══════════════════════════════════════════════════════════
                    // SLIDER DE PROGRESO
                    // ═══════════════════════════════════════════════════════════
                    val sliderInteractionSource = remember { MutableInteractionSource() }
                    val sliderFocused = sliderInteractionSource.collectIsFocusedAsState().value

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .focusRequester(sliderFocusRequester)
                            .focusable(interactionSource = sliderInteractionSource)
                            .onPreviewKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                                when (native.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        seekBackward()
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        seekForward()
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        focusManager.moveFocus(FocusDirection.Down)
                                        true
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        // Track de fondo
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (sliderFocused) 8.dp else 4.dp)
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(4.dp))
                                .background(ProgressTrackColor)
                        )

                        // Buffered progress
                        if (durationMs > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(bufferedPositionMs.toFloat() / durationMs.toFloat())
                                    .height(if (sliderFocused) 8.dp else 4.dp)
                                    .align(Alignment.CenterStart)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(BufferedColor)
                            )
                        }

                        // Progress actual
                        if (durationMs > 0) {
                            val currentProgress = (if (isScrubbing) scrubPositionMs else positionMs)
                                .coerceIn(0L, durationMs).toFloat() / durationMs.toFloat()

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(currentProgress)
                                    .height(if (sliderFocused) 8.dp else 4.dp)
                                    .align(Alignment.CenterStart)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AccentColor)
                            )

                            // Thumb (solo visible cuando está enfocado)
                            if (sliderFocused) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(start = (currentProgress * 100).dp.coerceAtMost(
                                            LocalFocusManager.current.let {
                                                // Calcular offset basado en el progreso
                                                ((currentProgress * 800).coerceAtMost(780f)).dp
                                            }
                                        ))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(AccentColor)
                                            .border(2.dp, Color.White, CircleShape)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ═══════════════════════════════════════════════════════════
                    // BOTONES DE CONTROL
                    // ═══════════════════════════════════════════════════════════
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Grupo izquierdo: Subtítulos y Audio
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            PlayerControlButton(
                                icon = Icons.Default.Subtitles,
                                contentDescription = "Subtítulos",
                                focusRequester = subtitlesButtonFocusRequester,
                                onClick = {
                                    dialogVisible = true
                                    registerInteraction()
                                },
                                onLeft = { /* Nada a la izquierda */ },
                                onRight = { audioButtonFocusRequester.requestFocus() },
                                onUp = { sliderFocusRequester.requestFocus() }
                            )

                            PlayerControlButton(
                                icon = Icons.Default.VolumeUp,
                                contentDescription = "Audio",
                                focusRequester = audioButtonFocusRequester,
                                onClick = {
                                    dialogVisible = true
                                    registerInteraction()
                                },
                                onLeft = { subtitlesButtonFocusRequester.requestFocus() },
                                onRight = { rewindButtonFocusRequester.requestFocus() },
                                onUp = { sliderFocusRequester.requestFocus() }
                            )
                        }

                        // Grupo central: Controles de reproducción
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Retroceder 10s
                            PlayerControlButton(
                                icon = Icons.Default.Replay10,
                                contentDescription = "Retroceder 10 segundos",
                                focusRequester = rewindButtonFocusRequester,
                                onClick = { seekBackward() },
                                onLeft = { audioButtonFocusRequester.requestFocus() },
                                onRight = { playButtonFocusRequester.requestFocus() },
                                onUp = { sliderFocusRequester.requestFocus() }
                            )

                            // Play/Pause (botón principal, más grande)
                            PlayPauseButton(
                                isPlaying = isPlaying,
                                focusRequester = playButtonFocusRequester,
                                onClick = { togglePlayPause() },
                                onLeft = { rewindButtonFocusRequester.requestFocus() },
                                onRight = { forwardButtonFocusRequester.requestFocus() },
                                onUp = { sliderFocusRequester.requestFocus() }
                            )

                            // Adelantar 10s
                            PlayerControlButton(
                                icon = Icons.Default.Forward10,
                                contentDescription = "Adelantar 10 segundos",
                                focusRequester = forwardButtonFocusRequester,
                                onClick = { seekForward() },
                                onLeft = { playButtonFocusRequester.requestFocus() },
                                onRight = { /* Nada a la derecha del grupo central */ },
                                onUp = { sliderFocusRequester.requestFocus() }
                            )
                        }

                        // Espaciador derecho para balance
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // DIÁLOGO DE SELECCIÓN DE PISTAS
        // ═══════════════════════════════════════════════════════════════════════
        if (dialogVisible) {
            TrackSelectionDialog(
                player = player,
                tracks = currentTracks,
                onDismiss = {
                    dialogVisible = false
                    registerInteraction()
                }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMPONENTES AUXILIARES
// ═══════════════════════════════════════════════════════════════════════════════

private enum class SeekIndicatorType {
    FORWARD, BACKWARD
}

@Composable
private fun SeekIndicator(type: SeekIndicatorType) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (type == SeekIndicatorType.FORWARD)
                    Icons.Default.Forward10 else Icons.Default.Replay10,
                contentDescription = null,
                tint = AccentColor,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = if (type == SeekIndicatorType.FORWARD) "+10s" else "-10s",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
private fun PlayerControlButton(
    icon: ImageVector,
    contentDescription: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onLeft: () -> Unit = {},
    onRight: () -> Unit = {},
    onUp: () -> Unit = {},
    onDown: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = interactionSource.collectIsFocusedAsState().value

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.15f else 1f,
        animationSpec = tween(150),
        label = "buttonScale"
    )

    val backgroundColor = when {
        isFocused -> AccentColor
        else -> Color.White.copy(alpha = 0.1f)
    }

    val iconTint = when {
        isFocused -> Color.Black
        else -> Color.White
    }

    Box(
        modifier = modifier
            .size((48 * scale).dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .semantics { this.contentDescription = contentDescription }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onClick()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onLeft()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onRight()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        onUp()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onDown()
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
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onLeft: () -> Unit = {},
    onRight: () -> Unit = {},
    onUp: () -> Unit = {},
    onDown: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = interactionSource.collectIsFocusedAsState().value

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = tween(150),
        label = "playButtonScale"
    )

    val backgroundColor = when {
        isFocused -> AccentColor
        else -> Color.White
    }

    val iconTint = Color.Black

    Box(
        modifier = modifier
            .size((64 * scale).dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .then(
                if (isFocused) Modifier.border(3.dp, Color.White, CircleShape)
                else Modifier
            )
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .semantics {
                contentDescription = if (isPlaying) "Pausar" else "Reproducir"
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onClick()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onLeft()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onRight()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        onUp()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onDown()
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
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(32.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UTILIDADES
// ═══════════════════════════════════════════════════════════════════════════════

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
