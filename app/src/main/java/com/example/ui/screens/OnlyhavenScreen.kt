package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.example.network.OnlyhavenSortMode
import com.example.ui.components.AppLottieLoadingAnimation
import com.example.ui.components.OnlyhavenModelCard
import com.example.ui.components.vaultTopGlow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.network.OnlyhavenCreator
import com.example.ui.MainViewModel
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlyhavenScreen(
    viewModel: MainViewModel,
    onOpenDrawer: () -> Unit = {}
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current

    val creators by viewModel.onlyhavenCreators.collectAsStateWithLifecycle()
    val searchQuery by viewModel.onlyhavenSearchQuery.collectAsStateWithLifecycle()
    val currentSort by viewModel.onlyhavenSortMode.collectAsStateWithLifecycle()
    val isLoading by viewModel.isOnlyhavenLoading.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isOnlyhavenLoadingMore.collectAsStateWithLifecycle()
    val canLoadMore by viewModel.onlyhavenCanLoadMore.collectAsStateWithLifecycle()
    val currentPage by viewModel.onlyhavenCurrentPage.collectAsStateWithLifecycle()
    val errorMessage by viewModel.onlyhavenErrorMessage.collectAsStateWithLifecycle()
    val savedCreatorKeys by viewModel.savedCreatorKeys.collectAsStateWithLifecycle()

    var showSortMenu by remember { mutableStateOf(false) }

    val scrollKey = "onlyhaven_creators"
    val initialScroll = remember(scrollKey) { viewModel.getScrollPosition(scrollKey) }

    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialScroll.first,
        initialFirstVisibleItemScrollOffset = initialScroll.second
    )

    // Continuously save scroll position into ViewModel registry
    LaunchedEffect(gridState, scrollKey) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (creators.isNotEmpty()) {
                    viewModel.saveScrollPosition(scrollKey, index, offset)
                }
            }
    }

    // Restore scroll position if creators were loaded asynchronously
    var hasRestoredInitialScroll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(creators.isNotEmpty()) {
        if (creators.isNotEmpty() && !hasRestoredInitialScroll) {
            val (savedIndex, savedOffset) = viewModel.getScrollPosition(scrollKey)
            if (savedIndex > 0 || savedOffset > 0) {
                if (savedIndex < creators.size) {
                    gridState.scrollToItem(savedIndex, savedOffset)
                }
            }
            hasRestoredInitialScroll = true
        }
    }

    var previousQuery by rememberSaveable { mutableStateOf(searchQuery) }
    var previousSort by rememberSaveable { mutableStateOf(currentSort.name) }
    var needsScrollToTop by remember { mutableStateOf(false) }

    // Reset scroll position to top when search query changes
    LaunchedEffect(searchQuery) {
        if (previousQuery != searchQuery) {
            previousQuery = searchQuery
            needsScrollToTop = true
            viewModel.saveScrollPosition(scrollKey, 0, 0)
            gridState.scrollToItem(0, 0)
        }
    }

    // Reset scroll position to top when sort mode changes
    LaunchedEffect(currentSort) {
        if (previousSort != currentSort.name) {
            previousSort = currentSort.name
            needsScrollToTop = true
            viewModel.saveScrollPosition(scrollKey, 0, 0)
            gridState.scrollToItem(0, 0)
        }
    }

    // Ensure top position is applied when new creators arrive after sort or query change
    LaunchedEffect(creators) {
        if (needsScrollToTop && creators.isNotEmpty()) {
            gridState.scrollToItem(0, 0)
            viewModel.saveScrollPosition(scrollKey, 0, 0)
            needsScrollToTop = false
        }
    }

    LaunchedEffect(Unit) {
        if (creators.isEmpty() && !isLoading) {
            viewModel.loadOnlyhavenCreators(query = searchQuery, page = 1)
        }
    }

    val shouldLoadMore = remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 4
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && !isLoading && !isLoadingMore && canLoadMore) {
            viewModel.loadOnlyhavenCreators(query = searchQuery, page = currentPage + 1, append = true)
        }
    }

    var isSearchExpanded by remember { mutableStateOf(false) }

    val isLight = palette.name.equals("light", ignoreCase = true)
    val circleBorderColor = if (isLight) Color.Black else Color.White

    Scaffold(
        containerColor = palette.bg,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            com.example.ui.components.SearchableTophead(
                title = "Onlyhaven",
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setOnlyhavenSearchQuery(it) },
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { isSearchExpanded = it },
                placeholder = "Search model (e.g. demon_mika)...",
                testTag = "search_models_input",
                onBack = { viewModel.navigateBack() },
                showBorder = false,
                actions = {
                    if (!isSearchExpanded) {
                        // Saved Models Action
                        IconButton(
                            onClick = { viewModel.navigateTo(com.example.ui.ScreenState.OnlyhavenSavedCreators) },
                            modifier = Modifier.testTag("saved_models_head_button")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_heart_filled),
                                contentDescription = "Saved Models",
                                tint = palette.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Search Action
                        IconButton(
                            onClick = { isSearchExpanded = true },
                            modifier = Modifier.testTag("search_creators_action_button")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_search),
                                contentDescription = "Search",
                                tint = palette.textPrimary
                            )
                        }

                        // Sort Action with Unified Dropdown Menu
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.testTag("sort_models_button")
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_app_sort),
                                    contentDescription = "Sort Models",
                                    tint = palette.textPrimary
                                )
                            }

                            com.example.ui.components.VaultSortDropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                options = OnlyhavenSortMode.entries.map { mode ->
                                    com.example.ui.components.VaultSortOption(
                                        value = mode,
                                        label = mode.displayName,
                                        isSelected = currentSort == mode
                                    )
                                },
                                onOptionSelected = { mode ->
                                    if (currentSort != mode) {
                                        viewModel.setOnlyhavenSortMode(mode)
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading && creators.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AppLottieLoadingAnimation(modifier = Modifier.size(64.dp))
                }
            } else if (errorMessage != null && creators.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = errorMessage ?: "Failed to connect to Onlyhaven",
                            color = palette.textSecondary,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.loadOnlyhavenCreators(query = searchQuery, page = 1) },
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Text("Retry", color = Color.White)
                        }
                    }
                }
            } else if (creators.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No models found", color = palette.textSecondary, fontSize = 16.sp)
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(1),
                    contentPadding = PaddingValues(
                        top = 16.dp,
                        bottom = 116.dp,
                        start = 14.dp,
                        end = 14.dp
                    ),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(creators, key = { "row_${it.service}_${it.id}" }) { creator ->
                        val isSaved = savedCreatorKeys.contains("${creator.service}_${creator.id}")

                        OnlyhavenModelCard(
                            creator = creator,
                            palette = palette,
                            accent = accent,
                            isLight = isLight,
                            circleBorderColor = circleBorderColor,
                            isSaved = isSaved,
                            onToggleSave = {
                                viewModel.toggleSaveOnlyhavenCreator(creator)
                            },
                            onClick = {
                                viewModel.navigateTo(com.example.ui.ScreenState.OnlyhavenCreatorDetails(creator))
                            }
                        )
                    }

                    if (isLoadingMore) {
                        item {
                            LoadingMoreIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingMoreIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        AppLottieLoadingAnimation(modifier = Modifier.size(36.dp))
    }
}
