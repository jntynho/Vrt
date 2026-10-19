package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.example.R
import com.example.network.OnlyhavenCreator
import com.example.network.OnlyhavenMediaItem
import com.example.ui.theme.VaultPalette

/**
 * Unified Model / Creator Card used across OnlyhavenScreen and SavedCreatorsScreen.
 * Eliminates 350+ lines of duplicate card UI code and ensures consistent aesthetics.
 */
@Composable
fun LazyGridItemScope.OnlyhavenModelCard(
    creator: OnlyhavenCreator,
    palette: VaultPalette,
    accent: Color,
    isLight: Boolean,
    circleBorderColor: Color,
    isSaved: Boolean,
    onToggleSave: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val avatarRequest = remember(creator.avatarUrl) {
        ImageRequest.Builder(context)
            .data(creator.avatarUrl)
            .crossfade(true)
            .crossfade(350)
            .build()
    }

    var isPopAnimating by remember { mutableStateOf(false) }
    val heartScale by animateFloatAsState(
        targetValue = if (isPopAnimating) 1.14f else 1f,
        animationSpec = tween(
            durationMillis = 150,
            easing = FastOutSlowInEasing
        ),
        label = "heart_soft_pulse",
        finishedListener = { isPopAnimating = false }
    )
    val animatedHeartTint by animateColorAsState(
        targetValue = if (isSaved) Color(0xFFFF3B5C) else palette.textSecondary,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "heart_soft_tint"
    )

    Card(
        modifier = modifier
            .animateItem()
            .fillMaxWidth()
            .vaultTopGlow(cornerRadius = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("model_row_${creator.id}"),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isLight) 2.5.dp else 1.dp
        ),
        colors = CardDefaults.cardColors(containerColor = palette.cardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Avatar with circle border & letter placeholder fallback
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(palette.surface),
                contentAlignment = Alignment.Center
            ) {
                if (creator.avatarUrl.isNotEmpty()) {
                    var isAvatarLoaded by remember(creator.avatarUrl) { mutableStateOf(false) }
                    val avatarAlpha by animateFloatAsState(
                        targetValue = if (isAvatarLoaded) 1f else 0f,
                        animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
                        label = "avatar_alpha"
                    )
                    val avatarScale by animateFloatAsState(
                        targetValue = if (isAvatarLoaded) 1f else 0.92f,
                        animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
                        label = "avatar_scale"
                    )

                    if (!isAvatarLoaded) {
                        val shimmerBrush = ShimmerBrush(targetValue = 900f)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(palette.surface)
                                .background(shimmerBrush)
                        )
                    }
                    AsyncImage(
                        model = avatarRequest,
                        contentDescription = creator.name,
                        contentScale = ContentScale.Crop,
                        onSuccess = { isAvatarLoaded = true },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = avatarAlpha
                                scaleX = avatarScale
                                scaleY = avatarScale
                            }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(palette.cardBg.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = creator.name.take(2).uppercase(),
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.5.dp, circleBorderColor, CircleShape)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 2. Model Name, Handle & Stats
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val label = creator.displayName.ifBlank { creator.name }
                Text(
                    text = label,
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "@${creator.name}",
                    color = palette.textSecondary,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (creator.postCount > 0 || creator.imageCount > 0 || creator.videoCount > 0) {
                    val statsText = buildString {
                        if (creator.postCount > 0) append("${creator.postCount} posts")
                        if (creator.videoCount > 0) {
                            if (isNotEmpty()) append(" • ")
                            append("${creator.videoCount} videos")
                        }
                    }
                    if (statsText.isNotEmpty()) {
                        Text(
                            text = statsText,
                            color = palette.textSecondary.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 3. Heart Save Button with simple, soft, smooth feedback
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = heartScale
                        scaleY = heartScale
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isPopAnimating = true
                        onToggleSave()
                    }
                    .testTag("toggle_save_model_${creator.id}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        id = if (isSaved) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                    ),
                    contentDescription = if (isSaved) "Unsave Model" else "Save Model",
                    tint = animatedHeartTint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Backward compatibility alias for OnlyhavenModelCard.
 */
@Composable
fun LazyGridItemScope.OnlyhavenCreatorCard(
    creator: OnlyhavenCreator,
    palette: VaultPalette,
    accent: Color,
    isLight: Boolean,
    circleBorderColor: Color,
    isSaved: Boolean,
    onToggleSave: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) = OnlyhavenModelCard(
    creator = creator,
    palette = palette,
    accent = accent,
    isLight = isLight,
    circleBorderColor = circleBorderColor,
    isSaved = isSaved,
    onToggleSave = onToggleSave,
    onClick = onClick,
    modifier = modifier
)

/**
 * Unified Media Grid Thumbnail Cell used across CreatorDetailsScreen and SavedCreatorsScreen.
 * Handles images, video badges, multi-attachment carousels, shimmering, and error states.
 */
@Composable
fun LazyGridItemScope.OnlyhavenMediaGridItem(
    mediaItem: OnlyhavenMediaItem,
    targetAttachment: OnlyhavenMediaItem = mediaItem,
    palette: VaultPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val thumbUrl = targetAttachment.thumbnailUrl.ifEmpty { targetAttachment.originalUrl }
    val thumbRequest = remember(thumbUrl) {
        ImageRequest.Builder(context)
            .data(thumbUrl)
            .crossfade(true)
            .crossfade(350)
            .build()
    }

    var isLoaded by remember(thumbUrl) { mutableStateOf(false) }
    var isError by remember(thumbUrl) { mutableStateOf(false) }

    val imageAlpha by animateFloatAsState(
        targetValue = if (isLoaded && !isError) 1f else 0f,
        animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
        label = "media_cell_img_alpha"
    )
    val imageScale by animateFloatAsState(
        targetValue = if (isLoaded && !isError) 1f else 0.93f,
        animationSpec = tween(durationMillis = 350, easing = LinearOutSlowInEasing),
        label = "media_cell_img_scale"
    )

    Box(
        modifier = modifier
            .animateItem()
            .fillMaxWidth()
            .aspectRatio(4f / 5f)
            .background(palette.cardBg)
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
    ) {
        if (!isLoaded && !isError) {
            val shimmerBrush = ShimmerBrush(targetValue = 900f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.cardBg)
                    .background(shimmerBrush),
                contentAlignment = Alignment.Center
            ) {
                AppLottieLoadingAnimation(modifier = Modifier.size(28.dp))
            }
        }

        if (isError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.cardBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_lock_error),
                    contentDescription = "Failed to load thumbnail",
                    tint = palette.textSecondary.copy(alpha = 0.45f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        AsyncImage(
            model = thumbRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = {
                isLoaded = true
                isError = false
            },
            onError = {
                isLoaded = true
                isError = true
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = imageAlpha
                    scaleX = imageScale
                    scaleY = imageScale
                }
        )

        if (mediaItem.isMultiMedia) {
            Icon(
                painter = painterResource(id = R.drawable.ic_instagram_carousel),
                contentDescription = "Multiple Items",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp)
            )
        } else if (targetAttachment.kind == "video") {
            Icon(
                painter = painterResource(id = R.drawable.ic_player_play),
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(18.dp)
            )
        }
    }
}
