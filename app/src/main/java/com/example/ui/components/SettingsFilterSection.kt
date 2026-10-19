package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

@Composable
fun SettingsFilterSection(
    modifier: Modifier = Modifier,
    enableStudioFilter: Boolean,
    onEnableStudioFilterChange: (Boolean) -> Unit,
    blockMalePerformers: Boolean = true,
    onBlockMalePerformersChange: (Boolean) -> Unit = {},
    blockedStudioNames: List<String>,
    onBlockStudio: (String) -> Unit = {},
    onUnblockStudio: (String) -> Unit,
    onClearAll: () -> Unit
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    var showClearConfirmDialog by remember { mutableStateOf(false) }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            modifier = Modifier.vaultTopGlow(glowColor = MaterialTheme.colorScheme.error),
            shape = VaultDialogShape,
            containerColor = palette.dialogBg,
            title = {
                Text(
                    text = "Clear All Blocked Studios?",
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
            },
            text = {
                Text(
                    text = "All blocked studios will be removed from the filter and will be allowed to appear in StashDB results again.",
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAll()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    ),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text("Clear All", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmDialog = false },
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text("Cancel", color = palette.textSecondary, fontWeight = FontWeight.Medium)
                }
            }
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Block Male Performers Switch Card (Cards Layout style)
        GroupedCard {
            SettingsRow(
                title = "Block Male Performers",
                subtitle = "Display female performers only and exclude male performers from StashDB results",
                trailing = {
                    Switch(
                        checked = blockMalePerformers,
                        onCheckedChange = onBlockMalePerformersChange,
                        colors = museSwitchColors(),
                        modifier = Modifier.testTag("block_male_performers_switch")
                    )
                },
                onClick = { onBlockMalePerformersChange(!blockMalePerformers) }
            )
        }

        // Blocked Studios List Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsSectionHeader(text = "Blocked Studios (${blockedStudioNames.size})")
            if (blockedStudioNames.isNotEmpty()) {
                TextButton(
                    onClick = { showClearConfirmDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Clear All",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Master Filter Switch Card - Placed directly above No Blocked Studios / Blocked Studios List
        GroupedCard {
            SettingsRow(
                title = "Enable Studio Filter",
                subtitle = "Exclude blocked studios from StashDB search results",
                icon = painterResource(id = R.drawable.ic_settings_filter),
                trailing = {
                    Switch(
                        checked = enableStudioFilter,
                        onCheckedChange = onEnableStudioFilterChange,
                        colors = museSwitchColors(),
                        modifier = Modifier.testTag("enable_studio_filter_switch")
                    )
                },
                onClick = { onEnableStudioFilterChange(!enableStudioFilter) }
            )
        }

        if (blockedStudioNames.isEmpty()) {
            GroupedCard(modifier = Modifier.height(180.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 20.dp, horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = palette.surface,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_settings_filter),
                                    contentDescription = null,
                                    tint = palette.textMuted.copy(alpha = 0.6f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Text(
                            text = "No Blocked Studios",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Studios you block from StashDB will appear here. All studio scenes will be displayed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            val listScrollState = rememberScrollState()
            GroupedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(listScrollState)
                    ) {
                        blockedStudioNames.forEachIndexed { index, studioName ->
                            if (index > 0) {
                                SettingsDivider()
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_nav_studio),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Column {
                                        Text(
                                            text = studioName,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 15.sp
                                            ),
                                            color = palette.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Blocked from StashDB",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                            color = palette.textSecondary
                                        )
                                    }
                                }

                                Button(
                                    onClick = { onUnblockStudio(studioName) },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isLight) Color.Black.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.08f),
                                        contentColor = palette.textPrimary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(
                                        text = "Unblock",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    if (listScrollState.canScrollBackward) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(palette.cardBg, Color.Transparent)
                                    )
                                )
                        )
                    }
                    if (listScrollState.canScrollForward) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, palette.cardBg)
                                    )
                                )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
