package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.glass.LiquidGlassNavigationBar
import com.example.ui.components.glass.backdrop.GlassSurface
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.delay

/**
 * Active Offscreen UI, JIT, & GPU Shader Pre-Warmup Engine.
 *
 * Runs behind the opaque LoadingScreen backdrop using a non-zero micro-alpha (0.004f)
 * offscreen hardware layer so Android's Compose Runtime (JIT), Layout Engine,
 * RenderThread, and GPU Shader Compiler (Skia/Vulkan/OpenGL) execute real composition,
 * measurement, layout, and draw passes across 5 sequential stages:
 *
 * - Stage 1: LiquidGlassNavigationBar + GlassSurface + Haze + Spring Indicator motion across all tabs.
 * - Stage 2: Material 3 Buttons, IconButtons, FilterChips, Ripples, Inputs, Drawer items & Vector Icons.
 * - Stage 3: LazyVerticalGrid & LazyRow + ShimmerBrush + VaultTopGlow + Gradients + Programmatic Scroll/Prefetch.
 * - Stage 4: Screen Transition Engine (AnimatedContent slide + fade + scale forward & backward passes).
 * - Stage 5: Final GPU pipeline flush & frame settlement before signaling completion.
 */
@Composable
fun UiEngineWarmupSandbox(
    onProgressUpdate: (String) -> Unit = {},
    onWarmupComplete: () -> Unit
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val warmupHazeState = remember { HazeState() }

    var currentStage by remember { mutableIntStateOf(1) }
    var simulatedTabId by remember { mutableStateOf("home") }
    var simulatedTransitionStep by remember { mutableIntStateOf(0) }
    var simulatedVisibility by remember { mutableStateOf(true) }
    var animatedProgressTarget by remember { mutableFloatStateOf(0.1f) }

    val gridState = rememberLazyGridState()
    val rowState = rememberLazyListState()
    val rippleInteractionSource = remember { MutableInteractionSource() }

    val animatedScale by animateFloatAsState(
        targetValue = animatedProgressTarget,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "warmup_spring_scale"
    )

    // Helper to wait for N real VSYNC frames so Compose + RenderThread actually draw every stage
    suspend fun awaitFrames(count: Int) {
        repeat(count) {
            withFrameNanos { }
        }
    }

    LaunchedEffect(Unit) {
        // Let initial composition settle
        awaitFrames(3)

        // =========================================================================
        // STAGE 1: LiquidGlassNavigationBar, GlassSurface, Haze & Spring Indicator
        // =========================================================================
        currentStage = 1
        onProgressUpdate("Warming up navigation & glass shaders...")
        val tabSequence = listOf("home", "actor", "studio", "stashdb", "settings", "home")
        for (tab in tabSequence) {
            simulatedTabId = tab
            animatedProgressTarget = if (animatedProgressTarget < 0.5f) 0.95f else 0.25f
            awaitFrames(4)
            delay(55L)
        }

        // =========================================================================
        // STAGE 2: Interactive Controls, Ripples, Vector Icons & Material 3 Surfaces
        // =========================================================================
        currentStage = 2
        onProgressUpdate("Pre-compiling interactive controls & ripples...")
        awaitFrames(3)
        // Trigger a real programmatic press & release on the ripple shader
        val press = PressInteraction.Press(Offset(24f, 24f))
        rippleInteractionSource.emit(press)
        awaitFrames(4)
        delay(80L)
        rippleInteractionSource.emit(PressInteraction.Release(press))
        awaitFrames(4)
        delay(80L)

        // =========================================================================
        // STAGE 3: LazyVerticalGrid, LazyRow, Card Shaders, Shimmer & Prefetch Pass
        // =========================================================================
        currentStage = 3
        onProgressUpdate("Preparing grid layouts & card shaders...")
        awaitFrames(5)
        try {
            gridState.scrollBy(48f)
            awaitFrames(3)
            gridState.scrollBy(-48f)
            rowState.scrollBy(40f)
            awaitFrames(3)
            rowState.scrollBy(-40f)
        } catch (_: Exception) {
        }
        delay(110L)

        // =========================================================================
        // STAGE 4: Screen Transition & Animation Engine (Forward & Back Motion)
        // =========================================================================
        currentStage = 4
        onProgressUpdate("Calibrating transition animations...")
        for (step in 1..4) {
            simulatedTransitionStep = step
            simulatedVisibility = step % 2 == 0
            awaitFrames(5)
            delay(85L)
        }
        simulatedVisibility = true
        awaitFrames(4)

        // =========================================================================
        // STAGE 5: Final GPU Pipeline Flush & Frame Settlement
        // =========================================================================
        currentStage = 5
        onProgressUpdate("Finalizing UI engine...")
        awaitFrames(6)
        delay(90L)

        onWarmupComplete()
    }

    // Active offscreen container: non-zero alpha (0.004f) + Offscreen compositing forces
    // full Compose JIT + Layout + Skia/Vulkan shader compilation while remaining invisible
    // behind the opaque LoadingScreen surface.
    Box(
        modifier = Modifier
            .size(340.dp, 460.dp)
            .clipToBounds()
            .graphicsLayer {
                alpha = 0.004f
                compositingStrategy = CompositingStrategy.Offscreen
                scaleX = 0.98f + (animatedScale * 0.02f)
                scaleY = 0.98f + (animatedScale * 0.02f)
            }
    ) {
        val shimmerBrush = ShimmerBrush(targetValue = 900f)

        // 1. Always keep Navigation Bar & Haze active across warmup stages
        Box(
            modifier = Modifier
                .fillMaxSize()
                .haze(warmupHazeState)
        ) {
            // Custom GPU Canvas & Gradient Shaders Warm-up
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.35f),
                            palette.cardBg,
                            palette.bg
                        )
                    ),
                    cornerRadius = CornerRadius(32f, 32f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.45f), Color.Transparent)
                    ),
                    radius = size.minDimension * 0.4f
                )
            }

            // Stage-driven content + AnimatedContent Transition Engine rehearsal
            AnimatedContent(
                targetState = simulatedTransitionStep + (currentStage * 10),
                transitionSpec = {
                    val isBack = targetState % 2 == 0
                    if (isBack) {
                        (slideInHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) { width -> -width / 4 } +
                                fadeIn(animationSpec = tween(200, easing = LinearOutSlowInEasing)) +
                                scaleIn(initialScale = 0.96f, animationSpec = tween(260, easing = FastOutSlowInEasing)))
                            .togetherWith(
                                slideOutHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) { width -> width } +
                                        fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing)) +
                                        scaleOut(targetScale = 1.04f, animationSpec = tween(200))
                            )
                    } else {
                        (slideInHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) { width -> width } +
                                fadeIn(animationSpec = tween(200, easing = LinearOutSlowInEasing)) +
                                scaleIn(initialScale = 0.96f, animationSpec = tween(260, easing = FastOutSlowInEasing)))
                            .togetherWith(
                                slideOutHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) { width -> -width / 4 } +
                                        fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing))
                            )
                    }
                },
                label = "warmup_transition_engine"
            ) { stateKey ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Warm up Material 3 Interactive Controls, Ripples & Vector Drawables
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .vaultTopGlow(cornerRadius = 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.cardBg)
                            .clickable(
                                interactionSource = rippleInteractionSource,
                                indication = ripple(bounded = true, color = accent)
                            ) {}
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val iconResList = listOf(
                            R.drawable.ic_nav_home,
                            R.drawable.ic_nav_actor,
                            R.drawable.ic_nav_studio,
                            R.drawable.ic_nav_bookmark,
                            R.drawable.ic_nav_stashdb,
                            R.drawable.ic_onlyhaven,
                            R.drawable.ic_kanime,
                            R.drawable.ic_nav_settings
                        )
                        iconResList.forEach { resId ->
                            Icon(
                                painter = painterResource(id = resId),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Warm up Buttons, IconButtons, FilterChips, Switch, Slider, OutlinedTextField
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {},
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Warmup $stateKey", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {},
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Action", fontSize = 10.sp)
                        }

                        FilterChip(
                            selected = stateKey % 2 == 0,
                            onClick = {},
                            label = { Text("4K", fontSize = 10.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp))
                            },
                            modifier = Modifier.height(28.dp)
                        )

                        IconButton(onClick = {}, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = palette.textPrimary)
                        }
                        IconButton(onClick = {}, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = palette.textSecondary)
                        }
                        IconButton(onClick = {}, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = null, tint = palette.textSecondary)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(checked = stateKey % 2 == 0, onCheckedChange = {})
                        Slider(
                            value = 0.5f,
                            onValueChange = {},
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                        Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(14.dp))
                        Icon(Icons.Default.Star, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                        Icon(Icons.Outlined.Star, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(14.dp))
                        Icon(Icons.Default.Menu, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(14.dp))
                        Icon(Icons.Default.Add, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(14.dp))
                        Icon(Icons.Default.Close, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(14.dp))
                        Icon(Icons.Default.Settings, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(14.dp))
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(14.dp))
                    }

                    OutlinedTextField(
                        value = "Warmup query",
                        onValueChange = {},
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text("Drawer Warmup", fontSize = 11.sp) },
                        selected = true,
                        onClick = {},
                        modifier = Modifier.height(32.dp)
                    )

                    HorizontalDivider(color = palette.border)

                    // LazyRow Warmup
                    LazyRow(
                        state = rowState,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    ) {
                        items(6, key = { it }) { idx ->
                            Surface(
                                shape = CircleShape,
                                color = if (idx == 0) accent else palette.cardBg,
                                modifier = Modifier
                                    .height(30.dp)
                                    .shadow(4.dp, CircleShape)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Tag $idx", fontSize = 10.sp, color = palette.textPrimary)
                                }
                            }
                        }
                    }

                    // LazyVerticalGrid + Card + Shimmer + AspectRatio + VaultTopGlow Warmup
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    ) {
                        items(4, key = { it }) { itemIdx ->
                            ElevatedCard(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.elevatedCardColors(containerColor = palette.cardBg),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .vaultTopGlow(cornerRadius = 16.dp)
                            ) {
                                Column {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(if (itemIdx % 2 == 0) 16f / 9f else 5f / 4f)
                                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                            .background(shimmerBrush)
                                    ) {
                                        GlassSurface(
                                            shape = RoundedCornerShape(8.dp),
                                            isDark = true,
                                            accentColor = accent,
                                            hazeState = null,
                                            blurRadius = 12.dp,
                                            transparency = 0.6f,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(4.dp)
                                                .size(28.dp, 14.dp)
                                        )
                                    }
                                    Card(
                                        shape = VaultDialogShape,
                                        colors = CardDefaults.cardColors(containerColor = palette.surface),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(4.dp)
                                            .border(1.dp, palette.border, VaultDialogShape)
                                    ) {
                                        Text(
                                            text = "Card $itemIdx",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = palette.textPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sibling 2 (Outside the .haze(warmupHazeState) container):
        // Warm up AnimatedVisibility + LiquidGlassNavigationBar (which uses .hazeChild(warmupHazeState))
        AnimatedVisibility(
            visible = simulatedVisibility,
            enter = slideInVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) { it } + fadeIn(animationSpec = tween(180)),
            exit = slideOutVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) { it } + fadeOut(animationSpec = tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LiquidGlassNavigationBar(
                selectedId = simulatedTabId,
                hazeState = warmupHazeState,
                onItemSelected = {}
            )
        }
    }
}
