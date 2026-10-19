package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.privacyImageBlur

val PrivacyScrim = Color.Black.copy(alpha = 0.75f)

@Composable
fun isAppLightTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

@Composable
fun SleekSlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 4.dp,
    thumbDiameter: Dp = 16.dp,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentValueRange by rememberUpdatedState(valueRange)
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    val thumbRadius = thumbDiameter / 2

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val thumbRadiusPx = with(density) { thumbRadius.toPx() }
        val usableWidth = (widthPx - thumbRadiusPx * 2).coerceAtLeast(1f)

        fun updateFromX(touchX: Float) {
            val clamped = (touchX - thumbRadiusPx).coerceIn(0f, usableWidth)
            val newFraction = clamped / usableWidth
            val newValue = currentValueRange.start + newFraction * (currentValueRange.endInclusive - currentValueRange.start)
            currentOnValueChange(newValue)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(usableWidth) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        updateFromX(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            updateFromX(change.position.x)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // Inactive slim track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(inactiveColor)
            )
            // Active slim track
            val activeTrackWidth = with(density) { (thumbRadiusPx + fraction * usableWidth).toDp() }
            Box(
                modifier = Modifier
                    .width(activeTrackWidth)
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(activeColor)
            )
            // Sleek, clean circular thumb
            val thumbOffset = with(density) { (fraction * usableWidth).toDp() }
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(thumbDiameter)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(activeColor)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

/**
 * Ultra-smooth, tactile fluid slider with continuous responsive touch tracking,
 * rounded capsule track, and glowing floating thumb.
 */
@Composable
fun SmoothFluidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveTrackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    trackHeight: Dp = 10.dp,
    thumbDiameter: Dp = 22.dp,
    expandedThumbDiameter: Dp = 26.dp,
    testTag: String? = null
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentValueRange by rememberUpdatedState(valueRange)
    val haptic = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }

    val animatedThumbDiameter by animateDpAsState(
        targetValue = if (isDragging) expandedThumbDiameter else thumbDiameter,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "fluid_thumb_diameter"
    )
    val animatedElevation by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 2.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "fluid_thumb_elevation"
    )

    val span = (currentValueRange.endInclusive - currentValueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - currentValueRange.start) / span).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.CenterStart
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val thumbRadiusPx = with(density) { thumbDiameter.toPx() / 2f }
        val usableWidth = (widthPx - thumbRadiusPx * 2f).coerceAtLeast(1f)

        fun updateFromTouch(touchX: Float) {
            val clamped = (touchX - thumbRadiusPx).coerceIn(0f, usableWidth)
            val newFraction = clamped / usableWidth
            val newValue = currentValueRange.start + newFraction * span
            currentOnValueChange(newValue)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(usableWidth, span, currentValueRange.start) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isDragging = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        updateFromTouch(down.position.x)
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            updateFromTouch(change.position.x)
                        }
                        isDragging = false
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // Inactive track: soft rounded capsule
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(inactiveTrackColor)
            )

            // Active track: glowing liquid gradient pill
            val currentThumbCenterPx = thumbRadiusPx + fraction * usableWidth
            val activeTrackWidthDp = with(density) { (currentThumbCenterPx + (trackHeight.toPx() / 2f)).toDp() }
            val maxWidthDp = with(density) { widthPx.toDp() }
            val clampedActiveWidthDp = if (activeTrackWidthDp > maxWidthDp) maxWidthDp else if (activeTrackWidthDp < 0.dp) 0.dp else activeTrackWidthDp
            Box(
                modifier = Modifier
                    .width(clampedActiveWidthDp)
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                activeColor.copy(alpha = 0.80f),
                                activeColor
                            )
                        )
                    )
            )

            // Thumb: smooth floating pearl
            val currentThumbDiameterPx = with(density) { animatedThumbDiameter.toPx() }
            val thumbOffset = with(density) { (currentThumbCenterPx - currentThumbDiameterPx / 2f).toDp() }
            val thumbCenterFill = if (activeColor.luminance() > 0.85f) Color(0xFF1E1E1E) else Color.White

            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(animatedThumbDiameter)
                    .shadow(
                        elevation = animatedElevation,
                        shape = CircleShape,
                        spotColor = activeColor.copy(alpha = 0.40f)
                    )
                    .clip(CircleShape)
                    .background(thumbCenterFill)
                    .border(2.dp, activeColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Subtle center pip
                Box(
                    modifier = Modifier
                        .size(if (isDragging) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(activeColor)
                )
            }
        }
    }
}


@Composable
fun GradientSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    currentColor: Color,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 8.dp,
    thumbDiameter: Dp = 18.dp
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val fraction = value.coerceIn(0f, 1f)
    val thumbRadius = thumbDiameter / 2

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val thumbRadiusPx = with(density) { thumbRadius.toPx() }
        val usableWidth = (widthPx - thumbRadiusPx * 2).coerceAtLeast(1f)

        fun updateFromX(touchX: Float) {
            val clamped = (touchX - thumbRadiusPx).coerceIn(0f, usableWidth)
            val newFraction = clamped / usableWidth
            currentOnValueChange(newFraction)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(usableWidth) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        updateFromX(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            updateFromX(change.position.x)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // The colored gradient track itself - slim and sleek
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black,
                                Color(0xFF333333),
                                Color(0xFF666666),
                                Color(0xFF999999),
                                Color(0xFFCCCCCC),
                                Color.White
                            )
                        )
                    )
                    .border(0.75.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
            )

            // Dynamic Thumb with current color fill and crisp contrasting border
            val thumbOffset = with(density) { (fraction * usableWidth).toDp() }
            val thumbBorderColor = if (currentColor.luminance() > 0.5f) Color.Black else Color.White
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(thumbDiameter)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(currentColor)
                    .border(2.dp, thumbBorderColor, CircleShape)
            )
        }
    }
}

/**
 * Lightweight, GPU-accelerated horizontal fade mask for smooth gradient edge aesthetic.
 */
fun Modifier.horizontalFadeEdge(fadeWidth: Dp = 20.dp): Modifier = this.then(
    Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = fadeWidth.toPx()
            if (size.width > fadePx * 2 && fadePx > 0f) {
                val leftFraction = (fadePx / size.width).coerceIn(0f, 0.49f)
                val rightFraction = 1f - leftFraction
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        leftFraction to Color.Black,
                        rightFraction to Color.Black,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
        }
)

/**
 * Smooth & Lightweight Photo Option Circle with Fade + Subtle Scale In reveal effect.
 */
@Composable
fun SelectPhotoCircleItem(
    imgUrl: String,
    isSelected: Boolean,
    skeletonBg: Color,
    skeletonBorder: Color,
    isBetaTest: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isImageLoaded by remember(imgUrl) { mutableStateOf(false) }

    val photoAlpha by animateFloatAsState(
        targetValue = if (isImageLoaded) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "circle_photo_alpha"
    )
    val photoScale by animateFloatAsState(
        targetValue = if (isImageLoaded) 1f else 0.88f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "circle_photo_scale"
    )
    val circleSelectScale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "circle_select_scale"
    )

    Box(
        modifier = modifier
            .size(50.dp)
            .graphicsLayer {
                scaleX = circleSelectScale
                scaleY = circleSelectScale
            }
            .clip(CircleShape)
            .background(skeletonBg)
            .border(
                BorderStroke(
                    if (isSelected) 2.5.dp else 1.2.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else skeletonBorder
                ),
                CircleShape
            )
            .clickable { onSelect() },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imgUrl)
                .crossfade(true)
                .build(),
            contentDescription = "Photo option",
            contentScale = ContentScale.Crop,
            onSuccess = { isImageLoaded = true },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = photoAlpha
                    scaleX = photoScale
                    scaleY = photoScale
                }
                .privacyImageBlur(isBetaTest)
        )
    }
}

private val SubtitleSpectrumColorStops = listOf(
    0.00f to Color(0xFFFFFFFF), // Pure White (default)
    0.14f to Color(0xFFFFEA00), // Cinema Yellow
    0.28f to Color(0xFFFF9100), // Warm Orange
    0.42f to Color(0xFFFF5252), // Coral Red
    0.56f to Color(0xFFF472B6), // Rose Pink
    0.70f to Color(0xFFC084FC), // Soft Purple
    0.85f to Color(0xFF38BDF8), // Sky Cyan
    1.00f to Color(0xFF4ADE80)  // Mint Green
)

fun sampleSubtitleSpectrumColor(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    if (f <= 0.02f) return Color.White
    for (i in 0 until SubtitleSpectrumColorStops.lastIndex) {
        val (startPos, startColor) = SubtitleSpectrumColorStops[i]
        val (endPos, endColor) = SubtitleSpectrumColorStops[i + 1]
        if (f in startPos..endPos) {
            val localT = ((f - startPos) / (endPos - startPos)).coerceIn(0f, 1f)
            return androidx.compose.ui.graphics.lerp(startColor, endColor, localT)
        }
    }
    return SubtitleSpectrumColorStops.last().second
}

fun getSubtitleSpectrumColorLabel(fraction: Float): String {
    val f = fraction.coerceIn(0f, 1f)
    return when {
        f < 0.07f -> "White"
        f < 0.21f -> "Yellow"
        f < 0.35f -> "Orange"
        f < 0.49f -> "Red"
        f < 0.63f -> "Pink"
        f < 0.77f -> "Purple"
        f < 0.92f -> "Cyan"
        else -> "Green"
    }
}

/**
 * Native tactile Multi-Color Spectrum Slider for instant subtitle color selection.
 */
@Composable
fun MultiColorSpectrumSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 6.dp,
    thumbDiameter: Dp = 16.dp
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val fraction = value.coerceIn(0f, 1f)
    val activeColor = remember(fraction) { sampleSubtitleSpectrumColor(fraction) }
    val haptic = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }

    val animatedThumbSize by animateDpAsState(
        targetValue = if (isDragging) (thumbDiameter + 4.dp) else thumbDiameter,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "spectrum_thumb_size"
    )
    val thumbRadius = thumbDiameter / 2

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val thumbRadiusPx = with(density) { thumbRadius.toPx() }
        val usableWidth = (widthPx - thumbRadiusPx * 2f).coerceAtLeast(1f)

        fun updateFromX(touchX: Float) {
            val clamped = (touchX - thumbRadiusPx).coerceIn(0f, usableWidth)
            val newFraction = clamped / usableWidth
            currentOnValueChange(newFraction)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(usableWidth) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isDragging = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        down.consume()
                        updateFromX(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            updateFromX(change.position.x)
                        }
                        isDragging = false
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // Multi-color rainbow gradient track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            colorStops = SubtitleSpectrumColorStops.toTypedArray()
                        )
                    )
                    .border(0.75.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            )

            // Dynamic color-matched floating thumb
            val currentThumbSizePx = with(density) { animatedThumbSize.toPx() }
            val thumbCenterPx = thumbRadiusPx + fraction * usableWidth
            val thumbOffset = with(density) { (thumbCenterPx - currentThumbSizePx / 2f).toDp() }
            val thumbBorderColor = if (activeColor.luminance() > 0.65f) Color(0xFF0F172A) else Color.White

            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .size(animatedThumbSize)
                    .shadow(if (isDragging) 6.dp else 3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(activeColor)
                    .border(2.dp, thumbBorderColor, CircleShape)
            )
        }
    }
}

