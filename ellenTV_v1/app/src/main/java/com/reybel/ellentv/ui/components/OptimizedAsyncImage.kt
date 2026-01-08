package com.reybel.ellentv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import coil.size.Scale
import coil.size.Size
import androidx.lifecycle.compose.LocalLifecycleOwner

const val OPTIMIZED_PLACEHOLDER_MEMORY_KEY = "optimized_async_image_placeholder"

@Composable
fun OptimizedAsyncImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    targetSizePx: Int? = 96
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val placeholderPainter = remember { ColorPainter(Color(0x22FFFFFF)) }
    val imageRequest = remember(url) {
        val builder = ImageRequest.Builder(context)
            .data(url)
            .crossfade(false) // Sin crossfade = más rápido
            .scale(Scale.FIT)
            .memoryCacheKey(url) // Cache explícita por URL
            .diskCacheKey(url)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            // Permite usar cache aunque la imagen esté desactualizada
            .allowHardware(true) // Usa hardware acceleration
            .lifecycle(lifecycle)
        when (targetSizePx) {
            null -> builder.size(Size.ORIGINAL)
            else -> builder.size(targetSizePx)
        }
        builder.build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = placeholderPainter,
        error = placeholderPainter,
        fallback = placeholderPainter
    )
}
