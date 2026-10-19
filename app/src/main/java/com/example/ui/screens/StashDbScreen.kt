package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.network.StashDbApiService
import com.example.network.StashSceneSortMode
import com.example.ui.MainViewModel
import com.example.ui.ScreenState
import com.example.ui.StashSearchType
import com.example.ui.components.*
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StashDbScreen(
    viewModel: MainViewModel,
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val savedLinks by viewModel.allLinks.collectAsStateWithLifecycle()
    val savedStashDbIds by remember(savedLinks) {
        derivedStateOf { savedLinks.mapNotNull { it.stashDbId }.toSet() }
    }
    val savedTitleAndDatePairs by remember(savedLinks) {
        derivedStateOf {
            savedLinks.map { Pair(it.title.trim().lowercase(), it.assignedDate) }.toSet()
        }
    }

    // Persistent state from MainViewModel
    val searchQuery by viewModel.stashSearchQuery.collectAsStateWithLifecycle()
    val activeType by viewModel.stashActiveType.collectAsStateWithLifecycle()
    val currentSort by viewModel.stashSortMode.collectAsStateWithLifecycle()
    val isSearchExpanded by viewModel.isStashSearchExpanded.collectAsStateWithLifecycle()

    var showSortMenu by remember { mutableStateOf(false) }

    val performerResults by viewModel.stashPerformerResults.collectAsStateWithLifecycle()
    val studioResults by viewModel.stashStudioResults.collectAsStateWithLifecycle()

    val selectedPerformer by viewModel.stashSelectedPerformer.collectAsStateWithLifecycle()
    val selectedStudio by viewModel.stashSelectedStudio.collectAsStateWithLifecycle()

    val scenesList by viewModel.stashScenesList.collectAsStateWithLifecycle()
    val selectedSceneIds by viewModel.stashSelectedSceneIds.collectAsStateWithLifecycle()
    val allActors by viewModel.allActors.collectAsStateWithLifecycle()

    val isSearchingTarget by viewModel.isStashLoadingEntities.collectAsStateWithLifecycle()
    val isLoadingScenes by viewModel.isStashLoadingScenes.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isStashLoadingMore.collectAsStateWithLifecycle()
    val canLoadMore by viewModel.stashCanLoadMore.collectAsStateWithLifecycle()
    val searchError by viewModel.stashSearchError.collectAsStateWithLifecycle()

    val isSavingWithProgress by viewModel.isSavingWithProgress.collectAsStateWithLifecycle()
    val saveProgressCurrent by viewModel.saveProgressCurrent.collectAsStateWithLifecycle()
    val saveProgressTotal by viewModel.saveProgressTotal.collectAsStateWithLifecycle()
    val saveCurrentTitle by viewModel.saveCurrentTitle.collectAsStateWithLifecycle()
    val saveCurrentPhase by viewModel.saveCurrentPhase.collectAsStateWithLifecycle()
    val saveSavedWithTorrentsCount by viewModel.saveSavedWithTorrentsCount.collectAsStateWithLifecycle()

    // System Back Press Handling
    BackHandler {
        if (isSavingWithProgress) {
            return@BackHandler
        } else if (selectedSceneIds.isNotEmpty()) {
            viewModel.clearStashSelection()
        } else if (isSearchExpanded) {
            viewModel.setStashSearchExpanded(false)
            viewModel.setStashSearchQuery("")
        } else {
            viewModel.navigateTo(ScreenState.Home)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // Grid state and smooth scroll-driven visibility for the horizontal results row
    val gridState = rememberLazyGridState()
    var isHorizontalResultsVisible by remember { mutableStateOf(true) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta < -12f && isHorizontalResultsVisible) {
                    isHorizontalResultsVisible = false
                } else if (delta > 12f && !isHorizontalResultsVisible) {
                    isHorizontalResultsVisible = true
                }
                return Offset.Zero
            }
        }
    }

    // Dynamic scroll observation: restore visibility whenever list returns to top
    val isAtTop by remember {
        derivedStateOf {
            gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset <= 4
        }
    }

    LaunchedEffect(isAtTop) {
        if (isAtTop) {
            isHorizontalResultsVisible = true
        }
    }

    // Reset visibility and scroll position to top on selection change, mode change, or sort change
    LaunchedEffect(selectedPerformer?.id, selectedStudio?.id, activeType, currentSort) {
        isHorizontalResultsVisible = true
        gridState.scrollToItem(0)
    }

    // Trigger loading more when scrolling near bottom
    val shouldLoadMore by remember {
        derivedStateOf {
            val totalItems = scenesList.size
            if (totalItems == 0 || isLoadingScenes || isLoadingMore || !canLoadMore || searchError != null) {
                false
            } else {
                val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleItem >= totalItems - 6
            }
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            val isApiKeyRequired = activeType != StashSearchType.SEXMEX
            if (!isApiKeyRequired || settings.stashDbApiKey.isNotBlank()) {
                viewModel.loadMoreStashScenes(settings.stashDbApiKey)
            }
        }
    }

    var studioToBlock by remember { mutableStateOf<Pair<String?, String>?>(null) }

    // Save all selected scenes to Links with batch DB insert and torrent fetching
    val saveSelectedScenes = remember(viewModel, coroutineScope, snackbarHostState) {
        {
            viewModel.saveSelectedStashScenesWithProgress { savedCount, torrentsCount ->
                if (coroutineScope.isActive) {
                    coroutineScope.launch {
                        val msg = when {
                            savedCount == 0 -> "No scenes saved"
                            savedCount == 1 -> if (torrentsCount > 0) "Saved 1 scene (1 with torrent)!" else "Saved 1 scene!"
                            else -> if (torrentsCount > 0) "Saved $savedCount scenes ($torrentsCount with torrents)!" else "Saved $savedCount scenes!"
                        }
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            }
        }
    }

    if (isSavingWithProgress) {
        StashBatchSaveProgressDialog(
            current = saveProgressCurrent,
            total = saveProgressTotal,
            currentTitle = saveCurrentTitle,
            currentPhase = saveCurrentPhase,
            savedWithTorrentsCount = saveSavedWithTorrentsCount,
            onCancel = { viewModel.cancelBatchSave() }
        )
    }

    if (studioToBlock != null) {
        val targetName = studioToBlock?.second ?: ""
        val targetId = studioToBlock?.first
        StashBlockStudioDialog(
            targetName = targetName,
            onConfirm = {
                viewModel.blockStudio(targetId, targetName)
                studioToBlock = null
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Blocked studio: $targetName")
                }
            },
            onDismiss = { studioToBlock = null }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.bg,
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (selectedSceneIds.isNotEmpty()) {
                        Text(
                            text = "${selectedSceneIds.size} Selected",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = accent
                        )
                    } else if (isSearchExpanded) {
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setStashSearchQuery(it) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = palette.textPrimary,
                                fontSize = 15.sp
                            ),
                            cursorBrush = SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    focusManager.clearFocus()
                                    viewModel.performStashSearch(settings.stashDbApiKey, searchQuery)
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .testTag("stashdb_header_search_input"),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = when (activeType) {
                                                StashSearchType.ACTORS -> "Search actor..."
                                                StashSearchType.STUDIO -> "Search studio..."
                                                StashSearchType.SEXMEX -> "Paste SexMex Scene URL or Model..."
                                            },
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontSize = 15.sp,
                                                color = palette.textMuted
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    } else {
                        Text(
                            text = "StashDB",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = palette.textPrimary
                        )
                    }
                },
                navigationIcon = {
                    if (selectedSceneIds.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.clearStashSelection() },
                            modifier = Modifier.testTag("clear_selection_top_bar_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear Selection",
                                tint = palette.textPrimary
                            )
                        }
                    } else if (isSearchExpanded) {
                        IconButton(
                            onClick = {
                                viewModel.setStashSearchExpanded(false)
                                viewModel.setStashSearchQuery("")
                            },
                            modifier = Modifier.testTag("close_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close Search",
                                tint = palette.textPrimary
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { viewModel.navigateTo(ScreenState.Home) },
                            modifier = Modifier.testTag("back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Home",
                                tint = palette.textPrimary
                            )
                        }
                    }
                },
                actions = {
                    // 1. Always accessible Save button when items are selected
                    if (selectedSceneIds.isNotEmpty()) {
                        IconButton(
                            onClick = { saveSelectedScenes() },
                            modifier = Modifier.testTag("save_selected_scenes_button")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_action_save),
                                contentDescription = "Save Selected Scenes",
                                tint = accent
                            )
                        }
                    }

                    // 2. Search expanded / collapsed action controls
                    if (isSearchExpanded) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.performStashSearch(settings.stashDbApiKey, searchQuery)
                                },
                                modifier = Modifier.testTag("stashdb_header_search_submit")
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_app_search),
                                    contentDescription = "Search",
                                    tint = accent
                                )
                            }
                            IconButton(
                                onClick = { viewModel.setStashSearchQuery("") },
                                modifier = Modifier.testTag("clear_search_text_button")
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_action_cancel),
                                    contentDescription = "Clear text",
                                    tint = palette.textSecondary
                                )
                            }
                        }
                    } else if (selectedSceneIds.isEmpty()) {
                        val canShowSortInHeader = when (activeType) {
                            StashSearchType.ACTORS -> selectedPerformer != null
                            StashSearchType.STUDIO -> selectedStudio != null
                            StashSearchType.SEXMEX -> false
                        }

                        AnimatedVisibility(
                            visible = canShowSortInHeader,
                            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.85f, animationSpec = tween(180)),
                            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.85f, animationSpec = tween(140))
                        ) {
                            Box {
                                IconButton(
                                    onClick = { showSortMenu = true },
                                    modifier = Modifier.testTag("stashdb_sort_button")
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_app_sort),
                                        contentDescription = "Sort Scenes",
                                        tint = palette.textPrimary
                                    )
                                }

                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                    modifier = Modifier
                                        .vaultTopGlow(cornerRadius = 16.dp)
                                        .widthIn(min = 210.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = palette.cardBg
                                ) {
                                    StashSceneSortMode.entries.forEach { mode ->
                                        val isSelected = currentSort == mode

                                        val animatedTextColor by animateColorAsState(
                                            targetValue = if (isSelected) accent else palette.textPrimary,
                                            animationSpec = tween(durationMillis = 220),
                                            label = "stash_sort_text_color"
                                        )
                                        val animatedBgColor by animateColorAsState(
                                            targetValue = if (isSelected) accent.copy(alpha = 0.12f) else Color.Transparent,
                                            animationSpec = tween(durationMillis = 220),
                                            label = "stash_sort_bg_color"
                                        )

                                        DropdownMenuItem(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(animatedBgColor)
                                                .testTag("stash_sort_option_${mode.name.lowercase()}"),
                                            text = {
                                                Text(
                                                    text = mode.displayName,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = animatedTextColor,
                                                    fontSize = 14.5.sp
                                                )
                                            },
                                            leadingIcon = {
                                                AnimatedVisibility(
                                                    visible = isSelected,
                                                    enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.6f, animationSpec = tween(180)),
                                                    exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.6f, animationSpec = tween(140))
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = accent,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            },
                                            onClick = {
                                                showSortMenu = false
                                                if (currentSort != mode) {
                                                    coroutineScope.launch {
                                                        isHorizontalResultsVisible = true
                                                        gridState.scrollToItem(0)
                                                    }
                                                    viewModel.setStashSortMode(mode, settings.stashDbApiKey)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        IconButton(
                            onClick = { viewModel.setStashSearchExpanded(true) },
                            modifier = Modifier.testTag("stashdb_search_action_button")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_search),
                                contentDescription = "Search",
                                tint = palette.textPrimary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.surface,
                    titleContentColor = palette.textPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Warning Banner: Missing StashDB API Key (Only shown for StashDB tabs: ACTORS & STUDIO)
                if (settings.stashDbApiKey.isBlank() && activeType != StashSearchType.SEXMEX) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "API Key Warning",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "StashDB API Key Missing",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = "Add your key in Settings to search actors and scenes.",
                                    fontSize = 11.sp,
                                    color = palette.textSecondary
                                )
                            }
                            FilledTonalButton(
                                onClick = { viewModel.navigateTo(ScreenState.Settings) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Settings", fontSize = 11.5.sp)
                            }
                        }
                    }
                }

                // Search Error Banner (Independent)
                if (searchError != null) {
                    Surface(
                        color = palette.cardBg,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = searchError ?: "Search failed",
                                fontSize = 11.5.sp,
                                color = palette.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Two Mode Selector Tabs: Actors & Studio with Smooth Sliding Indicator
                Surface(
                    color = palette.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawLine(
                                color = palette.border,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                ) {
                    val indicatorBias by animateFloatAsState(
                        targetValue = when (activeType) {
                            StashSearchType.ACTORS -> -1f
                            StashSearchType.STUDIO -> 0f
                            StashSearchType.SEXMEX -> 1f
                        },
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        label = "stash_tab_indicator_bias"
                    )
                    val actorTabColor by animateColorAsState(
                        targetValue = if (activeType == StashSearchType.ACTORS) accent else palette.textSecondary,
                        animationSpec = tween(durationMillis = 180),
                        label = "actor_tab_color"
                    )
                    val studioTabColor by animateColorAsState(
                        targetValue = if (activeType == StashSearchType.STUDIO) accent else palette.textSecondary,
                        animationSpec = tween(durationMillis = 180),
                        label = "studio_tab_color"
                    )
                    val sexmexTabColor by animateColorAsState(
                        targetValue = if (activeType == StashSearchType.SEXMEX) accent else palette.textSecondary,
                        animationSpec = tween(durationMillis = 180),
                        label = "sexmex_tab_color"
                    )

                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                // Actors Tab
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { viewModel.setStashActiveType(StashSearchType.ACTORS, settings.stashDbApiKey) }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_nav_actor),
                                            contentDescription = null,
                                            tint = actorTabColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Actor",
                                            fontWeight = if (activeType == StashSearchType.ACTORS) FontWeight.Bold else FontWeight.SemiBold,
                                            color = actorTabColor
                                        )
                                    }
                                }

                                // Studio Tab
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { viewModel.setStashActiveType(StashSearchType.STUDIO, settings.stashDbApiKey) }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_nav_studio),
                                            contentDescription = null,
                                            tint = studioTabColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Studio",
                                            fontWeight = if (activeType == StashSearchType.STUDIO) FontWeight.Bold else FontWeight.SemiBold,
                                            color = studioTabColor
                                        )
                                    }
                                }

                                // SexMex Tab
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { viewModel.setStashActiveType(StashSearchType.SEXMEX, settings.stashDbApiKey) }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_sexmex),
                                            contentDescription = null,
                                            tint = sexmexTabColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "SexMex",
                                            fontWeight = if (activeType == StashSearchType.SEXMEX) FontWeight.Bold else FontWeight.SemiBold,
                                            color = sexmexTabColor
                                        )
                                    }
                                }
                            }

                            // Smooth Sliding Indicator Underline (Full-width like Model Details)
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
                        HorizontalDivider(color = palette.border)
                    }
                }

                val hasActiveHorizontalResults = when (activeType) {
                    StashSearchType.ACTORS, StashSearchType.SEXMEX -> performerResults.isNotEmpty()
                    StashSearchType.STUDIO -> studioResults.isNotEmpty()
                }

                // Horizontal Results Row with Smooth Native Scroll-Driven Collapse
                AnimatedVisibility(
                    visible = isHorizontalResultsVisible && (hasActiveHorizontalResults || isSearchingTarget),
                    enter = expandVertically(
                        animationSpec = spring(
                            stiffness = Spring.StiffnessMediumLow,
                            dampingRatio = Spring.DampingRatioNoBouncy
                        )
                    ) + fadeIn(animationSpec = tween(150)),
                    exit = shrinkVertically(
                        animationSpec = spring(
                            stiffness = Spring.StiffnessMediumLow,
                            dampingRatio = Spring.DampingRatioNoBouncy
                        )
                    ) + fadeOut(animationSpec = tween(150))
                ) {
                    if (isSearchingTarget) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .width(70.dp)
                                    .height(10.dp)
                                    .adaptiveSkeleton(RoundedCornerShape(4.dp))
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalFadeEdge(24.dp)
                            ) {
                                items(7) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.width(76.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .padding(vertical = 4.dp)
                                                .size(60.dp)
                                                .adaptiveSkeleton(CircleShape)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .width(52.dp)
                                                .height(11.dp)
                                                .adaptiveSkeleton(RoundedCornerShape(4.dp))
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        AnimatedContent(
                            targetState = activeType,
                            transitionSpec = {
                                val isForward = targetState.tabIndex() > initialState.tabIndex()
                                (fadeIn(animationSpec = tween(220, easing = LinearOutSlowInEasing)) +
                                        slideInHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { if (isForward) it / 5 else -it / 5 })
                                    .togetherWith(
                                        fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                                                slideOutHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)) { if (isForward) -it / 5 else it / 5 }
                                    )
                            },
                            label = "stash_results_type_anim"
                        ) { type ->
                            if ((type == StashSearchType.ACTORS || type == StashSearchType.SEXMEX) && performerResults.isNotEmpty()) {
                                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    Text(
                                        text = "Results : ${performerResults.size}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        ),
                                        color = palette.textMuted,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalFadeEdge(24.dp)
                                    ) {
                                        itemsIndexed(performerResults, key = { index, performer -> "${type.name}_${performer.id}_$index" }) { _, performer ->
                                            val isSelected = selectedPerformer?.id == performer.id
                                            val matchingActor = remember(type, allActors, performer.id, performer.name) {
                                                if (type == StashSearchType.ACTORS) {
                                                    allActors.firstOrNull { actor ->
                                                        (actor.stashDbId != null && actor.stashDbId == performer.id) ||
                                                        actor.name.trim().equals(performer.name.trim(), ignoreCase = true)
                                                    }
                                                } else null
                                            }
                                            HorizontalActorCircleItem(
                                                performer = performer,
                                                isSelected = isSelected,
                                                onClick = { viewModel.selectStashPerformer(performer, settings.stashDbApiKey) },
                                                actorEntity = matchingActor
                                            )
                                        }
                                    }
                                }
                            } else if (type == StashSearchType.STUDIO && studioResults.isNotEmpty()) {
                                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    Text(
                                        text = "Results : ${studioResults.size}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        ),
                                        color = palette.textMuted,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                    )
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalFadeEdge(24.dp)
                                    ) {
                                        itemsIndexed(studioResults, key = { index, studio -> "${studio.id}_$index" }) { _, studio ->
                                            val isSelected = selectedStudio?.id == studio.id
                                            HorizontalStudioCircleItem(
                                                studio = studio,
                                                isSelected = isSelected,
                                                onClick = { viewModel.selectStashStudio(studio, settings.stashDbApiKey) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Main Content: 2-Cards-Per-Row Grid
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Crossfade(
                        targetState = isLoadingScenes,
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        label = "stash_scenes_loading_crossfade"
                    ) { loading ->
                        if (loading) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 116.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize(),
                                userScrollEnabled = false
                            ) {
                                items(6) {
                                    StashGridSkeletonCard()
                                }
                            }
                        } else if (scenesList.isNotEmpty()) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                state = gridState,
                                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 116.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .nestedScroll(nestedScrollConnection)
                            ) {
                                itemsIndexed(
                                    items = scenesList,
                                    key = { index, scene -> "${currentSort.name}_${scene.id}_$index" }
                                ) { index, scene ->
                                    val isSelected = selectedSceneIds.contains(scene.id)
                                    val parsedDate = remember(scene.date) {
                                        StashDbApiService.parseDateToMillis(scene.date)
                                    }
                                    val isAlreadySaved = remember(scene.id, scene.title, parsedDate, savedStashDbIds, savedTitleAndDatePairs) {
                                        (scene.id in savedStashDbIds) ||
                                            (parsedDate != null && Pair(scene.title.trim().lowercase(), parsedDate) in savedTitleAndDatePairs)
                                    }

                                    StashSortAnimatedCardItem(
                                        index = index,
                                        sortKey = currentSort.name,
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                                            placementSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            ),
                                            fadeOutSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing)
                                        )
                                    ) {
                                        StashGridPhotoCard(
                                            scene = scene,
                                            isSelected = isSelected,
                                            isAlreadySaved = isAlreadySaved,
                                            onToggleSelect = {
                                                viewModel.toggleStashSceneSelection(scene.id)
                                            },
                                            onStudioClick = { id, name ->
                                                studioToBlock = Pair(id, name)
                                            }
                                        )
                                    }
                                }

                                if (isLoadingMore) {
                                    item(span = { GridItemSpan(2) }) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 16.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            SmoothProgressIndicator(
                                                color = accent,
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "Loading more scenes...",
                                                color = palette.textSecondary,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (searchQuery.isNotBlank() && !isSearchingTarget) {
                            EmptyStateView(
                                icon = Icons.Default.SearchOff,
                                title = "No Scenes Available",
                                subtitle = "Select another result from the top row or try a new search query."
                            )
                        } else {
                            AnimatedContent(
                                targetState = activeType,
                                transitionSpec = {
                                    val isForward = targetState.tabIndex() > initialState.tabIndex()
                                    (fadeIn(animationSpec = tween(220, easing = LinearOutSlowInEasing)) +
                                            slideInHorizontally(animationSpec = tween(220, easing = FastOutSlowInEasing)) { if (isForward) it / 5 else -it / 5 })
                                        .togetherWith(
                                            fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                                                    slideOutHorizontally(animationSpec = tween(200, easing = FastOutSlowInEasing)) { if (isForward) -it / 5 else it / 5 }
                                        )
                                },
                                label = "stash_empty_state_anim"
                            ) { type ->
                                EmptyStateView(
                                    icon = when (type) {
                                        StashSearchType.ACTORS -> Icons.Outlined.Person
                                        StashSearchType.STUDIO -> Icons.Outlined.Videocam
                                        StashSearchType.SEXMEX -> Icons.Default.Language
                                    },
                                    title = when (type) {
                                        StashSearchType.ACTORS -> "Search Actor & Explore Scenes"
                                        StashSearchType.STUDIO -> "Search Studio & Explore Scenes"
                                        StashSearchType.SEXMEX -> "Search SexMex Model or Scene"
                                    },
                                    subtitle = if (type == StashSearchType.SEXMEX) {
                                        "Search for a performer or scene title, or explore the latest releases directly."
                                    } else {
                                        "Tap the search icon in the header, type a name or query, and tap search. Click any scene to select, then tap the checkmark in the header to save."
                                    },
                                    content = if (type == StashSearchType.SEXMEX) {
                                        {
                                            TextButton(
                                                onClick = { viewModel.exploreLatestSexMex() },
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.testTag("sexmex_explore_button")
                                            ) {
                                                Text(
                                                    text = "Explore",
                                                    style = MaterialTheme.typography.titleSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 0.5.sp
                                                    ),
                                                    color = accent
                                                )
                                            }
                                        }
                                    } else null
                                )
                            }
                        }
                    }

                    val showScrollToTop by remember {
                        derivedStateOf {
                            gridState.firstVisibleItemIndex > 2 || (gridState.firstVisibleItemIndex > 0 && gridState.firstVisibleItemScrollOffset > 80)
                        }
                    }

                    val navBarHeight = settings.navBarHeightDp.coerceIn(48, 80).dp

                    ScrollToTopButton(
                        visible = showScrollToTop,
                        onClick = {
                            coroutineScope.launch {
                                isHorizontalResultsVisible = true
                                gridState.animateScrollToItem(0)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding(),
                        size = 42.dp,
                        bottomPadding = navBarHeight + 24.dp,
                        endPadding = 16.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun StashSortAnimatedCardItem(
    index: Int,
    sortKey: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val initialOffsetY = remember(density) { with(density) { 14.dp.toPx() } }
    val progress = remember(sortKey) { Animatable(0f) }

    LaunchedEffect(sortKey) {
        val staggerDelay = (index.coerceAtMost(7)) * 20
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 260,
                delayMillis = staggerDelay,
                easing = FastOutSlowInEasing
            )
        )
    }

    Box(
        modifier = modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            translationY = (1f - p) * initialOffsetY
            val scale = 0.965f + (0.035f * p)
            scaleX = scale
            scaleY = scale
        }
    ) {
        content()
    }
}

