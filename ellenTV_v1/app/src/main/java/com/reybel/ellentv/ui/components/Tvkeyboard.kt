package com.reybel.ellentv.ui.components

import android.view.KeyEvent
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════════════════════════
// COLORES DEL TECLADO
// ═══════════════════════════════════════════════════════════════════════════════
private val KeyBackground = Color(0xFF1A1A1A)
private val KeyBackgroundFocused = Color(0xFF00D9FF)
private val KeyBackgroundSpecial = Color(0xFF2A2A2A)
private val KeyBackgroundSearch = Color(0xFF00D9FF)
private val KeyTextColor = Color.White
private val KeyTextColorFocused = Color.Black
private val AccentColor = Color(0xFF00D9FF)
private val DefaultKeySize = 40.dp
private val KeyHeight = 44.dp
private val SpecialKeyHeight = 44.dp

// ═══════════════════════════════════════════════════════════════════════════════
// LAYOUTS DE TECLADO
// ═══════════════════════════════════════════════════════════════════════════════
private val KEYBOARD_ROWS_QWERTY = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
    listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
    listOf("Z", "X", "C", "V", "B", "N", "M")
)

private val KEYBOARD_ROWS_ABC = listOf(
    listOf("A", "B", "C", "D", "E", "F", "G"),
    listOf("H", "I", "J", "K", "L", "M", "N"),
    listOf("O", "P", "Q", "R", "S", "T", "U"),
    listOf("V", "W", "X", "Y", "Z"),
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
)

// Teclas especiales
private const val KEY_SPACE = "SPACE"
private const val KEY_BACKSPACE = "BACKSPACE"
private const val KEY_CLEAR = "CLEAR"
private const val KEY_SEARCH = "SEARCH"

/**
 * Teclado virtual para Android TV con navegación D-pad completa.
 *
 * Features:
 * - Layout QWERTY o ABC
 * - Navegación con D-pad en todas las direcciones
 * - Teclas especiales (espacio, borrar, limpiar, buscar)
 * - Animaciones de foco
 * - Campo de texto en vivo arriba
 */
@Composable
fun TVKeyboard(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search...",
    useQwertyLayout: Boolean = true,
    initialFocusRequester: FocusRequester? = null,
    onNavigateUp: () -> Unit = {}, // Para salir del teclado hacia arriba (resultados)
    onNavigateDown: () -> Unit = {}, // Para salir del teclado hacia abajo
    onNavigateRight: () -> Unit = {} // Para salir del teclado hacia la derecha (resultados)
) {
    val keyboardRows = if (useQwertyLayout) KEYBOARD_ROWS_QWERTY else KEYBOARD_ROWS_ABC

    // Estado de posición del cursor en el teclado
    var focusedRow by remember { mutableIntStateOf(1) } // Empezar en segunda fila (letras)
    var focusedCol by remember { mutableIntStateOf(0) }

    // Focus requesters para cada tecla - crear una matriz
    val keyFocusRequesters = remember(keyboardRows) {
        keyboardRows.mapIndexed { rowIndex, row ->
            row.mapIndexed { colIndex, _ ->
                FocusRequester()
            }
        }
    }

    // Focus requesters para teclas especiales
    val spaceFocusRequester = remember { FocusRequester() }
    val backspaceFocusRequester = remember { FocusRequester() }
    val clearFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }

    // Número de filas especiales (espacio, etc.)
    val specialRowIndex = keyboardRows.size

    // Focus inicial
    LaunchedEffect(Unit) {
        if (initialFocusRequester != null) {
            initialFocusRequester.requestFocus()
        } else {
            // Focus en primera letra (Q o A según layout)
            keyFocusRequesters.getOrNull(1)?.getOrNull(0)?.requestFocus()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val horizontalPadding = 12.dp
        val rowSpacing = 4.dp
        val specialSpacing = 6.dp
        val maxKeysInRow = keyboardRows.maxOf { it.size }
        val availableWidth = maxWidth - horizontalPadding * 2
        val computedKeySize = ((availableWidth - rowSpacing * (maxKeysInRow - 1)) / maxKeysInRow)
            .coerceIn(32.dp, 48.dp)
        val keySize = computedKeySize.coerceAtMost(DefaultKeySize)

        val specialRowFixedWidth = 140.dp + 56.dp + 56.dp + 120.dp + specialSpacing * 3
        val specialScale = (availableWidth / specialRowFixedWidth).coerceAtMost(1f)
        val spaceKeyWidth = 140.dp * specialScale
        val backspaceKeyWidth = 56.dp * specialScale
        val clearKeyWidth = 56.dp * specialScale
        val searchKeyWidth = 120.dp * specialScale

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ═══════════════════════════════════════════════════════════════════════
            // CAMPO DE TEXTO (Display del query)
            // ═══════════════════════════════════════════════════════════════════════
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E1E))
                    .border(2.dp, AccentColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = AccentColor,
                        modifier = Modifier.size(24.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = Color.White.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        Text(
                            text = value,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }

                    // Cursor parpadeante
                    if (value.isNotEmpty()) {
                        Text(
                            text = "│",
                            color = AccentColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ═══════════════════════════════════════════════════════════════════════
            // FILAS DEL TECLADO
            // ═══════════════════════════════════════════════════════════════════════
            keyboardRows.forEachIndexed { rowIndex, row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(rowSpacing),
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Centrar filas más cortas
                    if (row.size < maxKeysInRow) {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    row.forEachIndexed { colIndex, key ->
                        KeyboardKey(
                            text = key,
                            focusRequester = keyFocusRequesters[rowIndex][colIndex],
                            size = keySize,
                            onClick = {
                                onValueChange(value + key.lowercase())
                            },
                            onNavigate = { direction ->
                                when (direction) {
                                    NavigationDirection.UP -> {
                                        if (rowIndex == 0) {
                                            onNavigateUp()
                                        } else {
                                            // Ir a la fila anterior
                                            val targetRow = rowIndex - 1
                                            val targetCol = colIndex.coerceAtMost(keyboardRows[targetRow].size - 1)
                                            keyFocusRequesters[targetRow][targetCol].requestFocus()
                                        }
                                    }
                                    NavigationDirection.DOWN -> {
                                        if (rowIndex == keyboardRows.size - 1) {
                                            // Ir a las teclas especiales
                                            spaceFocusRequester.requestFocus()
                                        } else {
                                            val targetRow = rowIndex + 1
                                            val targetCol = colIndex.coerceAtMost(keyboardRows[targetRow].size - 1)
                                            keyFocusRequesters[targetRow][targetCol].requestFocus()
                                        }
                                    }
                                    NavigationDirection.LEFT -> {
                                        if (colIndex > 0) {
                                            keyFocusRequesters[rowIndex][colIndex - 1].requestFocus()
                                        }
                                    }
                                    NavigationDirection.RIGHT -> {
                                        if (colIndex < row.size - 1) {
                                            keyFocusRequesters[rowIndex][colIndex + 1].requestFocus()
                                        } else {
                                            onNavigateRight()
                                        }
                                    }
                                }
                            }
                        )
                    }

                    if (row.size < maxKeysInRow) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════════
            // FILA DE TECLAS ESPECIALES
            // ═══════════════════════════════════════════════════════════════════════
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(specialSpacing),
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))

                // SPACE
                SpecialKey(
                    icon = Icons.Default.SpaceBar,
                    label = "Space",
                    focusRequester = spaceFocusRequester,
                    width = spaceKeyWidth,
                    onClick = {
                        onValueChange(value + " ")
                    },
                    onNavigate = { direction ->
                        when (direction) {
                            NavigationDirection.UP -> {
                                // Ir a la última fila del teclado
                                val lastRow = keyboardRows.size - 1
                                keyFocusRequesters[lastRow][0].requestFocus()
                            }
                            NavigationDirection.DOWN -> onNavigateDown()
                            NavigationDirection.LEFT -> { /* Nada */ }
                            NavigationDirection.RIGHT -> backspaceFocusRequester.requestFocus()
                        }
                    }
                )

                // BACKSPACE
                SpecialKey(
                    icon = Icons.AutoMirrored.Filled.Backspace,
                    label = null,
                    focusRequester = backspaceFocusRequester,
                    width = backspaceKeyWidth,
                    onClick = {
                        if (value.isNotEmpty()) {
                            onValueChange(value.dropLast(1))
                        }
                    },
                    onNavigate = { direction ->
                        when (direction) {
                            NavigationDirection.UP -> {
                                val lastRow = keyboardRows.size - 1
                                val midCol = keyboardRows[lastRow].size / 2
                                keyFocusRequesters[lastRow][midCol].requestFocus()
                            }
                            NavigationDirection.DOWN -> onNavigateDown()
                            NavigationDirection.LEFT -> spaceFocusRequester.requestFocus()
                            NavigationDirection.RIGHT -> clearFocusRequester.requestFocus()
                        }
                    }
                )

                // CLEAR
                SpecialKey(
                    icon = Icons.Default.Clear,
                    label = null,
                    focusRequester = clearFocusRequester,
                    width = clearKeyWidth,
                    onClick = {
                        onValueChange("")
                    },
                    onNavigate = { direction ->
                        when (direction) {
                            NavigationDirection.UP -> {
                                val lastRow = keyboardRows.size - 1
                                val lastCol = keyboardRows[lastRow].size - 1
                                keyFocusRequesters[lastRow][lastCol.coerceAtMost(keyboardRows[lastRow].size - 1)].requestFocus()
                            }
                            NavigationDirection.DOWN -> onNavigateDown()
                            NavigationDirection.LEFT -> backspaceFocusRequester.requestFocus()
                            NavigationDirection.RIGHT -> searchFocusRequester.requestFocus()
                        }
                    }
                )

                // SEARCH
                SearchKey(
                    focusRequester = searchFocusRequester,
                    enabled = value.isNotEmpty(),
                    width = searchKeyWidth,
                    onClick = {
                        if (value.isNotEmpty()) {
                            onSearch()
                        }
                    },
                    onNavigate = { direction ->
                        when (direction) {
                            NavigationDirection.UP -> {
                                val lastRow = keyboardRows.size - 1
                                val lastCol = keyboardRows[lastRow].size - 1
                                keyFocusRequesters[lastRow][lastCol].requestFocus()
                            }
                            NavigationDirection.DOWN -> onNavigateDown()
                            NavigationDirection.LEFT -> clearFocusRequester.requestFocus()
                            NavigationDirection.RIGHT -> onNavigateRight()
                        }
                    }
                )

                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMPONENTES DE TECLAS
// ═══════════════════════════════════════════════════════════════════════════════

private enum class NavigationDirection {
    UP, DOWN, LEFT, RIGHT
}

@Composable
private fun KeyboardKey(
    text: String,
    focusRequester: FocusRequester,
    size: Dp,
    onClick: () -> Unit,
    onNavigate: (NavigationDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = interactionSource.collectIsFocusedAsState().value

    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) KeyBackgroundFocused else KeyBackground,
        animationSpec = tween(100),
        label = "keyBg"
    )

    val textColor = if (isFocused) KeyTextColorFocused else KeyTextColor

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = tween(100),
        label = "keyScale"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onClick()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        onNavigate(NavigationDirection.UP)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onNavigate(NavigationDirection.DOWN)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onNavigate(NavigationDirection.LEFT)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onNavigate(NavigationDirection.RIGHT)
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
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SpecialKey(
    icon: ImageVector,
    label: String?,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onNavigate: (NavigationDirection) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 56.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = interactionSource.collectIsFocusedAsState().value

    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) KeyBackgroundFocused else KeyBackgroundSpecial,
        animationSpec = tween(100),
        label = "specialKeyBg"
    )

    val contentColor = if (isFocused) KeyTextColorFocused else KeyTextColor

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(100),
        label = "specialKeyScale"
    )

    Box(
        modifier = modifier
            .width(width)
            .height(SpecialKeyHeight)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onClick()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        onNavigate(NavigationDirection.UP)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onNavigate(NavigationDirection.DOWN)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onNavigate(NavigationDirection.LEFT)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onNavigate(NavigationDirection.RIGHT)
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            if (label != null) {
                Text(
                    text = label,
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
private fun SearchKey(
    focusRequester: FocusRequester,
    enabled: Boolean,
    width: Dp,
    onClick: () -> Unit,
    onNavigate: (NavigationDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused = interactionSource.collectIsFocusedAsState().value

    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0xFF333333)
            isFocused -> Color.White
            else -> KeyBackgroundSearch
        },
        animationSpec = tween(100),
        label = "searchKeyBg"
    )

    val contentColor = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        isFocused -> AccentColor
        else -> Color.Black
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused && enabled) 1.05f else 1f,
        animationSpec = tween(100),
        label = "searchKeyScale"
    )

    Box(
        modifier = modifier
            .width(width)
            .height(KeyHeight)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .then(
                if (isFocused && enabled) {
                    Modifier.border(2.dp, AccentColor, RoundedCornerShape(10.dp))
                } else Modifier
            )
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (enabled) onClick()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        onNavigate(NavigationDirection.UP)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onNavigate(NavigationDirection.DOWN)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onNavigate(NavigationDirection.LEFT)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onNavigate(NavigationDirection.RIGHT)
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { if (enabled) onClick() }
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = "Search",
                color = contentColor,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}
