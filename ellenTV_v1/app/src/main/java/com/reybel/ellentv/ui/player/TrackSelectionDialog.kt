package com.reybel.ellentv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

@Composable
fun TrackSelectionDialog(
    player: ExoPlayer,
    tracks: Tracks,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val audioTracks = remember(tracks) { tracks.collectTrackItems(C.TRACK_TYPE_AUDIO) }
    val subtitleTracks = remember(tracks) { tracks.collectTrackItems(C.TRACK_TYPE_TEXT) }
    val subtitleSelected = remember(tracks) {
        subtitleTracks.any { it.group.isTrackSelected(it.trackIndex) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF101010),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 520.dp, max = 840.dp)
                    .padding(28.dp)
            ) {
                Text(
                    text = "Audio",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )

                Spacer(modifier = Modifier.padding(top = 12.dp))

                if (audioTracks.isEmpty()) {
                    Text(
                        text = "Sin pistas de audio disponibles",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    audioTracks.forEach { item ->
                        TrackOptionRow(
                            label = item.displayLabel,
                            selected = item.group.isTrackSelected(item.trackIndex),
                            contentDescription = "Seleccionar audio ${item.displayLabel}",
                            onClick = {
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setOverrideForType(
                                        TrackSelectionOverride(
                                            item.group.mediaTrackGroup,
                                            listOf(item.trackIndex)
                                        )
                                    )
                                    .build()
                                onDismiss()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.padding(top = 20.dp))

                Text(
                    text = "Subtítulos",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                )

                Spacer(modifier = Modifier.padding(top = 12.dp))

                TrackOptionRow(
                    label = "Desactivado",
                    selected = !subtitleSelected,
                    contentDescription = "Desactivar subtítulos",
                    onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        onDismiss()
                    }
                )

                if (subtitleTracks.isEmpty()) {
                    Text(
                        text = "Sin subtítulos disponibles",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    subtitleTracks.forEach { item ->
                        TrackOptionRow(
                            label = item.displayLabel,
                            selected = item.group.isTrackSelected(item.trackIndex),
                            contentDescription = "Seleccionar subtítulos ${item.displayLabel}",
                            onClick = {
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(
                                        TrackSelectionOverride(
                                            item.group.mediaTrackGroup,
                                            listOf(item.trackIndex)
                                        )
                                    )
                                    .build()
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

private data class TrackItem(
    val group: Tracks.Group,
    val trackIndex: Int,
    val format: Format,
    val displayLabel: String
)

private fun Tracks.collectTrackItems(trackType: Int): List<TrackItem> {
    return groups
        .filter { it.type == trackType }
        .flatMap { group ->
            (0 until group.length).map { index ->
                val format = group.getTrackFormat(index)
                TrackItem(
                    group = group,
                    trackIndex = index,
                    format = format,
                    displayLabel = formatDisplayLabel(format, trackType)
                )
            }
        }
}

private fun formatDisplayLabel(format: Format, trackType: Int): String {
    val language = format.language
        ?.takeUnless { it == "und" }
        ?.let { Locale.forLanguageTag(it).displayLanguage }
        ?.takeUnless { it.isBlank() }
        ?: "Desconocido"
    val codec = (format.codecs ?: format.sampleMimeType)?.uppercase(Locale.getDefault())
    return if (trackType == C.TRACK_TYPE_AUDIO && !codec.isNullOrBlank()) {
        "$language • $codec"
    } else {
        language
    }
}

@Composable
private fun TrackOptionRow(
    label: String,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = interactionSource.collectIsFocusedAsState().value
    val background = when {
        isFocused -> Color.White
        selected -> Color.White.copy(alpha = 0.2f)
        else -> Color.Transparent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(background, shape = RoundedCornerShape(14.dp))
            .focusable(interactionSource = interactionSource)
            .semantics { this.contentDescription = contentDescription }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == android.view.KeyEvent.ACTION_UP &&
                    (native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick()
                    return@onPreviewKeyEvent true
                }
                false
            }
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            color = if (isFocused) Color.Black else Color.White,
            style = MaterialTheme.typography.bodyLarge
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (isFocused) Color.Black else Color.White
            )
        }
    }
}
