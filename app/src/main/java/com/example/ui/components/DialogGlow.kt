package com.example.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalVaultPalette

/**
 * Modern rounded shape matching the Nuvio / Vault dialog design.
 */
val VaultDialogShape = RoundedCornerShape(26.dp)

/**
 * Modern dark dialog background color (#1A1F25)
 */
val VaultDialogContainerColor = Color(0xFF1A1F25)

/**
 * Renders an ultra-subtle, high-end fading frame border around dialogs.
 * 
 * 1. Base color: Pure White in Dark themes, pure Black in Light theme, matching the reference image.
 * 2. Fully adaptive to any window height: The gradient calculates dynamically based on each dialog's runtime height,
 *    gradually fading along the left and right vertical edges until it completely vanishes (alpha = 0) at the two bottom corners.
 */
fun Modifier.vaultDialogTopGlow(
    baseColor: Color,
    cornerRadius: Dp = 26.dp,
    strokeWidth: Dp = 1.2.dp
): Modifier = this.drawWithContent {
    drawContent()
    val strokePx = strokeWidth.toPx()
    val halfStroke = strokePx / 2f
    val rPx = cornerRadius.toPx()

    // Dynamic proportional fade line based on each specific window's height
    // Vanishes right as the bottom corner curves begin
    val fadeEndY = (size.height - rPx).coerceAtLeast(1f)

    val frameBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to baseColor.copy(alpha = 0.18f),
            0.28f to baseColor.copy(alpha = 0.13f),
            0.60f to baseColor.copy(alpha = 0.07f),
            0.85f to baseColor.copy(alpha = 0.02f),
            1.0f to Color.Transparent
        ),
        startY = 0f,
        endY = fadeEndY
    )

    drawRoundRect(
        brush = frameBrush,
        topLeft = Offset(halfStroke, halfStroke),
        size = Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = CornerRadius(rPx, rPx),
        style = Stroke(width = strokePx)
    )
}

/**
 * Composable helper that dynamically injects White for Dark themes and Black for Light theme.
 */
@Composable
fun Modifier.vaultTopGlow(
    glowColor: Color? = null,
    cornerRadius: Dp = 26.dp
): Modifier {
    val palette = LocalVaultPalette.current
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f || palette.name.equals("light", ignoreCase = true)
    val baseColor = glowColor ?: if (isLight) Color.Black else Color.White

    return this.vaultDialogTopGlow(
        baseColor = baseColor,
        cornerRadius = cornerRadius
    )
}
