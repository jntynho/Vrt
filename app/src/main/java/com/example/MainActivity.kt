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
        
        // Setup global Coil ImageLoader with browser User-Agent and SVG support for StashDB studio logos
        val imageLoader = ImageLoader.Builder(applicationContext)
            .okHttpClient(NetworkClient.okHttpClient)
            .components {
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
            val isAppLocked by viewModel.isAppLocked.collectAsStateWithLifecycle()

            GoonyTheme(
                paletteName = safeSettings.currentTheme,
                accentColorHex = safeSettings.accentColorHex,
                betaTestPrivacy = safeSettings.betaTestPrivacy
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Zero-Leak Root State Management: Loading -> (AppLocked | MainAppShell)
                        // MainAppShell is NEVER composed or rendered while isAppLocked is true.
                        AnimatedContent(
                            targetState = when {
                                isAppResourcesLoading -> AppRootState.LOADING
                                isAppLocked -> AppRootState.LOCKED
                                else -> AppRootState.AUTHENTICATED
                            },
                            transitionSpec = {
                                when {
                                    initialState == AppRootState.LOADING && targetState == AppRootState.LOCKED -> {
                                        fadeIn(animationSpec = tween(280)) togetherWith fadeOut(animationSpec = tween(200))
                                    }
                                    initialState == AppRootState.LOCKED && targetState == AppRootState.AUTHENTICATED -> {
                                        (fadeIn(animationSpec = tween(380, easing = FastOutSlowInEasing)) +
                                                scaleIn(initialScale = 0.96f, animationSpec = tween(380, easing = FastOutSlowInEasing)))
                                            .togetherWith(
                                                fadeOut(animationSpec = tween(220, easing = FastOutLinearInEasing)) +
                                                        scaleOut(targetScale = 1.04f, animationSpec = tween(220))
                                            )
                                    }
                                    initialState == AppRootState.LOADING && targetState == AppRootState.AUTHENTICATED -> {
                                        (fadeIn(animationSpec = tween(380, easing = FastOutSlowInEasing)) +
                                                scaleIn(initialScale = 0.96f, animationSpec = tween(380, easing = FastOutSlowInEasing)))
                                            .togetherWith(
                                                fadeOut(animationSpec = tween(260, easing = FastOutLinearInEasing))
                                            )
                                    }
                                    else -> {
                                        fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(250))
                                    }
                                }
                            },
                            label = "RootSecurityAndAppTransition"
                        ) { state ->
                            when (state) {
                                AppRootState.LOADING -> {
                                    LoadingScreen()
                                }
                                AppRootState.LOCKED -> {
                                    AppLockScreen(
                                        onUnlock = { pin ->
                                            viewModel.unlockApp(pin)
                                        }
                                    )
                                }
                                AppRootState.AUTHENTICATED -> {
                                    MainAppShell(viewModel = viewModel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
