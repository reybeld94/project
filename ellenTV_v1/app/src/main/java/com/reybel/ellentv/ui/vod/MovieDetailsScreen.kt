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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reybel.ellentv.data.api.VodItem
import com.reybel.ellentv.ui.components.OptimizedAsyncImage
import java.util.Locale

// Colores del tema cinematográfico
private val CyanAccent = Color(0xFF22D3EE)
private val CyanAccentDark = Color(0xFF0891B2)
private val SurfaceDark = Color(0xFF0F172A)

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
    val overview = item.overview?.takeIf { it.isNotBlank() } ?: "Sin sinopsis disponible."
    val cast = item.tmdbCast
        ?.filter { it.isNotBlank() }
        ?.take(4)
        ?.joinToString(", ")
    val voteAverage = item.tmdbVoteAverage?.let { String.format(Locale.US, "%.1f", it) }

    // Animación de entrada
    var visible by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(600),
        label = "contentAlpha"
    )
    val backdropScale by animateFloatAsState(
        targetValue = if (visible) 1f else 1.1f,
        animationSpec = tween(800),
        label = "backdropScale"
    )

    LaunchedEffect(Unit) {
        visible = true
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDark)
    ) {
        // ═══════════════════════════════════════════════════════════════
        // CAPA 1: Fondo difuminado (backdrop blur como base)
        // ═══════════════════════════════════════════════════════════════
        if (!backdropUrl.isNullOrBlank()) {
            OptimizedAsyncImage(
                url = backdropUrl,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.2f)
                    .blur(50.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .graphicsLayer { alpha = 0.4f },
                contentScale = ContentScale.Crop,
                targetSizePx = 512
            )
        }

        // Overlay oscuro sobre el fondo difuminado
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceDark.copy(alpha = 0.7f),
                            SurfaceDark.copy(alpha = 0.85f),
                            SurfaceDark.copy(alpha = 0.95f)
                        )
                    )
                )
        )

        // ═══════════════════════════════════════════════════════════════
        // CAPA 2: Backdrop principal - esquina superior derecha
        // Pegado a bordes derecho y superior, fusionado con degradados suaves
        // ═══════════════════════════════════════════════════════════════
        if (!backdropUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(0.70f)
                    .fillMaxHeight(0.75f)
                    .scale(backdropScale)
            ) {
                // Imagen principal del backdrop
                OptimizedAsyncImage(
                    url = backdropUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetSizePx = 1920
                )

                // Fade lateral izquierdo - cubre TODO el alto, degradado amplio
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.6f) // 60% del ancho para un fade muy suave
                        .align(Alignment.CenterStart)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    SurfaceDark,
                                    SurfaceDark.copy(alpha = 0.95f),
                                    SurfaceDark.copy(alpha = 0.8f),
                                    SurfaceDark.copy(alpha = 0.5f),
                                    SurfaceDark.copy(alpha = 0.2f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Fade inferior - cubre TODO el ancho, degradado amplio
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.5f) // 50% del alto para fade suave
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    SurfaceDark.copy(alpha = 0.2f),
                                    SurfaceDark.copy(alpha = 0.5f),
                                    SurfaceDark.copy(alpha = 0.8f),
                                    SurfaceDark.copy(alpha = 0.95f),
                                    SurfaceDark
                                )
                            )
                        )
                )

                // Fade esquina inferior izquierda - fusión diagonal extra
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .fillMaxHeight(0.7f)
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    SurfaceDark,
                                    SurfaceDark.copy(alpha = 0.8f),
                                    SurfaceDark.copy(alpha = 0.4f),
                                    Color.Transparent
                                ),
                                center = Offset(0f, Float.POSITIVE_INFINITY),
                                radius = 1200f
                            )
                        )
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // CAPA 3: Contenido principal
        // ═══════════════════════════════════════════════════════════════
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp)
                .graphicsLayer { alpha = contentAlpha },
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ─────────────────────────────────────────────────────────────
            // Sección superior: Título y metadata principal
            // ─────────────────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth(0.55f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Indicador de tipo
                Text(
                    text = "PELÍCULA",
                    color = CyanAccent.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelLarge.copy(
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Medium
                    )
                )

                // Título principal
                Text(
                    text = item.displayTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.5f),
                            offset = Offset(2f, 2f),
                            blurRadius = 8f
                        )
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Chips de metadata
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Año
                    year?.let {
                        MetadataChip(text = it)
                    }

                    // Tipo
                    MetadataChip(text = "Movie")

                    // Estado TMDB
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
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                cast?.let {
                    Text(
                        text = "Cast: $it",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Sinopsis
                Text(
                    text = overview,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 26.sp
                    ),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ─────────────────────────────────────────────────────────
                // Botones de acción
                // ─────────────────────────────────────────────────────────
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botón PLAY principal
                    val playScale by animateFloatAsState(
                        targetValue = if (playFocused) 1.05f else 1f,
                        animationSpec = tween(150),
                        label = "playScale"
                    )

                    Surface(
                        onClick = { onPlay(item.id) },
                        color = if (playFocused) CyanAccent else CyanAccentDark,
                        shape = RoundedCornerShape(12.dp),
                        shadowElevation = if (playFocused) 16.dp else 4.dp,
                        modifier = Modifier
                            .height(56.dp)
                            .width(180.dp)
                            .scale(playScale)
                            .focusRequester(focusRequester)
                            .onFocusChanged { playFocused = it.isFocused }
                            .focusable()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = if (playFocused) SurfaceDark else Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Reproducir",
                                color = if (playFocused) SurfaceDark else Color.White,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }

                    // Botón Mi Lista
                    ActionButton(
                        icon = Icons.Outlined.Add,
                        label = "Mi Lista",
                        isFocused = listFocused,
                        onFocusChanged = { listFocused = it },
                        onClick = { /* TODO */ }
                    )

                    // Botón circular Like
                    CircularIconButton(
                        icon = Icons.Outlined.FavoriteBorder,
                        isFocused = likeFocused,
                        focusedColor = Color(0xFFEF4444),
                        onFocusChanged = { likeFocused = it },
                        onClick = { /* TODO */ }
                    )

                    // Botón circular Compartir
                    CircularIconButton(
                        icon = Icons.Outlined.Share,
                        isFocused = shareFocused,
                        onFocusChanged = { shareFocused = it },
                        onClick = { /* TODO */ }
                    )
                }
            }

            // ─────────────────────────────────────────────────────────────
            // Sección inferior: Info adicional
            // ─────────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                // Info cards con datos disponibles
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item.tmdbId?.let {
                        InfoCard(label = "TMDB ID", value = it.toString())
                    }

                    item.releaseDate?.let {
                        InfoCard(label = "Estreno", value = it)
                    }

                    voteAverage?.let {
                        InfoCard(label = "Voto TMDB", value = it)
                    }

                    item.tmdbOriginalLanguage?.let {
                        InfoCard(label = "Idioma", value = it)
                    }

                    item.containerExtension?.let {
                        InfoCard(label = "Formato", value = it.uppercase())
                    }

                    // Si no hay mucha info de TMDB, mostrar estado
                    if (item.tmdbId == null) {
                        InfoCard(
                            label = "Estado",
                            value = when (item.tmdbStatus) {
                                "synced" -> "Sincronizado"
                                "pending" -> "Pendiente"
                                "not_found" -> "No encontrado"
                                else -> "Sin info"
                            }
                        )
                    }
                }

                // Indicador de navegación
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Presiona",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Surface(
                        color = Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "←",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = "para volver",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Componentes auxiliares
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun MetadataChip(
    text: String,
    backgroundColor: Color = Color.White.copy(alpha = 0.1f),
    textColor: Color = Color.White.copy(alpha = 0.9f)
) {
    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
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
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(150),
        label = "actionScale"
    )

    Surface(
        onClick = onClick,
        color = if (isFocused) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 2.dp,
            color = if (isFocused) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.15f)
        ),
        modifier = Modifier
            .height(56.dp)
            .scale(scale)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .focusable()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(
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
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = tween(150),
        label = "circularScale"
    )

    Surface(
        onClick = onClick,
        color = if (isFocused) focusedColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
        shape = CircleShape,
        border = BorderStroke(
            width = 2.dp,
            color = if (isFocused) focusedColor.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f)
        ),
        modifier = Modifier
            .size(56.dp)
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
                tint = if (isFocused) focusedColor else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp)
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
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier
                .width(120.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label.uppercase(),
                color = Color.White.copy(alpha = 0.4f),
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.sp
                )
            )
            Text(
                text = value,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
