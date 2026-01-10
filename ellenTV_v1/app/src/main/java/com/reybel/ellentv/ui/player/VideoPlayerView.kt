package com.reybel.ellentv.ui.player

import android.graphics.Color as AColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
    showStats: Boolean = false, // ← Nuevo parámetro para HUD
    playerManager: PlayerManager? = null // ← Para el HUD
) {
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
