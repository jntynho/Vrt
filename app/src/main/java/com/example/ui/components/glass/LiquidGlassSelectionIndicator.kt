package com.example.ui.components.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pure Liquid Glass Selection Indicator with Velocity-Coupled Fluid Physics & High-Performance Specular Glass.
 *
 * Implements:
 * - Directional fluid elongation with trailing liquid droplet lag.
 * - Perfectly circular, harmonious pill shape with adaptive width for phones and tablets.
 * - Single-pass specular translucent liquid glass gradient and complete 360-degree crystal edge highlight.
 * - Frosted diffusion & inner luminescence directly modulated by activeTabBlurDp and intensity sliders.
 * - Reactive liquid elastic pulse animation on gesture arrival / reselection.
 * - Zero re-layout overhead (uses graphicsLayer translationX, scaleX, scaleY).
 */
@Composable
fun LiquidGlassSelectionIndicator(
    currentCenterX: Float,
    itemWidth: Dp,
    height: Dp,
    accentColor: Color,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
    stretchX: Float = 1.0f,
    squashY: Float = 1.0f,
    motionDirection: Float = 0f,
    isVisible: Boolean = true,
    pulseTrigger: Int = 0,
    intensity: Float = 0.6f,
    activeTabBlurDp: Int = 16
) {
    val alphaAnim = remember { Animatable(if (isVisible) 1f else 0f) }
    val pulseScaleAnim = remember { Animatable(1f) }

    LaunchedEffect(isVisible) {
        if (!isVisible) {
            alphaAnim.animateTo(0f, animationSpec = tween(150))
        } else {
            alphaAnim.animateTo(1f, animationSpec = tween(150))
        }
    }

    LaunchedEffect(pulseTrigger) {
        if (pulseTrigger > 0) {
            pulseScaleAnim.snapTo(1f)
            pulseScaleAnim.animateTo(
                targetValue = 1.06f,
                animationSpec = tween(durationMillis = 85)
            )
            pulseScaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    // Adaptive circular harmonious pill geometry
    val baseWidth = (itemWidth - 10.dp).coerceIn(44.dp, 84.dp)
    val pillShape = RoundedCornerShape(percent = 50)

    val clampedIntensity = intensity.coerceIn(0f, 1f)
    val factor = (clampedIntensity / 0.6f).coerceIn(0.20f, 1.65f)
    val blurFraction = (activeTabBlurDp.coerceIn(0, 40) / 40f)
    val frostedDiffusionBoost = 0.80f + (0.45f * blurFraction)

    val SpecularTint = lerp(Color.White, accentColor, if (isDarkTheme) 0.18f else 0.12f)

    // Vibrant translucent liquid glass gradient with rich dark-theme presence and refraction
    val glassIndicatorGradient = if (isDarkTheme) {
        listOf(
            SpecularTint.copy(alpha = (0.34f * factor * frostedDiffusionBoost).coerceIn(0.12f, 0.62f)),
            accentColor.copy(alpha = (0.22f * factor * frostedDiffusionBoost).coerceIn(0.08f, 0.44f)),
            Color.White.copy(alpha = (0.16f * factor * frostedDiffusionBoost).coerceIn(0.06f, 0.35f)),
            SpecularTint.copy(alpha = (0.14f * factor * frostedDiffusionBoost).coerceIn(0.05f, 0.32f))
        )
    } else {
        listOf(
            Color.White.copy(alpha = (0.85f * factor * frostedDiffusionBoost).coerceIn(0.20f, 0.98f)),
            SpecularTint.copy(alpha = (0.64f * factor * frostedDiffusionBoost).coerceIn(0.16f, 0.92f)),
            Color.White.copy(alpha = (0.45f * factor * frostedDiffusionBoost).coerceIn(0.12f, 0.85f))
        )
    }

    // Complete, continuous 360-degree specular glass perimeter border (no broken horseshoe artifact)
    val glassBorderBrush = Brush.verticalGradient(
        colors = if (isDarkTheme) {
            listOf(
                Color.White.copy(alpha = (0.65f * factor).coerceIn(0.22f, 0.95f)),
                SpecularTint.copy(alpha = (0.38f * factor).coerceIn(0.15f, 0.68f)),
                accentColor.copy(alpha = (0.28f * factor).coerceIn(0.12f, 0.52f)),
                Color.White.copy(alpha = (0.22f * factor).coerceIn(0.10f, 0.42f))
            )
        } else {
            listOf(
                Color.White.copy(alpha = (0.88f * factor).coerceIn(0.25f, 1.0f)),
                SpecularTint.copy(alpha = (0.50f * factor).coerceIn(0.18f, 0.88f)),
                Color.White.copy(alpha = (0.28f * factor).coerceIn(0.12f, 0.65f))
            )
        }
    )

    val pulseScale = pulseScaleAnim.value
    // Directional fluid transform pivot: shifts opposite to motion direction to stretch naturally
    val pivotX = (0.5f - (motionDirection * 0.18f)).coerceIn(0.32f, 0.68f)

    Box(
        modifier = modifier
            .graphicsLayer {
                translationX = currentCenterX - (baseWidth.toPx() / 2f)
                transformOrigin = TransformOrigin(pivotX, 0.5f)
                scaleX = stretchX * pulseScale
                scaleY = (squashY * (2f - pulseScale)).coerceIn(0.90f, 1.04f)
                alpha = alphaAnim.value
            }
            .width(baseWidth)
            .height(height)
            .clip(pillShape)
            .background(Brush.verticalGradient(glassIndicatorGradient))
            .drawBehind {
                if (blurFraction > 0.02f && clampedIntensity > 0.02f) {
                    val glowAlpha = (if (isDarkTheme) 0.35f else 0.45f) * blurFraction * factor.coerceAtMost(1.2f)
                    val radius = size.maxDimension * (0.45f + 0.45f * blurFraction)
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                SpecularTint.copy(alpha = glowAlpha),
                                accentColor.copy(alpha = glowAlpha * 0.45f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.38f),
                            radius = radius.coerceAtLeast(1f)
                        ),
                        cornerRadius = CornerRadius(size.height * 0.5f, size.height * 0.5f)
                    )
                }
            }
            .border(
                width = 1.dp,
                brush = glassBorderBrush,
                shape = pillShape
            )
    )
}
