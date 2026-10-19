package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

/**
 * Redesigned Progress Dialog for StashDB batch scene saving.
 * Faithfully follows the signature Vault dialog design language:
 * - VaultDialogShape (26.dp)
 * - vaultTopGlow fading top frame border
 * - Palette theme consistency
 * - Polished animated progress bar, live stage card, and status badges.
 */
@Composable
fun StashBatchSaveProgressDialog(
    current: Int,
    total: Int,
    currentTitle: String,
    currentPhase: String,
    savedWithTorrentsCount: Int,
    onCancel: () -> Unit
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current

    val percentage = if (total > 0) ((current.toFloat() / total) * 100).toInt().coerceIn(0, 100) else 0
    val progressFraction = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "stash_batch_save_progress"
    )

    AlertDialog(
        onDismissRequest = { /* Non-dismissable on touch outside */ },
        modifier = Modifier.vaultTopGlow(),
        shape = VaultDialogShape,
        containerColor = palette.dialogBg,
        icon = {
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.12f),
                modifier = Modifier.size(54.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_action_save),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        },
        title = {
            Text(
                text = "Saving Scenes...",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Stats Row: Scene counter & styled percentage pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scene $current of $total",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textSecondary
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = accent.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "$percentage%",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Smooth Gradient Animated Progress Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(palette.border.copy(alpha = 0.6f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(accent, accent.copy(alpha = 0.85f))
                                )
                            )
                    )
                }

                // Active Scene Card with current phase & torrent metadata
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = palette.cardBg,
                    border = BorderStroke(1.dp, palette.border.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (currentTitle.isNotBlank()) {
                            Text(
                                text = currentTitle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (currentPhase.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 2.dp,
                                    color = accent
                                )
                                Text(
                                    text = currentPhase,
                                    fontSize = 12.sp,
                                    color = palette.textMuted
                                )
                            }
                        }

                        if (savedWithTorrentsCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.12f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_magnet),
                                        contentDescription = null,
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "Torrents Found: $savedWithTorrentsCount",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF10B981)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                modifier = Modifier.testTag("cancel_batch_save_button")
            ) {
                Text(
                    text = "Cancel",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp
                )
            }
        }
    )
}

/**
 * Dedicated Security/Block dialog for filtering a studio from StashDB.
 *
 * SPECIFIC DESIGN INSTRUCTION:
 * Does NOT use the standard dialog design language or color scheme!
 * Features a dedicated Deep Obsidian & Crimson Alert cyber-security theme:
 * - Sharp modern 18.dp container (not VaultDialogShape)
 * - Deep Obsidian (#0D0F15) container with Rose-Crimson perimeter border
 * - Glowing crimson security shield emblem
 * - High-contrast studio badge banner & danger typography
 */
@Composable
fun StashBlockStudioDialog(
    targetName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // Dedicated color constants distinct from the application's general palette
    val dialogBg = Color(0xFF0D0F15)
    val crimsonAccent = Color(0xFFE11D48)
    val crimsonGlow = Color(0xFF9F1239)
    val crimsonBannerBg = Color(0xFF1C1017)
    val crimsonText = Color(0xFFFDA4AF)
    val textPrimaryWhite = Color(0xFFF8FAFC)
    val textSlateMuted = Color(0xFF94A3B8)
    val outlineBorder = Color(0xFF334155)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(
            width = 1.2.dp,
            color = crimsonAccent.copy(alpha = 0.40f),
            shape = RoundedCornerShape(18.dp)
        ),
        shape = RoundedCornerShape(18.dp),
        containerColor = dialogBg,
        icon = {
            // Distinct Crimson Shield Emblem
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(crimsonAccent, crimsonGlow)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_settings_filter),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        },
        title = {
            Text(
                text = "Block Studio?",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = textPrimaryWhite,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // High-visibility Studio Name Callout Banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = crimsonBannerBg,
                    border = BorderStroke(1.dp, crimsonAccent.copy(alpha = 0.40f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(crimsonAccent)
                        )
                        Text(
                            text = targetName,
                            color = crimsonText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = "Do you want to block this studio? All scenes and future results associated with it will be suppressed and filtered out from StashDB.",
                    color = textSlateMuted,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = crimsonAccent,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                modifier = Modifier.testTag("confirm_block_studio_button")
            ) {
                Text(
                    text = "Block Studio",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, outlineBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = textSlateMuted
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Cancel",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.5.sp
                )
            }
        }
    )
}
