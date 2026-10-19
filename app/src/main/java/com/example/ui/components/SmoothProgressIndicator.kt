package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.example.R

/**
 * Modern Lottie Loading Animation (Default loading animation in the app).
 * Plays the smooth 3-dot bouncing wave animation from raw/loading.json.
 */
@Composable
fun AppLottieLoadingAnimation(
    modifier: Modifier = Modifier.size(60.dp),
    iterations: Int = LottieConstants.IterateForever,
    speed: Float = 1.0f
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.loading))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = iterations,
        speed = speed
    )

    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = modifier
    )
}

/**
 * Default App Loading Indicator: uses AppLottieLoadingAnimation
 */
@Composable
fun SmoothProgressIndicator(
    modifier: Modifier = Modifier.size(48.dp),
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = color.copy(alpha = 0.16f),
    strokeWidth: Dp = 3.dp,
    durationMillis: Int = 1200,
    dotCount: Int = 12
) {
    AppLottieLoadingAnimation(
        modifier = modifier
    )
}

/**
 * Horizontal Loading Animation: uses AppLottieLoadingAnimation
 */
@Composable
fun HorizontalBubbleLoadingAnimation(
    modifier: Modifier = Modifier,
    bubbleColor: Color = MaterialTheme.colorScheme.primary,
    bubbleCount: Int = 4,
    bubbleSize: Dp = 13.dp,
    durationMillis: Int = 1100
) {
    AppLottieLoadingAnimation(
        modifier = modifier.size(64.dp)
    )
}

