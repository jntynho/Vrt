package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.AppLottieLoadingAnimation
import com.example.ui.components.UiEngineWarmupSandbox
import com.example.ui.theme.LocalVaultPalette

/**
 * Minimalist Loading Screen (Resource loading & UI Engine Pre-Warmup interface).
 * 1. Runs the active offscreen UiEngineWarmupSandbox behind the opaque backdrop to pre-compile
 *    Compose JIT functions, GPU Shaders, LiquidGlassNavigationBar, Ripples, LazyGrids, and Transitions.
 * 2. Visually displays ONLY:
 *    - Centered app icon.
 *    - Lottie loading animation directly beneath it.
 */
@Composable
fun LoadingScreen(
    statusText: String = "",
    modifier: Modifier = Modifier,
    onProgressUpdate: (String) -> Unit = {},
    onWarmupComplete: () -> Unit = {},
    onDismiss: (() -> Unit)? = null
) {
    val palette = LocalVaultPalette.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.bg),
        contentAlignment = Alignment.Center
    ) {
        // Layer 0 (Behind opaque foreground): Active UI & Shader Warm-up Sandbox
        UiEngineWarmupSandbox(
            onProgressUpdate = onProgressUpdate,
            onWarmupComplete = onWarmupComplete
        )

        // Layer 1 (Opaque visual foreground): Covers the sandbox completely while keeping the exact clean UI
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.bg),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Prominent circular app icon with enlarged center emblem
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .clip(CircleShape)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_background),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                    Image(
                        painter = painterResource(id = R.drawable.ic_loading_foreground),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Smooth Lottie Animation directly underneath
                Box(
                    modifier = Modifier
                        .size(width = 180.dp, height = 72.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AppLottieLoadingAnimation(
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
