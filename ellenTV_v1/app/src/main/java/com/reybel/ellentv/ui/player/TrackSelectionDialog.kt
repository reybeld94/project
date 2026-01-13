package com.reybel.ellentv.ui.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

// ═══════════════════════════════════════════════════════════════════════════════
// COLORES DEL TEMA
// ═══════════════════════════════════════════════════════════════════════════════
private val DialogBackground = Color(0xFF121212)
private val TabSelectedBackground = Color(0xFF00D9FF)
private val TabUnselectedBackground = Color(0xFF1E1E1E)
private val ItemHoverBackground = Color(0xFF2A2A2A)
private val ItemSelectedBackground = Color(0xFF00D9FF).copy(alpha = 0.15f)
private val AccentColor = Color(0xFF00D9FF)
private val DividerColor = Color(0xFF2A2A2A)

// ═══════════════════════════════════════════════════════════════════════════════
// TABS
// ═══════════════════════════════════════════════════════════════════════════════
private enum class TrackTab {
    AUDIO, SUBTITLES
}

@Composable
fun TrackSelectionDialog(
    player: ExoPlayer,
    tracks: Tracks,
    onDismiss: () -> Unit
) {
    // BackHandler para cerrar con botón back
    BackHandler(onBack = onDismiss)

    // Estado del tab seleccionado
    var selectedTab by remember { mutableStateOf(TrackTab.AUDIO) }

    // Recolectar pistas
    val audioTracks = remember(tracks) { tracks.collectTrackItems(C.TRACK_TYPE_AUDIO) }
    val subtitleTracks = remember(tracks) { tracks.collectTrackItems(C.TRACK_TYPE_TEXT) }

    // Estado de selección de subtítulos
    val subtitleSelected = remember(tracks) {
        subtitleTracks.any { it.group.isTrackSelected(it.trackIndex) }
    }

    // Focus requesters
    val audioTabFocusRequester = remember { FocusRequester() }
    val subtitlesTabFocusRequester = remember { FocusRequester() }
    val closeButtonFocusRequester = remember { FocusRequester() }
    val firstItemFocusRequester = remember { FocusRequester() }

    val focusManager = LocalFocusManager.current

    // Focus inicial en el tab de audio
    LaunchedEffect(Unit) {
        audioTabFocusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 520.dp)
                .heightIn(max = 480.dp) // Altura máxima controlada
                .padding(24.dp),
            shape = RoundedCornerShape(20.dp),
            color = DialogBackground,
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // ═══════════════════════════════════════════════════════════════
                // HEADER CON TABS Y BOTÓN CERRAR
                // ═══════════════════════════════════════════════════════════════
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tabs
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TabButton(
                            text = "Audio",
                            icon = Icons.Default.VolumeUp,
                            isSelected = selectedTab == TrackTab.AUDIO,
                            focusRequester = audioTabFocusRequester,
                            onClick = { selectedTab = TrackTab.AUDIO },
                            onRight = { subtitlesTabFocusRequester.requestFocus() },
                            onDown = { firstItemFocusRequester.requestFocus() }
                        )

                        TabButton(
                            text = "Subtítulos",
                            icon = Icons.Default.Subtitles,
                            isSelected = selectedTab == TrackTab.SUBTITLES,
                            focusRequester = subtitlesTabFocusRequester,
                            onClick = { selectedTab = TrackTab.SUBTITLES },
                            onLeft = { audioTabFocusRequester.requestFocus() },
                            onRight = { closeButtonFocusRequester.requestFocus() },
                            onDown = { firstItemFocusRequester.requestFocus() }
                        )
                    }

                    // Botón cerrar
                    CloseButton(
                        focusRequester = closeButtonFocusRequester,
                        onClick = onDismiss,
                        onLeft = { subtitlesTabFocusRequester.requestFocus() },
                        onDown = { firstItemFocusRequester.requestFocus() }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider(color = DividerColor, thickness = 1.dp)

                Spacer(modifier = Modifier.height(12.dp))

                // ═══════════════════════════════════════════════════════════════
                // CONTENIDO CON SCROLL
                // ═══════════════════════════════════════════════════════════════
                val listState = rememberLazyListState()

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false) // No forzar altura mínima
                        .heightIn(max = 320.dp), // Máximo para scroll
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    when (selectedTab) {
                        TrackTab.AUDIO -> {
                            if (audioTracks.isEmpty()) {
                                item {
                                    EmptyStateMessage(
                                        message = "No hay pistas de audio disponibles",
                                        icon = Icons.Default.VolumeUp
                                    )
                                }
                            } else {
                                itemsIndexed(
                                    items = audioTracks,
                                    key = { index, item -> "audio_${index}_${item.format.id}" }
                                ) { index, item ->
                                    val isFirst = index == 0
                                    val isLast = index == audioTracks.lastIndex

                                    TrackOptionRow(
                                        label = item.displayLabel,
                                        sublabel = item.format.codecs?.uppercase(Locale.getDefault()),
                                        selected = item.group.isTrackSelected(item.trackIndex),
                                        contentDescription = "Seleccionar audio ${item.displayLabel}",
                                        focusRequester = if (isFirst) firstItemFocusRequester else null,
                                        onClick = {
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                                .addOverride(
                                                    TrackSelectionOverride(
                                                        item.group.mediaTrackGroup,
                                                        listOf(item.trackIndex)
                                                    )
                                                )
                                                .build()
                                            onDismiss()
                                        },
                                        onUp = {
                                            if (isFirst) {
                                                audioTabFocusRequester.requestFocus()
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        TrackTab.SUBTITLES -> {
                            // Opción "Desactivar subtítulos"
                            item(key = "subtitle_off") {
                                TrackOptionRow(
                                    label = "Desactivado",
                                    sublabel = null,
                                    selected = !subtitleSelected,
                                    icon = Icons.Default.SubtitlesOff,
                                    contentDescription = "Desactivar subtítulos",
                                    focusRequester = firstItemFocusRequester,
                                    onClick = {
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                            .build()
                                        onDismiss()
                                    },
                                    onUp = { subtitlesTabFocusRequester.requestFocus() }
                                )
                            }

                            if (subtitleTracks.isEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "No hay subtítulos disponibles",
                                        color = Color.White.copy(alpha = 0.5f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            } else {
                                itemsIndexed(
                                    items = subtitleTracks,
                                    key = { index, item -> "sub_${index}_${item.format.id}" }
                                ) { index, item ->
                                    TrackOptionRow(
                                        label = item.displayLabel,
                                        sublabel = item.format.label,
                                        selected = item.group.isTrackSelected(item.trackIndex),
                                        contentDescription = "Seleccionar subtítulos ${item.displayLabel}",
                                        onClick = {
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                                .addOverride(
                                                    TrackSelectionOverride(
                                                        item.group.mediaTrackGroup,
                                                        listOf(item.trackIndex)
                                                    )
                                                )
                                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                .build()
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // HINT DE NAVEGACIÓN (opcional)
                // ═══════════════════════════════════════════════════════════════
                Spacer(modifier = Modifier.height(12.dp))

                HorizontalDivider(color = DividerColor, thickness = 1.dp)

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "← → Cambiar tab  •  ↑ ↓ Navegar  •  OK Seleccionar  •  BACK Cerrar",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMPONENTES
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun TabButton(
    text: String,
    icon: ImageVector,
    isSelected: Boolean,
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

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused -> AccentColor
            isSelected -> AccentColor.copy(alpha = 0.2f)
            else -> TabUnselectedBackground
        },
        animationSpec = tween(150),
        label = "tabBg"
    )

    val textColor = when {
        isFocused -> Color.Black
        isSelected -> AccentColor
        else -> Color.White.copy(alpha = 0.7f)
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(150),
        label = "tabScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .then(
                if (isSelected && !isFocused) {
                    Modifier.border(1.dp, AccentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                } else Modifier
            )
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                when (native.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        onClick()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onLeft()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onRight()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        onUp()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onDown()
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            )
        }
    }
}

@Composable
private fun CloseButton(
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onLeft: () -> Unit = {},
    onDown: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = interactionSource.collectIsFocusedAsState().value

    val backgroundColor = if (isFocused) AccentColor else Color.White.copy(alpha = 0.1f)
    val iconTint = if (isFocused) Color.Black else Color.White.copy(alpha = 0.7f)

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .semantics { contentDescription = "Cerrar" }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                when (native.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        onClick()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onLeft()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
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
            imageVector = Icons.Default.Close,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun TrackOptionRow(
    label: String,
    sublabel: String?,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
    onUp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = interactionSource.collectIsFocusedAsState().value

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused -> AccentColor
            selected -> ItemSelectedBackground
            else -> Color.Transparent
        },
        animationSpec = tween(100),
        label = "rowBg"
    )

    val textColor = when {
        isFocused -> Color.Black
        selected -> AccentColor
        else -> Color.White
    }

    val sublabelColor = when {
        isFocused -> Color.Black.copy(alpha = 0.7f)
        else -> Color.White.copy(alpha = 0.5f)
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1f,
        animationSpec = tween(100),
        label = "rowScale"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .focusable(interactionSource = interactionSource)
            .semantics { this.contentDescription = contentDescription }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                when (native.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        onClick()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        onUp()
                        false // Permitir navegación normal hacia arriba
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Icono opcional
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Textos
            Column {
                Text(
                    text = label,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                sublabel?.let {
                    Text(
                        text = it,
                        color = sublabelColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Indicador de selección
        if (selected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (isFocused) Color.Black.copy(alpha = 0.2f) else AccentColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = if (isFocused) Color.Black else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyStateMessage(
    message: String,
    icon: ImageVector
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UTILIDADES PARA TRACKS
// ═══════════════════════════════════════════════════════════════════════════════

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
        ?.takeUnless { it == "und" || it.isBlank() }
        ?.let { langCode ->
            try {
                Locale.forLanguageTag(langCode).displayLanguage.takeUnless { it.isBlank() }
            } catch (e: Exception) {
                null
            }
        }
        ?: "Desconocido"

    val codec = format.codecs
        ?.takeUnless { it.isBlank() }
        ?.uppercase(Locale.getDefault())
        ?: format.sampleMimeType
            ?.takeUnless { it.isBlank() }
            ?.substringAfter("/")
            ?.uppercase(Locale.getDefault())

    // Para audio, mostrar idioma y codec
    // Para subtítulos, mostrar solo idioma (más limpio)
    return when (trackType) {
        C.TRACK_TYPE_AUDIO -> {
            if (!codec.isNullOrBlank() && codec != "NULL") {
                "$language • $codec"
            } else {
                language
            }
        }
        else -> language
    }
}