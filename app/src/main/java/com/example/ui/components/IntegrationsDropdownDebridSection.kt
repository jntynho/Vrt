package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

enum class DebridServiceOption(
    val id: String,
    val title: String,
    val tokenUrlHint: String
) {
    REAL_DEBRID(
        id = "real_debrid",
        title = "Real-Debrid",
        tokenUrlHint = "Get API token from real-debrid.com/apitoken"
    ),
    TORBOX(
        id = "torbox",
        title = "Torbox",
        tokenUrlHint = "Get API key from torbox.app/settings"
    )
}

/**
 * Integrations Debrid Section in grouped card style:
 * - Section header "Debrid" outside the Card
 * - Grouped Card: RoundedCornerShape(24.dp), palette.cardBg
 * - Row 1: Provider selection row with anchored DropdownMenu (Real-Debrid / Torbox)
 * - Row 2: API key field with show/hide toggle and provider hint
 * - Row 3: Priority selection row with anchored DropdownMenu (Auto / Real-Debrid first / Torbox first)
 */
@Composable
fun IntegrationsDropdownDebridSection(
    realDebridKey: String,
    onRealDebridKeyChange: (String) -> Unit,
    torboxKey: String,
    onTorboxKeyChange: (String) -> Unit,
    debridOrder: String = "AUTO",
    onDebridOrderChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current // INTEG-REDESIGN
    val accent = LocalAccentColor.current // INTEG-REDESIGN
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f // INTEG-REDESIGN

    var selectedService by remember {
        mutableStateOf(
            if (realDebridKey.isNotBlank()) DebridServiceOption.REAL_DEBRID
            else if (torboxKey.isNotBlank()) DebridServiceOption.TORBOX
            else DebridServiceOption.REAL_DEBRID
        )
    }

    var isServicesDropdownExpanded by remember { mutableStateOf(false) }
    var isOrderDropdownExpanded by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }

    val activeKey = when (selectedService) {
        DebridServiceOption.REAL_DEBRID -> realDebridKey
        DebridServiceOption.TORBOX -> torboxKey
    }

    val onActiveKeyChange: (String) -> Unit = { newKey ->
        when (selectedService) {
            DebridServiceOption.REAL_DEBRID -> onRealDebridKeyChange(newKey)
            DebridServiceOption.TORBOX -> onTorboxKeyChange(newKey)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // ① SettingsSectionHeader("Debrid") outside the Card
        Text(
            text = "Debrid",
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp, // INTEG-REDESIGN
                fontWeight = FontWeight.Bold
            ),
            color = palette.textMuted, // INTEG-REDESIGN
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp) // INTEG-REDESIGN
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp), // INTEG-REDESIGN: 24dp radius
            colors = CardDefaults.cardColors(containerColor = palette.cardBg), // INTEG-REDESIGN: palette.cardBg
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), // INTEG-REDESIGN: 0dp elevation
            border = null // INTEG-REDESIGN: no border
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Row 1: Debrid Services Row with polished DropdownMenu underneath
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isServicesDropdownExpanded = true }
                            .padding(vertical = 18.dp, horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(accent.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (selectedService == DebridServiceOption.REAL_DEBRID) Icons.Outlined.CloudDownload else Icons.Outlined.Storage,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Debrid Services",
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = palette.textPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = selectedService.title,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 13.sp
                                    ),
                                    color = palette.textSecondary
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                            contentDescription = "Select Debrid Service",
                            tint = palette.textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = isServicesDropdownExpanded,
                        onDismissRequest = { isServicesDropdownExpanded = false },
                        modifier = Modifier
                            .background(palette.cardBg)
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 8.dp
                    ) {
                        DebridServiceOption.values().forEach { option ->
                            val isCurrentSelected = selectedService == option
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(if (isCurrentSelected) accent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (option == DebridServiceOption.REAL_DEBRID) Icons.Outlined.CloudDownload else Icons.Outlined.Storage,
                                                contentDescription = null,
                                                tint = if (isCurrentSelected) accent else palette.textMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = option.title,
                                                fontSize = 14.sp,
                                                color = palette.textPrimary,
                                                fontWeight = if (isCurrentSelected) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                            Text(
                                                text = if (option == DebridServiceOption.REAL_DEBRID) {
                                                    "High-speed multi-hoster & cloud caching"
                                                } else {
                                                    "Fast torrent cloud & direct downloader"
                                                },
                                                fontSize = 12.sp,
                                                color = palette.textSecondary,
                                                maxLines = 1
                                            )
                                        }
                                        if (isCurrentSelected) {
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = accent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    selectedService = option
                                    isServicesDropdownExpanded = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Divider 1
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    thickness = 1.dp,
                    color = Color.White.copy(alpha = 0.10f)
                )

                // Row 2: API Key OutlinedTextField
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp, horizontal = 20.dp)
                ) {
                    Text(
                        text = "${selectedService.title} API Key",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = palette.textPrimary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = activeKey,
                        onValueChange = onActiveKeyChange,
                        placeholder = {
                            Text(
                                "Paste ${selectedService.title} API token here...",
                                fontSize = 14.sp,
                                color = palette.textSecondary
                            )
                        },
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = palette.textPrimary
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = if (isLight) Color.Black.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = if (isLight) Color.Black.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.05f),
                            focusedBorderColor = accent,
                            unfocusedBorderColor = palette.border,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            cursorColor = accent
                        ),
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_settings_integrations),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .size(22.dp)
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { showApiKey = !showApiKey },
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Icon(
                                    imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showApiKey) "Hide API Key" else "Show API Key",
                                    tint = palette.textSecondary
                                )
                            }
                        },
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("debrid_api_key_input")
                    )

                    Text(
                        text = selectedService.tokenUrlHint,
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }

                // Divider 2
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    thickness = 1.dp,
                    color = Color.White.copy(alpha = 0.10f)
                )

                // Row 3: Priority Selection Row
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isOrderDropdownExpanded = true }
                            .padding(vertical = 18.dp, horizontal = 20.dp), // INTEG-REDESIGN
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Priority",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 16.sp, // INTEG-REDESIGN
                                    fontWeight = FontWeight.Medium
                                ),
                                color = palette.textPrimary // INTEG-REDESIGN
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when (debridOrder) {
                                    "REAL_DEBRID_FIRST" -> "Real-Debrid first"
                                    "TORBOX_FIRST" -> "Torbox first"
                                    else -> "Auto (Cache check -> instant stream)"
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 13.sp // INTEG-REDESIGN
                                ),
                                color = palette.textSecondary // INTEG-REDESIGN
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                            contentDescription = null,
                            tint = palette.textMuted, // INTEG-REDESIGN
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = isOrderDropdownExpanded,
                        onDismissRequest = { isOrderDropdownExpanded = false },
                        modifier = Modifier
                            .background(palette.cardBg)
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 8.dp
                    ) {
                        val priorityOptions = listOf(
                            "AUTO" to "Auto (Cache check -> instant stream)",
                            "REAL_DEBRID_FIRST" to "Real-Debrid first",
                            "TORBOX_FIRST" to "Torbox first"
                        )
                        priorityOptions.forEach { (key, label) ->
                            val isCurrentSelected = debridOrder == key || (key == "AUTO" && debridOrder != "REAL_DEBRID_FIRST" && debridOrder != "TORBOX_FIRST")
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp) // INTEG-REDESIGN: 48dp height
                                            .padding(horizontal = 16.dp), // INTEG-REDESIGN: 16dp horizontal
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isCurrentSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = accent, // INTEG-REDESIGN
                                                modifier = Modifier.size(18.dp) // INTEG-REDESIGN: 18dp check
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.width(18.dp))
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = label,
                                            fontSize = 14.sp, // INTEG-REDESIGN: 14sp
                                            color = palette.textPrimary, // INTEG-REDESIGN
                                            fontWeight = if (isCurrentSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }
                                },
                                onClick = {
                                    onDebridOrderChange(key)
                                    isOrderDropdownExpanded = false
                                },
                                contentPadding = PaddingValues(0.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
