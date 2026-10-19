package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.SettingsSection
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

@Composable
fun museSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = Color(0xFF057DF2),
    checkedThumbColor = Color.White,
    uncheckedTrackColor = Color.White.copy(alpha = 0.14f),
    uncheckedThumbColor = Color(0xFF9A9A9E),
    uncheckedBorderColor = Color.Transparent,
    checkedBorderColor = Color.Transparent
)

@Composable
fun GroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = LocalVaultPalette.current.cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = null,
        content = content
    )
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        thickness = 1.dp,
        color = Color.White.copy(alpha = 0.10f)
    )
}

@Composable
fun SettingsSectionHeader(text: String) {
    val palette = LocalVaultPalette.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        ),
        color = palette.textMuted,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsNavigationChevron() {
    val palette = LocalVaultPalette.current
    Icon(
        imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
        contentDescription = null,
        tint = palette.textMuted,
        modifier = Modifier.size(14.dp)
    )
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: Painter? = null,
    imageVector: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 18.dp, horizontal = 20.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null || imageVector != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageVector != null) {
                        Icon(
                            imageVector = imageVector,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    } else if (icon != null) {
                        Icon(
                            painter = icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (trailing != null) 12.dp else 0.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = palette.textPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = palette.textSecondary
                    )
                }
            }
        }

        if (trailing != null) {
            trailing()
        }
    }
}

private fun SettingsSection.sectionOrder(): Int = when (this) {
    SettingsSection.MAIN_MENU -> 0
    SettingsSection.DISPLAY -> 1
    SettingsSection.PRIVACY -> 2
    SettingsSection.INTEGRATIONS -> 3
    SettingsSection.FILTER -> 4
    SettingsSection.DATA_BACKUP -> 5
    SettingsSection.SAMPLE_DATA -> 6
}

fun settingsTransitionSpec(): AnimatedContentTransitionScope<SettingsSection>.() -> ContentTransform = {
    val isBack = targetState == SettingsSection.MAIN_MENU || (targetState.sectionOrder() < initialState.sectionOrder())
    if (isBack) {
        (slideInHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { width -> -width / 4 } +
                fadeIn(animationSpec = tween(220, easing = LinearOutSlowInEasing)))
            .togetherWith(
                slideOutHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { width -> width } +
                        fadeOut(animationSpec = tween(220, easing = FastOutLinearInEasing))
            ).apply {
                targetContentZIndex = 0f
            }
    } else {
        (slideInHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { width -> width } +
                fadeIn(animationSpec = tween(220, easing = LinearOutSlowInEasing)))
            .togetherWith(
                slideOutHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing)) { width -> -width / 4 } +
                        fadeOut(animationSpec = tween(220, easing = FastOutLinearInEasing))
            ).apply {
                targetContentZIndex = 1f
            }
    }
}

fun SettingsSection.title(): String = when (this) {
    SettingsSection.MAIN_MENU -> "Settings"
    SettingsSection.DISPLAY -> "Display"
    SettingsSection.PRIVACY -> "Privacy"
    SettingsSection.INTEGRATIONS -> "Integrations"
    SettingsSection.FILTER -> "Filter"
    SettingsSection.DATA_BACKUP -> "Data & Backup"
    SettingsSection.SAMPLE_DATA -> "Sample Data"
}
