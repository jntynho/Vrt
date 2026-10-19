package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.entity.StudioEntity
import com.example.ui.theme.LocalBetaTestPrivacy
import com.example.ui.theme.LocalVaultPalette
import com.example.ui.theme.privacyImageBlur
import java.util.Locale

@Composable
fun StudioDetailsDialog(
    studio: StudioEntity,
    onDismiss: () -> Unit,
    onSave: (StudioEntity) -> Unit,
    onDeleteCascade: (String) -> Unit
) {
    var isAdjustMode by remember { mutableStateOf(false) }
    var confirmDeleteStudio by remember { mutableStateOf(false) }

    val initialFraction = remember(studio.id, studio.logoBgColor) {
        val bg = studio.logoBgColor
        if (bg != null) {
            try {
                val parsed = android.graphics.Color.parseColor(bg)
                val r = android.graphics.Color.red(parsed)
                val g = android.graphics.Color.green(parsed)
                val b = android.graphics.Color.blue(parsed)
                ((r + g + b) / 3f) / 255f
            } catch (_: Exception) {
                0.5f
            }
        } else {
            0.0f
        }
    }
    var gradientFraction by remember(studio.id, studio.logoBgColor) { mutableFloatStateOf(initialFraction) }
    var isCustomBgEnabled by remember(studio.id, studio.logoBgColor) { mutableStateOf(studio.logoBgColor != null) }
    var editedName by remember(studio.id) { mutableStateOf(studio.name) }
    var editedLogoUrl by remember(studio.id) { mutableStateOf(studio.logoUrl ?: "") }

    val isLight = isAppLightTheme()
    val circleBorderColor = if (isLight) Color.Black else Color.White
    val isBetaTestStudio = LocalBetaTestPrivacy.current
    val palette = LocalVaultPalette.current

    val currentGray = (gradientFraction * 255).toInt().coerceIn(0, 255)
    val currentBgColor = if (isCustomBgEnabled) Color(currentGray, currentGray, currentGray) else Color(0xFF000000)
    val hexString = String.format(Locale.US, "#%02X%02X%02X", currentGray, currentGray, currentGray)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.vaultTopGlow(),
        containerColor = palette.dialogBg,
        shape = VaultDialogShape,
        title = {
            if (!isAdjustMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Studio Details",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(
                        onClick = { isAdjustMode = true },
                        modifier = Modifier.testTag("adjust_studio_bg_button")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_details_adjust_brush),
                            contentDescription = "Adjust Logo Background",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3B3C3E))
                            .testTag("adjust_logo_bg_back_button")
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { isAdjustMode = false }
                            )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_player_back),
                            contentDescription = "Back",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Logo Background",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        },
        text = {
            if (!isAdjustMode) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Editable Name Field
                    SceneInputField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        placeholder = "Name",
                        readOnly = false,
                        trailingIcon = {
                            PasteTrailingIcon(onPaste = { editedName = it })
                        },
                        backgroundColor = palette.cardBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("studio_name_static_input")
                    )

                    // Editable Image URL Field
                    SceneInputField(
                        value = editedLogoUrl,
                        onValueChange = { editedLogoUrl = it },
                        placeholder = "Image URL",
                        readOnly = false,
                        trailingIcon = {
                            PasteTrailingIcon(onPaste = { editedLogoUrl = it })
                        },
                        backgroundColor = palette.cardBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("studio_image_static_input")
                    )

                    // Delete Studio Section
                    if (!confirmDeleteStudio) {
                        Button(
                            onClick = { confirmDeleteStudio = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f),
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = CircleShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("delete_studio_cascade_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Delete Studio Scene",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f)
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Delete '${studio.name}' and all associated scenes without exception?",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { confirmDeleteStudio = false },
                                        shape = CircleShape
                                    ) {
                                        Text("Cancel")
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Button(
                                        onClick = {
                                            confirmDeleteStudio = false
                                            onDeleteCascade(studio.id)
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        ),
                                        shape = CircleShape
                                    ) {
                                        Text("Confirm Delete", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Inline Adjust Studio Background View
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Big Circular Preview with dynamic background color
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .shadow(3.dp, CircleShape)
                            .graphicsLayer {
                                shape = CircleShape
                                clip = true
                            }
                            .clip(CircleShape)
                            .background(currentBgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!studio.logoUrl.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        shape = CircleShape
                                        clip = true
                                    }
                                    .clip(CircleShape)
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = studio.logoUrl,
                                    contentDescription = studio.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .privacyImageBlur(isBetaTestStudio)
                                )
                                if (isBetaTestStudio) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(PrivacyScrim)
                                    )
                                }
                            }
                        } else {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_nav_studio),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                        }

                        // Top border overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(2.5.dp, circleBorderColor, CircleShape)
                        )
                    }

                    // Gradient Slider from Black to White
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Background Color",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isCustomBgEnabled) hexString else "Default",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        GradientSlider(
                            value = gradientFraction,
                            onValueChange = {
                                gradientFraction = it
                                isCustomBgEnabled = true
                            },
                            currentColor = currentBgColor
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isAdjustMode) {
                Button(
                    onClick = {
                        val finalHex = if (isCustomBgEnabled) hexString else null
                        onSave(studio.copy(name = editedName.trim().ifBlank { studio.name }, logoUrl = editedLogoUrl.trim().ifEmpty { null }, logoBgColor = finalHex))
                    },
                    shape = CircleShape
                ) {
                    Text("Save")
                }
            } else {
                Button(
                    onClick = {
                        val finalHex = if (isCustomBgEnabled) hexString else studio.logoBgColor
                        onSave(studio.copy(name = editedName.trim().ifBlank { studio.name }, logoUrl = editedLogoUrl.trim().ifEmpty { null }, logoBgColor = finalHex))
                        onDismiss()
                    },
                    shape = CircleShape,
                    modifier = Modifier.testTag("studio_details_save_button")
                ) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            if (!isAdjustMode) {
                TextButton(
                    onClick = onDismiss,
                    shape = CircleShape
                ) {
                    Text("Close")
                }
            } else {
                TextButton(
                    onClick = { isAdjustMode = false },
                    shape = CircleShape
                ) {
                    Text("Back")
                }
            }
        }
    )
}
