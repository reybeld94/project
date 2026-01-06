package com.reybel.ellentv.ui.player

import android.graphics.Color as AColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@Composable
fun VideoPlayerView(
    player: ExoPlayer,
    attachPlayer: Boolean = true,
    modifier: Modifier = Modifier,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    showControls: Boolean = false,
    showBuffering: Boolean = true,
    showStats: Boolean = false, // ← Nuevo parámetro para HUD
    playerManager: PlayerManager? = null // ← Para el HUD
) {
    var isBuffering by remember { mutableStateOf(false) }
    var playbackState by remember { mutableStateOf(Player.STATE_IDLE) }

    // Listener para estados del player
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                isBuffering = state == Player.STATE_BUFFERING
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                if (isLoading && playbackState == Player.STATE_BUFFERING) {
                    isBuffering = true
                }
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = if (attachPlayer) player else null
                    useController = showControls
                    this.resizeMode = resizeMode
                    setShutterBackgroundColor(AColor.BLACK)
                    setBackgroundColor(AColor.BLACK)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    controllerShowTimeoutMs = 3000
                    controllerHideOnTouch = true
                    keepScreenOn = true
                }
            },
            update = { pv ->
                val target = if (attachPlayer) player else null
                if (pv.player !== target) {
                    pv.player = target
                }
                pv.useController = showControls
                pv.resizeMode = resizeMode
            }
        )

        // Indicator de buffering custom
        if (showBuffering && isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Color.White,
                    strokeWidth = 4.dp
                )
            }
        }

        // Stats HUD (opcional para debugging)
        if (showStats && playerManager != null) {
            PlayerStatsHUD(
                playerManager = playerManager,
                visible = true,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}