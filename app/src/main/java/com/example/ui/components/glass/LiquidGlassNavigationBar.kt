package com.example.ui.components.glass

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.example.ui.components.glass.backdrop.GlassSurface
import com.example.ui.screens.LocalHazeState
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Floating Liquid Glass Navigation Bar with Advanced Fluid Physics & Motion Gestures.
 *
 * Implements:
 * 1. Deep translucent floating glass capsule with bidirectional tapering edge light.
 * 2. Diffused ambient floating shadow.
 * 3. Liquid Glass Selection Indicator with velocity-coupled fluid stretch & squash.
 * 4. Micro-haptic tactile feedback on active tab gesture.
 * 5. Proximity-synchronized icon and label transitions without color jumping.
 * 6. Flawless initial launch placement without teleportation glitches.
 */
@Composable
fun LiquidGlassNavigationBar(
    items: List<LiquidGlassNavItem> = LiquidGlassNavItem.defaultNavItems,
    selectedId: String,
    onItemSelected: (LiquidGlassNavItem) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: dev.chrisbanes.haze.HazeState? = LocalHazeState.current,
    onItemLongPressed: ((LiquidGlassNavItem) -> Unit)? = null,
    onItemReselected: ((LiquidGlassNavItem) -> Unit)? = null,
    barHeightDp: Int = 70,
    transparency: Float = 0.65f,
    blurRadiusDp: Int = 24,
    activeTabIntensity: Float = 0.6f
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var pulseTrigger by remember { mutableIntStateOf(0) }

    // Precise luminance calculation using Compose's native luminance()
    val isLight = remember(palette.bg) {
        palette.bg.luminance() > 0.45f
    }
    val isDark = !isLight

    val selectedIndex by remember(selectedId, items) {
        derivedStateOf {
            items.indexOfFirst { it.id.equals(selectedId, ignoreCase = true) }
        }
    }
    val hasValidSelection = selectedIndex >= 0

    val safeHeight = barHeightDp.coerceIn(48, 80)
    val barHeight = if (isLandscape) (safeHeight * 0.82f).dp else safeHeight.dp
    val barCornerRadius = barHeight / 2
    val barShape = RoundedCornerShape(barCornerRadius)

    val unselectedItemColor = if (isDark) {
        Color.White.copy(alpha = 0.70f)
    } else {
        Color(0xFF3C404E).copy(alpha = 0.80f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                horizontal = if (isLandscape) 32.dp else 16.dp,
                vertical = if (isLandscape) 6.dp else 10.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .height(barHeight)
                .shadow(
                    elevation = if (isDark) 14.dp else 18.dp,
                    shape = barShape,
                    ambientColor = if (isDark) Color.Black.copy(alpha = 0.40f) else Color(0x241E2235),
                    spotColor = if (isDark) accent.copy(alpha = 0.22f) else Color(0x1E000000)
                )
        ) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            val itemCount = items.size.coerceAtLeast(1)
            val itemWidthPx = totalWidthPx / itemCount
            val itemWidthDp = with(density) { itemWidthPx.toDp() }

            val effectiveIndex = if (hasValidSelection) selectedIndex.coerceIn(0, items.lastIndex) else 0
            val targetCenterX = (effectiveIndex + 0.5f) * itemWidthPx
            val indicatorHeight = barHeight - if (isLandscape) 8.dp else 12.dp

            val positionAnim = remember { Animatable(targetCenterX) }
            var isInitialized by remember { mutableStateOf(false) }
            var lastWidthPx by remember { mutableFloatStateOf(totalWidthPx) }

            var isDragging by remember { mutableStateOf(false) }
            var dragOffsetValue by remember { mutableFloatStateOf(0f) }
            var pressedIndex by remember { mutableStateOf<Int?>(null) }
            var previewIndex by remember { mutableStateOf<Int?>(null) }

            // 1. Initial Launch Snap & Orientation Change Instant Snap (Prevents unwanted fly-in animation)
            LaunchedEffect(totalWidthPx, effectiveIndex) {
                if (totalWidthPx > 0f) {
                    if (!isInitialized || abs(totalWidthPx - lastWidthPx) > 10f) {
                        positionAnim.snapTo(targetCenterX)
                        isInitialized = true
                        lastWidthPx = totalWidthPx
                    }
                }
            }

            // 2. Smooth Spring Motion on Tab Selection
            LaunchedEffect(targetCenterX, isDragging) {
                if (!isDragging && isInitialized && hasValidSelection) {
                    val distance = abs(targetCenterX - positionAnim.value)
                    if (distance > 1.5f) {
                        positionAnim.animateTo(
                            targetValue = targetCenterX,
                            animationSpec = spring(
                                dampingRatio = 0.82f,
                                stiffness = 380f
                            )
                        )
                    } else {
                        positionAnim.snapTo(targetCenterX)
                    }
                }
            }

            // 3. Velocity-Coupled Directional Fluid Physics (Squash & Stretch)
            val currentVelocity = positionAnim.velocity
            val velocityStretch = (1.0f + (abs(currentVelocity) / 3000f).coerceIn(0f, 0.20f))
            val interactiveDragStretch = if (isDragging && dragOffsetValue != 0f) {
                (1.0f + (abs(dragOffsetValue) / 320f).coerceIn(0f, 0.16f))
            } else {
                1.0f
            }
            val stretchX = maxOf(velocityStretch, interactiveDragStretch)
            val squashY = 1.0f / sqrt(stretchX)

            // LAYER 1: Deep Translucent Glass Surface with Tapering Edge Light
            GlassSurface(
                shape = barShape,
                isDark = isDark,
                accentColor = accent,
                hazeState = hazeState,
                blurRadius = blurRadiusDp.coerceIn(0, 40).dp,
                transparency = transparency.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxSize()
            )

            // LAYER 2: Moving Specular Liquid Glass Selection Indicator
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(barShape)
                    .padding(vertical = if (isLandscape) 4.dp else 6.dp)
            ) {
                LiquidGlassSelectionIndicator(
                    currentCenterX = positionAnim.value,
                    stretchX = stretchX,
                    squashY = squashY,
                    itemWidth = itemWidthDp,
                    height = indicatorHeight,
                    accentColor = accent,
                    isDarkTheme = isDark,
                    isVisible = hasValidSelection,
                    pulseTrigger = pulseTrigger,
                    intensity = activeTabIntensity,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }

            val currentSelectedIndex by rememberUpdatedState(selectedIndex)
            val currentEffectiveIndex by rememberUpdatedState(effectiveIndex)
            val currentOnItemSelected by rememberUpdatedState(onItemSelected)
            val currentOnItemReselected by rememberUpdatedState(onItemReselected)
            val currentOnItemLongPressed by rememberUpdatedState(onItemLongPressed)

            // LAYER 3: 100% Crisp Interactive Navigation Items with Proximity-Based Synchronization
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(items.size, totalWidthPx, selectedIndex) {
                        val touchSlop = viewConfiguration.touchSlop
                        val minCenterX = 0.5f * itemWidthPx
                        val maxCenterX = (itemCount - 0.5f) * itemWidthPx

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downX = down.position.x
                            val downTime = System.currentTimeMillis()
                            val startIndex = (downX / itemWidthPx).toInt().coerceIn(0, items.lastIndex)

                            pressedIndex = startIndex
                            var hasDragStarted = false
                            var isLongPressTriggered = false
                            var lastHapticIndex = startIndex
                            val gestureStartIndicatorX = positionAnim.value
                            val isTouchingIndicator = abs(downX - gestureStartIndicatorX) <= (itemWidthPx * 0.75f)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    break
                                }

                                val dx = change.position.x - downX

                                if (!hasDragStarted) {
                                    if (abs(dx) > touchSlop) {
                                        hasDragStarted = true
                                        isDragging = true
                                        pressedIndex = null
                                        change.consume()
                                    } else if (!isLongPressTriggered && startIndex == currentEffectiveIndex && (System.currentTimeMillis() - downTime) > 400L) {
                                        isLongPressTriggered = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        currentOnItemLongPressed?.invoke(items[currentEffectiveIndex])
                                    }
                                } else {
                                    change.consume()
                                    val currentTouchX = change.position.x
                                    val targetX = if (isTouchingIndicator) {
                                        gestureStartIndicatorX + (currentTouchX - downX)
                                    } else {
                                        currentTouchX
                                    }
                                    val clampedCenterX = targetX.coerceIn(minCenterX, maxCenterX)

                                    dragOffsetValue = clampedCenterX - gestureStartIndicatorX
                                    coroutineScope.launch {
                                        positionAnim.snapTo(clampedCenterX)
                                    }

                                    val currentHoverIndex = ((clampedCenterX - minCenterX) / itemWidthPx).roundToInt().coerceIn(0, items.lastIndex)
                                    if (currentHoverIndex != lastHapticIndex) {
                                        lastHapticIndex = currentHoverIndex
                                        previewIndex = currentHoverIndex
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            }

                            // Pointer lifted (Up) or cancelled
                            pressedIndex = null
                            if (hasDragStarted) {
                                val finalIndex = ((positionAnim.value - minCenterX) / itemWidthPx).roundToInt().coerceIn(0, items.lastIndex)
                                previewIndex = null
                                isDragging = false
                                dragOffsetValue = 0f

                                if (finalIndex != currentSelectedIndex) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnItemSelected(items[finalIndex])
                                } else {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    pulseTrigger++
                                    currentOnItemReselected?.invoke(items[finalIndex])
                                }
                            } else if (!isLongPressTriggered) {
                                val elapsed = System.currentTimeMillis() - downTime
                                if (elapsed < 600L) {
                                    val tappedIndex = (downX / itemWidthPx).toInt().coerceIn(0, items.lastIndex)
                                    if (tappedIndex != currentSelectedIndex) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        currentOnItemSelected(items[tappedIndex])
                                    } else {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        pulseTrigger++
                                        currentOnItemReselected?.invoke(items[tappedIndex])
                                    }
                                }
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val currentIndicatorX = positionAnim.value
                items.forEachIndexed { index, item ->
                    val itemCenterXPx = (index + 0.5f) * itemWidthPx
                    val distanceToIndicator = abs(currentIndicatorX - itemCenterXPx)
                    val proximity = (1.0f - (distanceToIndicator / itemWidthPx)).coerceIn(0f, 1f)
                    val isItemSelected = if (isDragging && previewIndex != null) index == previewIndex else index == effectiveIndex
                    val isItemPressed = pressedIndex == index

                    LiquidGlassNavigationItem(
                        item = item,
                        isSelected = isItemSelected,
                        itemWidth = itemWidthDp,
                        accentColor = accent,
                        unselectedColor = unselectedItemColor,
                        proximity = proximity,
                        pulseTrigger = pulseTrigger,
                        onClick = {
                            if (index != currentSelectedIndex) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnItemSelected(item)
                            } else {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                pulseTrigger++
                                currentOnItemReselected?.invoke(item)
                            }
                        },
                        isPressed = isItemPressed
                    )
                }
            }
        }
    }
}
