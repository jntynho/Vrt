package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.network.OnlyhavenCreator
import com.example.network.OnlyhavenSavedPost
import com.example.ui.MainViewModel
import com.example.ui.ScreenState
import com.example.ui.components.AppTophead
import com.example.ui.components.NavIconType
import com.example.ui.components.OnlyhavenMediaGridItem
import com.example.ui.components.OnlyhavenModelCard
import com.example.ui.components.OnlyhavenPreviewOverlay
import com.example.ui.components.ScrollToTopButton
import com.example.ui.components.vaultTopGlow
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette
import kotlinx.coroutines.launch

enum class SavedTab {
    MODELS,
    IMAGES,
    VIDEOS;

    companion object {
        val CREATORS get() = MODELS
    }
}

enum class SavedSortOption(val displayName: String) {
    RECENTLY_ADDED("Recently Added"),
    OLDEST_ADDED("Oldest Added"),
    A_Z("A - Z"),
    Z_A("Z - A")
}

@Composable
fun SavedCreatorsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val savedCreators by viewModel.savedOnlyhavenCreators.collectAsStateWithLifecycle()
    val savedKeys by viewModel.savedCreatorKeys.collectAsStateWithLifecycle()
    val savedPosts by viewModel.savedOnlyhavenPosts.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val modelsGridState = rememberLazyGridState()
    val imagesGridState = rememberLazyGridState()
    val videosGridState = rememberLazyGridState()

    var selectedTab by remember { mutableStateOf(SavedTab.MODELS) }
    val currentGridState = when (selectedTab) {
        SavedTab.MODELS -> modelsGridState
        SavedTab.IMAGES -> imagesGridState
        SavedTab.VIDEOS -> videosGridState
    }
    val sortOption by viewModel.savedSortOption.collectAsStateWithLifecycle()
    var showSortMenu by remember { mutableStateOf(false) }

    // Fallback: If switching from Model with A - Z or Z - A to Image/Video, reset to Recently Added
    LaunchedEffect(selectedTab) {
        if ((selectedTab == SavedTab.IMAGES || selectedTab == SavedTab.VIDEOS) &&
            (sortOption == SavedSortOption.A_Z || sortOption == SavedSortOption.Z_A)
        ) {
            viewModel.savedSortOption.value = SavedSortOption.RECENTLY_ADDED
        }
    }

    // Reset scroll position to top when sort option changes
    var previousSort by remember { mutableStateOf(sortOption) }
    LaunchedEffect(sortOption) {
        if (previousSort != sortOption) {
            previousSort = sortOption
            currentGridState.scrollToItem(0)
        }
    }

    // Filter Images and Videos from savedPosts
    val savedImages = remember(savedPosts) {
        savedPosts.filter { post ->
            post.mediaItem.kind == "image" || post.mediaItem.postAttachments.any { it.kind == "image" }
        }
    }

    val savedVideos = remember(savedPosts) {
        savedPosts.filter { post ->
            post.mediaItem.kind == "video" || post.mediaItem.postAttachments.any { it.kind == "video" }
        }
    }

    // Sort Models
    val sortedCreators = remember(savedCreators, sortOption) {
        when (sortOption) {
            SavedSortOption.A_Z -> savedCreators.sortedBy { (it.displayName.ifBlank { it.name }).lowercase() }
            SavedSortOption.Z_A -> savedCreators.sortedByDescending { (it.displayName.ifBlank { it.name }).lowercase() }
            SavedSortOption.RECENTLY_ADDED -> savedCreators.sortedByDescending { it.savedAt }
            SavedSortOption.OLDEST_ADDED -> savedCreators.sortedBy { it.savedAt }
        }
    }

    // Sort Images
    val sortedImages = remember(savedImages, sortOption) {
        when (sortOption) {
            SavedSortOption.OLDEST_ADDED -> savedImages.sortedBy { it.savedAt }
            else -> savedImages.sortedByDescending { it.savedAt }
        }
    }

    // Sort Videos
    val sortedVideos = remember(savedVideos, sortOption) {
        when (sortOption) {
            SavedSortOption.OLDEST_ADDED -> savedVideos.sortedBy { it.savedAt }
            else -> savedVideos.sortedByDescending { it.savedAt }
        }
    }

    // Preview state for saved posts
    var selectedPreviewPost by remember { mutableStateOf<OnlyhavenSavedPost?>(null) }
    var initialPreviewIndex by remember { mutableIntStateOf(0) }

    BackHandler {
        if (selectedPreviewPost != null) {
            selectedPreviewPost = null
        } else {
            viewModel.navigateBack()
        }
    }

    val isLight = palette.name.equals("light", ignoreCase = true)
    val circleBorderColor = if (isLight) Color.Black else Color.White

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.bg,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppTophead(
                    title = "Saved",
                    navIconType = NavIconType.BACK,
                    onNavClick = { viewModel.navigateBack() },
                    showBorder = false,
                    actions = {
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.testTag("sort_saved_button")
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_app_sort),
                                    contentDescription = "Sort",
                                    tint = palette.textPrimary
                                )
                            }

                            val availableSortOptions = if (selectedTab == SavedTab.MODELS) {
                                listOf(
                                    SavedSortOption.A_Z,
                                    SavedSortOption.Z_A,
                                    SavedSortOption.RECENTLY_ADDED,
                                    SavedSortOption.OLDEST_ADDED
                                )
                            } else {
                                listOf(
                                    SavedSortOption.RECENTLY_ADDED,
                                    SavedSortOption.OLDEST_ADDED
                                )
                            }

                            com.example.ui.components.VaultSortDropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                options = availableSortOptions.map { option ->
                                    com.example.ui.components.VaultSortOption(
                                        value = option,
                                        label = option.displayName,
                                        isSelected = sortOption == option
                                    )
                                },
                                onOptionSelected = { selectedOption ->
                                    viewModel.savedSortOption.value = selectedOption
                                }
                            )
                        }
                    }
                )

                // Tab Switcher Row (Model vs Image vs Video)
                val indicatorBias by animateFloatAsState(
                    targetValue = when (selectedTab) {
                        SavedTab.MODELS -> -1f
                        SavedTab.IMAGES -> 0f
                        SavedTab.VIDEOS -> 1f
                    },
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                    label = "saved_tab_indicator_bias"
                )

                val modelsTabColor by animateColorAsState(
                    targetValue = if (selectedTab == SavedTab.MODELS) accent else palette.textSecondary,
                    animationSpec = tween(durationMillis = 180),
                    label = "models_tab_color"
                )
                val imagesTabColor by animateColorAsState(
                    targetValue = if (selectedTab == SavedTab.IMAGES) accent else palette.textSecondary,
                    animationSpec = tween(durationMillis = 180),
                    label = "images_tab_color"
                )
                val videosTabColor by animateColorAsState(
                    targetValue = if (selectedTab == SavedTab.VIDEOS) accent else palette.textSecondary,
                    animationSpec = tween(durationMillis = 180),
                    label = "videos_tab_color"
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { selectedTab = SavedTab.MODELS }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Model (${savedCreators.size})",
                                    fontWeight = if (selectedTab == SavedTab.MODELS) FontWeight.Bold else FontWeight.SemiBold,
                                    color = modelsTabColor,
                                    fontSize = 14.sp
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { selectedTab = SavedTab.IMAGES }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Image (${savedImages.size})",
                                    fontWeight = if (selectedTab == SavedTab.IMAGES) FontWeight.Bold else FontWeight.SemiBold,
                                    color = imagesTabColor,
                                    fontSize = 14.sp
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { selectedTab = SavedTab.VIDEOS }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Video (${savedVideos.size})",
                                    fontWeight = if (selectedTab == SavedTab.VIDEOS) FontWeight.Bold else FontWeight.SemiBold,
                                    color = videosTabColor,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .align(Alignment.BottomCenter)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(1f / 3f)
                                    .fillMaxHeight()
                                    .align(BiasAlignment(indicatorBias, 0f))
                                    .background(accent)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                SavedTab.MODELS -> {
                    if (sortedCreators.isEmpty()) {
                        EmptySavedState(itemType = "Model", palette = palette)
                    } else {
                        LazyVerticalGrid(
                            state = modelsGridState,
                            columns = GridCells.Fixed(1),
                            contentPadding = PaddingValues(top = 14.dp, bottom = 116.dp, start = 14.dp, end = 14.dp),
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(sortedCreators, key = { "saved_${it.service}_${it.id}" }) { creator ->
                                val isSaved = savedKeys.contains("${creator.service}_${creator.id}")
                                OnlyhavenModelCard(
                                    creator = creator,
                                    palette = palette,
                                    accent = accent,
                                    isLight = isLight,
                                    circleBorderColor = circleBorderColor,
                                    isSaved = isSaved,
                                    onToggleSave = { viewModel.toggleSaveOnlyhavenCreator(creator) },
                                    onClick = { viewModel.navigateTo(ScreenState.OnlyhavenCreatorDetails(creator)) }
                                )
                            }
                        }
                    }
                }
                SavedTab.IMAGES -> {
                    if (sortedImages.isEmpty()) {
                        EmptySavedState(itemType = "Image", palette = palette)
                    } else {
                        LazyVerticalGrid(
                            state = imagesGridState,
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 116.dp, start = 2.dp, end = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(sortedImages, key = { "saved_img_${it.id}" }) { post ->
                                OnlyhavenMediaGridItem(
                                    mediaItem = post.mediaItem,
                                    targetAttachment = post.mediaItem,
                                    palette = palette,
                                    onClick = {
                                        initialPreviewIndex = 0
                                        selectedPreviewPost = post
                                    }
                                )
                            }
                        }
                    }
                }
                SavedTab.VIDEOS -> {
                    if (sortedVideos.isEmpty()) {
                        EmptySavedState(itemType = "Video", palette = palette)
                    } else {
                        LazyVerticalGrid(
                            state = videosGridState,
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 116.dp, start = 2.dp, end = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(sortedVideos, key = { "saved_vid_${it.id}" }) { post ->
                                OnlyhavenMediaGridItem(
                                    mediaItem = post.mediaItem,
                                    targetAttachment = post.mediaItem,
                                    palette = palette,
                                    onClick = {
                                        initialPreviewIndex = 0
                                        selectedPreviewPost = post
                                    }
                                )
                            }
                        }
                    }
                }
            }

            val showScrollToTop by remember(currentGridState) {
                derivedStateOf {
                    currentGridState.firstVisibleItemIndex > 2 || (currentGridState.firstVisibleItemIndex > 0 && currentGridState.firstVisibleItemScrollOffset > 80)
                }
            }

            ScrollToTopButton(
                visible = showScrollToTop,
                onClick = {
                    coroutineScope.launch {
                        currentGridState.animateScrollToItem(0)
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd),
                bottomPadding = 96.dp,
                endPadding = 16.dp
            )
        }
    }

    // Full-Screen Preview Overlay for Saved Posts
    OnlyhavenPreviewOverlay(
        parentPost = selectedPreviewPost?.mediaItem,
        creator = OnlyhavenCreator(
            id = selectedPreviewPost?.creatorId ?: "",
            name = selectedPreviewPost?.creatorName ?: "",
            displayName = selectedPreviewPost?.creatorDisplayName ?: selectedPreviewPost?.creatorName ?: "",
            service = selectedPreviewPost?.creatorService ?: "",
            profileUrl = "",
            avatarUrl = selectedPreviewPost?.creatorAvatarUrl ?: "",
            headerUrl = ""
        ),
        initialIndex = initialPreviewIndex,
        onDismiss = { selectedPreviewPost = null },
        viewModel = viewModel
    )
}

/**
 * Modern alias for SavedCreatorsScreen.
 */
@Composable
fun SavedModelsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) = SavedCreatorsScreen(viewModel = viewModel, modifier = modifier)

@Composable
fun EmptySavedState(
    itemType: String,
    palette: com.example.ui.theme.VaultPalette
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "No saved yet",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = palette.textPrimary,
                fontSize = 18.sp
            )
            Text(
                text = itemType,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun EmptyModelsState(accent: Color, palette: com.example.ui.theme.VaultPalette, onExplore: () -> Unit) =
    EmptySavedState(itemType = "Model", palette = palette)

@Composable
fun EmptyCreatorsState(accent: Color, palette: com.example.ui.theme.VaultPalette, onExplore: () -> Unit) =
    EmptySavedState(itemType = "Model", palette = palette)

@Composable
fun EmptyImagesState(accent: Color, palette: com.example.ui.theme.VaultPalette, onExplore: () -> Unit) =
    EmptySavedState(itemType = "Image", palette = palette)

@Composable
fun EmptyVideosState(accent: Color, palette: com.example.ui.theme.VaultPalette, onExplore: () -> Unit) =
    EmptySavedState(itemType = "Video", palette = palette)

