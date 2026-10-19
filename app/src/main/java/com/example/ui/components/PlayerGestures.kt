package com.example.ui.components

import android.app.Activity
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import com.example.ui.theme.LocalAccentColor
import kotlin.math.abs

@Composable
fun VerticalSideBarIndicator(
    visible: Boolean,
    percent: Int,
    painter: Painter? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    barHeight: Dp = 120.dp,
    barWidth: Dp = 7.dp,
    fontSize: TextUnit = 12.sp,
    iconSize: Dp = 20.dp,
    sidePadding: Dp = 24.dp
) {
    val displayPercent = percent.coerceIn(0, 100)
    val animatedProgress by animateFloatAsState(
        targetValue = displayPercent / 100f,
        animationSpec = tween(durationMillis = 80, easing = LinearOutSlowInEasing),
        label = "indicator_fill"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.92f, animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(220)) + scaleOut(targetScale = 0.95f, animationSpec = tween(220)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = sidePadding, vertical = 16.dp)
                .widthIn(min = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$displayPercent%",
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.85f),
                        blurRadius = 8f
                    )
                )
            )

            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(barHeight)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(animatedProgress.coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .background(LocalAccentColor.current)
                )
            }

            if (painter != null) {
                Icon(
                    painter = painter,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(iconSize)
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

fun Modifier.playerTouchGestures(
    duration: Long,
    enableGestures: Boolean,
    currentPositionProvider: () -> Long,
    audioManager: AudioManager?,
    maxAudioVolume: Int,
    activity: Activity?,
    isScrubbing: Boolean,
    onGestureSeekingChanged: ((Boolean) -> Unit)? = null,
    onSeekLive: (targetMs: Long) -> Unit,
    onSeekFinal: (targetMs: Long) -> Unit,
    onVolumeChanged: (percent: Int?) -> Unit,
    onBrightnessChanged: (percent: Int?) -> Unit,
    onDoubleTap: (isForward: Boolean) -> Unit,
    onSingleTap: () -> Unit
): Modifier = this.pointerInput(duration, enableGestures) {
    var lastTapTime = 0L
    var lastTapPos = androidx.compose.ui.geometry.Offset.Zero

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed || isScrubbing) return@awaitEachGesture

        val startPos = down.position
        val startX = startPos.x
        val startY = startPos.y
        val screenWidth = size.width.toFloat().coerceAtLeast(1f)
        val screenHeight = size.height.toFloat().coerceAtLeast(1f)
        val isLeftSide = startX < screenWidth * 0.5f

        val initialVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val winAttributes = activity?.window?.attributes
        val initialBrightness = if ((winAttributes?.screenBrightness ?: -1f) < 0f) 0.5f else winAttributes!!.screenBrightness
        val initialPosition = currentPositionProvider().coerceAtLeast(0L)

        var gestureType = 0 // 0: None, 1: Seek, 2: Volume, 3: Brightness
        var hasMoved = false
        var currentSeekTargetMs = initialPosition
        val pointerId = down.id

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break

            if (change.isConsumed && gestureType == 0) break

            if (change.pressed) {
                val totalDx = change.position.x - startX
                val totalDy = change.position.y - startY
                val absDx = abs(totalDx)
                val absDy = abs(totalDy)

                if (gestureType == 0) {
                    val touchSlop = 12f
                    if (absDx > touchSlop || (enableGestures && absDy > 32f)) {
                        hasMoved = true
                        gestureType = if (absDx > absDy) {
                            onGestureSeekingChanged?.invoke(true)
                            1 // Horizontal Seek
                        } else if (enableGestures && absDy > absDx * 1.3f) {
                            if (isLeftSide) 2 else 3 // 2: Volume, 3: Brightness
                        } else {
                            0
                        }
                    } else if (!enableGestures && absDy > touchSlop && absDy > absDx * 1.2f) {
                        break
                    }
                }

                if (gestureType == 1) {
                    change.consume()
                    if (duration > 0 && duration != C.TIME_UNSET) {
                        val seekRangeMs = maxOf(duration * 0.45f, 240_000f).toLong().coerceAtLeast(60_000L)
                        val deltaMs = ((totalDx / (screenWidth * 0.45f)) * seekRangeMs).toLong()
                        val targetMs = (initialPosition + deltaMs).coerceIn(0L, duration)
                        currentSeekTargetMs = targetMs
                        onSeekLive(targetMs)
                    }
                } else if (gestureType == 2 && enableGestures) {
                    change.consume()
                    val fractionChange = -totalDy / (screenHeight * 0.45f)
                    val newVolume = (initialVolume + (fractionChange * maxAudioVolume)).toInt().coerceIn(0, maxAudioVolume)
                    try {
                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                    } catch (_: Exception) {}
                    val percent = if (maxAudioVolume > 0) ((newVolume.toFloat() / maxAudioVolume) * 100).toInt() else 0
                    onVolumeChanged(percent)
                } else if (gestureType == 3 && enableGestures) {
                    change.consume()
                    val fractionChange = -totalDy / (screenHeight * 0.45f)
                    val newBrightness = (initialBrightness + fractionChange).coerceIn(0.01f, 1.0f)
                    try {
                        activity?.window?.let { win ->
                            val lp = win.attributes
                            lp.screenBrightness = newBrightness
                            win.attributes = lp
                        }
                    } catch (_: Exception) {}
                    val percent = (newBrightness * 100).toInt()
                    onBrightnessChanged(percent)
                }
            } else {
                // Touch Released
                if (gestureType == 1) {
                    onGestureSeekingChanged?.invoke(false)
                    if (duration > 0 && duration != C.TIME_UNSET) {
                        val safeTarget = currentSeekTargetMs.coerceIn(0L, duration)
                        onSeekFinal(safeTarget)
                    }
                } else if (gestureType == 2) {
                    onVolumeChanged(null)
                } else if (gestureType == 3) {
                    onBrightnessChanged(null)
                } else if (gestureType == 0 && !hasMoved) {
                    val now = System.currentTimeMillis()
                    val distFromLastTap = (change.position - lastTapPos).getDistance()
                    if (now - lastTapTime < 300L && distFromLastTap < 100f) {
                        lastTapTime = 0L
                        val isForward = startX >= screenWidth * 0.5f
                        onDoubleTap(isForward)
                    } else {
                        lastTapTime = now
                        lastTapPos = change.position
                        onSingleTap()
                    }
                }
                break
            }
        }
    }
}
