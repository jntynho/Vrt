package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.example.R
import com.example.network.StashScene
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalBetaTestPrivacy
import com.example.ui.theme.LocalVaultPalette
import com.example.ui.theme.privacyImageBlur

/**
 * Adaptive Skeleton Modifier tuned for Dark, AMOLED, and Light themes
 */
@Composable
fun Modifier.adaptiveSkeleton(
    shape: Shape = RoundedCornerShape(8.dp),
    enabled: Boolean = true
): Modifier {
    val palette = LocalVaultPalette.current
    if (!enabled) {
        return this
            .clip(shape)
            .background(palette.cardBg)
    }
    val transition = rememberInfiniteTransition(label = "adaptive_skeleton_anim")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_alpha"
    )
    return this
        .clip(shape)
        .background(palette.skeletonBg.copy(alpha = alpha))
}

/**
 * 1:1 Pixel-Matched Skeleton Card for StashDB Scene Grid:
 * - Exact 16:9 Cover Skeleton
 * - Exact padding, spacing, line heights, divider, and icon dimensions
 * - Eliminates Layout Shift / jump when real cards load
 */
@Composable
fun StashGridSkeletonCard(
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .vaultTopGlow(cornerRadius = 16.dp)
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = palette.cardBg),
        border = null
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 1. Exact 16:9 Cover Skeleton
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .adaptiveSkeleton(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            )
            // 2. Exact Title & metadata skeleton container matching StashGridPhotoCard pixel-for-pixel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = palette.cardBg,
                        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Title (Exact 18dp height matching 18sp line-height, with smooth rounded corners)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(18.dp)
                        .adaptiveSkeleton(RoundedCornerShape(50))
                )
                // Divider line below title (Exact 0.5dp thickness with 1dp vertical padding)
                HorizontalDivider(
                    color = palette.border.copy(alpha = 0.35f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
                // Row 1: Actor Icon (13.5dp) + Actor text placeholder (15dp height, smooth rounded corners)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.size(13.5.dp).adaptiveSkeleton(CircleShape))
                    Box(modifier = Modifier.fillMaxWidth(0.65f).height(15.dp).adaptiveSkeleton(RoundedCornerShape(50)))
                }
                // Row 2: Studio Icon (13.5dp) + Studio text placeholder (15dp height + 1dp vertical padding, smooth rounded corners)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                ) {
                    Box(modifier = Modifier.size(13.5.dp).adaptiveSkeleton(CircleShape))
                    Box(modifier = Modifier.fillMaxWidth(0.48f).height(15.dp).adaptiveSkeleton(RoundedCornerShape(50)))
                }
                // Row 3: Calendar Icon (13.5dp) + Date text placeholder (15dp height, smooth rounded corners)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.size(13.5.dp).adaptiveSkeleton(CircleShape))
                    Box(modifier = Modifier.fillMaxWidth(0.35f).height(15.dp).adaptiveSkeleton(RoundedCornerShape(50)))
                }
            }
        }
    }
}

/**
 * 2-Cards-Per-Row Scene Card matching the exact layout:
 * - Smooth entrance slide-up + fade-in animation
 * - Rounded corners (16.dp)
 * - Cover image (16:9)
 * - Dimmed/desaturated image with circular check badge when selected
 * - Bold title (1 line with ellipsis)
 * - Subtle divider
 * - 3 metadata rows with outlined icons (Person, Studio Logo/Videocam, CalendarToday)
 */
@Composable
fun StashGridPhotoCard(
    scene: StashScene,
    isSelected: Boolean,
    isAlreadySaved: Boolean = false,
    onToggleSelect: () -> Unit,
    onStudioClick: (studioId: String?, studioName: String) -> Unit = { _, _ -> }
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val context = LocalContext.current
    val uPath = remember { Path() }

    val grayscaleFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.0f) })
    }

    val animatedCardAlpha by animateFloatAsState(
        targetValue = if (isAlreadySaved && !isSelected) 0.65f else 1.0f,
        animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing),
        label = "card_saved_alpha"
    )

    val animatedCardScale by animateFloatAsState(
        targetValue = if (isSelected) 0.978f else 1.0f,
        animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing),
        label = "card_select_scale"
    )

    val selectionProgress by animateFloatAsState(
        targetValue = if (isSelected) 1.0f else 0.0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "card_select_progress"
    )

    val isBetaTest = LocalBetaTestPrivacy.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = animatedCardAlpha
                scaleX = animatedCardScale
                scaleY = animatedCardScale
            }
            .vaultTopGlow(cornerRadius = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleSelect
            )
            .testTag("stash_scene_${scene.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = palette.cardBg),
        border = null
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            var isImageLoaded by remember(scene.coverUrl) { mutableStateOf(false) }
            // 1. Cover Image Box: Fixed 16:9 aspect ratio with adaptiveSkeleton placeholder (paused once loaded)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .adaptiveSkeleton(
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        enabled = !isImageLoaded && !scene.coverUrl.isNullOrBlank()
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!scene.coverUrl.isNullOrBlank()) {
                    val imageRequest = remember(scene.coverUrl) {
                        ImageRequest.Builder(context)
                            .data(scene.coverUrl)
                            .memoryCacheKey(scene.coverUrl)
                            .diskCacheKey(scene.coverUrl)
                            .size(640, 360)
                            .precision(Precision.INEXACT)
                            .allowHardware(true)
                            .crossfade(200)
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = scene.title,
                        contentScale = ContentScale.Crop,
                        colorFilter = if (isAlreadySaved) grayscaleFilter else null,
                        onSuccess = { isImageLoaded = true },
                        modifier = Modifier
                            .fillMaxSize()
                            .privacyImageBlur(isBetaTest)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(40.dp)
                    )
                }

                if (isBetaTest && !scene.coverUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.75f))
                    )
                }

                // Smooth lightweight dimmed overlay on selection or saved
                val overlayAlpha = if (isSelected) 0.32f else if (isAlreadySaved) 0.18f else 0.0f
                if (overlayAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = overlayAlpha))
                    )
                }

                // Circular Check badge in top right for selected scenes (smooth, fast & light)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isSelected,
                        enter = fadeIn(animationSpec = tween(160)) + scaleIn(
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            initialScale = 0.6f
                        ),
                        exit = fadeOut(animationSpec = tween(120)) + scaleOut(
                            animationSpec = tween(120, easing = FastOutSlowInEasing),
                            targetScale = 0.6f
                        )
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = accent,
                            shadowElevation = 3.dp,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Card Content: Custom U-shape border with smooth animated progress
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = palette.cardBg,
                        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    )
                    .drawBehind {
                        if (selectionProgress > 0.05f) {
                            val cornerRadiusPx = 16.dp.toPx()
                            val strokeWidth = (0.6f + 1.4f * selectionProgress).dp.toPx()
                            val halfStroke = strokeWidth / 2f

                            uPath.reset()
                            uPath.moveTo(halfStroke, 0f)
                            uPath.lineTo(halfStroke, (size.height - cornerRadiusPx).coerceAtLeast(0f))
                            uPath.arcTo(
                                rect = Rect(
                                    left = halfStroke,
                                    top = (size.height - 2 * cornerRadiusPx + halfStroke).coerceAtLeast(0f),
                                    right = (2 * cornerRadiusPx - halfStroke).coerceAtMost(size.width),
                                    bottom = size.height - halfStroke
                                ),
                                startAngleDegrees = 180f,
                                sweepAngleDegrees = -90f,
                                forceMoveTo = false
                            )
                            uPath.lineTo((size.width - cornerRadiusPx).coerceAtLeast(0f), size.height - halfStroke)
                            uPath.arcTo(
                                rect = Rect(
                                    left = (size.width - 2 * cornerRadiusPx + halfStroke).coerceAtLeast(0f),
                                    top = (size.height - 2 * cornerRadiusPx + halfStroke).coerceAtLeast(0f),
                                    right = size.width - halfStroke,
                                    bottom = size.height - halfStroke
                                ),
                                startAngleDegrees = 90f,
                                sweepAngleDegrees = -90f,
                                forceMoveTo = false
                            )
                            uPath.lineTo(size.width - halfStroke, 0f)

                            val borderBrush = Brush.verticalGradient(
                                0.0f to accent.copy(alpha = 0.12f * selectionProgress),
                                0.45f to accent.copy(alpha = 0.65f * selectionProgress),
                                1.0f to accent.copy(alpha = selectionProgress),
                                startY = 0f,
                                endY = size.height
                            )

                            drawPath(
                                path = uPath,
                                brush = borderBrush,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Title (Bold, 1 line with ellipsis and fixed 18sp line-height)
                Text(
                    text = scene.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    ),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Divider line below title
                HorizontalDivider(
                    color = palette.border.copy(alpha = 0.35f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 1.dp)
                )

                // Row 1: Actor Icon + Actors
                val actorText = if (scene.femalePerformers.isNotEmpty()) {
                    scene.femalePerformers.joinToString(", ") { it.name }
                } else {
                    "No Performers Listed"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nav_actor),
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(13.5.dp)
                    )
                    Text(
                        text = actorText,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Row 2: Studio Icon + Studio Name (Clickable to Block Studio)
                val studioText = scene.studioName ?: "Studio"
                val hasStudio = !scene.studioName.isNullOrBlank()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = hasStudio) {
                            onStudioClick(scene.studioId, scene.studioName ?: "")
                        }
                        .padding(vertical = 1.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nav_studio),
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(13.5.dp)
                    )
                    Text(
                        text = studioText,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Row 3: Calendar Icon + Date
                val dateText = scene.date ?: "No date"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_calendar_event),
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(13.5.dp)
                    )
                    Text(
                        text = dateText,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
