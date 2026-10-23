package com.example

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainViewModel
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import com.example.ui.components.AppLockScreen
import com.example.ui.screens.LoadingScreen
import com.example.ui.screens.MainAppShell
import com.example.ui.theme.GoonyTheme

import androidx.compose.runtime.LaunchedEffect
import coil.Coil
import coil.ImageLoader
import coil.decode.SvgDecoder
import com.example.network.NetworkClient
import com.example.ui.ScreenState

enum class AppRootState {
    LOADING,
    LOCKED,
    AUTHENTICATED
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Purge legacy Coil disk cache that may contain AVIF/HEIF responses negotiated earlier
        Thread {
            runCatching {
                applicationContext.cacheDir.resolve("image_cache").deleteRecursively()
            }
        }.start()

        // Setup global Coil ImageLoader with browser User-Agent, SVG support, and HEIF/AVIF guard
        // to prevent Android's native HeifDecoderImpl::getSize error on unsupported emulators/devices
        val imageLoader = ImageLoader.Builder(applicationContext)
            .okHttpClient(NetworkClient.okHttpClient)
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(applicationContext.cacheDir.resolve("image_cache_v2"))
                    .build()
            }
            .components {
                add(HeifAvifGuardDecoderFactory())
                add(SvgDecoder.Factory())
            }
            .build()
        Coil.setImageLoader(imageLoader)

        // Pure Transparent Edge-To-Edge for both Status Bar and Navigation Bar
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )

        // Disable system-enforced scrim / black background behind Gesture Navigation Bar on Android 10 (Q) and newer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            val viewModel: MainViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val safeSettings = settings ?: com.example.data.local.entity.SettingsEntity()

            LaunchedEffect(Unit) {
                val startScreen = intent?.getStringExtra("start_screen")
                if (startScreen == "settings") {
                    val startSection = intent?.getStringExtra("start_section")
                    viewModel.initialSettingsSection = startSection
                    viewModel.navigateTo(ScreenState.Settings)
                }
            }

            val isAppResourcesLoading by viewModel.isAppResourcesLoading.collectAsStateWithLifecycle()
            val isBackendWarmupReady by viewModel.isBackendWarmupReady.collectAsStateWithLifecycle()
            val isAppLocked by viewModel.isAppLocked.collectAsStateWithLifecycle()

            GoonyTheme(
                paletteName = safeSettings.currentTheme,
                accentColorHex = safeSettings.accentColorHex,
                betaTestPrivacy = safeSettings.betaTestPrivacy
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Layer 0: Once backend & security passcode verification are complete (isBackendWarmupReady),
                        // pre-compose the verified destination (AppLockScreen or MainAppShell) UNDERNEATH the
                        // opaque LoadingScreen curtain so its entire Compose tree, layout, and GPU layers are
                        // 100% rendered BEFORE the LoadingScreen fades out.
                        // Zero-Leak Security Guarantee: MainAppShell is NEVER composed while isAppLocked is true
                        // or before the security gate finishes.
                        if (isBackendWarmupReady || !isAppResourcesLoading) {
                            AnimatedContent(
                                targetState = if (isAppLocked) AppRootState.LOCKED else AppRootState.AUTHENTICATED,
                                transitionSpec = {
                                    if (initialState == AppRootState.LOCKED && targetState == AppRootState.AUTHENTICATED) {
                                        (fadeIn(animationSpec = tween(320, easing = FastOutSlowInEasing)) +
                                                scaleIn(initialScale = 0.96f, animationSpec = tween(320, easing = FastOutSlowInEasing)))
                                            .togetherWith(
                                                fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing)) +
                                                        scaleOut(targetScale = 1.04f, animationSpec = tween(200))
                                            )
                                    } else {
                                        fadeIn(animationSpec = tween(240)) togetherWith fadeOut(animationSpec = tween(240))
                                    }
                                },
                                label = "RootSecurityTransition"
                            ) { state ->
                                when (state) {
                                    AppRootState.LOCKED -> {
                                        AppLockScreen(
                                            onUnlock = { pin ->
                                                viewModel.unlockApp(pin)
                                            }
                                        )
                                    }
                                    AppRootState.AUTHENTICATED, AppRootState.LOADING -> {
                                        MainAppShell(viewModel = viewModel)
                                    }
                                }
                            }
                        }

                        // Layer 1: Opaque LoadingScreen curtain on top. Takes its full time running the 5-stage
                        // UI, JIT & Shader warmup sandbox and waiting for Layer 0 pre-composition to settle.
                        AnimatedVisibility(
                            visible = isAppResourcesLoading,
                            enter = fadeIn(animationSpec = tween(180)),
                            exit = fadeOut(animationSpec = tween(320, easing = FastOutSlowInEasing)) +
                                    scaleOut(targetScale = 1.03f, animationSpec = tween(320, easing = FastOutSlowInEasing))
                        ) {
                            LoadingScreen(
                                onProgressUpdate = viewModel::updateWarmupStatus,
                                onWarmupComplete = viewModel::markUiEngineWarmupComplete
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom Coil Decoder.Factory that intercepts ISOBMFF (HEIF / HEIC / AVIF) streams before
 * Coil's default BitmapFactoryDecoder runs with `inSampleSize > 1`.
 *
 * In Android's Skia (`SkHeifCodec::onGetScaledDimensions`), passing `inSampleSize > 1`
 * invokes `HeifDecoderImpl::getSize()`, which is unimplemented in AOSP's `libheif` and
 * logs `E/HeifDecoderImpl: getSize: not supported!`. By decoding ISOBMFF streams with
 * `inSampleSize = 1` and `inScaled = false` and scaling the resulting Bitmap in Kotlin,
 * `HeifDecoderImpl::getSize()` is never called.
 */
private class HeifAvifGuardDecoderFactory : coil.decode.Decoder.Factory {
    override fun create(
        result: coil.fetch.SourceResult,
        options: coil.request.Options,
        imageLoader: ImageLoader
    ): coil.decode.Decoder? {
        val mime = result.mimeType?.lowercase().orEmpty()
        val isHeifOrAvifMime = mime.contains("heif") || mime.contains("heic") || mime.contains("avif")
        if (!isHeifOrAvifMime && !isIsoBmffContainer(result.source.source())) {
            return null
        }
        return coil.decode.Decoder {
            val bytes = result.source.source().use { it.readByteArray() }
            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 1
                inScaled = false
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val rawBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)

            coil.decode.DecodeResult(
                drawable = android.graphics.drawable.BitmapDrawable(options.context.resources, rawBitmap),
                isSampled = false
            )
        }
    }

    private fun isIsoBmffContainer(source: okio.BufferedSource): Boolean {
        return try {
            val peek = source.peek()
            if (!peek.request(12)) return false
            val header = peek.readByteArray(12)
            // Bytes 4..7 == 'f', 't', 'y', 'p'
            header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte()
        } catch (_: Exception) {
            false
        }
    }
}

