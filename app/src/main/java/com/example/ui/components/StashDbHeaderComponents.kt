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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.example.network.StashPerformer
import com.example.network.StashStudio
import com.example.ui.StashSearchType
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalBetaTestPrivacy
import com.example.ui.theme.LocalVaultPalette
import com.example.ui.theme.privacyImageBlur

import androidx.compose.ui.BiasAlignment
import com.example.data.local.entity.ActorEntity

val StudioLogoBgDark = Color(0xFF000000)
val StudioLogoBgLight = Color(0xFF000000)

/**
 * Circular Item for Actor displayed in the horizontal row (Circle on top, Name below)
 */
@Composable
fun HorizontalActorCircleItem(
    performer: StashPerformer,
    isSelected: Boolean,
    onClick: () -> Unit,
    actorEntity: ActorEntity? = null
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val context = LocalContext.current

    val posX = actorEntity?.imagePositionX ?: 50f
    val posY = actorEntity?.imagePositionY ?: 0f
    val zoom = actorEntity?.imageZoom ?: 1.0f

    val circleScale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "actor_circle_scale"
    )

    val isBetaTest = LocalBetaTestPrivacy.current

    val imageRequest = remember(performer.imageUrl) {
        if (!performer.imageUrl.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(performer.imageUrl)
                .memoryCacheKey(performer.imageUrl)
                .diskCacheKey(performer.imageUrl)
                .size(200, 200)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .crossfade(180)
                .build()
        } else null
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .testTag("stash_actor_${performer.id}")
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .size(60.dp)
                .graphicsLayer {
                    scaleX = circleScale
                    scaleY = circleScale
                }
                .border(
                    BorderStroke(
                        if (isSelected) 2.5.dp else 1.2.dp,
                        if (isSelected) accent else palette.border.copy(alpha = 0.6f)
                    ),
                    CircleShape
                )
                .clip(CircleShape)
                .background(palette.cardBg),
            contentAlignment = Alignment.Center
        ) {
            if (imageRequest != null) {
                val z = zoom.coerceIn(1f, 3f)
                val biasX = (posX.coerceIn(0f, 100f) - 50f) / 50f
                val biasY = (posY.coerceIn(0f, 100f) - 50f) / 50f
                AsyncImage(
                    model = imageRequest,
                    contentDescription = performer.name,
                    contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(biasX, biasY),
                    modifier = Modifier
                        .fillMaxSize()
                        .privacyImageBlur(isBetaTest)
                        .graphicsLayer {
                            val maxX = size.width * (z - 1f) / 2f
                            val maxY = size.height * (z - 1f) / 2f
                            scaleX = z
                            scaleY = z
                            translationX = -biasX * maxX
                            translationY = -biasY * maxY
                        }
                )
                if (isBetaTest) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.75f))
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = palette.textMuted,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = performer.name,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) accent else palette.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Circular Item for Studio displayed in the horizontal row (Circle on top, Name below)
 */
@Composable
fun HorizontalStudioCircleItem(
    studio: StashStudio,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current

    val circleScale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "studio_circle_scale"
    )

    val context = LocalContext.current
    val formattedLogoUrl = remember(studio.logoUrl) {
        studio.logoUrl?.trim()?.replace("http://", "https://")
    }

    val imageRequest = remember(formattedLogoUrl) {
        if (!formattedLogoUrl.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(formattedLogoUrl)
                .memoryCacheKey(formattedLogoUrl)
                .diskCacheKey(formattedLogoUrl)
                .size(200, 200)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .crossfade(180)
                .build()
        } else null
    }

    val studioLogoBg = if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
        StudioLogoBgLight else StudioLogoBgDark

    val isBetaTest = LocalBetaTestPrivacy.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .testTag("stash_studio_${studio.id}")
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .size(60.dp)
                .graphicsLayer {
                    scaleX = circleScale
                    scaleY = circleScale
                }
                .border(
                    BorderStroke(
                        if (isSelected) 2.5.dp else 1.2.dp,
                        if (isSelected) accent else palette.border.copy(alpha = 0.6f)
                    ),
                    CircleShape
                )
                .clip(CircleShape)
                .background(studioLogoBg),
            contentAlignment = Alignment.Center
        ) {
            if (imageRequest != null) {
                var isImageError by remember(formattedLogoUrl) { mutableStateOf(false) }
                if (!isImageError) {
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = studio.name,
                        contentScale = ContentScale.Fit,
                        onError = { isImageError = true },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .privacyImageBlur(isBetaTest)
                    )
                    if (isBetaTest) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.75f))
                        )
                    }
                } else {
                    StudioFallbackEmblem(name = studio.name, accentColor = accent)
                }
            } else {
                StudioFallbackEmblem(name = studio.name, accentColor = accent)
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = studio.name,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) accent else palette.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Fallback emblem for studios when logo URL is missing or fails to load.
 */
@Composable
fun StudioFallbackEmblem(name: String, accentColor: Color) {
    val studioLogoBg = if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
        StudioLogoBgLight else StudioLogoBgDark

    val initials = name.trim().split(" ", "-", "_")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifBlank { "S" }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(accentColor.copy(alpha = 0.35f), studioLogoBg)
                )
            )
    ) {
        Text(
            text = initials,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: (@Composable () -> Unit)? = null
) {
    val palette = LocalVaultPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.textMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(54.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = palette.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            textAlign = TextAlign.Center
        )
        if (content != null) {
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

fun StashSearchType.tabIndex(): Int = when (this) {
    StashSearchType.ACTORS -> 0
    StashSearchType.STUDIO -> 1
    StashSearchType.SEXMEX -> 2
}
