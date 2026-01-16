package com.reybel.ellentv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class AppSection(val label: String) {
    LIVE("Live TV"),
    ON_DEMAND("On Demand"),
    SETTINGS("Settings")
}

@Composable
fun SideMenuDrawer(
    open: Boolean,
    current: AppSection,
    onSelect: (AppSection) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(open) {
        if (open) firstFocus.requestFocus()
    }

    // Scrim + drawer encima del contenido
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // scrim suave (ligero)
                .background(Color.Black.copy(alpha = 0.18f))
        ) {
            // Drawer
            AnimatedVisibility(
                visible = open,
                enter = slideInHorizontally(initialOffsetX = { -it }),
                exit = slideOutHorizontally(targetOffsetX = { -it }),
                modifier = Modifier
                    .align(Alignment.CenterStart)
            ) {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        // degradado: sólido a la izquierda, transparente hacia la derecha
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.96f),
                                    Color.Black.copy(alpha = 0.82f),
                                    Color.Black.copy(alpha = 0.12f)
                                )
                            )
                        )
                        .padding(start = 20.dp, top = 26.dp, end = 24.dp, bottom = 18.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Text(
                            text = "ELLEN TV",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Color.White.copy(alpha = 0.92f)
                        )

                        Spacer(Modifier.height(10.dp))

                        DrawerItem(
                            text = AppSection.LIVE.label,
                            selected = current == AppSection.LIVE,
                            focusRequester = firstFocus,
                            onClick = { onSelect(AppSection.LIVE) }
                        )
                        DrawerItem(
                            text = AppSection.ON_DEMAND.label,
                            selected = current == AppSection.ON_DEMAND,
                            onClick = { onSelect(AppSection.ON_DEMAND) }
                        )
                        DrawerItem(
                            text = AppSection.SETTINGS.label,
                            selected = current == AppSection.SETTINGS,
                            onClick = { onSelect(AppSection.SETTINGS) }
                        )

                        Spacer(Modifier.weight(1f))

                        Text(
                            text = "DPAD RIGHT = close",
                            color = Color.White.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }

    val bg = when {
        selected -> Color(0xFF1E88E5).copy(alpha = 0.25f)
        focused -> Color.White.copy(alpha = 0.14f)
        else -> Color.Transparent
    }

    val border = when {
        focused -> Color(0xFF42A5F5).copy(alpha = 0.95f)
        selected -> Color.White.copy(alpha = 0.16f)
        else -> Color.Transparent
    }

    Surface(
        onClick = onClick,
        color = bg,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, border),
        tonalElevation = if (focused || selected) 4.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.95f),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
            )
        }
    }
}
