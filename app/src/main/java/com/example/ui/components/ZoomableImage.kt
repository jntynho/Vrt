package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

/**
 * Gallery-grade ZoomableImage:
 * - Natural two-finger pinch-to-zoom centered around touch centroid (focal point).
 * - Elastic spring physics with rubber-band overscroll.
 * - Double-tap to zoom centered on the exact tapped coordinate.
 * - Seamless gesture handoff to HorizontalPager:
 *   - At 1x: Full native swiping to adjacent images.
 *   - When zoomed in: Free panning; swiping past the image boundary smoothly transitions to next/previous photo.
 */
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    onZoomChanged: ((Boolean) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    onNavigateNext: (() -> Unit)? = null,
    onNavigatePrevious: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()

    val scaleAnim = remember { Animatable(1f) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Reset zoom and offsets automatically when resetKey changes (e.g. pager page changed or BackHandler reset)
    LaunchedEffect(resetKey) {
        if (scaleAnim.value > 1.05f) {
            launch {
                scaleAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                offsetXAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                offsetYAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        } else {
            scaleAnim.snapTo(1f)
            offsetXAnim.snapTo(0f)
            offsetYAnim.snapTo(0f)
        }
    }

    val isZoomed = scaleAnim.value > 1.05f
    LaunchedEffect(isZoomed) {
        onZoomChanged?.invoke(isZoomed)
    }

    val context = LocalContext.current
    var isImageLoaded by remember(model) { mutableStateOf(false) }
    var isImageError by remember(model) { mutableStateOf(false) }

    val imageRequest = remember(model) {
        if (model is ImageRequest) {
            model
        } else {
            ImageRequest.Builder(context)
                .data(model)
                .crossfade(true)
                .crossfade(260)
                .build()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onTap = { onTap?.invoke() },
                    onDoubleTap = { tapOffset ->
                        coroutineScope.launch {
                            if (scaleAnim.value > 1.05f) {
                                launch {
                                    scaleAnim.animateTo(
                                        targetValue = 1f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                                launch {
                                    offsetXAnim.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                                launch {
                                    offsetYAnim.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            } else {
                                val targetScale = 2.75f
                                if (containerSize.width > 0 && containerSize.height > 0) {
                                    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                    val focus = tapOffset - center

                                    val maxOffsetX = (containerSize.width * (targetScale - 1f) / 2f).coerceAtLeast(0f)
                                    val maxOffsetY = (containerSize.height * (targetScale - 1f) / 2f).coerceAtLeast(0f)

                                    val targetOffsetX = (-focus.x * (targetScale - 1f)).coerceIn(-maxOffsetX, maxOffsetX)
                                    val targetOffsetY = (-focus.y * (targetScale - 1f)).coerceIn(-maxOffsetY, maxOffsetY)

                                    launch {
                                        scaleAnim.animateTo(
                                            targetValue = targetScale,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioLowBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    }
                                    launch {
                                        offsetXAnim.animateTo(
                                            targetValue = targetOffsetX,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioLowBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    }
                                    launch {
                                        offsetYAnim.animateTo(
                                            targetValue = targetOffsetY,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioLowBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    }
                                } else {
                                    launch { scaleAnim.animateTo(targetScale) }
                                }
                            }
                        }
                    }
                )
            }
            .pointerInput(resetKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    coroutineScope.launch {
                        scaleAnim.stop()
                        offsetXAnim.stop()
                        offsetYAnim.stop()
                    }

                    var overscrollX = 0f

                    do {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.filter { it.pressed }
                        val pressedCount = pressedPointers.size

                        if (pressedCount >= 2) {
                            // Two-finger pinch with centroid focal-point calculation
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)

                            if (containerSize.width > 0 && containerSize.height > 0) {
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                val focus = centroid - center

                                val currentScale = scaleAnim.value
                                val newScale = (currentScale * zoom).coerceIn(0.75f, 6.0f)

                                val currentOffsetX = offsetXAnim.value
                                val currentOffsetY = offsetYAnim.value

                                val newOffsetX = currentOffsetX + (focus.x - currentOffsetX) * (1f - zoom) + pan.x
                                val newOffsetY = currentOffsetY + (focus.y - currentOffsetY) * (1f - zoom) + pan.y

                                coroutineScope.launch {
                                    scaleAnim.snapTo(newScale)
                                    offsetXAnim.snapTo(newOffsetX)
                                    offsetYAnim.snapTo(newOffsetY)
                                }
                            }

                            event.changes.forEach {
                                if (it.positionChanged()) it.consume()
                            }
                        } else if (pressedCount == 1 && scaleAnim.value > 1.05f) {
                            // Single-finger pan when zoomed in
                            val pan = event.calculatePan()
                            val currentScale = scaleAnim.value
                            val maxOffsetX = (containerSize.width * (currentScale - 1f) / 2f).coerceAtLeast(0f)
                            val maxOffsetY = (containerSize.height * (currentScale - 1f) / 2f).coerceAtLeast(0f)

                            val currentOffsetX = offsetXAnim.value
                            val currentOffsetY = offsetYAnim.value

                            val targetX = currentOffsetX + pan.x
                            val targetY = (currentOffsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)

                            val finalX = when {
                                targetX > maxOffsetX -> {
                                    val over = targetX - maxOffsetX
                                    overscrollX = over
                                    maxOffsetX + over * 0.35f
                                }
                                targetX < -maxOffsetX -> {
                                    val over = targetX - (-maxOffsetX)
                                    overscrollX = over
                                    -maxOffsetX + over * 0.35f
                                }
                                else -> {
                                    overscrollX = 0f
                                    targetX
                                }
                            }

                            coroutineScope.launch {
                                offsetXAnim.snapTo(finalX)
                                offsetYAnim.snapTo(targetY)
                            }

                            event.changes.forEach {
                                if (it.positionChanged()) it.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // Gesture released (fingers lifted)
                    val currentScale = scaleAnim.value
                    if (currentScale < 1.05f) {
                        // Spring back to 1x and center
                        coroutineScope.launch {
                            scaleAnim.animateTo(
                                targetValue = 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                        coroutineScope.launch {
                            offsetXAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                        coroutineScope.launch {
                            offsetYAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    } else {
                        val finalScale = currentScale.coerceIn(1f, 5f)
                        val maxOffsetX = (containerSize.width * (finalScale - 1f) / 2f).coerceAtLeast(0f)
                        val maxOffsetY = (containerSize.height * (finalScale - 1f) / 2f).coerceAtLeast(0f)

                        val overscrollThreshold = (containerSize.width * 0.16f).coerceAtLeast(70f)
                        if (overscrollX > overscrollThreshold) {
                            // Swiped right past leftmost boundary -> navigate to previous photo
                            coroutineScope.launch {
                                scaleAnim.animateTo(1f)
                                offsetXAnim.animateTo(0f)
                                offsetYAnim.animateTo(0f)
                                onNavigatePrevious?.invoke()
                            }
                        } else if (overscrollX < -overscrollThreshold) {
                            // Swiped left past rightmost boundary -> navigate to next photo
                            coroutineScope.launch {
                                scaleAnim.animateTo(1f)
                                offsetXAnim.animateTo(0f)
                                offsetYAnim.animateTo(0f)
                                onNavigateNext?.invoke()
                            }
                        } else {
                            val clampedX = offsetXAnim.value.coerceIn(-maxOffsetX, maxOffsetX)
                            val clampedY = offsetYAnim.value.coerceIn(-maxOffsetY, maxOffsetY)

                            coroutineScope.launch {
                                if (finalScale != currentScale) {
                                    scaleAnim.animateTo(
                                        targetValue = finalScale,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                            coroutineScope.launch {
                                offsetXAnim.animateTo(
                                    targetValue = clampedX,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                            coroutineScope.launch {
                                offsetYAnim.animateTo(
                                    targetValue = clampedY,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (!isImageLoaded && !isImageError) {
            AppLottieLoadingAnimation(
                modifier = Modifier
                    .size(64.dp)
                    .align(Alignment.Center)
            )
        }

        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            onSuccess = {
                isImageLoaded = true
                isImageError = false
            },
            onError = {
                isImageLoaded = true
                isImageError = true
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    translationX = offsetXAnim.value
                    translationY = offsetYAnim.value
                }
        )
    }
}
