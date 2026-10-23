package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

data class VaultSortOption<T>(
    val value: T,
    val label: String,
    val isSelected: Boolean,
    val testTag: String? = null
)

@Composable
fun <T> VaultSortDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    options: List<VaultSortOption<T>>,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .vaultTopGlow(cornerRadius = 16.dp)
            .widthIn(min = 210.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = palette.cardBg
    ) {
        options.forEach { option ->
            val isSelected = option.isSelected

            val animatedTextColor by animateColorAsState(
                targetValue = if (isSelected) accent else palette.textPrimary,
                animationSpec = tween(durationMillis = 220),
                label = "vault_sort_text_color"
            )
            val animatedBgColor by animateColorAsState(
                targetValue = if (isSelected) accent.copy(alpha = 0.12f) else Color.Transparent,
                animationSpec = tween(durationMillis = 220),
                label = "vault_sort_bg_color"
            )

            DropdownMenuItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(animatedBgColor)
                    .let { mod ->
                        if (option.testTag != null) mod.testTag(option.testTag) else mod
                    },
                text = {
                    Text(
                        text = option.label,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = animatedTextColor,
                        fontSize = 14.5.sp
                    )
                },
                leadingIcon = {
                    AnimatedVisibility(
                        visible = isSelected,
                        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.6f, animationSpec = tween(180)),
                        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.6f, animationSpec = tween(140))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                onClick = {
                    onDismissRequest()
                    onOptionSelected(option.value)
                }
            )
        }
    }
}

@Composable
fun ShimmerBrush(targetValue: Float = 1000f): Brush {
    val shimmerColors = listOf(
        LocalVaultPalette.current.skeletonBg.copy(alpha = 0.4f),
        LocalVaultPalette.current.skeletonBg.copy(alpha = 0.22f),
        LocalVaultPalette.current.skeletonBg.copy(alpha = 0.4f)
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnimation = transition.animateFloat(
        initialValue = 0f,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_anim"
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnimation.value, y = translateAnimation.value)
    )
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier, height: Int = 220) {
    val brush = ShimmerBrush()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = LocalVaultPalette.current.cardBg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush)
        )
    }
}


