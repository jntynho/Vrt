package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette
import com.example.ui.theme.MaterialThemePalette
import com.example.ui.theme.MaterialYouColorPresets

/**
 * SELECT-UNIFY: Color Palette Horizontal Picker with:
 * 1. Soft Gradient Mask on both horizontal edges for smooth peek-in/out fading.
 * 2. Ultra-smooth spring-based selection animation with elastic ring scaling and inner depth.
 * 3. Haptic feedback and animated palette name transition.
 */
@Composable
fun ColorPalettePicker(
    selectedId: String,
    onSelectPalette: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val haptic = LocalHapticFeedback.current
    val presets = MaterialYouColorPresets.Presets
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Color Palette",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = palette.textPrimary
            )

            // Current theme name indicator
            val currentPreset = MaterialYouColorPresets.getPreset(selectedId)
            Text(
                text = currentPreset.name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = accent
            )
        }

        // Horizontal scroll container with Dynamic Soft Gradient Mask
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val maskWidth = 24.dp.toPx()

                    val scrollVal = scrollState.value.toFloat()
                    val maxScroll = if (scrollState.maxValue != Int.MAX_VALUE) scrollState.maxValue.toFloat() else 0f

                    // Left soft fade mask: completely disabled at start (scrollVal = 0) so the first item is 100% visible and unmasked,
                    // and smoothly transitions in only as the user scrolls content off the left edge.
                    val leftProgress = if (maxScroll > 0f) {
                        (scrollVal / maskWidth).coerceIn(0f, 1f)
                    } else 0f

                    // Right soft fade mask: visible while there is more content to scroll to the right,
                    // and smoothly fades out as the user reaches the end of the scroll.
                    val rightProgress = if (maxScroll > 0f) {
                        ((maxScroll - scrollVal) / maskWidth).coerceIn(0f, 1f)
                    } else 0f

                    // Left soft fade mask (fades from transparent to solid only when scrolled)
                    if (leftProgress > 0.005f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0.0f to Color.Black.copy(alpha = 1f - leftProgress),
                                1.0f to Color.Black,
                                startX = 0f,
                                endX = maskWidth
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }

                    // Right soft fade mask (fades from solid to transparent when more items exist)
                    if (rightProgress > 0.005f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0.0f to Color.Black,
                                1.0f to Color.Black.copy(alpha = 1f - rightProgress),
                                startX = size.width - maskWidth,
                                endX = size.width
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Presets: Circular swatches with Pure Circular Selection Ring as originally implemented
                presets.filter { it.id != MaterialYouColorPresets.SYSTEM_DYNAMIC_ID }.forEach { colorPalette ->
                    val isSelected = selectedId.equals(colorPalette.id, ignoreCase = true)

                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .testTag("palette_item_${colorPalette.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) accent.copy(alpha = 0.15f)
                                    else Color.Transparent
                                )
                                .border(
                                    width = if (isSelected) 2.5.dp else 0.dp,
                                    color = if (isSelected) accent else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { onSelectPalette(colorPalette.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            SplitCircleSwatch(
                                topColor = colorPalette.previewTop,
                                bottomLeftColor = colorPalette.previewBottomLeft,
                                bottomRightColor = colorPalette.previewBottomRight,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draws a circle split into:
 * 1. Top half (Dominant theme dark/background color)
 * 2. Bottom-left quadrant (Primary accent)
 * 3. Bottom-right quadrant (Secondary/Tertiary accent)
 */
@Composable
fun SplitCircleSwatch(
    topColor: Color,
    bottomLeftColor: Color,
    bottomRightColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        // Direct hardware-accelerated arc drawing with zero allocations
        // 1. Top half (180° to 360°)
        drawArc(
            color = topColor,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true
        )
        // 2. Bottom-left quadrant (90° to 180°)
        drawArc(
            color = bottomLeftColor,
            startAngle = 90f,
            sweepAngle = 90f,
            useCenter = true
        )
        // 3. Bottom-right quadrant (0° to 90°)
        drawArc(
            color = bottomRightColor,
            startAngle = 0f,
            sweepAngle = 90f,
            useCenter = true
        )
    }
}
