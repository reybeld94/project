package com.reybel.ellentv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.reybel.ellentv.ui.player.VideoPlayerView

@Composable
fun PreviewSection(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    attachPlayer: Boolean = true
)
 {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.18f)
    ) {
        // Preview SIEMPRE dibuja el video (nunca “black screen”)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            VideoPlayerView(
                player = player,
                modifier = Modifier.fillMaxSize(),
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                attachPlayer = attachPlayer
            )

        }
    }
}
