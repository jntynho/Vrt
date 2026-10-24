package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalVaultPalette

/**
 * Modern rounded shape matching the Nuvio / Vault dialog design.
 */
val VaultDialogShape = RoundedCornerShape(26.dp)

/**
 * Unified corner radius and shape for all Sort dropdown menus and their selection highlights.
 */
val VaultSortMenuCornerRadius = 16.dp
val VaultSortMenuShape = RoundedCornerShape(VaultSortMenuCornerRadius)

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

/**
 * Strips Material 3 DropdownMenu's built-in 8.dp top and bottom vertical padding
 * so that menu items and selection cards sit flush with the window edges with zero gaps.
 */
fun Modifier.removeDropdownMenuVerticalPadding(): Modifier = this.layout { measurable, constraints ->
    val vPad = 8.dp.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = vPad * 2))
    val resolvedHeight = (placeable.height - vPad * 2).coerceAtLeast(0)
    layout(placeable.width, resolvedHeight) {
        placeable.placeRelative(0, -vPad)
    }
}

/**
 * Unified Sort DropdownMenu container used across the entire project:
 * - Zero outer/vertical padding so selection cards touch the window edges cleanly.
 * - Consistent 16.dp rounded corners with top glow border.
 */
@Composable
fun VaultSortDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalVaultPalette.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .widthIn(min = 190.dp)
            .clip(VaultSortMenuShape)
            .vaultTopGlow(cornerRadius = VaultSortMenuCornerRadius)
            .removeDropdownMenuVerticalPadding(),
        shape = VaultSortMenuShape,
        containerColor = palette.cardBg,
        content = content
    )
}

/**
 * Unified Sort DropdownMenuItem:
 * - No checkmark icon (`leadingIcon = null`).
 * - Selection card shape (`VaultSortMenuShape`, 16.dp) visually matches the menu window corners.
 * - Zero outer margins/gaps between items and window edges.
 */
@Composable
fun VaultSortDropdownItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = MaterialTheme.colorScheme.primary

    val animatedTextColor by animateColorAsState(
        targetValue = if (isSelected) accent else palette.textPrimary,
        animationSpec = tween(durationMillis = 180),
        label = "vault_sort_text_color"
    )
    val animatedBgColor by animateColorAsState(
        targetValue = if (isSelected) accent.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "vault_sort_bg_color"
    )

    DropdownMenuItem(
        modifier = modifier
            .fillMaxWidth()
            .clip(VaultSortMenuShape)
            .background(animatedBgColor),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        text = {
            Text(
                text = label,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = animatedTextColor,
                fontSize = 14.5.sp
            )
        },
        onClick = onClick
    )
}

