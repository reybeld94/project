package com.reybel.ellentv.ui.vod

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reybel.ellentv.data.api.VodItem
import com.reybel.ellentv.ui.components.OptimizedAsyncImage

// Color base - negro puro para que los degradados funcionen
private val BackgroundColor = Color.Black
private val CyanAccent = Color(0xFF22D3EE)
private val CyanAccentDark = Color(0xFF0891B2)

@Composable
fun MovieDetailsScreen(
    item: VodItem,
    onPlay: (vodId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    var playFocused by remember { mutableStateOf(false) }
    var listFocused by remember { mutableStateOf(false) }
    var likeFocused by remember { mutableStateOf(false) }
    var shareFocused by remember { mutableStateOf(false) }

    // Extraer datos disponibles de VodItem
    val backdropUrl = item.backdropUrl
    val genre = item.genreNames
        ?.flatMap { it.split(",") }
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?.joinToString(" • ")
    val year = item.releaseDate?.extractYearFromDate() ?: item.displayTitle.extractYearFromTitle()
    val overview = item.resolvedDescription()?.takeIf { it.isNotBlank() } ?: "No description available."
    val rating = item.tmdbVoteAverage?.let { String.format("%.1f", it) }
    val language = item.tmdbOriginalLanguage?.uppercase()
    val cast = item.resolvedCast()?.take(4)?.joinToString(", ")

    // Animación de entrada
    var visible by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(500),
        label = "contentAlpha"
    )

    LaunchedEffect(Unit) {
        visible = true
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundColor)
    ) {
        // ═══════════════════════════════════════════════════════════════
        // BACKDROP - esquina superior derecha con degradados a negro
        // ═══════════════════════════════════════════════════════════════
        if (!backdropUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(0.72f)
                    .fillMaxHeight(0.85f)
            ) {
                // Imagen del backdrop
                OptimizedAsyncImage(
                    url = backdropUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetSizePx = 1920
                )

                // Degradado izquierdo - de negro a transparente
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0.0f to BackgroundColor,
                                    0.05f to BackgroundColor.copy(alpha = 0.98f),
                                    0.15f to BackgroundColor.copy(alpha = 0.85f),
                                    0.30f to BackgroundColor.copy(alpha = 0.5f),
                                    0.50f to BackgroundColor.copy(alpha = 0.1f),
                                    0.65f to Color.Transparent,
                                    1.0f to Color.Transparent
                                )
                            )
                        )
                )

                // Degradado inferior - de transparente a negro
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    0.70f to BackgroundColor.copy(alpha = 0.4f),
                                    0.85f to BackgroundColor.copy(alpha = 0.85f),
                                    0.95f to BackgroundColor.copy(alpha = 0.98f),
                                    1.0f to BackgroundColor
                                )
                            )
                        )
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // CONTENIDO PRINCIPAL
        // ═══════════════════════════════════════════════════════════════
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 32.dp)
                .graphicsLayer { alpha = contentAlpha }
        ) {
            // Contenido izquierdo
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.50f)
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Label tipo
                Text(
                    text = "MOVIE",
                    color = CyanAccent.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium.copy(
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )

                // Título
                Text(
                    text = item.displayTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Badges pequeños
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rating con estrella
                    rating?.let {
                        Surface(
                            color = Color(0xFFFBBF24).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFFBBF24),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = it,
                                    color = Color(0xFFFBBF24),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                    year?.let { MetadataChip(text = it) }
                    language?.let { MetadataChip(text = it) }
                    if (item.tmdbStatus == "synced") {
                        MetadataChip(
                            text = "TMDB",
                            backgroundColor = Color(0xFF01B4E4).copy(alpha = 0.2f),
                            textColor = Color(0xFF01B4E4)
                        )
                    }
                }

                // Géneros
                genre?.let {
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Cast
                cast?.let {
                    Text(
                        text = "Cast: $it",
                        color = Color.White.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Descripción - máximo 3 líneas
                Text(
                    text = overview,
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 22.sp
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Botones de acción
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botón PLAY
                    val playScale by animateFloatAsState(
                        targetValue = if (playFocused) 1.03f else 1f,
                        animationSpec = tween(150),
                        label = "playScale"
                    )

                    Surface(
                        onClick = { onPlay(item.id) },
                        color = if (playFocused) CyanAccent else CyanAccentDark,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .width(150.dp)
                            .scale(playScale)
                            .focusRequester(focusRequester)
                            .onFocusChanged { playFocused = it.isFocused }
                            .focusable()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = if (playFocused) Color.Black else Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Play",
                                color = if (playFocused) Color.Black else Color.White,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }

                    // My List
                    ActionButton(
                        icon = Icons.Outlined.Add,
                        label = "My List",
                        isFocused = listFocused,
                        onFocusChanged = { listFocused = it },
                        onClick = { }
                    )

                    // Like
                    CircularIconButton(
                        icon = Icons.Outlined.FavoriteBorder,
                        isFocused = likeFocused,
                        focusedColor = Color(0xFFEF4444),
                        onFocusChanged = { likeFocused = it },
                        onClick = { }
                    )

                    // Share
                    CircularIconButton(
                        icon = Icons.Outlined.Share,
                        isFocused = shareFocused,
                        onFocusChanged = { shareFocused = it },
                        onClick = { }
                    )
                }
            }

            // Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Empty space - info is shown above
                Spacer(modifier = Modifier.weight(1f))

                // Back hint
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Press",
                        color = Color.White.copy(alpha = 0.35f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Surface(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "←",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                    Text(
                        text = "to go back",
                        color = Color.White.copy(alpha = 0.35f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Componentes
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun MetadataChip(
    text: String,
    backgroundColor: Color = Color.White.copy(alpha = 0.1f),
    textColor: Color = Color.White.copy(alpha = 0.85f)
) {
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1f,
        animationSpec = tween(150),
        label = "actionScale"
    )

    Surface(
        onClick = onClick,
        color = if (isFocused) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isFocused) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.12f)
        ),
        modifier = Modifier
            .height(48.dp)
            .scale(scale)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .focusable()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

@Composable
private fun CircularIconButton(
    icon: ImageVector,
    isFocused: Boolean,
    focusedColor: Color = Color.White,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(150),
        label = "circularScale"
    )

    Surface(
        onClick = onClick,
        color = if (isFocused) focusedColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f),
        shape = CircleShape,
        border = BorderStroke(
            width = 1.5.dp,
            color = if (isFocused) focusedColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f)
        ),
        modifier = Modifier
            .size(48.dp)
            .scale(scale)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .focusable()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) focusedColor else Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun InfoCard(
    label: String,
    value: String
) {
    Surface(
        color = Color.White.copy(alpha = 0.04f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label.uppercase(),
                color = Color.White.copy(alpha = 0.35f),
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = value,
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

private fun String.extractYearFromTitle(): String? {
    val match = Regex("(19|20)\\d{2}").find(this)
    return match?.value
}

private fun String.extractYearFromDate(): String? {
    return takeIf { length >= 4 }?.substring(0, 4)
}

private fun VodItem.resolvedDescription(): String? {
    return listOfNotNull(overview, description, desc, shortDesc, longDesc)
        .firstOrNull { it.isNotBlank() }
        ?.trim()
}

private fun VodItem.resolvedCast(): List<String>? {
    return (tmdbCast ?: cast)
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.ifEmpty { null }
}
