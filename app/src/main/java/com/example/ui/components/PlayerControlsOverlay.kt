package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.R
import com.example.network.StreamQuality
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

enum class SubtitleEdgeStyle(val label: String, val media3EdgeType: Int) {
    OUTLINE("Outline", androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE),
    DROP_SHADOW("Shadow", androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW),
    RAISED("Raised", androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_RAISED),
    DEPRESSED("Depressed", androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DEPRESSED),
    NONE("None", androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE)
}

enum class SubtitleFontFamily(val label: String) {
    LEXEND("Lexend"),
    SANS_SERIF("Sans"),
    SERIF("Serif"),
    MONOSPACE("Mono")
}

fun formatQualityDisplayLabel(raw: String): String {
    val lower = raw.lowercase().trim()
    val is48fps = lower.contains("48fps") || lower.contains("1080i")
    val isMp4 = lower.contains("mp4")
    return when {
        lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> "2160p (4K)"
        lower.contains("1440") || lower.contains("2k") || lower.contains("qhd") -> "1440p"
        is48fps -> "1080p 48fps"
        lower.contains("1080") || lower.contains("fhd") || lower == "hd" -> if (isMp4) "1080p (MP4)" else "1080p"
        lower.contains("720") -> if (isMp4) "720p (MP4)" else "720p"
        lower.contains("480") -> "480p"
        lower.contains("360") && !lower.contains("vr") -> "360p"
        else -> {
            val digits = raw.filter { it.isDigit() }
            if (digits.isNotEmpty()) "${digits}p" else raw
        }
    }
}

enum class PlayerPanelMenu {
    MAIN,
    QUALITY,
    CAPTIONS
}

@Composable
fun PlayerControlsOverlay(
    visible: Boolean,
    isFullscreen: Boolean,
    isLandscape: Boolean,
    title: String,
    isPlaying: Boolean,
    currentPos: Long,
    bufferedPos: Long,
    duration: Long,
    isBuffering: Boolean = false,
    is4kOrHdr: Boolean = false,
    isVrMode: Boolean = false,
    vrUseGyro: Boolean = true,
    vrStereoMode: Int = androidx.media3.common.C.STEREO_MODE_MONO,
    qualities: List<StreamQuality> = emptyList(),
    selectedQualityIndex: Int = 0,
    onSelectQuality: ((Int) -> Unit)? = null,
    subtitles: List<com.example.network.SubtitleTrack> = emptyList(),
    selectedSubtitleIndex: Int = -1,
    onSelectSubtitle: ((Int) -> Unit)? = null,
    subtitleScale: Float = 1.0f,
    onSubtitleScaleChange: ((Float) -> Unit)? = null,
    subtitleBottomPadding: Float = 0.08f,
    onSubtitleBottomPaddingChange: ((Float) -> Unit)? = null,
    subtitleColorFraction: Float = 0.0f,
    onSubtitleColorFractionChange: ((Float) -> Unit)? = null,
    subtitleBgOpacity: Float = 0.0f,
    onSubtitleBgOpacityChange: ((Float) -> Unit)? = null,
    subtitleEdgeStyle: SubtitleEdgeStyle = SubtitleEdgeStyle.OUTLINE,
    onSubtitleEdgeStyleChange: ((SubtitleEdgeStyle) -> Unit)? = null,
    subtitleFontFamily: SubtitleFontFamily = SubtitleFontFamily.LEXEND,
    onSubtitleFontFamilyChange: ((SubtitleFontFamily) -> Unit)? = null,
    onToggleVrMode: (() -> Unit)? = null,
    onToggleVrGyro: (() -> Unit)? = null,
    onRecenterVr: (() -> Unit)? = null,
    onToggleVrStereoMode: (() -> Unit)? = null,
    onBack: () -> Unit,
    onRewind10s: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onForward10s: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenExternal: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    onSeekLive: (targetMs: Long) -> Unit,
    onSeekFinal: (targetMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val isLight = palette.name.equals("light", ignoreCase = true)

    var showSettingsPanel by remember { mutableStateOf(false) }
    var currentPanelMenu by remember { mutableStateOf(PlayerPanelMenu.MAIN) }
    var showCaptionSettings by remember { mutableStateOf(false) }

    // Ensure settings slide panel closes when entering VR mode
    androidx.compose.runtime.LaunchedEffect(isVrMode) {
        if (isVrMode) {
            showSettingsPanel = false
            showCaptionSettings = false
        }
    }

    // Intercept back navigation when slide panel is open
    BackHandler(enabled = showSettingsPanel) {
        if (currentPanelMenu == PlayerPanelMenu.CAPTIONS && showCaptionSettings) {
            showCaptionSettings = false
        } else if (currentPanelMenu != PlayerPanelMenu.MAIN) {
            currentPanelMenu = PlayerPanelMenu.MAIN
            showCaptionSettings = false
        } else {
            showSettingsPanel = false
        }
    }

    val cleanQualities = remember(qualities) {
        val rawList = if (qualities.isNotEmpty()) qualities else listOf(StreamQuality(quality = "1080p", url = ""))
        val mapped = rawList.mapIndexed { idx, q ->
            idx to formatQualityDisplayLabel(q.quality)
        }
        val seen = mutableSetOf<String>()
        mapped.filter { seen.add(it.second) }
    }

    AnimatedVisibility(
        visible = visible || showSettingsPanel,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(180)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = if (isVrMode) 0.35f else 0.72f),
                            Color.Transparent,
                            Color.Black.copy(alpha = if (isVrMode) 0.65f else 0.85f)
                        )
                    )
                )
        ) {
            // Dismiss scrim when slide panel is open: clicking on empty video space dismisses the slide panel
            if (showSettingsPanel) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(15f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showSettingsPanel = false
                            showCaptionSettings = false
                        }
                )
            }

            // Top Bar: Title, HDR pill, and Back Button
            if (isFullscreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 840.dp)
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 36.dp, vertical = 12.dp)
                            .align(Alignment.TopCenter),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                text = title,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    shadow = Shadow(
                                        color = Color.Black.copy(alpha = 0.65f),
                                        offset = Offset(0f, 2f),
                                        blurRadius = 4f
                                    )
                                ),
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (is4kOrHdr) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFFF59E0B).copy(alpha = 0.25f),
                                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.75f))
                                ) {
                                    Text(
                                        text = "4K HDR",
                                        color = Color(0xFFFDE68A),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // Layer-free Back Arrow with soft shadow, aligned right with timeline scrubber edge
                        ShadowedBackArrowButton(
                            onClick = onBack,
                            testTag = "back_player_button",
                            isFullscreen = true,
                            contentAlignment = Alignment.CenterEnd
                        )
                    }
                } else {
                    ShadowedBackArrowButton(
                        onClick = onBack,
                        testTag = "back_inline_player",
                        isFullscreen = false,
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                    )
                }

            // Center 3-Button Controls (10s Rewind, Play/Pause, 10s Forward) - Hidden during Buffering
            AnimatedVisibility(
                visible = !isBuffering,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                val centerIconSize = if (isFullscreen) 52.dp else 42.dp
                val centerGap = if (isFullscreen) 40.dp else 18.dp

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(centerGap)
                ) {
                    IconButton(
                        onClick = onRewind10s,
                        modifier = Modifier.size(centerIconSize)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_player_rewind),
                            contentDescription = "Rewind 10s",
                            tint = Color.White,
                            modifier = Modifier.size(if (isFullscreen) 36.dp else 28.dp)
                        )
                    }

                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(centerIconSize)
                            .testTag(if (isFullscreen) "play_pause_button" else "inline_play_pause_button")
                    ) {
                        Icon(
                            painter = painterResource(id = if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play),
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(if (isFullscreen) 38.dp else 30.dp)
                        )
                    }

                    IconButton(
                        onClick = onForward10s,
                        modifier = Modifier.size(centerIconSize)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_player_forward),
                            contentDescription = "Forward 10s",
                            tint = Color.White,
                            modifier = Modifier.size(if (isFullscreen) 36.dp else 28.dp)
                        )
                    }
                }
            }

            // Bottom Timeline & Actions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 840.dp)
                    .then(if (isFullscreen) Modifier.navigationBarsPadding() else Modifier)
                    .padding(
                        horizontal = if (isFullscreen) 36.dp else 16.dp,
                        vertical = if (isFullscreen) 8.dp else 6.dp
                    )
                    .align(Alignment.BottomCenter)
            ) {
                // 1. Scrubber Bar at the top of bottom controls
                PlayerTimelineScrubber(
                    currentPos = currentPos,
                    bufferedPos = bufferedPos,
                    duration = duration,
                    isFullscreen = isFullscreen,
                    onScrubbingChanged = onScrubbingChanged,
                    onSeekLive = onSeekLive,
                    onSeekFinal = onSeekFinal,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(if (isFullscreen) 4.dp else 2.dp))

                // 2. Video Duration on the LEFT and Action buttons on the RIGHT
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Video Duration on the LEFT
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatPlayerDuration(currentPos),
                            color = Color.White,
                            fontSize = if (isFullscreen) 13.sp else 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = " / ${formatPlayerDuration(duration)}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = if (isFullscreen) 13.sp else 12.sp
                        )
                    }

                    // Actions on the RIGHT
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (isFullscreen) 8.dp else 6.dp)
                    ) {
                        if (isVrMode) {
                            // Gyroscope toggle
                            if (onToggleVrGyro != null) {
                                PlayerOverlayActionButton(
                                    onClick = onToggleVrGyro,
                                    iconRes = R.drawable.ic_vr_gyro,
                                    contentDescription = if (vrUseGyro) "Gyroscope Motion Active" else "Touch Pan Only",
                                    testTag = "vr_gyro_toggle_button",
                                    isFullscreen = isFullscreen,
                                    isActive = vrUseGyro,
                                    activeColor = accent
                                )
                            }

                            // Recenter Scene
                            if (onRecenterVr != null) {
                                PlayerOverlayActionButton(
                                    onClick = onRecenterVr,
                                    iconRes = R.drawable.ic_vr_recenter,
                                    contentDescription = "Recenter Scene View",
                                    testTag = "vr_recenter_button",
                                    isFullscreen = isFullscreen,
                                    isActive = false,
                                    activeColor = accent
                                )
                            }

                            // Exit VR
                            if (onToggleVrMode != null) {
                                PlayerOverlayActionButton(
                                    onClick = onToggleVrMode,
                                    iconRes = R.drawable.ic_player_vr,
                                    contentDescription = "Exit VR Mode",
                                    testTag = "vr_player_button",
                                    isFullscreen = isFullscreen,
                                    isActive = true,
                                    activeColor = accent
                                )
                            }

                            PlayerOverlayActionButton(
                                onClick = onToggleFullscreen,
                                iconRes = if (isFullscreen) {
                                    if (isLandscape) R.drawable.ic_player_fullscreen_exit else R.drawable.ic_player_fullscreen
                                } else {
                                    R.drawable.ic_player_fullscreen
                                },
                                contentDescription = "Fullscreen Mode",
                                testTag = if (isFullscreen) "fullscreen_toggle_button" else "fullscreen_inline_button",
                                isFullscreen = isFullscreen,
                                isActive = isFullscreen,
                                activeColor = accent
                            )
                        } else {
                            // Standard Mode Actions (Ordered Right-to-Left: Fullscreen -> PiP -> External Player -> Setting -> VR)
                            if (isFullscreen && onToggleVrMode != null) {
                                PlayerOverlayActionButton(
                                    onClick = onToggleVrMode,
                                    iconRes = R.drawable.ic_player_vr,
                                    contentDescription = "VR 180° Magic Window",
                                    testTag = "vr_player_button",
                                    isFullscreen = isFullscreen,
                                    isActive = false,
                                    activeColor = accent
                                )
                            }

                            // 4th from right: Settings Button toggling Left Slide Panel
                            PlayerOverlayActionButton(
                                onClick = {
                                    if (!showSettingsPanel) {
                                        currentPanelMenu = PlayerPanelMenu.MAIN
                                    }
                                    showCaptionSettings = false
                                    showSettingsPanel = !showSettingsPanel
                                },
                                iconRes = R.drawable.ic_player_settings,
                                contentDescription = "Settings",
                                testTag = if (isFullscreen) "settings_player_button" else "settings_inline_button",
                                isFullscreen = isFullscreen,
                                isActive = showSettingsPanel,
                                activeColor = accent
                            )

                            // 3rd from right: Open by other player (External Player Button)
                            PlayerOverlayActionButton(
                                onClick = onOpenExternal,
                                iconRes = R.drawable.ic_player_external,
                                contentDescription = "Open External",
                                testTag = if (isFullscreen) "open_external_player_button" else "external_player_inline_button",
                                isFullscreen = isFullscreen,
                                isActive = false,
                                activeColor = accent
                            )

                            // 2nd from right: PiP Window Button
                            PlayerOverlayActionButton(
                                onClick = onEnterPip,
                                iconRes = R.drawable.ic_player_pip,
                                contentDescription = "Picture in Picture",
                                testTag = if (isFullscreen) "popup_player_button" else "popup_inline_button",
                                isFullscreen = isFullscreen,
                                isActive = false,
                                activeColor = accent
                            )

                            // 1st from right (Rightmost): Fullscreen / Rotate Button
                            PlayerOverlayActionButton(
                                onClick = onToggleFullscreen,
                                iconRes = if (isFullscreen) {
                                    if (isLandscape) R.drawable.ic_player_fullscreen_exit else R.drawable.ic_player_fullscreen
                                } else {
                                    R.drawable.ic_player_fullscreen
                                },
                                contentDescription = "Fullscreen Mode",
                                testTag = if (isFullscreen) "fullscreen_toggle_button" else "fullscreen_inline_button",
                                isFullscreen = isFullscreen,
                                isActive = isFullscreen,
                                activeColor = accent
                            )
                        }
                    }
                }
            }

            // Native Left Slide Panel for Settings & Quality (Theme-aware, borderless, fixed-width, seamless bounce)
            AnimatedVisibility(
                visible = showSettingsPanel,
                enter = slideInHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) { -it } + fadeIn(tween(180)),
                exit = slideOutHorizontally(
                    animationSpec = tween(180, easing = FastOutLinearInEasing)
                ) { -it } + fadeOut(tween(140)),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .zIndex(20f)
            ) {
                val fixedPanelWidth = if (isFullscreen) 268.dp else 220.dp
                val panelBgColor = palette.bg
                val itemSurfaceColor = if (isLight) {
                    palette.textPrimary.copy(alpha = 0.06f)
                } else {
                    palette.cardBg.copy(alpha = 0.75f)
                }
                val itemSubtleSurfaceColor = if (isLight) {
                    palette.textPrimary.copy(alpha = 0.05f)
                } else {
                    palette.cardBg.copy(alpha = 0.55f)
                }

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(fixedPanelWidth)
                        .drawBehind {
                            // Seamless left-skirt extension so bouncy spring overshoot never exposes a gap on the left edge
                            val bleedPx = 72.dp.toPx()
                            drawRect(
                                color = panelBgColor,
                                topLeft = Offset(-bleedPx, 0f),
                                size = Size(bleedPx + 4.dp.toPx(), size.height)
                            )
                        }
                ) {
                    Surface(
                        shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                        color = panelBgColor,
                        shadowElevation = 16.dp,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = if (isFullscreen) 16.dp else 12.dp,
                                    end = if (isFullscreen) 16.dp else 12.dp,
                                    top = if (isFullscreen) 18.dp else 14.dp,
                                    bottom = if (isFullscreen) 18.dp else 14.dp
                                )
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AnimatedContent(
                                targetState = currentPanelMenu,
                                transitionSpec = {
                                    val isForward = targetState != PlayerPanelMenu.MAIN
                                    (slideInHorizontally(
                                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                                    ) { if (isForward) it / 6 else -it / 6 } + fadeIn(
                                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                                    )).togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(160, easing = FastOutSlowInEasing)
                                        ) { if (isForward) -it / 6 else it / 6 } + fadeOut(
                                            animationSpec = tween(140, easing = FastOutSlowInEasing)
                                        )
                                    ).using(SizeTransform(clip = false))
                                },
                                label = "SettingsSlidePanelContent"
                            ) { menu ->
                                when (menu) {
                                    PlayerPanelMenu.MAIN -> {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            // Category 1: Quality option
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(CircleShape)
                                                    .background(itemSurfaceColor)
                                                    .clickable {
                                                        currentPanelMenu = PlayerPanelMenu.QUALITY
                                                    }
                                                    .padding(
                                                        horizontal = 14.dp,
                                                        vertical = if (isFullscreen) 14.dp else 11.dp
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Icon(
                                                        painter = painterResource(id = R.drawable.ic_settings_display),
                                                        contentDescription = null,
                                                        tint = accent,
                                                        modifier = Modifier.size(if (isFullscreen) 20.dp else 18.dp)
                                                    )
                                                    Text(
                                                        text = "Quality",
                                                        color = palette.textPrimary,
                                                        fontSize = if (isFullscreen) 14.5.sp else 13.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }

                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    val currentLabel = cleanQualities.find { it.first == selectedQualityIndex }?.second ?: "Auto"
                                                    Text(
                                                        text = currentLabel,
                                                        color = accent,
                                                        fontSize = if (isFullscreen) 12.5.sp else 11.5.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )

                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                                        contentDescription = "Open Quality Options",
                                                        tint = palette.textSecondary,
                                                        modifier = Modifier.size(if (isFullscreen) 13.dp else 11.dp)
                                                    )
                                                }
                                            }

                                            // Category 2: Captions option
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(CircleShape)
                                                    .background(itemSurfaceColor)
                                                    .clickable {
                                                        currentPanelMenu = PlayerPanelMenu.CAPTIONS
                                                    }
                                                    .padding(
                                                        horizontal = 14.dp,
                                                        vertical = if (isFullscreen) 14.dp else 11.dp
                                                    ),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Icon(
                                                        painter = painterResource(id = R.drawable.ic_player_captions),
                                                        contentDescription = null,
                                                        tint = accent,
                                                        modifier = Modifier.size(if (isFullscreen) 20.dp else 18.dp)
                                                    )
                                                    Text(
                                                        text = "Captions",
                                                        color = palette.textPrimary,
                                                        fontSize = if (isFullscreen) 14.5.sp else 13.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }

                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    val currentSubLabel = if (selectedSubtitleIndex >= 0 && selectedSubtitleIndex < subtitles.size) {
                                                        subtitles[selectedSubtitleIndex].label
                                                    } else {
                                                        "Off"
                                                    }
                                                    Text(
                                                        text = currentSubLabel,
                                                        color = accent,
                                                        fontSize = if (isFullscreen) 12.5.sp else 11.5.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )

                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                                        contentDescription = "Open Captions Options",
                                                        tint = palette.textSecondary,
                                                        modifier = Modifier.size(if (isFullscreen) 13.dp else 11.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    PlayerPanelMenu.QUALITY -> {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            // Back navigation header to return to main options
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(CircleShape)
                                                    .clickable { currentPanelMenu = PlayerPanelMenu.MAIN }
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                    contentDescription = "Back",
                                                    tint = palette.textPrimary,
                                                    modifier = Modifier.size(if (isFullscreen) 18.dp else 16.dp)
                                                )
                                                Text(
                                                    text = "Quality",
                                                    color = palette.textPrimary,
                                                    fontSize = if (isFullscreen) 14.5.sp else 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            // Quality Options List
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                cleanQualities.forEach { (originalIdx, label) ->
                                                    val isSelected = originalIdx == selectedQualityIndex
                                                    val rowBg by animateColorAsState(
                                                        targetValue = if (isSelected) accent.copy(alpha = if (isLight) 0.16f else 0.22f) else itemSubtleSurfaceColor,
                                                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                                                        label = "quality_row_bg"
                                                    )
                                                    val rowTextColor by animateColorAsState(
                                                        targetValue = if (isSelected) accent else palette.textPrimary,
                                                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                                                        label = "quality_row_text"
                                                    )
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(CircleShape)
                                                            .background(rowBg)
                                                            .clickable {
                                                                onSelectQuality?.invoke(originalIdx)
                                                                showSettingsPanel = false
                                                            }
                                                            .padding(horizontal = 16.dp, vertical = if (isFullscreen) 11.dp else 9.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = label,
                                                            color = rowTextColor,
                                                            fontSize = if (isFullscreen) 13.5.sp else 12.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                        )

                                                        if (isSelected) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = "Selected",
                                                                tint = accent,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    PlayerPanelMenu.CAPTIONS -> {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            // Header row with Back button on the left and Reset + Settings buttons on the right
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .clip(CircleShape)
                                                        .clickable {
                                                            if (showCaptionSettings) {
                                                                showCaptionSettings = false
                                                            } else {
                                                                currentPanelMenu = PlayerPanelMenu.MAIN
                                                            }
                                                        }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                        contentDescription = "Back",
                                                        tint = palette.textPrimary,
                                                        modifier = Modifier.size(if (isFullscreen) 18.dp else 16.dp)
                                                    )
                                                    Text(
                                                        text = if (showCaptionSettings) "Caption Style" else "Captions",
                                                        color = palette.textPrimary,
                                                        fontSize = if (isFullscreen) 14.5.sp else 13.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }

                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    if (showCaptionSettings) {
                                                        Row(
                                                            modifier = Modifier
                                                                .clip(CircleShape)
                                                                .background(accent.copy(alpha = 0.14f))
                                                                .border(1.dp, accent.copy(alpha = 0.35f), CircleShape)
                                                                .clickable {
                                                                    onSubtitleScaleChange?.invoke(1.0f)
                                                                    onSubtitleBottomPaddingChange?.invoke(0.08f)
                                                                    onSubtitleColorFractionChange?.invoke(0.0f)
                                                                    onSubtitleBgOpacityChange?.invoke(0.0f)
                                                                    onSubtitleEdgeStyleChange?.invoke(SubtitleEdgeStyle.OUTLINE)
                                                                    onSubtitleFontFamilyChange?.invoke(SubtitleFontFamily.LEXEND)
                                                                }
                                                                .padding(horizontal = 8.dp, vertical = 5.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Icon(
                                                                painter = painterResource(id = R.drawable.ic_action_reset),
                                                                contentDescription = "Reset",
                                                                tint = accent,
                                                                modifier = Modifier.size(11.dp)
                                                            )
                                                            Text(
                                                                text = "Reset",
                                                                color = accent,
                                                                fontSize = 10.5.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }

                                                    // Settings button in front of Captions title
                                                    Box(
                                                        modifier = Modifier
                                                            .size(if (isFullscreen) 32.dp else 28.dp)
                                                            .clip(CircleShape)
                                                            .background(
                                                                if (showCaptionSettings) accent.copy(alpha = 0.22f)
                                                                else itemSurfaceColor
                                                            )
                                                            .border(
                                                                width = 1.dp,
                                                                color = if (showCaptionSettings) accent.copy(alpha = 0.6f)
                                                                else palette.border,
                                                                shape = CircleShape
                                                            )
                                                            .clickable { showCaptionSettings = !showCaptionSettings }
                                                            .testTag("caption_settings_button"),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(id = R.drawable.ic_player_settings),
                                                            contentDescription = "Caption Settings",
                                                            tint = if (showCaptionSettings) accent else palette.textPrimary,
                                                            modifier = Modifier.size(if (isFullscreen) 16.dp else 14.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // Soft, simple, smooth transition between Caption Track List and Caption Style
                                            AnimatedContent(
                                                targetState = showCaptionSettings,
                                                transitionSpec = {
                                                    (slideInHorizontally(
                                                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                                                    ) { if (targetState) it / 6 else -it / 6 } + fadeIn(
                                                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                                                    )).togetherWith(
                                                        slideOutHorizontally(
                                                            animationSpec = tween(160, easing = FastOutSlowInEasing)
                                                        ) { if (targetState) -it / 6 else it / 6 } + fadeOut(
                                                            animationSpec = tween(140, easing = FastOutSlowInEasing)
                                                        )
                                                    ).using(SizeTransform(clip = false))
                                                },
                                                label = "CaptionSubViewTransition"
                                            ) { isCustomizing ->
                                                if (isCustomizing) {
                                                    val activeSpectrumColor = remember(subtitleColorFraction) {
                                                        sampleSubtitleSpectrumColor(subtitleColorFraction)
                                                    }
                                                    val activeColorLabel = remember(subtitleColorFraction) {
                                                        getSubtitleSpectrumColorLabel(subtitleColorFraction)
                                                    }
                                                    val lexendFontFamily = remember { FontFamily(Font(R.font.lexend)) }

                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(16.dp))
                                                            .background(itemSubtleSurfaceColor)
                                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        // 1. Multi-Color Spectrum Slider (Text Color)
                                                        Column(modifier = Modifier.fillMaxWidth()) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                Text(
                                                                    text = "Text Color",
                                                                    color = palette.textPrimary,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                                ) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .size(10.dp)
                                                                            .clip(CircleShape)
                                                                            .background(activeSpectrumColor)
                                                                            .border(1.dp, palette.textPrimary.copy(alpha = 0.5f), CircleShape)
                                                                    )
                                                                    Text(
                                                                        text = activeColorLabel,
                                                                        color = if (isLight && subtitleColorFraction < 0.08f) palette.textPrimary else activeSpectrumColor,
                                                                        fontSize = 11.5.sp,
                                                                        fontWeight = FontWeight.Bold
                                                                    )
                                                                }
                                                            }
                                                            MultiColorSpectrumSlider(
                                                                value = subtitleColorFraction,
                                                                onValueChange = { onSubtitleColorFractionChange?.invoke(it) },
                                                                trackHeight = 6.dp,
                                                                thumbDiameter = 15.dp,
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .height(28.dp)
                                                            )
                                                        }

                                                        // 2. Caption Size (50% to 200%)
                                                        Column(modifier = Modifier.fillMaxWidth()) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                Text(
                                                                    text = "Size",
                                                                    color = palette.textPrimary,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                                Text(
                                                                    text = "${(subtitleScale * 100).toInt()}%",
                                                                    color = accent,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                            SleekSlimSlider(
                                                                value = subtitleScale,
                                                                onValueChange = { onSubtitleScaleChange?.invoke(it) },
                                                                valueRange = 0.5f..2.0f,
                                                                trackHeight = 4.dp,
                                                                thumbDiameter = 14.dp,
                                                                activeColor = accent,
                                                                inactiveColor = palette.textSecondary.copy(alpha = 0.25f),
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .height(28.dp)
                                                            )
                                                        }

                                                        // 3. Vertical Position (0% to 45%)
                                                        Column(modifier = Modifier.fillMaxWidth()) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                Text(
                                                                    text = "Vertical Position",
                                                                    color = palette.textPrimary,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                                Text(
                                                                    text = "${(subtitleBottomPadding * 100).toInt()}%",
                                                                    color = accent,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                            SleekSlimSlider(
                                                                value = subtitleBottomPadding,
                                                                onValueChange = { onSubtitleBottomPaddingChange?.invoke(it) },
                                                                valueRange = 0.0f..0.45f,
                                                                trackHeight = 4.dp,
                                                                thumbDiameter = 14.dp,
                                                                activeColor = accent,
                                                                inactiveColor = palette.textSecondary.copy(alpha = 0.25f),
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .height(28.dp)
                                                            )
                                                        }

                                                        // 4. Background Opacity (0% to 100%)
                                                        Column(modifier = Modifier.fillMaxWidth()) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                Text(
                                                                    text = "Background Opacity",
                                                                    color = palette.textPrimary,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                                Text(
                                                                    text = "${(subtitleBgOpacity * 100).toInt()}%",
                                                                    color = accent,
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                            SleekSlimSlider(
                                                                value = subtitleBgOpacity,
                                                                onValueChange = { onSubtitleBgOpacityChange?.invoke(it) },
                                                                valueRange = 0.0f..1.0f,
                                                                trackHeight = 4.dp,
                                                                thumbDiameter = 14.dp,
                                                                activeColor = accent,
                                                                inactiveColor = palette.textSecondary.copy(alpha = 0.25f),
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .height(28.dp)
                                                            )
                                                        }

                                                        // 5. Edge Style / Border
                                                        Column(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            verticalArrangement = Arrangement.spacedBy(5.dp)
                                                        ) {
                                                            Text(
                                                                text = "Edge Style / Border",
                                                                color = palette.textPrimary,
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .horizontalScroll(rememberScrollState()),
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                SubtitleEdgeStyle.entries.forEach { edge ->
                                                                    val isSelected = edge == subtitleEdgeStyle
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .clip(CircleShape)
                                                                            .background(
                                                                                if (isSelected) accent.copy(alpha = if (isLight) 0.16f else 0.22f)
                                                                                else itemSurfaceColor
                                                                            )
                                                                            .border(
                                                                                width = 1.dp,
                                                                                color = if (isSelected) accent
                                                                                else palette.border,
                                                                                shape = CircleShape
                                                                            )
                                                                            .clickable { onSubtitleEdgeStyleChange?.invoke(edge) }
                                                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                                                        contentAlignment = Alignment.Center
                                                                    ) {
                                                                        Text(
                                                                            text = edge.label,
                                                                            color = if (isSelected) accent else palette.textPrimary.copy(alpha = 0.85f),
                                                                            fontSize = 11.sp,
                                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }

                                                        // 6. Font & Weight
                                                        Column(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            verticalArrangement = Arrangement.spacedBy(5.dp)
                                                        ) {
                                                            Text(
                                                                text = "Font & Weight",
                                                                color = palette.textPrimary,
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.Medium
                                                            )

                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .horizontalScroll(rememberScrollState()),
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                SubtitleFontFamily.entries.forEach { family ->
                                                                    val isSelected = family == subtitleFontFamily
                                                                    val chipFont = when (family) {
                                                                        SubtitleFontFamily.LEXEND -> lexendFontFamily
                                                                        SubtitleFontFamily.SANS_SERIF -> FontFamily.SansSerif
                                                                        SubtitleFontFamily.SERIF -> FontFamily.Serif
                                                                        SubtitleFontFamily.MONOSPACE -> FontFamily.Monospace
                                                                    }
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .clip(CircleShape)
                                                                            .background(
                                                                                if (isSelected) accent.copy(alpha = if (isLight) 0.16f else 0.22f)
                                                                                else itemSurfaceColor
                                                                            )
                                                                            .border(
                                                                                width = 1.dp,
                                                                                color = if (isSelected) accent
                                                                                else palette.border,
                                                                                shape = CircleShape
                                                                            )
                                                                            .clickable { onSubtitleFontFamilyChange?.invoke(family) }
                                                                            .padding(horizontal = 11.dp, vertical = 5.dp),
                                                                        contentAlignment = Alignment.Center
                                                                    ) {
                                                                        Text(
                                                                            text = family.label,
                                                                            color = if (isSelected) accent else palette.textPrimary.copy(alpha = 0.85f),
                                                                            fontSize = 11.sp,
                                                                            fontFamily = chipFont,
                                                                            fontWeight = FontWeight.Bold
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    // Captions Track Selection List
                                                    Column(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        // 1. Off option
                                                        val isOffSelected = selectedSubtitleIndex == -1
                                                        val offBg by animateColorAsState(
                                                            targetValue = if (isOffSelected) accent.copy(alpha = if (isLight) 0.16f else 0.22f) else itemSubtleSurfaceColor,
                                                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                                                            label = "sub_off_bg"
                                                        )
                                                        val offTextColor by animateColorAsState(
                                                            targetValue = if (isOffSelected) accent else palette.textPrimary,
                                                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                                                            label = "sub_off_text"
                                                        )
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clip(CircleShape)
                                                                .background(offBg)
                                                                .clickable {
                                                                    onSelectSubtitle?.invoke(-1)
                                                                    showSettingsPanel = false
                                                                }
                                                                .padding(horizontal = 16.dp, vertical = if (isFullscreen) 11.dp else 9.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(
                                                                text = "Off",
                                                                color = offTextColor,
                                                                fontSize = if (isFullscreen) 13.5.sp else 12.sp,
                                                                fontWeight = if (isOffSelected) FontWeight.Bold else FontWeight.Normal
                                                            )

                                                            if (isOffSelected) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Check,
                                                                    contentDescription = "Selected",
                                                                    tint = accent,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                        }

                                                        // 2. Available Subtitle Tracks
                                                        subtitles.forEachIndexed { idx, sub ->
                                                            val isSelected = idx == selectedSubtitleIndex
                                                            val subBg by animateColorAsState(
                                                                targetValue = if (isSelected) accent.copy(alpha = if (isLight) 0.16f else 0.22f) else itemSubtleSurfaceColor,
                                                                animationSpec = tween(180, easing = FastOutSlowInEasing),
                                                                label = "sub_item_bg"
                                                            )
                                                            val subTextColor by animateColorAsState(
                                                                targetValue = if (isSelected) accent else palette.textPrimary,
                                                                animationSpec = tween(180, easing = FastOutSlowInEasing),
                                                                label = "sub_item_text"
                                                            )
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clip(CircleShape)
                                                                    .background(subBg)
                                                                    .clickable {
                                                                        onSelectSubtitle?.invoke(idx)
                                                                        showSettingsPanel = false
                                                                    }
                                                                    .padding(horizontal = 16.dp, vertical = if (isFullscreen) 11.dp else 9.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceBetween
                                                            ) {
                                                                Text(
                                                                    text = sub.label,
                                                                    color = subTextColor,
                                                                    fontSize = if (isFullscreen) 13.5.sp else 12.sp,
                                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                                )

                                                                if (isSelected) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Check,
                                                                        contentDescription = "Selected",
                                                                        tint = accent,
                                                                        modifier = Modifier.size(16.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShadowedBackArrowButton(
    onClick: () -> Unit,
    testTag: String,
    isFullscreen: Boolean,
    contentAlignment: Alignment = Alignment.Center,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val iconSize = if (isFullscreen) 22.dp else 20.dp

    Box(
        modifier = modifier
            .size(if (isFullscreen) 36.dp else 34.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .testTag(testTag),
        contentAlignment = contentAlignment
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(id = R.drawable.ic_player_back),
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .size(iconSize)
                    .offset(x = 0.5.dp, y = 1.5.dp)
                    .blur(radius = 2.5.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            )
            Icon(
                painter = painterResource(id = R.drawable.ic_player_back),
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun PlayerOverlayActionButton(
    onClick: () -> Unit,
    iconRes: Int,
    contentDescription: String,
    testTag: String,
    isFullscreen: Boolean,
    isActive: Boolean = false,
    activeColor: Color = Color(0xFF38BDF8),
    modifier: Modifier = Modifier
) {
    val buttonSize = if (isFullscreen) 34.dp else 28.dp
    val iconSize = if (isFullscreen) 20.dp else 17.dp

    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = if (isActive) activeColor else Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}
