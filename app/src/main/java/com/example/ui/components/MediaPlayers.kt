package com.example.ui.components

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.Surface
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.network.StreamQuality
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PlayerMode {
    FULLSCREEN,
    INLINE_CARD
}

/**
 * Unified Modern High-Performance Media Player Engine
 */
@OptIn(UnstableApi::class)
@Composable
fun CoreMediaPlayer(
    mode: PlayerMode,
    title: String,
    qualities: List<StreamQuality>,
    subtitles: List<com.example.network.SubtitleTrack> = emptyList(),
    defaultHeaders: Map<String, String> = emptyMap(),
    initialPositionMs: Long = 0L,
    startInLandscape: Boolean = false,
    startInVrMode: Boolean = false,
    exoPlayer: ExoPlayer? = null,
    enableGestures: Boolean = true,
    lockInlinePortrait: Boolean = true,
    onClose: () -> Unit,
    onTerminateAll: (() -> Unit)? = null,
    onFullscreen: ((currentPositionMs: Long) -> Unit)? = null,
    onFullscreenWithMode: ((currentPositionMs: Long, startInLandscape: Boolean) -> Unit)? = null,
    onEnterPip: ((currentPositionMs: Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val isFullscreen = mode == PlayerMode.FULLSCREEN

    val fallbackPlayer = remember(context, exoPlayer == null) {
        if (exoPlayer == null) PlayerFactory.createPlayer(context) else null
    }
    val activeExoPlayer = exoPlayer ?: fallbackPlayer ?: remember(context) { PlayerFactory.createPlayer(context) }
    val playerSession = remember(activeExoPlayer) {
        PlayerSessionTracker.getOrCreate(activeExoPlayer)
    }

    DisposableEffect(fallbackPlayer) {
        onDispose { fallbackPlayer?.release() }
    }

    var activeQualityIndex by remember(qualities, activeExoPlayer) {
        val savedUrl = playerSession.selectedQualityUrl
        val savedIdx = if (!savedUrl.isNullOrBlank()) {
            qualities.indexOfFirst { it.url == savedUrl }
        } else -1
        val defaultIdx = qualities.indexOfFirst { it.isDefault }
        val resolvedIdx = when {
            savedIdx >= 0 -> savedIdx
            defaultIdx >= 0 -> defaultIdx
            else -> 0
        }
        playerSession.selectedQualityUrl = qualities.getOrNull(resolvedIdx)?.url
        mutableIntStateOf(resolvedIdx)
    }
    val primaryQuality = remember(qualities, activeQualityIndex) {
        qualities.getOrNull(activeQualityIndex) ?: qualities.firstOrNull()
    }

    var activeSubtitleIndex by remember(subtitles, activeExoPlayer) {
        val resolvedSubIdx = if (playerSession.hasUserSelectedSubtitle &&
            playerSession.selectedSubtitleIndex in -1 until subtitles.size
        ) {
            playerSession.selectedSubtitleIndex
        } else {
            if (subtitles.isNotEmpty()) 0 else -1
        }
        mutableIntStateOf(resolvedSubIdx)
    }

    var subtitleScale by remember(activeExoPlayer) { mutableFloatStateOf(playerSession.subtitleScale) }
    var subtitleBottomPadding by remember(activeExoPlayer) { mutableFloatStateOf(playerSession.subtitleBottomPadding) }
    var subtitleColorFraction by remember(activeExoPlayer) { mutableFloatStateOf(playerSession.subtitleColorFraction) }
    var subtitleBgOpacity by remember(activeExoPlayer) { mutableFloatStateOf(playerSession.subtitleBgOpacity) }
    var subtitleEdgeStyle by remember(activeExoPlayer) { mutableStateOf(playerSession.subtitleEdgeStyle) }
    var subtitleFontFamily by remember(activeExoPlayer) { mutableStateOf(playerSession.subtitleFontFamily) }

    val resolvedSubtitleTypeface = remember(context, subtitleFontFamily) {
        val baseTypeface = when (subtitleFontFamily) {
            SubtitleFontFamily.LEXEND -> androidx.core.content.res.ResourcesCompat.getFont(context, R.font.lexend)
                ?: android.graphics.Typeface.DEFAULT
            SubtitleFontFamily.SANS_SERIF -> android.graphics.Typeface.SANS_SERIF
            SubtitleFontFamily.SERIF -> android.graphics.Typeface.SERIF
            SubtitleFontFamily.MONOSPACE -> android.graphics.Typeface.MONOSPACE
        }
        android.graphics.Typeface.create(baseTypeface, android.graphics.Typeface.BOLD)
    }
    val resolvedCaptionStyle = remember(
        subtitleColorFraction,
        subtitleBgOpacity,
        subtitleEdgeStyle,
        resolvedSubtitleTypeface
    ) {
        val fgColor = sampleSubtitleSpectrumColor(subtitleColorFraction)
        val fgArgb = android.graphics.Color.argb(
            255,
            (fgColor.red * 255).toInt().coerceIn(0, 255),
            (fgColor.green * 255).toInt().coerceIn(0, 255),
            (fgColor.blue * 255).toInt().coerceIn(0, 255)
        )
        val bgAlpha = (subtitleBgOpacity * 255).toInt().coerceIn(0, 255)
        val bgArgb = android.graphics.Color.argb(bgAlpha, 0, 0, 0)
        androidx.media3.ui.CaptionStyleCompat(
            fgArgb,
            bgArgb,
            android.graphics.Color.TRANSPARENT,
            subtitleEdgeStyle.media3EdgeType,
            android.graphics.Color.BLACK,
            resolvedSubtitleTypeface
        )
    }

    LaunchedEffect(activeSubtitleIndex, activeExoPlayer) {
        if (activeSubtitleIndex == -1) {
            activeExoPlayer.trackSelectionParameters = activeExoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
        } else {
            val sub = subtitles.getOrNull(activeSubtitleIndex)
            val builder = activeExoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            if (sub != null && sub.language.isNotBlank()) {
                builder.setPreferredTextLanguage(sub.language)
            }
            activeExoPlayer.trackSelectionParameters = builder.build()
        }
    }

    var switchingQualityLabel by remember { mutableStateOf<String?>(null) }
    var switchingSubtitleLabel by remember { mutableStateOf<String?>(null) }

    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPos by remember {
        val existingPos = activeExoPlayer.currentPosition.coerceAtLeast(0L)
        mutableLongStateOf(if (initialPositionMs > 0L) initialPositionMs else existingPos)
    }
    var duration by remember {
        val d = activeExoPlayer.duration
        mutableLongStateOf(if (d > 0L && d != C.TIME_UNSET) d else 0L)
    }
    var bufferedPos by remember {
        mutableLongStateOf(activeExoPlayer.bufferedPosition.coerceAtLeast(0L))
    }
    var isScrubbing by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var errorDetails by remember { mutableStateOf<String?>(null) }
    var lastSeekTime by remember { mutableLongStateOf(0L) }

    var isGestureSeeking by remember { mutableStateOf(false) }

    val audioManager = remember(context) {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
    }
    val maxAudioVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    var gestureVolumePercent by remember { mutableStateOf<Int?>(null) }
    var gestureBrightnessPercent by remember { mutableStateOf<Int?>(null) }
    var volumeHideJob by remember { mutableStateOf<Job?>(null) }
    var brightnessHideJob by remember { mutableStateOf<Job?>(null) }

    var isInPipMode by remember {
        mutableStateOf(activity?.isInPictureInPictureMode == true)
    }

    var isVrMode by remember {
        val shouldStartVr = isFullscreen && (startInVrMode || primaryQuality?.quality?.contains("vr", ignoreCase = true) == true)
        mutableStateOf(shouldStartVr)
    }
    var vrUseGyro by remember { mutableStateOf(true) }
    var sphericalViewRef by remember { mutableStateOf<VrSceneSurfaceView?>(null) }

    if (isFullscreen) {
        LaunchedEffect(startInLandscape, isVrMode) {
            if (startInLandscape || isVrMode) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    var vrStereoMode by remember(primaryQuality?.url) {
        val url = primaryQuality?.url?.lowercase() ?: ""
        val titleLower = title.lowercase()
        val topBottomRegex = Regex("""\b(over-under|top-bottom|topbottom|half-ou|ou)\b""")
        val isTopBottom = topBottomRegex.containsMatchIn(url) || topBottomRegex.containsMatchIn(titleLower)
        mutableIntStateOf(
            if (isTopBottom) C.STEREO_MODE_TOP_BOTTOM else C.STEREO_MODE_LEFT_RIGHT
        )
    }

    LaunchedEffect(activeExoPlayer.videoFormat) {
        val formatStereo = activeExoPlayer.videoFormat?.stereoMode
        if (formatStereo == C.STEREO_MODE_TOP_BOTTOM || formatStereo == C.STEREO_MODE_LEFT_RIGHT) {
            vrStereoMode = formatStereo
        }
    }

    // Lifecycle binding for VrSceneSurfaceView (prevents gyroscope sensor & GL thread battery drain in background)
    DisposableEffect(activity, isVrMode, sphericalViewRef) {
        val compAct = activity as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (isVrMode) {
                when (event) {
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                        sphericalViewRef?.onPause()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        sphericalViewRef?.onResume()
                    }
                    else -> Unit
                }
            }
        }
        compAct?.lifecycle?.addObserver(observer)
        onDispose {
            compAct?.lifecycle?.removeObserver(observer)
            if (isVrMode) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            isInPipMode = info.isInPictureInPictureMode
            if (info.isInPictureInPictureMode) {
                showControls = false
            }
        }
        val compAct = activity as? ComponentActivity
        compAct?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            compAct?.removeOnPictureInPictureModeChangedListener(listener)
        }
    }

    val handleBackAction = {
        if (isVrMode && !startInVrMode) {
            sphericalViewRef?.onPause()
            sphericalViewRef?.clearPlayerSurface(activeExoPlayer)
            isVrMode = false
            showControls = true
        } else if (isVrMode) {
            sphericalViewRef?.onPause()
            sphericalViewRef?.clearPlayerSurface(activeExoPlayer)
            activity?.requestedOrientation = if (lockInlinePortrait) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            onClose()
        } else if (!isInPipMode) {
            if (isFullscreen) {
                activity?.requestedOrientation = if (lockInlinePortrait) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            onClose()
        }
    }

    if (isFullscreen || isVrMode) {
        BackHandler { handleBackAction() }
    }

    // Auto-hide controls
    LaunchedEffect(showControls, isPlaying, isScrubbing, isGestureSeeking) {
        if (showControls && isPlaying && !isScrubbing && !isGestureSeeking) {
            delay(4500)
            showControls = false
        }
    }

    var isRealHdrStream by remember { mutableStateOf(false) }

    // Platform effects (System bars, brightness, wide color gamut, portrait lock in inline card mode)
    ManageFullscreenWindowEffects(
        activity = activity,
        isFullscreen = isFullscreen,
        isRealHdrStream = isRealHdrStream,
        lockInlinePortrait = lockInlinePortrait
    )

    // Lifecycle binding (Preserving PiP playback)
    val lifecycleOwner = LocalLifecycleOwner.current
    BindPlayerLifecycle(lifecycleOwner, activeExoPlayer, activity)

    val activeHeaders = remember(primaryQuality, defaultHeaders) {
        val streamHeaders = primaryQuality?.headers ?: emptyMap()
        defaultHeaders + streamHeaders
    }

    var resolvedPlaybackUrl by remember(primaryQuality) {
        val sessionUrl = if (playerSession.lastRawUrl == primaryQuality?.url) playerSession.lastResolvedStreamUrl else null
        mutableStateOf(sessionUrl ?: primaryQuality?.url)
    }
    var resolvedPlaybackHeaders by remember(primaryQuality, defaultHeaders) {
        val sessionHdrs = if (playerSession.lastRawUrl == primaryQuality?.url && playerSession.lastResolvedHeaders.isNotEmpty()) {
            playerSession.lastResolvedHeaders
        } else {
            activeHeaders
        }
        mutableStateOf(sessionHdrs)
    }
    var retryTrigger by remember { mutableIntStateOf(0) }

    // Player state & format listener
    BindPlayerStateListener(
        player = activeExoPlayer,
        onStateChanged = { buffering, playing, dur ->
            isBuffering = buffering
            isPlaying = playing
            if (dur > 0) duration = dur
        },
        onFormatChanged = {
            isRealHdrStream = checkIsVideoRealHdr(activeExoPlayer)
        },
        onError = { title, details ->
            isBuffering = false
            if (activeQualityIndex < qualities.lastIndex &&
                (title.contains("Codec", ignoreCase = true) || title.contains("Decoder", ignoreCase = true) || title.contains("Limits", ignoreCase = true) || title.contains("Unsupported", ignoreCase = true))
            ) {
                val nextIdx = activeQualityIndex + 1
                val nextQuality = qualities[nextIdx]
                val currentQualityLabel = qualities.getOrNull(activeQualityIndex)?.quality ?: "Stream"
                Toast.makeText(context, "$currentQualityLabel exceeds hardware, trying ${nextQuality.quality}...", Toast.LENGTH_SHORT).show()
                activeQualityIndex = nextIdx
            } else {
                errorMessage = title
                errorDetails = details
            }
        },
        onClearError = {
            errorMessage = null
            errorDetails = null
        }
    )

    // Adaptive position tracking
    TrackPlayerPositions(
        player = activeExoPlayer,
        showControls = showControls,
        isScrubbing = isScrubbing,
        isGestureSeeking = isGestureSeeking,
        lastSeekTime = lastSeekTime,
        primaryQuality = primaryQuality,
        onTick = { dur, buf, pos ->
            if (dur > 0) duration = dur
            bufferedPos = buf
            if (pos >= 0) currentPos = pos
        }
    )

    // Media preparation
    PreparePlayerMediaSource(
        context = context,
        player = activeExoPlayer,
        primaryQuality = primaryQuality,
        subtitles = subtitles,
        activeHeaders = activeHeaders,
        initialPositionMs = initialPositionMs,
        retryTrigger = retryTrigger,
        episodeHint = title.takeIf { it.isNotBlank() && it != "Media Stream" },
        onStreamResolved = { url, hdrs ->
            resolvedPlaybackUrl = url
            resolvedPlaybackHeaders = hdrs
        },
        onBufferingChanged = { isBuffering = it },
        onDurationChanged = { duration = it },
        onPositionChanged = { currentPos = it },
        onError = { title, details ->
            isBuffering = false
            errorMessage = title
            errorDetails = details
        },
        onClearError = {
            errorMessage = null
            errorDetails = null
        }
    )

    val playerGesturesModifier = if (!isVrMode) {
        Modifier.playerTouchGestures(
            duration = duration,
            enableGestures = enableGestures,
            currentPositionProvider = { activeExoPlayer.currentPosition },
            audioManager = audioManager,
            maxAudioVolume = maxAudioVolume,
            activity = activity,
            isScrubbing = isScrubbing,
            onGestureSeekingChanged = { seeking ->
                isGestureSeeking = seeking
                activeExoPlayer.setSeekParameters(if (seeking) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
            },
            onSeekLive = { target ->
                currentPos = target
                lastSeekTime = System.currentTimeMillis()
            },
            onSeekFinal = { target ->
                currentPos = target
                lastSeekTime = System.currentTimeMillis()
                isBuffering = true
                activeExoPlayer.setSeekParameters(SeekParameters.EXACT)
                activeExoPlayer.seekTo(target)
            },
            onVolumeChanged = { percent ->
                if (percent != null) {
                    volumeHideJob?.cancel()
                    gestureVolumePercent = percent
                } else {
                    volumeHideJob?.cancel()
                    volumeHideJob = coroutineScope.launch {
                        delay(800)
                        gestureVolumePercent = null
                    }
                }
            },
            onBrightnessChanged = { percent ->
                if (percent != null) {
                    brightnessHideJob?.cancel()
                    gestureBrightnessPercent = percent
                } else {
                    brightnessHideJob?.cancel()
                    brightnessHideJob = coroutineScope.launch {
                        delay(800)
                        gestureBrightnessPercent = null
                    }
                }
            },
            onDoubleTap = { isForward ->
                if (isForward) {
                    val target = (activeExoPlayer.currentPosition + 10000).coerceAtMost(duration).coerceAtLeast(0L)
                    isBuffering = true
                    activeExoPlayer.seekTo(target)
                    currentPos = target
                } else {
                    val target = (activeExoPlayer.currentPosition - 10000).coerceAtLeast(0L)
                    isBuffering = true
                    activeExoPlayer.seekTo(target)
                    currentPos = target
                }
                lastSeekTime = System.currentTimeMillis()
            },
            onSingleTap = {
                showControls = !showControls
            }
        )
    } else {
        val density = LocalDensity.current
        val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
        val topNotificationSafeZonePx = remember(density, isLandscape, statusBarTopPx) {
            val minZonePx = with(density) { (if (isLandscape) 44.dp else 52.dp).toPx() }
            (statusBarTopPx + with(density) { 16.dp.toPx() }).coerceAtLeast(minZonePx)
        }
        val currentTopSafeZonePx by rememberUpdatedState(topNotificationSafeZonePx)
        val currentDuration by rememberUpdatedState(duration)
        val currentExoPlayer by rememberUpdatedState(activeExoPlayer)
        val currentSphericalView by rememberUpdatedState(sphericalViewRef)
        var vrSingleTapJob by remember { mutableStateOf<Job?>(null) }
        var lastVrTapTime by remember { mutableLongStateOf(0L) }
        var lastVrTapX by remember { mutableFloatStateOf(0f) }
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.isConsumed || isScrubbing || down.position.y <= currentTopSafeZonePx) return@awaitEachGesture
                val startPos = down.position
                var lastPos = startPos
                val startTime = System.currentTimeMillis()
                var hasDragged = false

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) {
                        val currentPointerPos = change.position
                        val dist = (currentPointerPos - startPos).getDistance()
                        if (dist > 10f) {
                            hasDragged = true
                            val dx = currentPointerPos.x - lastPos.x
                            val dy = currentPointerPos.y - lastPos.y
                            currentSphericalView?.panCameraByTouch(
                                dxPx = dx,
                                dyPx = dy,
                                viewportWidth = size.width.toFloat(),
                                viewportHeight = size.height.toFloat()
                            )
                            change.consume()
                        }
                        lastPos = currentPointerPos
                    } else {
                        val elapsed = System.currentTimeMillis() - startTime
                        val dist = (change.position - startPos).getDistance()
                        if (!hasDragged && dist < 16f && elapsed < 350L) {
                            val now = System.currentTimeMillis()
                            val isDoubleTap = (now - lastVrTapTime < 320L) && (kotlin.math.abs(startPos.x - lastVrTapX) < 140f)
                            if (isDoubleTap && startPos.x < size.width * 0.25f) {
                                vrSingleTapJob?.cancel()
                                vrSingleTapJob = null
                                // Double-tap left side in Magic Window -> Seek -10s
                                val dur = currentDuration
                                val player = currentExoPlayer
                                if (dur > 0 && dur != C.TIME_UNSET) {
                                    val target = (player.currentPosition - 10000).coerceAtLeast(0L)
                                    isBuffering = true
                                    player.seekTo(target)
                                    currentPos = target
                                } else {
                                    player.seekBack()
                                }
                                lastSeekTime = now
                                lastVrTapTime = 0L
                            } else if (isDoubleTap && startPos.x > size.width * 0.75f) {
                                vrSingleTapJob?.cancel()
                                vrSingleTapJob = null
                                // Double-tap right side in Magic Window -> Seek +10s
                                val dur = currentDuration
                                val player = currentExoPlayer
                                if (dur > 0 && dur != C.TIME_UNSET) {
                                    val target = (player.currentPosition + 10000).coerceAtMost(dur).coerceAtLeast(0L)
                                    isBuffering = true
                                    player.seekTo(target)
                                    currentPos = target
                                } else {
                                    player.seekForward()
                                }
                                lastSeekTime = now
                                lastVrTapTime = 0L
                            } else {
                                lastVrTapTime = now
                                lastVrTapX = startPos.x
                                vrSingleTapJob?.cancel()
                                vrSingleTapJob = coroutineScope.launch {
                                    delay(280)
                                    showControls = !showControls
                                }
                            }
                        }
                        break
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(playerGesturesModifier)
            .testTag(if (isFullscreen) "video_player_overlay" else "inline_video_player")
    ) {
        if (isVrMode) {
            // Half-Frame Scene Split 180° VR Magic Window with Scene-Middle Recentering & Boundary Clamping
            AndroidView(
                factory = { ctx ->
                    VrSceneSurfaceView(ctx).apply {
                        setStereoMode(vrStereoMode)
                        setUseSensorRotation(vrUseGyro)
                        setVideoSurfaceListener(object : VrSceneSurfaceView.VideoSurfaceListener {
                            override fun onVideoSurfaceCreated(surface: Surface) {
                                activeExoPlayer.setVideoSurface(surface)
                                if (activeExoPlayer.playbackState == androidx.media3.common.Player.STATE_READY) {
                                    activeExoPlayer.play()
                                }
                            }
                            override fun onVideoSurfaceDestroyed(surface: Surface) {
                                activeExoPlayer.clearVideoSurface(surface)
                            }
                        })
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        sphericalViewRef = this
                        onResume()
                    }
                },
                update = { vrView ->
                    sphericalViewRef = vrView
                    vrView.setStereoMode(vrStereoMode)
                    vrView.setUseSensorRotation(vrUseGyro)
                },
                onRelease = { vrView ->
                    vrView.onPause()
                    vrView.setVideoSurfaceListener(null)
                    vrView.clearPlayerSurface(activeExoPlayer)
                    sphericalViewRef = null
                },
                onReset = { vrView ->
                    vrView.onPause()
                    vrView.setVideoSurfaceListener(null)
                    vrView.clearPlayerSurface(activeExoPlayer)
                    sphericalViewRef = null
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Standard 2D Surface with Safe Lifecycle Detachment
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = activeExoPlayer
                        useController = false
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        subtitleView?.apply {
                            setApplyEmbeddedStyles(false)
                            setApplyEmbeddedFontSizes(false)
                            setStyle(resolvedCaptionStyle)
                            setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale)
                            setBottomPaddingFraction(subtitleBottomPadding)
                            setCues(sanitizeCuesForVerticalPosition(activeExoPlayer.currentCues.cues))
                        }
                        val cueSanitizerListener = object : androidx.media3.common.Player.Listener {
                            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                                subtitleView?.setCues(sanitizeCuesForVerticalPosition(cueGroup.cues))
                            }
                        }
                        activeExoPlayer.addListener(cueSanitizerListener)
                        tag = cueSanitizerListener
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { playerView ->
                    @Suppress("UNCHECKED_CAST")
                    val existingListener = playerView.tag as? androidx.media3.common.Player.Listener
                    if (playerView.player != activeExoPlayer) {
                        existingListener?.let { (playerView.player as? ExoPlayer)?.removeListener(it) }
                        playerView.player = activeExoPlayer
                        existingListener?.let {
                            activeExoPlayer.removeListener(it)
                            activeExoPlayer.addListener(it)
                        }
                    }
                    playerView.keepScreenOn = true
                    playerView.subtitleView?.apply {
                        setApplyEmbeddedStyles(false)
                        setApplyEmbeddedFontSizes(false)
                        setStyle(resolvedCaptionStyle)
                        setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale)
                        setBottomPaddingFraction(subtitleBottomPadding)
                        setCues(sanitizeCuesForVerticalPosition(activeExoPlayer.currentCues.cues))
                    }
                },
                onRelease = { playerView ->
                    @Suppress("UNCHECKED_CAST")
                    val existingListener = playerView.tag as? androidx.media3.common.Player.Listener
                    existingListener?.let { (playerView.player as? ExoPlayer)?.removeListener(it) }
                    playerView.player = null
                },
                onReset = { playerView ->
                    @Suppress("UNCHECKED_CAST")
                    val existingListener = playerView.tag as? androidx.media3.common.Player.Listener
                    existingListener?.let { (playerView.player as? ExoPlayer)?.removeListener(it) }
                    playerView.player = null
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Buffering Animation - Prominent centered Lottie loader
        if (isBuffering && errorMessage == null) {
            AppLottieLoadingAnimation(
                modifier = Modifier
                    .size(if (isFullscreen) 92.dp else 70.dp)
                    .align(Alignment.Center)
            )
        }

        // Error Dialog
        if (errorMessage != null && !isInPipMode) {
            PlayerErrorDialog(
                errorMessage = errorMessage ?: "Playback Error",
                errorDetails = errorDetails,
                onRetry = {
                    errorMessage = null
                    errorDetails = null
                    isBuffering = true
                    com.example.network.debrid.DebridManager.invalidateCache(primaryQuality?.url)
                    com.example.network.debrid.DebridManager.invalidateCache(resolvedPlaybackUrl)
                    PlayerSessionTracker.clearSession(activeExoPlayer)
                    if (isVrMode) {
                        isVrMode = false // Graceful fallback to 2D view on decoder failure
                    }
                    retryTrigger++
                },
                onOpenExternal = {
                    openExternalVideoPlayer(context, resolvedPlaybackUrl ?: primaryQuality?.url, title, resolvedPlaybackHeaders)
                }
            )
        }

        // Side Indicators (Volume & Brightness)
        val overlaySidePadding = if (isFullscreen && isLandscape) 32.dp else 16.dp
        val volumePercent = gestureVolumePercent ?: 0
        VerticalSideBarIndicator(
            visible = gestureVolumePercent != null,
            percent = volumePercent,
            painter = painterResource(
                id = if (volumePercent == 0) R.drawable.ic_gesture_volume_mute else R.drawable.ic_gesture_volume_up
            ),
            barHeight = if (isFullscreen && isLandscape) 160.dp else 110.dp,
            sidePadding = overlaySidePadding,
            modifier = Modifier.align(Alignment.CenterStart)
        )

        VerticalSideBarIndicator(
            visible = gestureBrightnessPercent != null,
            percent = gestureBrightnessPercent ?: 0,
            painter = painterResource(id = R.drawable.ic_gesture_brightness),
            barHeight = if (isFullscreen && isLandscape) 160.dp else 110.dp,
            sidePadding = overlaySidePadding,
            modifier = Modifier.align(Alignment.CenterEnd)
        )

        // Switching Quality or Subtitle Notification Pill
        val switchingNotice = switchingQualityLabel?.let { "Switching to $it..." }
            ?: switchingSubtitleLabel?.let { if (it == "Off") "Captions turned Off" else "Captions: $it" }

        AnimatedVisibility(
            visible = switchingNotice != null && !isInPipMode,
            enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { -it },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(if (isFullscreen) Modifier.statusBarsPadding() else Modifier)
                .padding(top = if (isFullscreen) 44.dp else 12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.90f),
                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.7f)),
                shadowElevation = 8.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    AppLottieLoadingAnimation(modifier = Modifier.size(16.dp))
                    Text(
                        text = switchingNotice ?: "",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Overlay Controls
        PlayerControlsOverlay(
            visible = showControls && errorMessage == null && !isInPipMode,
            isFullscreen = isFullscreen,
            isLandscape = isLandscape,
            title = title,
            isPlaying = isPlaying,
            currentPos = currentPos,
            bufferedPos = bufferedPos,
            duration = duration,
            isBuffering = isBuffering,
            is4kOrHdr = isRealHdrStream,
            isVrMode = isVrMode,
            vrUseGyro = vrUseGyro,
            vrStereoMode = vrStereoMode,
            qualities = qualities,
            selectedQualityIndex = activeQualityIndex,
            onSelectQuality = { newIndex ->
                if (newIndex in qualities.indices && newIndex != activeQualityIndex) {
                    val targetQuality = qualities[newIndex]
                    val targetLabel = formatQualityDisplayLabel(targetQuality.quality)
                    switchingQualityLabel = targetLabel
                    coroutineScope.launch {
                        delay(2200)
                        if (switchingQualityLabel == targetLabel) {
                            switchingQualityLabel = null
                        }
                    }
                    playerSession.selectedQualityUrl = targetQuality.url
                    activeQualityIndex = newIndex
                    if (isFullscreen && targetQuality.quality.contains("vr", ignoreCase = true)) {
                        isVrMode = true
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
            },
            subtitles = subtitles,
            selectedSubtitleIndex = activeSubtitleIndex,
            onSelectSubtitle = { newSubIndex ->
                if (newSubIndex != activeSubtitleIndex) {
                    val targetLabel = if (newSubIndex >= 0 && newSubIndex < subtitles.size) subtitles[newSubIndex].label else "Off"
                    switchingSubtitleLabel = targetLabel
                    coroutineScope.launch {
                        delay(2000)
                        if (switchingSubtitleLabel == targetLabel) {
                            switchingSubtitleLabel = null
                        }
                    }
                    playerSession.hasUserSelectedSubtitle = true
                    playerSession.selectedSubtitleIndex = newSubIndex
                    activeSubtitleIndex = newSubIndex
                }
            },
            subtitleScale = subtitleScale,
            onSubtitleScaleChange = {
                subtitleScale = it
                playerSession.subtitleScale = it
            },
            subtitleBottomPadding = subtitleBottomPadding,
            onSubtitleBottomPaddingChange = {
                subtitleBottomPadding = it
                playerSession.subtitleBottomPadding = it
            },
            subtitleColorFraction = subtitleColorFraction,
            onSubtitleColorFractionChange = {
                subtitleColorFraction = it
                playerSession.subtitleColorFraction = it
            },
            subtitleBgOpacity = subtitleBgOpacity,
            onSubtitleBgOpacityChange = {
                subtitleBgOpacity = it
                playerSession.subtitleBgOpacity = it
            },
            subtitleEdgeStyle = subtitleEdgeStyle,
            onSubtitleEdgeStyleChange = {
                subtitleEdgeStyle = it
                playerSession.subtitleEdgeStyle = it
            },
            subtitleFontFamily = subtitleFontFamily,
            onSubtitleFontFamilyChange = {
                subtitleFontFamily = it
                playerSession.subtitleFontFamily = it
            },
            onToggleVrMode = {
                if (isVrMode) {
                    sphericalViewRef?.onPause()
                    sphericalViewRef?.clearPlayerSurface(activeExoPlayer)
                    isVrMode = false
                    showControls = true
                } else {
                    isVrMode = true
                    showControls = true
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            },
            onToggleVrGyro = {
                vrUseGyro = !vrUseGyro
            },
            onRecenterVr = {
                sphericalViewRef?.recenter()
            },
            onBack = handleBackAction,
            onRewind10s = {
                if (duration > 0 && duration != C.TIME_UNSET) {
                    val target = (activeExoPlayer.currentPosition - 10000).coerceAtLeast(0L)
                    isBuffering = true
                    activeExoPlayer.seekTo(target)
                    currentPos = target
                } else {
                    activeExoPlayer.seekBack()
                }
                lastSeekTime = System.currentTimeMillis()
            },
            onTogglePlayPause = {
                if (activeExoPlayer.isPlaying) {
                    activeExoPlayer.pause()
                    isPlaying = false
                    isBuffering = false
                } else {
                    if (activeExoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                        activeExoPlayer.seekTo(0L)
                        currentPos = 0L
                    }
                    activeExoPlayer.play()
                    isPlaying = true
                    if (activeExoPlayer.playbackState != androidx.media3.common.Player.STATE_READY) {
                        isBuffering = true
                    }
                }
            },
            onForward10s = {
                if (duration > 0 && duration != C.TIME_UNSET) {
                    val target = (activeExoPlayer.currentPosition + 10000).coerceAtMost(duration).coerceAtLeast(0L)
                    isBuffering = true
                    activeExoPlayer.seekTo(target)
                    currentPos = target
                } else {
                    activeExoPlayer.seekForward()
                }
                lastSeekTime = System.currentTimeMillis()
            },
            onEnterPip = {
                triggerPipMode(
                    context = context,
                    activity = activity,
                    player = activeExoPlayer,
                    currentPos = currentPos,
                    onCustomPip = onEnterPip,
                    onBeforeEnter = { showControls = false }
                )
            },
            onOpenExternal = {
                openExternalVideoPlayer(context, resolvedPlaybackUrl ?: primaryQuality?.url, title, resolvedPlaybackHeaders)
            },
            onToggleFullscreen = {
                if (isFullscreen) {
                    toggleScreenOrientation(activity, isLandscape)
                } else {
                    if (onFullscreenWithMode != null) {
                        onFullscreenWithMode(currentPos, true)
                    } else {
                        onFullscreen?.invoke(currentPos)
                    }
                }
            },
            onScrubbingChanged = { scrubbing ->
                isScrubbing = scrubbing
                activeExoPlayer.setSeekParameters(if (scrubbing) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
            },
            onSeekLive = { target ->
                currentPos = target
                lastSeekTime = System.currentTimeMillis()
            },
            onSeekFinal = { target ->
                currentPos = target
                lastSeekTime = System.currentTimeMillis()
                isBuffering = true
                activeExoPlayer.setSeekParameters(SeekParameters.EXACT)
                activeExoPlayer.seekTo(target)
            }
        )
    }
}

/**
 * Fullscreen Video Player Overlay
 */
@Composable
fun GoPlayer(
    title: String,
    qualities: List<StreamQuality>,
    subtitles: List<com.example.network.SubtitleTrack> = emptyList(),
    defaultHeaders: Map<String, String> = emptyMap(),
    initialPositionMs: Long = 0L,
    startInLandscape: Boolean = false,
    startInVrMode: Boolean = false,
    exoPlayer: ExoPlayer? = null,
    lockInlinePortrait: Boolean = true,
    onClose: () -> Unit,
    onTerminateAll: (() -> Unit)? = null
) {
    CoreMediaPlayer(
        mode = PlayerMode.FULLSCREEN,
        title = title,
        qualities = qualities,
        subtitles = subtitles,
        defaultHeaders = defaultHeaders,
        initialPositionMs = initialPositionMs,
        startInLandscape = startInLandscape,
        startInVrMode = startInVrMode,
        exoPlayer = exoPlayer,
        lockInlinePortrait = lockInlinePortrait,
        onClose = onClose,
        onTerminateAll = onTerminateAll
    )
}

/**
 * Embedded 16:9 Inline Video Player for Card Covers
 */
@Composable
fun InlineCardPlayer(
    title: String,
    qualities: List<StreamQuality>,
    subtitles: List<com.example.network.SubtitleTrack> = emptyList(),
    defaultHeaders: Map<String, String> = emptyMap(),
    initialPositionMs: Long = 0L,
    exoPlayer: ExoPlayer? = null,
    enableGestures: Boolean = true,
    lockInlinePortrait: Boolean = true,
    onClose: () -> Unit,
    onFullscreen: (currentPositionMs: Long) -> Unit,
    onFullscreenWithMode: ((currentPositionMs: Long, startInLandscape: Boolean) -> Unit)? = null,
    onEnterPip: ((currentPositionMs: Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    CoreMediaPlayer(
        mode = PlayerMode.INLINE_CARD,
        title = title,
        qualities = qualities,
        subtitles = subtitles,
        defaultHeaders = defaultHeaders,
        initialPositionMs = initialPositionMs,
        exoPlayer = exoPlayer,
        enableGestures = enableGestures,
        lockInlinePortrait = lockInlinePortrait,
        onClose = onClose,
        onFullscreen = onFullscreen,
        onFullscreenWithMode = onFullscreenWithMode,
        onEnterPip = onEnterPip,
        modifier = modifier
    )
}

/**
 * Strips embedded line/position coordinates from parsed Cues (such as SsaParser's hardcoded
 * line = 0.95f in .ass/.ssa subtitles and WebvttCueParser's line overrides) so that Media3's
 * SubtitlePainter always honors SubtitleView.setBottomPaddingFraction(subtitleBottomPadding).
 */
@OptIn(UnstableApi::class)
private fun sanitizeCuesForVerticalPosition(
    cues: List<androidx.media3.common.text.Cue>
): List<androidx.media3.common.text.Cue> {
    if (cues.isEmpty()) return cues
    val textCues = cues.filter { it.bitmap == null && !it.text.isNullOrBlank() }
    val bitmapCues = cues.filter { it.bitmap != null }

    val sanitizedTextCues = if (textCues.size <= 1) {
        textCues.map { cue ->
            cue.buildUpon()
                .setLine(androidx.media3.common.text.Cue.DIMEN_UNSET, androidx.media3.common.text.Cue.TYPE_UNSET)
                .setLineAnchor(androidx.media3.common.text.Cue.TYPE_UNSET)
                .setPosition(androidx.media3.common.text.Cue.DIMEN_UNSET)
                .setPositionAnchor(androidx.media3.common.text.Cue.TYPE_UNSET)
                .build()
        }
    } else {
        val combinedText = android.text.SpannableStringBuilder()
        textCues.forEachIndexed { index, cue ->
            if (index > 0) combinedText.append("\n")
            combinedText.append(cue.text)
        }
        listOf(
            textCues.first().buildUpon()
                .setText(combinedText)
                .setLine(androidx.media3.common.text.Cue.DIMEN_UNSET, androidx.media3.common.text.Cue.TYPE_UNSET)
                .setLineAnchor(androidx.media3.common.text.Cue.TYPE_UNSET)
                .setPosition(androidx.media3.common.text.Cue.DIMEN_UNSET)
                .setPositionAnchor(androidx.media3.common.text.Cue.TYPE_UNSET)
                .build()
        )
    }

    return bitmapCues + sanitizedTextCues
}

