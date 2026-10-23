package com.example.ui.screens

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.MainViewModel
import com.example.ui.ScreenState
import com.example.ui.components.GoPlayer
import com.example.ui.components.HorizontalBubbleLoadingAnimation
import com.example.ui.components.VaultDialogShape
import com.example.ui.components.vaultTopGlow
import com.example.ui.components.glass.LiquidGlassNavigationBar
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch

private val DialogScrim = Color.Black.copy(alpha = 0.65f)

val LocalHazeState = compositionLocalOf<HazeState?> { null }

private fun getTabOrder(screen: ScreenState): Int? {
    return when (screen) {
        is ScreenState.Bookmarks -> -1
        is ScreenState.Home -> 0
        is ScreenState.Actors -> 10
        is ScreenState.ActorScenes -> 11
        is ScreenState.Studios -> 20
        is ScreenState.StudioScenes -> 21
        is ScreenState.StashDb -> 30
        is ScreenState.Onlyhaven -> 40
        is ScreenState.OnlyhavenSavedCreators -> 41
        is ScreenState.OnlyhavenCreatorDetails -> 42
        is ScreenState.Kanime -> 50
        is ScreenState.KanimeEpisodes -> 51
        is ScreenState.Settings -> 60
        is ScreenState.AddEditLink -> 100
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppShell(viewModel: MainViewModel) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val hazeState = remember { HazeState() }

    val currentScreen by viewModel.screenState.collectAsStateWithLifecycle()
    val navDirection by viewModel.navDirection.collectAsStateWithLifecycle()
    val activeVideo by viewModel.activeVideo.collectAsStateWithLifecycle()
    val resolvingStatus by viewModel.resolvingVideoStatus.collectAsStateWithLifecycle()
    val resolvingCardId by viewModel.resolvingCardId.collectAsStateWithLifecycle()
    val videoResolutionError by viewModel.videoResolutionError.collectAsStateWithLifecycle()
    val currentSettings by viewModel.settings.collectAsStateWithLifecycle()
    val activeInlineVideo by viewModel.activeInlineVideo.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var isInPipMode by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    val currentActiveVideo by rememberUpdatedState(activeVideo)
    val currentActiveInlineVideo by rememberUpdatedState(activeInlineVideo)

    // Synchronize screen orientation when inline card video playback is active (and not in fullscreen)
    LaunchedEffect(activity, activeVideo, activeInlineVideo, currentSettings.lockInlinePlayerPortrait) {
        if (activity != null && activeVideo == null) {
            if (activeInlineVideo != null && currentSettings.lockInlinePlayerPortrait) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    DisposableEffect(activity) {
        if (activity != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
                isInPipMode = info.isInPictureInPictureMode
                if (info.isInPictureInPictureMode) {
                    val inlineVid = currentActiveInlineVideo
                    if (currentActiveVideo == null && inlineVid != null) {
                        viewModel.enterPipFromInline(
                            inlineVid.cardId,
                            viewModel.sharedPlayerManager.getPlayer().currentPosition
                        )
                    }
                } else {
                    if (viewModel.enteredPipFromInlineCard) {
                        viewModel.returnToInlineFromPip()
                    }
                }
            }
            activity.addOnPictureInPictureModeChangedListener(listener)
            onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
        } else {
            onDispose { }
        }
    }

    // Handle back button press
    BackHandler(enabled = activeVideo != null || drawerState.isOpen || currentScreen !is ScreenState.Home) {
        if (activeVideo != null) {
            viewModel.closeVideo()
        } else if (drawerState.isOpen) {
            coroutineScope.launch { drawerState.close() }
        } else {
            viewModel.navigateBack()
        }
    }

    CompositionLocalProvider(
        LocalHazeState provides hazeState
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen && activeVideo == null && !isInPipMode,
            scrimColor = Color.Black.copy(alpha = 0.5f),
            drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = palette.cardBg,
                        drawerContentColor = palette.textPrimary,
                        modifier = Modifier.width(280.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_app_icon_full),
                                    contentDescription = "Goony Logo",
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                )
                                Text("Goony", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = palette.textPrimary)
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            HorizontalDivider(color = palette.border)
                            Spacer(modifier = Modifier.height(12.dp))

                            val drawerItemColors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = accent.copy(alpha = 0.18f),
                                selectedTextColor = accent,
                                selectedIconColor = accent,
                                unselectedTextColor = palette.textPrimary,
                                unselectedIconColor = palette.textSecondary
                            )

                            @Composable
                            fun VaultDrawerItem(
                                label: String,
                                iconRes: Int,
                                selected: Boolean,
                                targetScreen: ScreenState
                            ) {
                                NavigationDrawerItem(
                                    icon = {
                                        Icon(
                                            painter = painterResource(id = iconRes),
                                            contentDescription = label,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = label,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    },
                                    selected = selected,
                                    onClick = {
                                        viewModel.navigateTo(targetScreen)
                                        coroutineScope.launch { drawerState.close() }
                                    },
                                    colors = drawerItemColors,
                                    shape = CircleShape,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }

                            VaultDrawerItem(
                                label = "Home",
                                iconRes = R.drawable.ic_nav_home,
                                selected = currentScreen is ScreenState.Home,
                                targetScreen = ScreenState.Home
                            )

                            VaultDrawerItem(
                                label = "Actors",
                                iconRes = R.drawable.ic_nav_actor,
                                selected = currentScreen is ScreenState.Actors || currentScreen is ScreenState.ActorScenes,
                                targetScreen = ScreenState.Actors
                            )

                            VaultDrawerItem(
                                label = "Studios",
                                iconRes = R.drawable.ic_nav_studio,
                                selected = currentScreen is ScreenState.Studios || currentScreen is ScreenState.StudioScenes,
                                targetScreen = ScreenState.Studios
                            )

                            VaultDrawerItem(
                                label = "Bookmarks",
                                iconRes = R.drawable.ic_nav_bookmark,
                                selected = currentScreen is ScreenState.Bookmarks,
                                targetScreen = ScreenState.Bookmarks
                            )

                            VaultDrawerItem(
                                label = "StashDB",
                                iconRes = R.drawable.ic_nav_stashdb,
                                selected = currentScreen is ScreenState.StashDb,
                                targetScreen = ScreenState.StashDb
                            )

                            VaultDrawerItem(
                                label = "Onlyhaven",
                                iconRes = R.drawable.ic_onlyhaven,
                                selected = currentScreen is ScreenState.Onlyhaven ||
                                    currentScreen is ScreenState.OnlyhavenCreatorDetails ||
                                    currentScreen is ScreenState.OnlyhavenSavedCreators,
                                targetScreen = ScreenState.Onlyhaven
                            )

                            VaultDrawerItem(
                                label = "Kanime",
                                iconRes = R.drawable.ic_kanime,
                                selected = currentScreen is ScreenState.Kanime || currentScreen is ScreenState.KanimeEpisodes,
                                targetScreen = ScreenState.Kanime
                            )

                            Spacer(modifier = Modifier.weight(1f))
                            HorizontalDivider(color = palette.border)
                            Spacer(modifier = Modifier.height(12.dp))

                            VaultDrawerItem(
                                label = "Settings",
                                iconRes = R.drawable.ic_nav_settings,
                                selected = currentScreen is ScreenState.Settings,
                                targetScreen = ScreenState.Settings
                            )
                        }
                    }
                }
            ) {
                Scaffold(
                    containerColor = palette.bg,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { _ ->
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val openDrawerLambda = remember(coroutineScope, drawerState) {
                            {
                                coroutineScope.launch { drawerState.open() }
                                Unit
                            }
                        }

                        val saveableStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

                        AnimatedContent(
                            targetState = currentScreen,
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .haze(hazeState),
                            transitionSpec = {
                                val initialOrder = getTabOrder(initialState)
                                val targetOrder = getTabOrder(targetState)
                                val isBack = when {
                                    navDirection == MainViewModel.NavigationDirection.BACK -> true
                                    targetState is ScreenState.Home -> true
                                    initialOrder != null && targetOrder != null && initialOrder != targetOrder -> targetOrder < initialOrder
                                    else -> false
                                }

                                if (isBack) {
                                    (slideInHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) { width -> -width / 4 } +
                                            fadeIn(animationSpec = tween(200, easing = LinearOutSlowInEasing)))
                                        .togetherWith(
                                            slideOutHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) { width -> width } +
                                                    fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing))
                                        ).apply {
                                            targetContentZIndex = 0f
                                        }
                                } else {
                                    (slideInHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) { width -> width } +
                                            fadeIn(animationSpec = tween(200, easing = LinearOutSlowInEasing)))
                                        .togetherWith(
                                            slideOutHorizontally(animationSpec = tween(260, easing = FastOutSlowInEasing)) { width -> -width / 4 } +
                                                    fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing))
                                        ).apply {
                                            targetContentZIndex = 1f
                                        }
                                }
                            },
                            label = "screen_motion_transition"
                        ) { screen ->
                            val stateKey = remember(screen) { screen::class.simpleName ?: "screen" }
                            saveableStateHolder.SaveableStateProvider(key = stateKey) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer()
                                ) {
                                    when (screen) {
                                        is ScreenState.Home -> HomeScreen(
                                            viewModel = viewModel,
                                            screen = screen,
                                            onOpenDrawer = openDrawerLambda
                                        )
                                        is ScreenState.Bookmarks -> BookmarksScreen(
                                            viewModel = viewModel
                                        )
                                        is ScreenState.AddEditLink -> AddEditLinkScreen(viewModel, screen.linkId)
                                        is ScreenState.Actors -> ActorManagementScreen(viewModel)
                                        is ScreenState.ActorScenes -> HomeScreen(
                                            viewModel = viewModel,
                                            screen = screen,
                                            onOpenDrawer = openDrawerLambda
                                        )
                                        is ScreenState.Studios -> StudioManagementScreen(viewModel)
                                        is ScreenState.StudioScenes -> HomeScreen(
                                            viewModel = viewModel,
                                            screen = screen,
                                            onOpenDrawer = openDrawerLambda
                                        )
                                        is ScreenState.StashDb -> StashDbScreen(
                                            viewModel = viewModel,
                                            onOpenDrawer = openDrawerLambda
                                        )
                                        is ScreenState.Onlyhaven -> OnlyhavenScreen(
                                             viewModel = viewModel,
                                             onOpenDrawer = openDrawerLambda
                                         )
                                        is ScreenState.OnlyhavenCreatorDetails -> CreatorDetailsScreen(
                                            viewModel = viewModel,
                                            creator = screen.creator
                                        )
                                        is ScreenState.OnlyhavenSavedCreators -> SavedCreatorsScreen(
                                            viewModel = viewModel
                                        )
                                         is ScreenState.Kanime -> KanimeScreen(viewModel = viewModel)
                                         is ScreenState.KanimeEpisodes -> KanimeEpisodesScreen(
                                             viewModel = viewModel,
                                             item = screen.item
                                         )
                                         is ScreenState.Settings -> SettingsScreen(viewModel)
                                    }
                                }
                            }
                        }

                        val selectedTabId = remember(currentScreen) {
                            when (currentScreen) {
                                is ScreenState.Home -> "home"
                                is ScreenState.Actors, is ScreenState.ActorScenes -> "actor"
                                is ScreenState.Studios, is ScreenState.StudioScenes -> "studio"
                                is ScreenState.StashDb -> "stashdb"
                                is ScreenState.Settings -> "settings"
                                else -> ""
                            }
                        }

                        val shouldShowBottomBar = activeVideo == null &&
                            currentScreen !is ScreenState.AddEditLink &&
                            currentScreen !is ScreenState.OnlyhavenCreatorDetails &&
                            currentScreen !is ScreenState.OnlyhavenSavedCreators &&
                            currentScreen !is ScreenState.Kanime &&
                            currentScreen !is ScreenState.KanimeEpisodes

                        AnimatedVisibility(
                            visible = shouldShowBottomBar,
                            enter = slideInVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(animationSpec = tween(220)),
                            exit = slideOutVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) { it } + fadeOut(animationSpec = tween(220)),
                            modifier = Modifier.align(Alignment.BottomCenter)
                        ) {
                            LiquidGlassNavigationBar(
                                selectedId = selectedTabId,
                                hazeState = hazeState,
                                onItemSelected = { item ->
                                    viewModel.navigateTo(item.targetScreen)
                                },
                                onItemReselected = { item ->
                                    when (item.id) {
                                        "home" -> {
                                            if (currentScreen is ScreenState.ActorScenes || currentScreen is ScreenState.StudioScenes) {
                                                viewModel.navigateTo(ScreenState.Home)
                                            } else {
                                                if (viewModel.searchQuery.value.isNotEmpty()) {
                                                    viewModel.searchQuery.value = ""
                                                }
                                                viewModel.triggerScrollToTop()
                                            }
                                        }
                                        "actor" -> {
                                            if (currentScreen is ScreenState.ActorScenes) {
                                                viewModel.navigateTo(ScreenState.Actors)
                                            }
                                        }
                                        "studio" -> {
                                            if (currentScreen is ScreenState.StudioScenes) {
                                                viewModel.navigateTo(ScreenState.Studios)
                                            }
                                        }
                                    }
                                },
                                barHeightDp = currentSettings.navBarHeightDp,
                                transparency = currentSettings.navBarTransparency,
                                blurRadiusDp = currentSettings.navBarBlurDp,
                                activeTabBlurDp = currentSettings.navBarActiveTabBlurDp,
                                activeTabIntensity = currentSettings.navBarActiveTabIntensity
                            )
                        }

                        AnimatedVisibility(
                            visible = activeVideo != null,
                            enter = fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                                    scaleIn(initialScale = 0.94f, animationSpec = tween(240, easing = FastOutSlowInEasing)),
                            exit = fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing)) +
                                    scaleOut(targetScale = 0.95f, animationSpec = tween(200, easing = FastOutLinearInEasing))
                        ) {
                            activeVideo?.let { video ->
                                GoPlayer(
                                    title = video.title,
                                    qualities = video.qualities,
                                    subtitles = video.subtitles,
                                    defaultHeaders = video.headers,
                                    initialPositionMs = video.initialPositionMs,
                                    startInLandscape = video.startInLandscape,
                                    startInVrMode = video.startInVrMode,
                                    exoPlayer = viewModel.sharedPlayerManager.getPlayer(),
                                    lockInlinePortrait = currentSettings.lockInlinePlayerPortrait,
                                    onClose = { viewModel.closeVideo() },
                                    onTerminateAll = { viewModel.terminateAllVideoPlayback() }
                                )
                            }
                        }

                        if (resolvingCardId == null && !isInPipMode) {
                            resolvingStatus?.let { statusText ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(DialogScrim)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {},
                                    contentAlignment = Alignment.Center
                                ) {
                                    Card(
                                        modifier = Modifier
                                            .widthIn(max = 320.dp)
                                            .padding(20.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = palette.cardBg)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(22.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            Image(
                                                painter = painterResource(id = R.drawable.ic_app_icon_full),
                                                contentDescription = "App Icon",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(54.dp)
                                                    .clip(CircleShape)
                                            )
                                            HorizontalBubbleLoadingAnimation(
                                                bubbleColor = accent,
                                                bubbleCount = 4,
                                                bubbleSize = 11.dp
                                            )
                                            Text(
                                                text = statusText,
                                                color = palette.textSecondary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Normal,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (resolvingCardId == null && !isInPipMode) {
                            videoResolutionError?.let { errText ->
                                AlertDialog(
                                    onDismissRequest = { viewModel.dismissVideoError() },
                                    modifier = Modifier.vaultTopGlow(glowColor = MaterialTheme.colorScheme.error),
                                    shape = VaultDialogShape,
                                    containerColor = palette.dialogBg,
                                    icon = {
                                        Icon(
                                            Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    },
                                    title = {
                                        Text(
                                            text = "Stream Playback Error",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = palette.textPrimary
                                        )
                                    },
                                    text = {
                                        Text(
                                            text = errText,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp,
                                            color = palette.textSecondary
                                        )
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = { viewModel.dismissVideoError() },
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                                        ) {
                                            Text("OK", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
    }
}
