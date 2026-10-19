package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.LocalVaultPalette

@Composable
fun ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    bottomPadding: Dp = 20.dp,
    endPadding: Dp = 18.dp
) {
    val haptic = LocalHapticFeedback.current
    val palette = LocalVaultPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scroll_top_press_scale"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.82f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) +
                slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = tween(220, easing = FastOutSlowInEasing)
                ),
        exit = fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                scaleOut(
                    targetScale = 0.82f,
                    animationSpec = tween(180, easing = FastOutSlowInEasing)
                ) +
                slideOutVertically(
                    targetOffsetY = { it / 3 },
                    animationSpec = tween(180, easing = FastOutSlowInEasing)
                ),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .padding(bottom = bottomPadding, end = endPadding)
                .size(size)
                .scale(scale)
                .vaultTopGlow(cornerRadius = size / 2)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = palette.textSecondary.copy(alpha = 0.18f),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                ),
            shape = CircleShape,
            color = palette.cardBg,
            tonalElevation = 1.dp,
            shadowElevation = 4.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_scroll_to_top),
                    contentDescription = "Scroll to top",
                    tint = palette.textPrimary,
                    modifier = Modifier.size(size * 0.45f)
                )
            }
        }
    }
}
