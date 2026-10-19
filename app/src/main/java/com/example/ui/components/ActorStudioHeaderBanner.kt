package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.data.local.entity.ActorEntity
import com.example.data.local.entity.StudioEntity
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalBetaTestPrivacy
import com.example.ui.theme.LocalVaultPalette
import com.example.ui.theme.parseHexColor
import com.example.ui.theme.privacyImageBlur

/**
 * Top Entity Header Banner for Actor or Studio scenes feed.
 * Occupies space above the first link card, displaying the actor/studio circle on the left
 * and their name + scene count in front of it on the right side.
 */
@Composable
fun ActorStudioHeaderBanner(
    actor: ActorEntity?,
    studio: StudioEntity?,
    sceneCount: Int,
    modifier: Modifier = Modifier,
    onAvatarClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val isBetaTest = LocalBetaTestPrivacy.current
    val isLight = isAppLightTheme()
    val circleBorderColor = if (isLight) Color.Black else Color.White
    val isVerified = !actor?.stashDbId.isNullOrBlank() || !studio?.stashDbId.isNullOrBlank()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular Avatar with unified Actor/Studio Management border - clickable to open in StashDb
        if (actor != null) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(palette.surface)
                    .clickable(onClick = onAvatarClick)
                    .testTag("stash_search_actor_button")
            ) {
                if (actor.imageUrl.isNotBlank()) {
                    val actorImageRequest = remember(actor.imageUrl) {
                        ImageRequest.Builder(context)
                            .data(actor.imageUrl)
                            .size(240, 240)
                            .crossfade(150)
                            .build()
                    }
                    val z = actor.imageZoom.coerceIn(1f, 3f)
                    val biasX = (actor.imagePositionX.coerceIn(0f, 100f) - 50f) / 50f
                    val biasY = (actor.imagePositionY.coerceIn(0f, 100f) - 50f) / 50f
                    AsyncImage(
                        model = actorImageRequest,
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(palette.cardBg.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nav_actor),
                            contentDescription = null,
                            tint = palette.textSecondary.copy(alpha = 0.9f),
                            modifier = Modifier.size(46.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.5.dp, circleBorderColor, CircleShape)
                )
            }
        } else if (studio != null) {
            val studioCustomBg = remember(studio.logoBgColor) {
                if (!studio.logoBgColor.isNullOrBlank()) {
                    parseHexColor(studio.logoBgColor, Color(0xFF000000))
                } else {
                    Color(0xFF000000)
                }
            }
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(if (!studio.logoUrl.isNullOrBlank()) studioCustomBg else palette.cardBg.copy(alpha = 0.85f))
                    .border(1.5.dp, circleBorderColor, CircleShape)
                    .clickable(onClick = onAvatarClick)
                    .testTag("stash_search_studio_button"),
                contentAlignment = Alignment.Center
            ) {
                if (!studio.logoUrl.isNullOrBlank()) {
                    val studioImageRequest = remember(studio.logoUrl) {
                        ImageRequest.Builder(context)
                            .data(studio.logoUrl)
                            .size(240, 240)
                            .crossfade(150)
                            .build()
                    }
                    AsyncImage(
                        model = studioImageRequest,
                        contentDescription = studio.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .privacyImageBlur(isBetaTest)
                    )
                    if (isBetaTest) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(PrivacyScrim)
                        )
                    }
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nav_studio),
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(46.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(18.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = actor?.name ?: studio?.name ?: "",
                    color = palette.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isVerified) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.ic_verified_badge),
                        contentDescription = "Verified",
                        tint = accent,
                        modifier = Modifier.size(23.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (sceneCount == 1) "1 scene" else "$sceneCount scenes",
                color = accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

