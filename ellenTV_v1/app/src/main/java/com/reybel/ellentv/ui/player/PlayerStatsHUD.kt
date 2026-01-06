package com.reybel.ellentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * HUD opcional para debugging y monitoreo
 * Muestra métricas del player en tiempo real
 */
@Composable
fun PlayerStatsHUD(
    playerManager: PlayerManager,
    visible: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    var stats by remember { mutableStateOf(PlayerStats()) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            stats = PlayerStats(
                bitrate = playerManager.getFormattedBitrate(),
                buffer = "${playerManager.getBufferedPercentage()}%",
                bufferLevel = playerManager.getBufferLevel(),
                position = formatTime(playerManager.getCurrentPosition()),
                urlIndex = "${playerManager.getCurrentUrlIndex() + 1}/${playerManager.getTotalUrls()}",
                isPlaying = playerManager.isPlaying()
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Column(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            StatRow("Bitrate", stats.bitrate)
            StatRow("Buffer", stats.buffer)
            StatRow("Level", stats.bufferLevel)
            StatRow("Position", stats.position)
            StatRow("URL", stats.urlIndex)
            StatRow("Playing", if (stats.isPlaying) "✓" else "✗")
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "$label:",
            color = Color(0xFF00FF00),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

private data class PlayerStats(
    val bitrate: String = "N/A",
    val buffer: String = "0%",
    val bufferLevel: String = "Normal",
    val position: String = "00:00",
    val urlIndex: String = "0/0",
    val isPlaying: Boolean = false
)

private fun formatTime(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / 60000) % 60
    val hours = millis / 3600000

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}