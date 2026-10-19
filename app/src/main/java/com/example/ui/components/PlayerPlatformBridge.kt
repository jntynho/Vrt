package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.network.StreamQuality
import kotlinx.coroutines.delay

/**
 * Context extension to resolve the hosting Activity
 */
fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Platform bridge for launching external media players (VLC, MX Player, etc.)
 */
fun openExternalVideoPlayer(
    context: Context,
    streamUrl: String?,
    title: String,
    headers: Map<String, String>
) {
    if (!streamUrl.isNullOrBlank()) {
        PlayerFactory.openInExternalPlayer(context, streamUrl, title, headers)
    } else {
        Toast.makeText(context, "No video stream URL available", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Picture-in-Picture bridge with intelligent aspect ratio calculation
 */
@OptIn(UnstableApi::class)
fun triggerPipMode(
    context: Context,
    activity: Activity?,
    player: ExoPlayer?,
    currentPos: Long,
    onCustomPip: ((Long) -> Unit)?,
    onBeforeEnter: () -> Unit
) {
    if (onCustomPip != null) {
        onCustomPip(currentPos)
        return
    }
    if (activity != null) {
        try {
            onBeforeEnter()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val format = player?.videoFormat
                val rational = if (format != null && format.width > 0 && format.height > 0) {
                    val w = format.width
                    val h = format.height
                    val ratio = w.toFloat() / h.toFloat()
                    if (ratio in 0.42f..2.38f) android.util.Rational(w, h) else android.util.Rational(16, 9)
                } else {
                    android.util.Rational(16, 9)
                }
                val params = android.app.PictureInPictureParams.Builder().setAspectRatio(rational).build()
                activity.enterPictureInPictureMode(params)
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                activity.enterPictureInPictureMode()
            }
        } catch (_: Throwable) {
            Toast.makeText(context, "Picture-in-Picture unavailable", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Toggles device orientation between Portrait and Sensor Landscape
 */
fun toggleScreenOrientation(activity: Activity?, isLandscape: Boolean) {
    activity?.let { act ->
        if (isLandscape) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
}

/**
 * Check if the active video stream is actual HDR (BT.2020 / ST2084 / HLG)
 */
@OptIn(UnstableApi::class)
fun checkIsVideoRealHdr(player: ExoPlayer): Boolean {
    val format = player.videoFormat
    val colorInfo = format?.colorInfo
    return colorInfo != null && (
        colorInfo.colorSpace == C.COLOR_SPACE_BT2020 ||
        colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 ||
        colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG
    )
}

/**
 * Fullscreen system bars, screen brightness, and dynamic color mode pipeline
 */
@Composable
fun ManageFullscreenWindowEffects(
    activity: Activity?,
    isFullscreen: Boolean,
    isRealHdrStream: Boolean
) {
    // Fullscreen system bars and brightness save/restore
    DisposableEffect(activity, isFullscreen) {
        val originalBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        val originalColorMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity?.window?.colorMode ?: ActivityInfo.COLOR_MODE_DEFAULT
        } else ActivityInfo.COLOR_MODE_DEFAULT

        if (isFullscreen) {
            activity?.let { act ->
                val windowInsetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
                windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        onDispose {
            activity?.let { act ->
                if (isFullscreen) {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    val windowInsetsController = WindowCompat.getInsetsController(act.window, act.window.decorView)
                    windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                }
                val lp = act.window.attributes
                lp.screenBrightness = originalBrightness
                act.window.attributes = lp
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    act.window.colorMode = originalColorMode
                }
            }
        }
    }

    // Dynamic wide color gamut / HDR mode for full-screen playback
    LaunchedEffect(activity, isFullscreen, isRealHdrStream) {
        if (activity != null && isFullscreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            }
            val hasHdrDisplay = display?.isHdr == true

            val targetColorMode = when {
                !isRealHdrStream -> ActivityInfo.COLOR_MODE_DEFAULT
                hasHdrDisplay -> ActivityInfo.COLOR_MODE_HDR
                else -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            }

            try {
                if (activity.window.colorMode != targetColorMode) {
                    activity.window.colorMode = targetColorMode
                }
            } catch (_: Exception) {
                try {
                    activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                } catch (_: Exception) {}
            }
        }
    }
}

/**
 * Lifecycle observer ensuring player pauses on background and resumes on foreground (preserving PiP playback)
 */
@Composable
fun BindPlayerLifecycle(
    lifecycleOwner: LifecycleOwner,
    player: ExoPlayer,
    activity: Activity? = null
) {
    DisposableEffect(lifecycleOwner, player, activity) {
        var wasPlayingBeforePause = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    val inPip = activity?.isInPictureInPictureMode == true
                    if (!inPip) {
                        wasPlayingBeforePause = player.isPlaying
                        player.pause()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (wasPlayingBeforePause) player.play()
                }
                Lifecycle.Event.ON_STOP -> {
                    val inPip = activity?.isInPictureInPictureMode == true
                    if (!inPip) {
                        player.pause()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Creates and attaches an ExoPlayer state & error listener
 */
@OptIn(UnstableApi::class)
@Composable
fun BindPlayerStateListener(
    player: ExoPlayer,
    onStateChanged: (isBuffering: Boolean, isPlaying: Boolean, duration: Long) -> Unit,
    onFormatChanged: () -> Unit,
    onError: (title: String, details: String) -> Unit,
    onClearError: () -> Unit
) {
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            private fun dispatchStateUpdate() {
                val d = player.duration
                val dur = if (d > 0 && d != C.TIME_UNSET) d else 0L
                val state = player.playbackState
                val isBuffering = state == Player.STATE_BUFFERING ||
                    (player.playWhenReady && !player.isPlaying && state != Player.STATE_ENDED && state != Player.STATE_IDLE)
                val isPlaying = player.isPlaying

                if (state == Player.STATE_READY) {
                    onClearError()
                }
                onStateChanged(isBuffering, isPlaying, dur)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                dispatchStateUpdate()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                dispatchStateUpdate()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                dispatchStateUpdate()
            }

            override fun onTracksChanged(tracks: Tracks) {
                dispatchStateUpdate()
                onFormatChanged()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                onFormatChanged()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                dispatchStateUpdate()
            }

            override fun onPlayerError(error: PlaybackException) {
                val exoEx = error as? androidx.media3.exoplayer.ExoPlaybackException
                val format = exoEx?.rendererFormat ?: player.videoFormat
                val width = format?.width ?: 0
                val height = format?.height ?: 0
                val resString = if (width > 0 && height > 0) " (${width}×${height})" else ""

                if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.message?.contains("NO_EXCEEDS_CAPABILITIES", ignoreCase = true) == true ||
                    error.message?.contains("DECODER_INIT_FAILED", ignoreCase = true) == true
                ) {
                    val is8kOrUltra = width >= 4000 || height >= 2400
                    val title = if (is8kOrUltra) "8K Video Exceeds Device Limits$resString" else "Video Codec Unsupported$resString"
                    val desc = if (is8kOrUltra) {
                        "This video's 8K resolution$resString exceeds this device's hardware decoder capability (max 4K). Tap 'Open External' to play with VLC or DeoVR."
                    } else {
                        "This device's hardware decoder does not support this video's codec or resolution$resString. Tap 'Open External' to play with VLC or MX Player."
                    }
                    onError(title, desc)
                } else {
                    onError("Playback Error (${error.errorCodeName})", error.message ?: "Failed to stream or decode video.")
                }
            }
        }

        player.addListener(listener)
        // Initial dispatch
        val d = player.duration
        val dur = if (d > 0 && d != C.TIME_UNSET) d else 0L
        val initialBuffering = player.playbackState == Player.STATE_BUFFERING || (player.playWhenReady && !player.isPlaying && player.playbackState != Player.STATE_ENDED)
        onStateChanged(initialBuffering, player.isPlaying, dur)

        onDispose { player.removeListener(listener) }
    }
}

/**
 * Adaptive player position and buffer poller loop with interactive gesture lock and smooth settling grace period
 */
@Composable
fun TrackPlayerPositions(
    player: ExoPlayer,
    showControls: Boolean,
    isScrubbing: Boolean,
    isGestureSeeking: Boolean = false,
    lastSeekTime: Long,
    primaryQuality: StreamQuality?,
    onTick: (duration: Long, buffered: Long, currentPos: Long) -> Unit
) {
    val currentIsScrubbing by androidx.compose.runtime.rememberUpdatedState(isScrubbing)
    val currentIsGestureSeeking by androidx.compose.runtime.rememberUpdatedState(isGestureSeeking)
    val currentLastSeekTime by androidx.compose.runtime.rememberUpdatedState(lastSeekTime)
    val currentOnTick by androidx.compose.runtime.rememberUpdatedState(onTick)

    LaunchedEffect(player, primaryQuality) {
        while (true) {
            val d = player.duration
            val dur = if (d > 0 && d != C.TIME_UNSET) d else 0L
            val buf = player.bufferedPosition.coerceAtLeast(0L)

            val isUserInteracting = currentIsScrubbing || currentIsGestureSeeking || (System.currentTimeMillis() - currentLastSeekTime < 800L)
            val pos = if (!isUserInteracting) {
                if (player.playbackState == Player.STATE_READY || player.isPlaying) {
                    player.currentPosition.coerceAtLeast(0L)
                } else -1L
            } else -1L

            currentOnTick(dur, buf, pos)
            delay(if (currentIsScrubbing || currentIsGestureSeeking) 50L else if (showControls) 250L else 1000L)
        }
    }
}

/**
 * Media source preparation with intelligent URL validation and resume position tracking
 */
@OptIn(UnstableApi::class)
@Composable
fun PreparePlayerMediaSource(
    context: Context,
    player: ExoPlayer,
    primaryQuality: StreamQuality?,
    subtitles: List<com.example.network.SubtitleTrack> = emptyList(),
    activeHeaders: Map<String, String>,
    initialPositionMs: Long,
    onBufferingChanged: (Boolean) -> Unit,
    onDurationChanged: (Long) -> Unit,
    onPositionChanged: (Long) -> Unit,
    onError: (title: String, details: String) -> Unit,
    onClearError: () -> Unit
) {
    var isFirstLoad by remember { mutableStateOf(true) }

    LaunchedEffect(primaryQuality, player, subtitles) {
        val quality = primaryQuality ?: return@LaunchedEffect
        val url = quality.url.trim()

        if (url.isBlank()) {
            onError("Invalid Stream URL", "The provided media link is empty.")
            return@LaunchedEffect
        }

        val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (currentUri == url && player.playbackState != Player.STATE_IDLE) {
            isFirstLoad = false
            onClearError()
            onBufferingChanged(player.playbackState == Player.STATE_BUFFERING)
            onDurationChanged(player.duration.coerceAtLeast(0L))
            if (!player.isPlaying && player.playbackState == Player.STATE_READY) {
                player.play()
            }
            return@LaunchedEffect
        }

        onClearError()
        onBufferingChanged(true)

        try {
            val streamUrl = if (url.startsWith("magnet:", ignoreCase = true) || com.example.network.torrent.MagnetParser.parseHash(url) != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val db = com.example.data.local.AppDatabase.getInstance(context)
                    val settings = db.settingsDao().getSettingsOnce()
                    val order = when (settings?.debridOrder) {
                        "REAL_DEBRID_FIRST" -> com.example.network.debrid.DebridOrder.REAL_DEBRID_FIRST
                        "TORBOX_FIRST" -> com.example.network.debrid.DebridOrder.TORBOX_FIRST
                        else -> com.example.network.debrid.DebridOrder.AUTO
                    }
                    val resolved = com.example.network.VideoResolvers.resolve(
                        rawUrl = url,
                        torboxApiKey = settings?.torboxApiKey.orEmpty(),
                        realDebridApiKey = settings?.realDebridApiKey.orEmpty(),
                        debridOrder = order
                    )
                    resolved.qualities.firstOrNull()?.url ?: url
                }
            } else {
                url
            }

            val mediaSource = PlayerFactory.buildMediaSource(context, streamUrl, activeHeaders, formatHint = quality.format, subtitles = subtitles)
            val currentPosMs = player.currentPosition.coerceAtLeast(0L)
            val resumePosition = if (isFirstLoad) {
                isFirstLoad = false
                initialPositionMs
            } else {
                currentPosMs
            }

            val wasPlaying = player.isPlaying || player.playWhenReady
            player.setMediaSource(mediaSource, true)
            player.prepare()
            player.seekTo(resumePosition)
            onPositionChanged(resumePosition)
            if (wasPlaying) {
                player.play()
            }
        } catch (e: Exception) {
            onError("Playback Setup Failed", e.message ?: "Could not build media source.")
            onBufferingChanged(false)
        }
    }
}
