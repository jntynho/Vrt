package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

/**
 * SELECT-UNIFY: Unified Selection Language component matching MUSE-REF identity:
 * - Internal selection background: accent.copy(alpha = 0.12f)
 * - Border: 1.dp accent.copy(alpha = 0.45f)
 * - Selection Indicator: 24dp CircleShape with spring/scaleIn animated Check icon (replaces RadioButton)
 * - Unselected: 24dp CircleShape with 1.5dp palette.border
 */
@Composable
fun SelectorOptionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,   // swatch / icon
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = CircleShape
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current

    val animBg by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(220),
        label = "selector_row_bg"
    ) // SELECT-UNIFY

    val animBorder by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.45f) else Color.Transparent,
        animationSpec = tween(220),
        label = "selector_row_border"
    ) // SELECT-UNIFY

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(animBg)
            .then(
                if (selected) Modifier.border(1.dp, animBorder, shape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 16.dp), // SELECT-UNIFY: Soft smooth padding
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp, // SELECT-UNIFY
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = palette.textPrimary, // SELECT-UNIFY: Stays textPrimary when selected
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp // SELECT-UNIFY
                        ),
                        color = palette.textSecondary, // SELECT-UNIFY
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Selection Indicator replacing RadioButton
        Box(
            modifier = Modifier.size(24.dp), // SELECT-UNIFY: 24dp
            contentAlignment = Alignment.Center
        ) {
            if (!selected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .border(1.5.dp, palette.border, CircleShape) // SELECT-UNIFY
                )
            }
            this@Row.AnimatedVisibility(
                visible = selected,
                enter = scaleIn(
                    initialScale = 0.4f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(tween(160)),
                exit = fadeOut(tween(120))
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(accent), // SELECT-UNIFY
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp) // SELECT-UNIFY: 14dp white Check icon
                    )
                }
            }
        }
    }
}

@Composable
fun UnifiedGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) = GroupedCard(modifier = modifier, content = content)

@Composable
fun UnifiedSettingsDivider() = SettingsDivider()

