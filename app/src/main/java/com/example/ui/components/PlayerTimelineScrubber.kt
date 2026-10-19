package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import com.example.ui.theme.LocalAccentColor

fun formatPlayerDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
fun PlayerTimelineScrubber(
    currentPos: Long,
    bufferedPos: Long,
    duration: Long,
    isFullscreen: Boolean,
    onScrubbingChanged: (Boolean) -> Unit,
    onSeekLive: (targetMs: Long) -> Unit,
    onSeekFinal: (targetMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubProgress by remember { mutableFloatStateOf(0f) }
    var scrubberWidthPx by remember { mutableFloatStateOf(1f) }
    var lastSeekReleaseTime by remember { mutableLongStateOf(0L) }
    var lastSeekReleaseFraction by remember { mutableFloatStateOf(0f) }

    val isSettling = !isScrubbing && (System.currentTimeMillis() - lastSeekReleaseTime < 450L)
    val effectiveFraction = if (isScrubbing) {
        scrubProgress
    } else if (isSettling) {
        lastSeekReleaseFraction
    } else {
        if (duration > 0) (currentPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    }
    val bufferedFraction = if (duration > 0) (bufferedPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

    // Custom Interactive Track Box
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (isFullscreen) 24.dp else 20.dp)
            .onSizeChanged { scrubberWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isScrubbing = true
                        onScrubbingChanged(true)
                        scrubProgress = (down.position.x / scrubberWidthPx).coerceIn(0f, 1f)
                        if (duration > 0 && duration != C.TIME_UNSET) {
                            val initialTarget = (scrubProgress * duration).toLong().coerceIn(0L, duration)
                            onSeekLive(initialTarget)
                        }

                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            change.consume()
                            if (change.pressed) {
                                scrubProgress = (change.position.x / scrubberWidthPx).coerceIn(0f, 1f)
                                if (duration > 0 && duration != C.TIME_UNSET) {
                                    val liveTarget = (scrubProgress * duration).toLong().coerceIn(0L, duration)
                                    onSeekLive(liveTarget)
                                }
                            } else {
                                lastSeekReleaseTime = System.currentTimeMillis()
                                lastSeekReleaseFraction = scrubProgress
                                if (duration > 0 && duration != C.TIME_UNSET) {
                                    val finalTarget = (scrubProgress * duration).toLong().coerceIn(0L, duration)
                                    onSeekFinal(finalTarget)
                                }
                                isScrubbing = false
                                onScrubbingChanged(false)
                                break
                            }
                        }
                    }
                }
                .testTag(if (isFullscreen) "video_seekbar" else "video_seekbar_inline"),
            contentAlignment = Alignment.CenterStart
        ) {
            val trackHeight = if (isScrubbing) 8.dp else 6.dp

            // Background Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.22f))
            )

            // Buffered Progress Track
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = bufferedFraction)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.40f))
            )

            // Active Progress Track
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = effectiveFraction)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(LocalAccentColor.current)
            )

            // Scrubber Thumb
            val thumbDiameter = if (isScrubbing) 18.dp else 12.dp
            val thumbRadiusPx = with(LocalDensity.current) { (thumbDiameter / 2).toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = ((scrubberWidthPx * effectiveFraction) - thumbRadiusPx).toInt().coerceIn(
                                0,
                                (scrubberWidthPx - thumbRadiusPx * 2).toInt().coerceAtLeast(0)
                            ),
                            y = 0
                        )
                    }
                    .size(thumbDiameter)
                    .shadow(4.dp, CircleShape)
                    .background(Color.White, CircleShape)
                    .clip(CircleShape)
            )
        }
}
