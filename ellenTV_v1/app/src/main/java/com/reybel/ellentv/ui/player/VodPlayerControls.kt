package com.reybel.ellentv.ui.player

import android.view.KeyEvent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

@Composable
fun VodPlayerControls(
    player: ExoPlayer,
    modifier: Modifier = Modifier
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var dialogVisible by remember { mutableStateOf(false) }
    var interactionCounter by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var currentTracks by remember { mutableStateOf(player.currentTracks) }
    var positionMs by remember { mutableStateOf(player.currentPosition) }
    var durationMs by remember { mutableStateOf(player.duration.coerceAtLeast(0L)) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableStateOf(0L) }

    val playButtonFocusRequester = remember { FocusRequester() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingState: Boolean) {
                isPlaying = isPlayingState
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (true) {
            if (!isScrubbing) {
                positionMs = player.currentPosition
            }
            durationMs = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    LaunchedEffect(controlsVisible, interactionCounter, dialogVisible) {
        if (!controlsVisible || dialogVisible) return@LaunchedEffect
        val snapshot = interactionCounter
        delay(5000)
        if (controlsVisible && interactionCounter == snapshot && !dialogVisible) {
            controlsVisible = false
        }
    }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            playButtonFocusRequester.requestFocus()
        }
    }

    fun registerInteraction(show: Boolean = true) {
        interactionCounter += 1
        if (show) {
            controlsVisible = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    controlsVisible = !controlsVisible
                    interactionCounter += 1
                }
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_UP) {
                    return@onPreviewKeyEvent controlsVisible
                }

                if (dialogVisible && native.keyCode == KeyEvent.KEYCODE_BACK) {
                    dialogVisible = false
                    return@onPreviewKeyEvent true
                }

                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        if (!controlsVisible) {
                            controlsVisible = true
                            interactionCounter += 1
                            return@onPreviewKeyEvent true
                        }
                        registerInteraction()
                        return@onPreviewKeyEvent false
                    }
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!controlsVisible) {
                            controlsVisible = true
                            interactionCounter += 1
                            return@onPreviewKeyEvent true
                        }
                        registerInteraction()
                        return@onPreviewKeyEvent false
                    }
                    else -> {
                        controlsVisible = !controlsVisible
                        interactionCounter += 1
                        return@onPreviewKeyEvent true
                    }
                }
            }
    ) {
        if (controlsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatTime(if (isScrubbing) scrubPositionMs else positionMs),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = formatTime(durationMs),
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                val durationRange = durationMs.coerceAtLeast(1L).toFloat()
                Slider(
                    value = (if (isScrubbing) scrubPositionMs else positionMs)
                        .coerceIn(0L, durationMs)
                        .toFloat(),
                    onValueChange = { value ->
                        scrubPositionMs = value.toLong()
                        isScrubbing = true
                        registerInteraction()
                    },
                    onValueChangeFinished = {
                        player.seekTo(scrubPositionMs)
                        isScrubbing = false
                        registerInteraction()
                    },
                    valueRange = 0f..durationRange,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ControlButton(
                        icon = Icons.Default.Replay10,
                        contentDescription = "Retroceder 10 segundos",
                        onClick = {
                            player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L))
                            registerInteraction()
                        }
                    )

                    ControlButton(
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                        onClick = {
                            if (isPlaying) player.pause() else player.play()
                            registerInteraction()
                        },
                        focusRequester = playButtonFocusRequester
                    )

                    ControlButton(
                        icon = Icons.Default.Forward10,
                        contentDescription = "Adelantar 10 segundos",
                        onClick = {
                            player.seekTo((player.currentPosition + 10_000).coerceAtMost(durationMs))
                            registerInteraction()
                        }
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    ControlButton(
                        icon = Icons.Default.Subtitles,
                        contentDescription = "Subtítulos",
                        onClick = {
                            dialogVisible = true
                            registerInteraction()
                        }
                    )

                    ControlButton(
                        icon = Icons.Default.VolumeUp,
                        contentDescription = "Audio",
                        onClick = {
                            dialogVisible = true
                            registerInteraction()
                        }
                    )
                }
            }
        }

        if (dialogVisible) {
            TrackSelectionDialog(
                player = player,
                tracks = currentTracks,
                onDismiss = { dialogVisible = false }
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .size(64.dp)
            .background(
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(18.dp)
            )
            .focusable(interactionSource = interactionSource)
            .semantics { this.contentDescription = contentDescription }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == KeyEvent.ACTION_UP &&
                    (native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    return@onPreviewKeyEvent true
                }
                false
            }
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isFocused) Color.Black else Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

private fun formatTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0L)
    val seconds = (totalSeconds % 60).toInt()
    val minutes = ((totalSeconds / 60) % 60).toInt()
    val hours = (totalSeconds / 3600).toInt()
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
