package com.reybel.ellentv.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.reybel.ellentv.R

// =====================
// Typography (Inter)
// =====================
val InterFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val AppTypography = Typography(
    titleLarge = Typography().titleLarge.copy(fontFamily = InterFont),
    titleMedium = Typography().titleMedium.copy(fontFamily = InterFont),
    bodyLarge = Typography().bodyLarge.copy(fontFamily = InterFont),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = InterFont),
    bodySmall = Typography().bodySmall.copy(fontFamily = InterFont),
    labelLarge = Typography().labelLarge.copy(fontFamily = InterFont),
)
