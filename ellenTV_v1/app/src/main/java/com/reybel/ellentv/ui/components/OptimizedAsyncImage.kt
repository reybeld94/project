package com.reybel.ellentv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import coil.decode.DataSource

@Composable
fun OptimizedAsyncImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val context = LocalContext.current
    val imageRequest = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(false) // Sin crossfade = más rápido
            .scale(Scale.FIT)
            .size(96) // Tamaño específico para mejor performance
            .memoryCacheKey(url) // Cache explícita por URL
            .diskCacheKey(url)
            // Permite usar cache aunque la imagen esté desactualizada
            .allowHardware(true) // Usa hardware acceleration
            .build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale
    )
}