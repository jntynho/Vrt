package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.R
import com.example.data.local.entity.ActorEntity
import com.example.network.StashDbApiService
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalBetaTestPrivacy
import com.example.ui.theme.LocalVaultPalette
import com.example.ui.theme.privacyImageBlur
import java.util.Locale

@Composable
fun ActorDetailsDialog(
    actor: ActorEntity,
    stashDbApiKey: String,
    existingNames: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (ActorEntity) -> Unit,
    onDeleteCascade: (String) -> Unit
) {
    val context = LocalContext.current
    var isAdjustMode by remember { mutableStateOf(false) }
    var confirmDeleteActor by remember { mutableStateOf(false) }

    var posX by remember(actor.id) { mutableFloatStateOf(actor.imagePositionX.coerceIn(0f, 100f)) }
    var posY by remember(actor.id) { mutableFloatStateOf(actor.imagePositionY.coerceIn(0f, 100f)) }
    var zoom by remember(actor.id) { mutableFloatStateOf(actor.imageZoom.coerceIn(1.0f, 3.0f)) }

    val initialActorImages: List<String> = remember(actor.id) {
        val list = mutableListOf<String>()
        if (actor.imageUrl.isNotBlank()) list.add(actor.imageUrl)
        if (!actor.originalImageUrl.isNullOrBlank() && !list.contains(actor.originalImageUrl)) {
            list.add(actor.originalImageUrl!!)
        }
        list
    }
    var editedName by remember(actor.id) { mutableStateOf(actor.name) }
    var editedImageUrl by remember(actor.id) { mutableStateOf(actor.imageUrl) }
    var selectedImageUrl by remember(actor.id) { mutableStateOf(actor.imageUrl) }
    var pendingPastedImageUrl by remember(actor.id) { mutableStateOf<String?>(null) }
    var fetchedImages by remember(actor.id) { mutableStateOf<List<String>>(initialActorImages) }
    var hasFetchedRemoteImages by remember(actor.id) { mutableStateOf(false) }
    var isFetchingImages by remember(actor.id) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    var resetRotationTarget by remember { mutableFloatStateOf(0f) }
    val resetRotation by animateFloatAsState(
        targetValue = resetRotationTarget,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "reset_spin"
    )

    val isDuplicateName = remember(editedName, existingNames, actor.name) {
        val trimmed = editedName.trim()
        trimmed.isNotEmpty() &&
            !trimmed.equals(actor.name, ignoreCase = true) &&
            existingNames.any { it.equals(trimmed, ignoreCase = true) }
    }

    // Verify pasted URL is a real loadable image and transition directly to Adjust Photo once loaded
    LaunchedEffect(pendingPastedImageUrl) {
        val candidateUrl = pendingPastedImageUrl?.trim() ?: return@LaunchedEffect
        if (candidateUrl.isBlank()) {
            pendingPastedImageUrl = null
            return@LaunchedEffect
        }
        val isLikelyUrl = candidateUrl.startsWith("http://", ignoreCase = true) ||
            candidateUrl.startsWith("https://", ignoreCase = true) ||
            candidateUrl.startsWith("content://", ignoreCase = true) ||
            candidateUrl.startsWith("file://", ignoreCase = true) ||
            candidateUrl.startsWith("data:image/", ignoreCase = true)
        if (!isLikelyUrl) {
            pendingPastedImageUrl = null
            return@LaunchedEffect
        }

        val request = ImageRequest.Builder(context)
            .data(candidateUrl)
            .allowHardware(false)
            .build()
        val result = try {
            context.imageLoader.execute(request)
        } catch (_: Exception) {
            null
        }
        if (result is SuccessResult) {
            editedImageUrl = candidateUrl
            selectedImageUrl = candidateUrl
            if (!fetchedImages.contains(candidateUrl)) {
                fetchedImages = listOf(candidateUrl) + fetchedImages
            }
            posX = 50f
            posY = 0f
            zoom = 1.0f
            pendingPastedImageUrl = null
            isAdjustMode = true
        } else {
            pendingPastedImageUrl = null
        }
    }

    LaunchedEffect(actor.id, isAdjustMode) {
        if (isAdjustMode && !hasFetchedRemoteImages && stashDbApiKey.isNotBlank()) {
            isFetchingImages = true
            try {
                val imgs = StashDbApiService.fetchPerformerAllImages(
                    stashDbId = actor.stashDbId,
                    performerName = actor.name,
                    apiKey = stashDbApiKey
                )
                val combined = mutableListOf<String>()
                if (actor.imageUrl.isNotBlank()) combined.add(actor.imageUrl)
                if (!actor.originalImageUrl.isNullOrBlank() && !combined.contains(actor.originalImageUrl)) {
                    combined.add(actor.originalImageUrl!!)
                }
                imgs.forEach { url ->
                    if (url.isNotBlank() && !combined.contains(url)) {
                        combined.add(url)
                    }
                }
                fetchedImages = combined.distinct()
                hasFetchedRemoteImages = true
            } catch (_: Exception) {
                // Graceful fallback on network or parsing failure
            } finally {
                isFetchingImages = false
            }
        }
    }

    val isBetaTest = LocalBetaTestPrivacy.current
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val isLight = isAppLightTheme()
    val circleBorderColor = if (isLight) Color.Black else Color.White

    AlertDialog(
        onDismissRequest = {
            if (isAdjustMode) isAdjustMode = false else onDismiss()
        },
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
                        text = "Actor Details",
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(
                        onClick = { isAdjustMode = true },
                        modifier = Modifier.testTag("adjust_actor_photo_button")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_details_adjust_brush),
                            contentDescription = "Adjust Photo Position & Zoom",
                            tint = accent
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(palette.surface)
                                .testTag("adjust_photo_back_button")
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
                            text = "Adjust Photo",
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    val isModified = posX != 50f || posY != 0f || zoom != 1.0f || (selectedImageUrl != actor.imageUrl)
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            resetRotationTarget -= 360f
                            posX = 50f
                            posY = 0f
                            zoom = 1.0f
                            selectedImageUrl = editedImageUrl.ifBlank { actor.imageUrl }
                        },
                        modifier = Modifier.testTag("adjust_photo_reset_button")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_action_reset),
                            contentDescription = "Reset",
                            tint = if (isModified) accent else palette.textPrimary.copy(alpha = 0.5f),
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer {
                                    rotationZ = resetRotation
                                }
                        )
                    }
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
                            .testTag("actor_name_static_input")
                    )
                    if (isDuplicateName) {
                        Text(
                            text = "An actor with this name already exists",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                    }

                    // Editable Image URL Field
                    SceneInputField(
                        value = editedImageUrl,
                        onValueChange = { newValue ->
                            val prevValue = editedImageUrl
                            editedImageUrl = newValue
                            selectedImageUrl = newValue
                            // Detect clipboard paste via keyboard/context-menu (multi-character jump to a URL)
                            val trimmed = newValue.trim()
                            if (trimmed.isNotEmpty() && kotlin.math.abs(newValue.length - prevValue.length) > 5) {
                                pendingPastedImageUrl = trimmed
                            }
                        },
                        placeholder = "Image URL",
                        readOnly = false,
                        trailingIcon = {
                            PasteTrailingIcon(onPaste = { pastedUrl ->
                                val trimmed = pastedUrl.trim()
                                editedImageUrl = trimmed
                                selectedImageUrl = trimmed
                                pendingPastedImageUrl = trimmed
                            })
                        },
                        backgroundColor = palette.cardBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("actor_image_static_input")
                    )

                    // Delete Actor and Linked Scenes Section
                    if (!confirmDeleteActor) {
                        Button(
                            onClick = { confirmDeleteActor = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f),
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = CircleShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("delete_actor_cascade_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Delete Actor Scene",
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
                                    text = "Delete '${actor.name}' and all scenes referencing solely this actor? (Scenes with multiple actors will be preserved).",
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
                                        onClick = { confirmDeleteActor = false },
                                        shape = CircleShape
                                    ) {
                                        Text("Cancel", color = palette.textSecondary)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Button(
                                        onClick = {
                                            confirmDeleteActor = false
                                            onDeleteCascade(actor.id)
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
                // Inline Adjust Photo View
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Big Circular Preview
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .shadow(3.dp, CircleShape)
                            .clip(CircleShape)
                            .background(palette.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        val currentPreviewUrl = selectedImageUrl.ifBlank { actor.imageUrl }
                        if (currentPreviewUrl.isNotBlank()) {
                            val z = zoom.coerceIn(1f, 3f)
                            val biasX = (posX.coerceIn(0f, 100f) - 50f) / 50f
                            val biasY = (posY.coerceIn(0f, 100f) - 50f) / 50f
                            AsyncImage(
                                model = currentPreviewUrl,
                                contentDescription = actor.name,
                                contentScale = ContentScale.Crop,
                                alignment = BiasAlignment(biasX, biasY),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .privacyImageBlur(isBetaTest)
                                    .then(
                                        if (z > 1.02f) {
                                            Modifier.graphicsLayer {
                                                val maxX = size.width * (z - 1f) / 2f
                                                val maxY = size.height * (z - 1f) / 2f
                                                scaleX = z
                                                scaleY = z
                                                translationX = -biasX * maxX
                                                translationY = -biasY * maxY
                                            }
                                        } else Modifier
                                    )
                            )
                            if (isBetaTest) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(PrivacyScrim)
                                )
                            }
                        } else {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_nav_actor),
                                    contentDescription = null,
                                    tint = palette.textMuted,
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

                    // 3 Compact Sliders: X, Y, Z (Zoom)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Slider X
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "X",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                modifier = Modifier.width(16.dp)
                            )
                            SleekSlimSlider(
                                value = posX,
                                onValueChange = { posX = it },
                                valueRange = 0f..100f,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${posX.toInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(38.dp)
                            )
                        }

                        // Slider Y
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Y",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                modifier = Modifier.width(16.dp)
                            )
                            SleekSlimSlider(
                                value = posY,
                                onValueChange = { posY = it },
                                valueRange = 0f..100f,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${posY.toInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(38.dp)
                            )
                        }

                        // Slider Z (Zoom)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Z",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                modifier = Modifier.width(16.dp)
                            )
                            SleekSlimSlider(
                                value = zoom,
                                onValueChange = { zoom = it },
                                valueRange = 1.0f..3.0f,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.1fx", zoom),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(38.dp)
                            )
                        }
                    }

                    // Horizontal Circle Photo Selector
                    if (isFetchingImages || fetchedImages.size > 1) {
                        val skeletonBg = palette.skeletonBg
                        val skeletonBorder = if (isLight) Color.Black.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.25f)

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (fetchedImages.size > 1) "Select Photo (${fetchedImages.size})" else "Select Photo",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textSecondary
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalFadeEdge(20.dp)
                            ) {
                                itemsIndexed(fetchedImages, key = { index, imgUrl -> "${imgUrl}_$index" }) { _, imgUrl ->
                                    SelectPhotoCircleItem(
                                        imgUrl = imgUrl,
                                        isSelected = imgUrl == selectedImageUrl,
                                        skeletonBg = skeletonBg,
                                        skeletonBorder = skeletonBorder,
                                        isBetaTest = isBetaTest,
                                        onSelect = {
                                            selectedImageUrl = imgUrl
                                            editedImageUrl = imgUrl
                                        }
                                    )
                                }

                                if (isFetchingImages) {
                                    val skeletonCount = (8 - fetchedImages.size).coerceAtLeast(5)
                                    items(skeletonCount) {
                                        Box(
                                            modifier = Modifier
                                                .size(50.dp)
                                                .clip(CircleShape)
                                                .background(skeletonBg)
                                                .border(
                                                    BorderStroke(1.2.dp, skeletonBorder),
                                                    CircleShape
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isAdjustMode) {
                Button(
                    onClick = {
                        val updatedActor = actor.copy(
                            name = editedName.trim().ifBlank { actor.name },
                            imageUrl = selectedImageUrl.ifBlank { actor.imageUrl },
                            imagePositionX = posX,
                            imagePositionY = posY,
                            imageZoom = zoom
                        )
                        onSave(updatedActor)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = CircleShape
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        val updatedActor = actor.copy(
                            name = editedName.trim().ifBlank { actor.name },
                            imageUrl = editedImageUrl.trim().ifBlank { actor.imageUrl },
                            imagePositionX = posX,
                            imagePositionY = posY,
                            imageZoom = zoom
                        )
                        onSave(updatedActor)
                        onDismiss()
                    },
                    enabled = editedName.isNotBlank() && !isDuplicateName,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = CircleShape,
                    modifier = Modifier.testTag("actor_details_save_button")
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (!isAdjustMode) {
                TextButton(
                    onClick = onDismiss,
                    shape = CircleShape
                ) {
                    Text("Close", color = palette.textSecondary)
                }
            } else {
                TextButton(
                    onClick = { isAdjustMode = false },
                    shape = CircleShape
                ) {
                    Text("Back", color = palette.textSecondary)
                }
            }
        }
    )
}
