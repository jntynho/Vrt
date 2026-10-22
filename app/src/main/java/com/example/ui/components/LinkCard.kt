package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.local.entity.ActorEntity
import com.example.data.local.entity.LinkEntity
import com.example.network.MediaUrlValidator
import com.example.ui.ActiveInlineVideoPlayback
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalBetaTestPrivacy
import com.example.ui.theme.LocalVaultPalette
import com.example.ui.theme.VaultScrims
import com.example.ui.theme.privacyImageBlur
import androidx.compose.runtime.mutableFloatStateOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private enum class CardActionMenuState {
    CLOSED,
    MAIN_MENU,
    QUALITY_MENU,
    DELETE_CONFIRM,
    ACTORS_MENU
}

private val threadLocalDateFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("MMM dd, yyyy", Locale.US)
}

fun formatDisplayDate(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) return ""
    return try {
        threadLocalDateFormatter.get()?.format(Date(timestamp)).orEmpty()
    } catch (_: Exception) {
        ""
    }
}

private const val DEFAULT_COVER_ASPECT_RATIO = 16f / 9f
private const val MIN_ADAPTIVE_COVER_ASPECT_RATIO = 5f / 4f
private const val MAX_ADAPTIVE_COVER_ASPECT_RATIO = 2.40f

/**
 * Silky-smooth Apple/Material-3 Emphasized Decelerate curve for geometric height morphing.
 */
private val AdaptiveMorphEasing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)

/**
 * Thread-safe in-memory cache mapping cover image URLs to their resolved aspect ratios (width / height).
 * Prevents layout jumps or recomposition jitter when scrolling back and forth in LazyColumn.
 */
private object CoverAspectRatioCache {
    private val map = ConcurrentHashMap<String, Float>()

    fun get(url: String): Float? = if (url.isNotBlank()) map[url] else null

    fun put(url: String, ratio: Float) {
        if (url.isNotBlank() && ratio.isFinite() && ratio > 0f) {
            val resolvedRatio = if (ratio < MIN_ADAPTIVE_COVER_ASPECT_RATIO) {
                DEFAULT_COVER_ASPECT_RATIO
            } else {
                ratio.coerceAtMost(MAX_ADAPTIVE_COVER_ASPECT_RATIO)
            }
            map[url] = resolvedRatio
        }
    }
}

private data class MainActorInfo(
    val name: String,
    val realId: String
)

@Composable
fun LinkCard(
    link: LinkEntity,
    actorsMap: Map<String, String> = emptyMap(),
    studiosMap: Map<String, String> = emptyMap(),
    fullActorsMap: Map<String, ActorEntity> = emptyMap(),
    preferredActorId: String? = null,
    isBookmarked: Boolean = false,
    isActiveCard: Boolean = false,
    onActivate: () -> Unit = {},
    onDismissActive: () -> Unit = {},
    onToggleBookmark: () -> Unit = {},
    onPlay: (url: String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onActorClick: (actorId: String) -> Unit = {},
    onStudioClick: (studioId: String) -> Unit = {},
    resolvingStatus: String? = null,
    isResolvingThisCard: Boolean = false,
    resolutionError: String? = null,
    onDismissResolutionError: () -> Unit = {},
    inlinePlayback: ActiveInlineVideoPlayback? = null,
    onCloseInlineVideo: () -> Unit = {},
    onFullscreenInlineVideo: (positionMs: Long) -> Unit = {},
    onFullscreenInlineVideoWithMode: ((positionMs: Long, startInLandscape: Boolean) -> Unit)? = null,
    onEnterPipInlineVideo: ((positionMs: Long) -> Unit)? = null,
    exoPlayer: androidx.media3.exoplayer.ExoPlayer? = null,
    enableVideoPlayerGestures: Boolean = true,
    adaptiveCoverAspectRatio: Boolean = true,
    onImageError: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val haptic = LocalHapticFeedback.current

    var subMenuState by remember { mutableStateOf<CardActionMenuState?>(null) }
    var selectedSource by remember { mutableStateOf<Source?>(null) }

    val currentMenuState = when {
        !isActiveCard -> CardActionMenuState.CLOSED
        subMenuState != null -> subMenuState!!
        else -> CardActionMenuState.MAIN_MENU
    }
    val isOverlayActive = currentMenuState != CardActionMenuState.CLOSED

    var lastOpenMenuState by remember { mutableStateOf(CardActionMenuState.MAIN_MENU) }
    LaunchedEffect(currentMenuState) {
        if (currentMenuState != CardActionMenuState.CLOSED) {
            lastOpenMenuState = currentMenuState
        }
    }

    var lastClickTime by remember { mutableLongStateOf(0L) }
    val debouncedClick: (() -> Unit) -> Unit = remember {
        { action ->
            val now = System.currentTimeMillis()
            if (now - lastClickTime >= 150L) {
                lastClickTime = now
                action()
            }
        }
    }

    val menuProgress by animateFloatAsState(
        targetValue = if (isOverlayActive) 1f else 0f,
        animationSpec = if (isOverlayActive) {
            tween(durationMillis = 380, easing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f))
        } else {
            tween(durationMillis = 240, easing = FastOutLinearInEasing)
        },
        label = "menu_progress"
    )

    var isAnyActionButtonPressed by remember { mutableStateOf(false) }

    val isActorsOverlayMode = currentMenuState == CardActionMenuState.ACTORS_MENU
    val isStandardSubMenu = currentMenuState == CardActionMenuState.QUALITY_MENU ||
            currentMenuState == CardActionMenuState.DELETE_CONFIRM

    val coverZoomScale by animateFloatAsState(
        targetValue = when {
            !isOverlayActive -> 1.0f
            isStandardSubMenu && isAnyActionButtonPressed -> 1.095f
            isStandardSubMenu -> 1.075f
            isAnyActionButtonPressed -> 1.075f
            else -> 1.05f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cover_subtle_zoom"
    )

    val isOverlayVisible by remember {
        derivedStateOf { menuProgress > 0.001f }
    }

    LaunchedEffect(isActiveCard) {
        if (!isActiveCard) {
            subMenuState = null
            selectedSource = null
            isAnyActionButtonPressed = false
            lastOpenMenuState = CardActionMenuState.MAIN_MENU
        }
    }

    fun handleCoverTap() {
        debouncedClick {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (isOverlayActive) {
                subMenuState = null
                onDismissActive()
            } else {
                subMenuState = CardActionMenuState.MAIN_MENU
                onActivate()
            }
        }
    }

    fun handleScrimTap() {
        debouncedClick {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (isStandardSubMenu) {
                subMenuState = CardActionMenuState.MAIN_MENU
            } else {
                subMenuState = null
                onDismissActive()
            }
        }
    }

    BackHandler(enabled = isOverlayActive) {
        if (isStandardSubMenu) {
            subMenuState = CardActionMenuState.MAIN_MENU
        } else {
            subMenuState = null
            onDismissActive()
        }
    }

    val mainActor = remember(link.actorIds, preferredActorId, fullActorsMap, actorsMap) {
        val matchedId = if (!preferredActorId.isNullOrBlank()) {
            link.actorIds.firstOrNull { id ->
                id.equals(preferredActorId, ignoreCase = true) ||
                id == fullActorsMap[preferredActorId]?.id ||
                fullActorsMap[id]?.id?.equals(preferredActorId, ignoreCase = true) == true ||
                fullActorsMap[id]?.name?.equals(preferredActorId, ignoreCase = true) == true
            } ?: link.actorIds.firstOrNull()
        } else {
            link.actorIds.firstOrNull()
        }

        if (matchedId != null) {
            val entity = fullActorsMap[matchedId] ?: fullActorsMap[matchedId.trim().lowercase()]
            val name = actorsMap[matchedId] ?: entity?.name ?: matchedId
            val realId = entity?.id ?: matchedId
            MainActorInfo(name = name, realId = realId)
        } else {
            null
        }
    }

    val studioName = remember(link.studioIds, studiosMap) {
        link.studioIds.firstOrNull()?.let { id -> studiosMap[id] ?: id }.orEmpty()
    }

    val displayDate = remember(link.createdAt, link.assignedDate) {
        formatDisplayDate(link.assignedDate ?: link.createdAt)
    }

    val preparedActors = remember(link.actorIds, fullActorsMap, actorsMap) {
        link.actorIds.map { actorId ->
            val entity = fullActorsMap[actorId] ?: fullActorsMap[actorId.trim().lowercase()]
            val actorName = actorsMap[actorId] ?: entity?.name ?: actorId
            val actorImg = entity?.imageUrl.orEmpty()
            val actorZoom = (entity?.imageZoom ?: 1.0f).coerceIn(1f, 3f)
            val actorPosX = entity?.imagePositionX ?: 50f
            val actorPosY = entity?.imagePositionY ?: 50f
            val realActorId = entity?.id ?: actorId
            val biasX = (actorPosX.coerceIn(0f, 100f) - 50f) / 50f
            val biasY = (actorPosY.coerceIn(0f, 100f) - 50f) / 50f
            PreparedCardActor(
                realId = realActorId,
                name = actorName,
                img = actorImg,
                zoom = actorZoom,
                biasX = biasX,
                biasY = biasY
            )
        }
    }

    fun handleUrlSelection(url: String?) {
        if (url.isNullOrBlank()) {
            Toast.makeText(context, "No URL specified for this quality", Toast.LENGTH_SHORT).show()
            return
        }
        val trimmed = url.trim()
        val ext = MediaUrlValidator.mediaExtensionOf(trimmed)
        val isStreamableVideo = ext in listOf("mp4", "m3u8", "mkv", "webm", "mpd", "ts", "mov", "avi") ||
                com.example.network.Scraping.isSupportedUrl(trimmed) ||
                trimmed.contains("/dash/", ignoreCase = true) ||
                trimmed.contains(".m3u8", ignoreCase = true) ||
                trimmed.contains(".mpd", ignoreCase = true) ||
                trimmed.contains(".mp4", ignoreCase = true) ||
                trimmed.contains("pornhub.com", ignoreCase = true) ||
                trimmed.contains("gofile.io", ignoreCase = true) ||
                trimmed.contains("cyberfile.me", ignoreCase = true) ||
                trimmed.startsWith("magnet:", ignoreCase = true)

        if (isStreamableVideo) {
            onPlay(trimmed)
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed))
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Could not open URL: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleMagnet(magnetUri: String?) {
        if (!magnetUri.isNullOrBlank()) {
            onPlay(magnetUri)
        } else {
            Toast.makeText(context, "No Magnet link specified for this quality", Toast.LENGTH_SHORT).show()
        }
    }

    val hasUrlHD = !link.urlHD.isNullOrBlank()
    val hasUrl4K = !link.url4K.isNullOrBlank()
    val hasUrlVR = !link.urlVR.isNullOrBlank()
    val hasAnyUrl = hasUrlHD || hasUrl4K || hasUrlVR

    val hasMagnetHD = !link.magnet.isNullOrBlank() || !link.torrentUrlHD.isNullOrBlank()
    val hasMagnet4K = !link.magnet4K.isNullOrBlank() || !link.torrentUrl4K.isNullOrBlank()
    val hasMagnetVR = !link.magnetVR.isNullOrBlank() || !link.torrentUrlVR.isNullOrBlank()
    val hasAnyMagnet = hasMagnetHD || hasMagnet4K || hasMagnetVR

    var isImageLoaded by remember(link.coverImage) {
        mutableStateOf(CoverAspectRatioCache.get(link.coverImage) != null)
    }
    var isImageError by remember(link.coverImage) { mutableStateOf(false) }

    var coverAspectRatio by remember(link.coverImage) {
        mutableFloatStateOf(
            CoverAspectRatioCache.get(link.coverImage) ?: DEFAULT_COVER_ASPECT_RATIO
        )
    }

    val isVideoPlaybackMode = inlinePlayback != null || isResolvingThisCard

    // Only animate height ratio when transitioning into/out of video playback or toggling the Display setting.
    // Initial cover ratio detection snaps in the exact same frame as the image so card & cover never separate.
    var isMorphTransitionActive by remember(link.id) { mutableStateOf(false) }
    var lastVideoPlaybackMode by remember(link.id) { mutableStateOf(isVideoPlaybackMode) }
    var lastAdaptiveSetting by remember(link.id) { mutableStateOf(adaptiveCoverAspectRatio) }

    if (lastVideoPlaybackMode != isVideoPlaybackMode || lastAdaptiveSetting != adaptiveCoverAspectRatio) {
        isMorphTransitionActive = true
        lastVideoPlaybackMode = isVideoPlaybackMode
        lastAdaptiveSetting = adaptiveCoverAspectRatio
    }

    // Interpolate inverse aspect ratio (height / width) so vertical pixel height changes linearly & smoothly.
    // When adaptiveCoverAspectRatio is turned off in Display settings, strictly use 16:9 as default.
    val targetHeightRatio = if (!adaptiveCoverAspectRatio || isVideoPlaybackMode) {
        1f / DEFAULT_COVER_ASPECT_RATIO
    } else {
        val effectiveAspectRatio = if (coverAspectRatio < MIN_ADAPTIVE_COVER_ASPECT_RATIO) {
            DEFAULT_COVER_ASPECT_RATIO
        } else {
            coverAspectRatio.coerceAtMost(MAX_ADAPTIVE_COVER_ASPECT_RATIO)
        }
        1f / effectiveAspectRatio
    }

    val animatedHeightRatio by animateFloatAsState(
        targetValue = targetHeightRatio,
        animationSpec = if (isMorphTransitionActive) {
            tween(
                durationMillis = 360,
                easing = AdaptiveMorphEasing
            )
        } else {
            snap()
        },
        finishedListener = {
            isMorphTransitionActive = false
        },
        label = "adaptive_card_height_ratio"
    )

    val effectiveAspectRatio = (1f / animatedHeightRatio.coerceAtLeast(0.1f))
        .coerceIn(MIN_ADAPTIVE_COVER_ASPECT_RATIO, MAX_ADAPTIVE_COVER_ASPECT_RATIO)

    val coverImageAlpha by animateFloatAsState(
        targetValue = if (isImageLoaded) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "cover_image_alpha"
    )

    val inlinePlayerAlpha by animateFloatAsState(
        targetValue = if (inlinePlayback != null) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (inlinePlayback != null) 280 else 220,
            easing = FastOutSlowInEasing
        ),
        label = "inline_player_crossfade"
    )

    val resolvingOverlayAlpha by animateFloatAsState(
        targetValue = if (isResolvingThisCard && (resolvingStatus != null || resolutionError != null)) 1f else 0f,
        animationSpec = tween(
            durationMillis = 220,
            easing = FastOutSlowInEasing
        ),
        label = "resolving_overlay_crossfade"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .testTag("scene_card_${link.id}")
    ) {
        // 1. Unified Adaptive Cover & 16:9 Inline Video Container (Single-pass layout with lower Metadata surface)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(effectiveAspectRatio)
                .background(palette.surface)
                .clipToBounds()
                .clickable(
                    enabled = inlinePlayback == null && !isResolvingThisCard,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    handleCoverTap()
                }
        ) {
            // Base Layer: Cover Image (hidden immediately when pressing play / resolving or during inline playback)
            if (inlinePlayback == null && !isResolvingThisCard) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            transformOrigin = TransformOrigin.Center
                            scaleX = coverZoomScale
                            scaleY = coverZoomScale
                        }
                ) {
                if (!isImageLoaded && link.coverImage.isNotEmpty() && !isImageError) {
                    val shimmerBrush = ShimmerBrush(targetValue = 900f)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(palette.cardBg)
                            .background(shimmerBrush)
                    )
                }

                val isBetaTest = LocalBetaTestPrivacy.current

                if (link.coverImage.isNotEmpty() && !isImageError) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(link.coverImage)
                            .crossfade(false)
                            .build(),
                        contentDescription = link.title,
                        contentScale = ContentScale.Crop,
                        onSuccess = { successState ->
                            val drawable = successState.result.drawable
                            val w = drawable.intrinsicWidth
                            val h = drawable.intrinsicHeight
                            if (w > 0 && h > 0) {
                                val rawRatio = w.toFloat() / h.toFloat()
                                val resolvedRatio = if (rawRatio < MIN_ADAPTIVE_COVER_ASPECT_RATIO) {
                                    DEFAULT_COVER_ASPECT_RATIO
                                } else {
                                    rawRatio.coerceAtMost(
                                        MAX_ADAPTIVE_COVER_ASPECT_RATIO
                                    )
                                }
                                CoverAspectRatioCache.put(link.coverImage, resolvedRatio)
                                coverAspectRatio = resolvedRatio
                            }
                            isImageLoaded = true
                            isImageError = false
                        },
                        onError = {
                            isImageLoaded = true
                            isImageError = true
                            coverAspectRatio = DEFAULT_COVER_ASPECT_RATIO
                            onImageError?.invoke()
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = coverImageAlpha }
                            .privacyImageBlur(isBetaTest)
                            .then(
                                if (menuProgress > 0.01f) {
                                    Modifier.blur(
                                        radius = (8f * menuProgress).dp,
                                        edgeTreatment = BlurredEdgeTreatment.Rectangle
                                    )
                                } else Modifier
                            )
                    )
                    if (isBetaTest) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.28f))
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = "No Cover Image",
                            tint = palette.textSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }
            }

            // Action Menu Scrim & Buttons Layer (when not playing inline video)
            if (inlinePlayback == null) {
                if (menuProgress > 0.001f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = menuProgress }
                            .background(VaultScrims.Overlay)
                            .clickable(
                                enabled = isOverlayActive,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                handleScrimTap()
                            }
                    )
                }

                if (isOverlayVisible) {
                    val activeOrLastMenuState = if (isOverlayActive) currentMenuState else lastOpenMenuState
                    CompositionLocalProvider(
                        LocalActionsInteractive provides isOverlayActive,
                        LocalActionMenuProgress provides menuProgress,
                        LocalActionButtonPressed provides { pressed -> isAnyActionButtonPressed = pressed }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (activeOrLastMenuState == CardActionMenuState.ACTORS_MENU) {
                                // Actors buttons overlay separated from standard action menus
                                ActorsActionMenu(
                                    actors = preparedActors,
                                    onActorClick = { actorRealId ->
                                        subMenuState = null
                                        onDismissActive()
                                        onActorClick(actorRealId)
                                    }
                                )
                            } else {
                                AnimatedContent(
                                    targetState = activeOrLastMenuState,
                                    transitionSpec = {
                                        fadeIn(tween(durationMillis = 80, easing = FastOutSlowInEasing))
                                            .togetherWith(
                                                fadeOut(tween(durationMillis = 100, easing = FastOutLinearInEasing))
                                            )
                                            .using(SizeTransform(clip = false))
                                    },
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = "simple_action_menu"
                                ) { state ->
                                    when (state) {
                                        CardActionMenuState.CLOSED,
                                        CardActionMenuState.ACTORS_MENU -> {
                                            Spacer(modifier = Modifier.size(0.dp))
                                        }
                                        CardActionMenuState.MAIN_MENU -> {
                                            MainActionMenu(
                                                onMagnetClick = {
                                                    selectedSource = Source.MAGNET
                                                    subMenuState = CardActionMenuState.QUALITY_MENU
                                                },
                                                onUrlClick = {
                                                    selectedSource = Source.URL
                                                    subMenuState = CardActionMenuState.QUALITY_MENU
                                                },
                                                onSave = onToggleBookmark,
                                                isSaved = isBookmarked,
                                                onEdit = {
                                                    onDismissActive()
                                                    onEdit()
                                                },
                                                onDelete = {
                                                    subMenuState = CardActionMenuState.DELETE_CONFIRM
                                                },
                                                showMagnet = hasAnyMagnet,
                                                showUrl = hasAnyUrl
                                            )
                                        }
                                        CardActionMenuState.QUALITY_MENU -> {
                                            val hasHD = if (selectedSource == Source.MAGNET) {
                                                !link.magnet.isNullOrBlank() || !link.torrentUrlHD.isNullOrBlank()
                                            } else {
                                                !link.urlHD.isNullOrBlank()
                                            }
                                            val has4K = if (selectedSource == Source.MAGNET) {
                                                !link.magnet4K.isNullOrBlank() || !link.torrentUrl4K.isNullOrBlank()
                                            } else {
                                                !link.url4K.isNullOrBlank()
                                            }
                                            val hasVR = if (selectedSource == Source.MAGNET) {
                                                !link.magnetVR.isNullOrBlank() || !link.torrentUrlVR.isNullOrBlank()
                                            } else {
                                                !link.urlVR.isNullOrBlank()
                                            }

                                            QualitySelectMenu(
                                                hasHD = hasHD,
                                                has4K = has4K,
                                                hasVR = hasVR,
                                                onSelectHD = {
                                                    onDismissActive()
                                                    if (selectedSource == Source.MAGNET) {
                                                        val targetUri = link.magnet.takeIf { !it.isNullOrBlank() } ?: link.torrentUrlHD
                                                        handleMagnet(targetUri)
                                                    } else {
                                                        handleUrlSelection(link.urlHD)
                                                    }
                                                },
                                                onSelect4K = {
                                                    onDismissActive()
                                                    if (selectedSource == Source.MAGNET) {
                                                        val targetUri = link.magnet4K.takeIf { !it.isNullOrBlank() } ?: link.torrentUrl4K
                                                        handleMagnet(targetUri)
                                                    } else {
                                                        handleUrlSelection(link.url4K)
                                                    }
                                                },
                                                onSelectVR = {
                                                    onDismissActive()
                                                    if (selectedSource == Source.MAGNET) {
                                                        val targetUri = link.magnetVR.takeIf { !it.isNullOrBlank() } ?: link.torrentUrlVR
                                                        handleMagnet(targetUri)
                                                    } else {
                                                        handleUrlSelection(link.urlVR)
                                                    }
                                                }
                                            )
                                        }
                                        CardActionMenuState.DELETE_CONFIRM -> {
                                            DeleteConfirmMenu(
                                                onCancel = {
                                                    subMenuState = CardActionMenuState.MAIN_MENU
                                                },
                                                onConfirm = {
                                                    onDismissActive()
                                                    onDelete()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Inline Video Player Layer (smoothly crossfades over the morphing cover)
            if (inlinePlayback != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = inlinePlayerAlpha }
                ) {
                    InlineCardPlayer(
                        title = inlinePlayback.title,
                        qualities = inlinePlayback.qualities,
                        subtitles = inlinePlayback.subtitles,
                        defaultHeaders = inlinePlayback.headers,
                        initialPositionMs = inlinePlayback.initialPositionMs,
                        exoPlayer = exoPlayer,
                        enableGestures = enableVideoPlayerGestures,
                        onClose = onCloseInlineVideo,
                        onFullscreen = onFullscreenInlineVideo,
                        onFullscreenWithMode = onFullscreenInlineVideoWithMode,
                        onEnterPip = onEnterPipInlineVideo,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Inline Resolution & Progress Overlay (solid surface, cover is completely hidden)
            if (isResolvingThisCard && resolvingStatus != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.surface)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AppLottieLoadingAnimation(
                        modifier = Modifier
                            .size(70.dp)
                            .align(Alignment.Center)
                    )
                    if (!resolvingStatus.isNullOrBlank()) {
                        Text(
                            text = resolvingStatus,
                            color = palette.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
                        )
                    }
                }
            } else if (isResolvingThisCard && resolutionError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.surface)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Resolution Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = resolutionError,
                            color = palette.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Button(
                            onClick = onDismissResolutionError,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text("OK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. Metadata Container
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = palette.surface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Row 1: Top-Left (Actor) | Top-Right (Studio)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (mainActor == null) {
                            Text(
                                text = "Scene",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.15.sp
                                ),
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        } else {
                            Row(
                                modifier = Modifier.weight(1f, fill = false),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = mainActor.name,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.15.sp
                                    ),
                                    color = accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clickable {
                                            debouncedClick {
                                                subMenuState = null
                                                onDismissActive()
                                                onActorClick(mainActor.realId)
                                            }
                                        }
                                        .padding(horizontal = 2.dp, vertical = 2.dp)
                                        .weight(1f, fill = false)
                                )

                                if (link.actorIds.size > 1) {
                                    Spacer(modifier = Modifier.width(4.dp))

                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                debouncedClick {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    if (isOverlayActive && isActorsOverlayMode) {
                                                        subMenuState = null
                                                        onDismissActive()
                                                    } else {
                                                        subMenuState = CardActionMenuState.ACTORS_MENU
                                                        onActivate()
                                                    }
                                                }
                                            }
                                            .testTag("more_actors_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_user_group),
                                            contentDescription = "More Actors",
                                            tint = accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        val firstStudioId = link.studioIds.firstOrNull()
                        if (studioName.isNotEmpty()) {
                            Text(
                                text = studioName,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Normal,
                                    letterSpacing = 0.2.sp
                                ),
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clickable(enabled = firstStudioId != null) {
                                        debouncedClick {
                                            if (firstStudioId != null) {
                                                subMenuState = null
                                                onDismissActive()
                                                onStudioClick(firstStudioId)
                                            }
                                        }
                                    }
                                    .padding(horizontal = 2.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Row 2: Bottom-Left (Title) | Bottom-Right (Date)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = link.title,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Normal,
                                lineHeight = 20.sp
                            ),
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (displayDate.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = displayDate,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    letterSpacing = 0.25.sp
                                ),
                                color = palette.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
