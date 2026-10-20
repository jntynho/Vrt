package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object BtnColors {
    val Magnet = Color(0xFF8B5CF6)
    val Url = Color(0xFF2F80ED)
    val Hd = Color(0xFF06B6D4)
    val K4 = Color(0xFFEC4899)
    val Save = Color(0xFFF59E0B)
    val Edit = Color(0xFF22A877)
    val Delete = Color(0xFFE84C4C)
    val Cancel = Color(0xFF64748B)
    val Vr = Color(0xFF38BDF8)
}

val LocalActionsInteractive = compositionLocalOf { true }
val LocalActionMenuProgress = compositionLocalOf { 1f }
val LocalActionButtonPressed = compositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * Hardware-accelerated sequential Center Zoom-In with subtle, refined Spring Bounce physics.
 *
 * Entrance:
 * - Sequentially cascades from Left to Right (Index 0 = Leftmost zooms in first).
 * - Each button independently zooms in from 0.0f -> subtle micro-bounce (~1.06f) -> settles smoothly at 1.0f.
 * - Alpha transitions cleanly from 0f -> 1f.
 *
 * Exit:
 * - Smoothly shrinks back to center (1.0f -> 0.0f) with reverse cascade and fade out.
 */
@Composable
fun Modifier.staggeredActionEntrance(
    @Suppress("UNUSED_PARAMETER") progress: Float,
    indexFromLeft: Int,
    totalItems: Int
): Modifier {
    val isMenuOpen = LocalActionsInteractive.current
    val scaleAnim = remember { Animatable(0f) }
    val alphaAnim = remember { Animatable(0f) }

    LaunchedEffect(isMenuOpen) {
        if (isMenuOpen) {
            val staggerDelayMs = (indexFromLeft.coerceAtLeast(0) * 45L).coerceAtMost(240L)
            if (staggerDelayMs > 0L) {
                delay(staggerDelayMs)
            }
            launch {
                alphaAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing)
                )
            }
            scaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.67f,
                    stiffness = 480f
                )
            )
        } else {
            val reverseIndex = (totalItems - 1 - indexFromLeft).coerceAtLeast(0)
            val exitDelayMs = (reverseIndex * 20L).coerceAtMost(100L)
            if (exitDelayMs > 0L) {
                delay(exitDelayMs)
            }
            launch {
                alphaAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 130, easing = FastOutLinearInEasing)
                )
            }
            scaleAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 150, easing = FastOutLinearInEasing)
            )
        }
    }

    return this.graphicsLayer {
        transformOrigin = TransformOrigin.Center
        val currentScale = scaleAnim.value.coerceAtLeast(0f)
        scaleX = currentScale
        scaleY = currentScale
        alpha = alphaAnim.value.coerceIn(0f, 1f)
    }
}

private val ActionTextShadowSingle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    shadow = androidx.compose.ui.graphics.Shadow(
        color = Color.Black.copy(alpha = 0.35f),
        offset = androidx.compose.ui.geometry.Offset(0f, 1f),
        blurRadius = 4f
    )
)

@Composable
fun ActionCircleButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    iconRotation: Float = 0f,
    text: String? = null,
    strongHaptic: Boolean = false,
    enabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    val interactive = LocalActionsInteractive.current
    val onButtonPressedChange = LocalActionButtonPressed.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    DisposableEffect(isPressed, enabled, interactive) {
        val activePress = isPressed && enabled && interactive
        if (activePress) {
            onButtonPressedChange(true)
        }
        onDispose {
            if (activePress) {
                onButtonPressedChange(false)
            }
        }
    }

    var lastClickTime by remember { mutableLongStateOf(0L) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled && interactive) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "press_spring"
    )

    Column(
        modifier = modifier.graphicsLayer {
            alpha = if (enabled) 1f else 0.42f
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(if (enabled) color else color.copy(alpha = 0.5f))
                .border(BorderStroke(2.dp, Color.White.copy(alpha = if (enabled) 0.35f else 0.15f)), CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, color = Color.White),
                    enabled = enabled && interactive,
                    role = Role.Button
                ) {
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime >= 150L) {
                        lastClickTime = now
                        haptic.performHapticFeedback(
                            if (strongHaptic) HapticFeedbackType.LongPress
                            else HapticFeedbackType.TextHandleMove
                        )
                        onClick()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(
                    painter = icon,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier
                        .size(31.dp)
                        .rotate(iconRotation)
                )
            } else if (text != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center,
                        style = ActionTextShadowSingle
                    )
                }
            }
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

enum class Source { MAGNET, URL }

@Composable
fun MainActionMenu(
    onMagnetClick: () -> Unit,
    onUrlClick: () -> Unit,
    onSave: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isSaved: Boolean = false,
    showMagnet: Boolean = true,
    showUrl: Boolean = true
) {
    val progress = LocalActionMenuProgress.current
    val totalButtons = (if (showMagnet) 1 else 0) + (if (showUrl) 1 else 0) + 3
    var btnIndex = 0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showMagnet) {
            val idx = btnIndex++
            ActionCircleButton(
                label = "Magnet",
                color = BtnColors.Magnet,
                onClick = onMagnetClick,
                icon = painterResource(R.drawable.ic_magnet),
                iconRotation = 0f,
                modifier = Modifier.staggeredActionEntrance(progress, indexFromLeft = idx, totalItems = totalButtons)
            )
        }
        if (showUrl) {
            val idx = btnIndex++
            ActionCircleButton(
                label = "URL",
                color = BtnColors.Url,
                onClick = onUrlClick,
                icon = painterResource(R.drawable.ic_url_link),
                modifier = Modifier.staggeredActionEntrance(progress, indexFromLeft = idx, totalItems = totalButtons)
            )
        }

        val saveIdx = btnIndex++
        val bookmarkScale by animateFloatAsState(
            targetValue = if (isSaved) 1.05f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "bookmark_pop"
        )
        val bookmarkRotation by animateFloatAsState(
            targetValue = if (isSaved) 12f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "bookmark_rot"
        )

        ActionCircleButton(
            label = if (isSaved) "Saved" else "Save",
            color = if (isSaved) BtnColors.Save else BtnColors.Save.copy(alpha = 0.9f),
            onClick = onSave,
            icon = painterResource(if (isSaved) R.drawable.ic_bookmark_saved else R.drawable.ic_bookmark_save),
            iconRotation = bookmarkRotation,
            modifier = Modifier
                .staggeredActionEntrance(progress, indexFromLeft = saveIdx, totalItems = totalButtons)
                .scale(bookmarkScale)
        )

        val editIdx = btnIndex++
        ActionCircleButton(
            label = "Edit",
            color = BtnColors.Edit,
            onClick = onEdit,
            icon = painterResource(R.drawable.ic_edit_pencil),
            modifier = Modifier.staggeredActionEntrance(progress, indexFromLeft = editIdx, totalItems = totalButtons)
        )

        val delIdx = btnIndex++
        ActionCircleButton(
            label = "Delete",
            color = BtnColors.Delete,
            onClick = onDelete,
            icon = painterResource(R.drawable.ic_delete_trash),
            modifier = Modifier.staggeredActionEntrance(progress, indexFromLeft = delIdx, totalItems = totalButtons)
        )
    }
}

@Composable
fun QualitySelectMenu(
    onSelectHD: () -> Unit,
    onSelect4K: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectVR: () -> Unit = {},
    hasHD: Boolean = true,
    has4K: Boolean = true,
    hasVR: Boolean = false
) {
    val progress = LocalActionMenuProgress.current
    val totalButtons = (if (hasHD) 1 else 0) + (if (has4K) 1 else 0) + (if (hasVR) 1 else 0)
    var btnIndex = 0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasHD) {
            val idx = btnIndex++
            ActionCircleButton(
                label = "HD",
                color = BtnColors.Hd,
                onClick = onSelectHD,
                icon = painterResource(R.drawable.ic_quality_hd),
                modifier = Modifier.staggeredActionEntrance(progress, indexFromLeft = idx, totalItems = totalButtons)
            )
        }
        if (has4K) {
            val idx = btnIndex++
            ActionCircleButton(
                label = "4K",
                color = BtnColors.K4,
                onClick = onSelect4K,
                icon = painterResource(R.drawable.ic_quality_4k),
                modifier = Modifier.staggeredActionEntrance(progress, indexFromLeft = idx, totalItems = totalButtons)
            )
        }
        if (hasVR) {
            val idx = btnIndex++
            ActionCircleButton(
                label = "VR",
                color = BtnColors.Vr,
                onClick = onSelectVR,
                icon = painterResource(R.drawable.ic_player_vr),
                modifier = Modifier.staggeredActionEntrance(progress, indexFromLeft = idx, totalItems = totalButtons)
            )
        }
    }
}

@Composable
fun DeleteConfirmMenu(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = LocalActionMenuProgress.current
    val totalButtons = 2

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Delete this item?",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionCircleButton(
                label = "Cancel",
                color = BtnColors.Cancel,
                onClick = onCancel,
                icon = painterResource(R.drawable.ic_action_cancel),
                modifier = Modifier.staggeredActionEntrance(progress, indexFromLeft = 0, totalItems = totalButtons)
            )
            ActionCircleButton(
                label = "Delete",
                color = BtnColors.Delete,
                onClick = onConfirm,
                icon = painterResource(R.drawable.ic_delete_trash),
                strongHaptic = true,
                modifier = Modifier.staggeredActionEntrance(progress, indexFromLeft = 1, totalItems = totalButtons)
            )
        }
    }
}
