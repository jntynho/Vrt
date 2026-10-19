package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.network.KanimeSortOrder
import com.example.network.KstreamMediaItem
import com.example.ui.MainViewModel
import com.example.ui.components.AppLottieLoadingAnimation
import com.example.ui.components.ShimmerBrush
import com.example.ui.components.vaultTopGlow
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalBetaTestPrivacy
import com.example.ui.theme.LocalVaultPalette
import com.example.ui.theme.privacyImageBlur

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanimeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    val feed by viewModel.kanimeFeed.collectAsStateWithLifecycle()
    val isLoading by viewModel.isKanimeLoading.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isKanimeLoadingMore.collectAsStateWithLifecycle()
    val error by viewModel.kanimeError.collectAsStateWithLifecycle()
    val canLoadMore by viewModel.kanimeCanLoadMore.collectAsStateWithLifecycle()
    val currentSort by viewModel.kanimeSortOrder.collectAsStateWithLifecycle()
    val currentSearchQuery by viewModel.kanimeSearchQuery.collectAsStateWithLifecycle()

    val initialScroll = remember { viewModel.getScrollPosition("kanime_grid") }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialScroll.first,
        initialFirstVisibleItemScrollOffset = initialScroll.second
    )

    // Continuously save the exact scroll position in ViewModel
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.saveScrollPosition("kanime_grid", index, offset)
            }
    }

    var isSearchExpanded by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf(currentSearchQuery) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Sync input when query changes
    LaunchedEffect(currentSearchQuery) {
        searchInput = currentSearchQuery
    }

    // Initial feed fetch
    LaunchedEffect(Unit) {
        viewModel.loadKanimeFeed()
    }

    // Smoothly scroll to top and reset saved scroll only when sort or search query actually changes
    var lastSort by remember { mutableStateOf(currentSort) }
    var lastQuery by remember { mutableStateOf(currentSearchQuery) }
    LaunchedEffect(currentSort, currentSearchQuery) {
        if (lastSort != currentSort || lastQuery != currentSearchQuery) {
            lastSort = currentSort
            lastQuery = currentSearchQuery
            viewModel.saveScrollPosition("kanime_grid", 0, 0)
            if (feed.isNotEmpty()) {
                gridState.animateScrollToItem(
                    index = 0,
                    scrollOffset = 0
                )
            }
        }
    }

    // Handle back button: close search first if open
    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        searchInput = ""
        if (currentSearchQuery.isNotEmpty()) {
            viewModel.setKanimeSearchQuery("")
        }
    }

    // Infinite scroll detection
    val shouldLoadMore by remember {
        derivedStateOf {
            val totalItems = gridState.layoutInfo.totalItemsCount
            val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleIndex >= totalItems - 4
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !isLoading && !isLoadingMore && canLoadMore) {
            viewModel.loadMoreKanime()
        }
    }

    val sortOptions = remember { KanimeSortOrder.entries }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.bg,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchExpanded) {
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                        BasicTextField(
                            value = searchInput,
                            onValueChange = { searchInput = it },
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
                                    viewModel.setKanimeSearchQuery(searchInput)
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .testTag("kanime_header_search_input"),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (searchInput.isEmpty()) {
                                        Text(
                                            text = "Search Kanime...",
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
                            text = if (currentSearchQuery.isNotBlank()) "Kanime: \"$currentSearchQuery\"" else "Kanime",
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
                    if (isSearchExpanded) {
                        IconButton(
                            onClick = {
                                isSearchExpanded = false
                                searchInput = ""
                                if (currentSearchQuery.isNotEmpty()) {
                                    viewModel.setKanimeSearchQuery("")
                                }
                            },
                            modifier = Modifier.testTag("close_kanime_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close Search",
                                tint = palette.textPrimary
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { viewModel.navigateBack() },
                            modifier = Modifier.testTag("kanime_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = palette.textPrimary
                            )
                        }
                    }
                },
                actions = {
                    if (isSearchExpanded) {
                        if (searchInput.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.setKanimeSearchQuery(searchInput)
                                },
                                modifier = Modifier.testTag("kanime_header_search_submit")
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_app_search),
                                    contentDescription = "Search",
                                    tint = accent
                                )
                            }
                            IconButton(
                                onClick = {
                                    searchInput = ""
                                    if (currentSearchQuery.isNotEmpty()) {
                                        viewModel.setKanimeSearchQuery("")
                                    }
                                },
                                modifier = Modifier.testTag("clear_kanime_search_text_button")
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_action_cancel),
                                    contentDescription = "Clear text",
                                    tint = palette.textSecondary
                                )
                            }
                        }
                    } else {
                        // Search Action Button
                        IconButton(
                            onClick = { isSearchExpanded = true },
                            modifier = Modifier.testTag("kanime_search_action_button")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_search),
                                contentDescription = "Search",
                                tint = if (currentSearchQuery.isNotBlank()) accent else palette.textPrimary
                            )
                        }

                        // Sort Action Button with standard unified DropdownMenu design
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.testTag("kanime_sort_action_button")
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_app_sort),
                                    contentDescription = "Sort",
                                    tint = palette.textPrimary
                                )
                            }

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.vaultTopGlow(cornerRadius = 16.dp),
                                shape = RoundedCornerShape(16.dp),
                                containerColor = palette.cardBg
                            ) {
                                sortOptions.forEach { order ->
                                    val isSelected = currentSort == order
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = order.displayName,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) accent else palette.textPrimary
                                            )
                                        },
                                        leadingIcon = {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = accent
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.setKanimeSortOrder(order)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.bg,
                    titleContentColor = palette.textPrimary
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading && feed.isEmpty() -> {
                    // Modern Lottie Loading Animation as in LinkCard
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AppLottieLoadingAnimation(
                            modifier = Modifier.size(70.dp)
                        )
                    }
                }
                error != null && feed.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = error ?: "Failed to load content",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.loadKanimeFeed(forceRefresh = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Text("Retry", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                feed.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (currentSearchQuery.isNotBlank()) "No results found for \"$currentSearchQuery\"" else "No content available",
                            color = palette.textSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(feed, key = { it.id }) { item ->
                            Box(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                                    fadeOutSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            ) {
                                KanimeCleanPosterCard(
                                    item = item,
                                    onClick = { viewModel.openKanimeItem(item) }
                                )
                            }
                        }

                        // Infinite scroll bottom loading indicator (Lottie loading animation)
                        if (isLoadingMore) {
                            item(span = { GridItemSpan(2) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AppLottieLoadingAnimation(
                                        modifier = Modifier.size(36.dp)
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

/**
 * Clean Poster Card:
 * Pure poster image with the same loading animation (ShimmerBrush + crossfade + scale/alpha fade-in)
 * as LinkCard and the rest of the project.
 * The title is strictly below the card, restricted to 1 line only.
 */
@Composable
private fun KanimeCleanPosterCard(
    item: KstreamMediaItem,
    onClick: () -> Unit
) {
    val palette = LocalVaultPalette.current
    val context = LocalContext.current
    val isBetaTest = LocalBetaTestPrivacy.current

    var isImageLoaded by remember(item.posterUrl) { mutableStateOf(false) }
    var isImageError by remember(item.posterUrl) { mutableStateOf(false) }

    val imageAlpha by animateFloatAsState(
        targetValue = if (isImageLoaded && !isImageError) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "poster_alpha"
    )
    val imageScale by animateFloatAsState(
        targetValue = if (isImageLoaded && !isImageError) 1f else 0.96f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "poster_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        // Pure poster image box (Aspect ratio 2:3)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.cardBg),
            contentAlignment = Alignment.Center
        ) {
            // Shimmer animation matching LinkCard while loading
            if (!isImageLoaded && !isImageError) {
                val shimmerBrush = ShimmerBrush(targetValue = 900f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.cardBg)
                        .background(shimmerBrush)
                )
            }

            if (!isImageError) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.posterUrl)
                        .crossfade(260)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    onSuccess = {
                        isImageLoaded = true
                        isImageError = false
                    },
                    onError = {
                        isImageLoaded = true
                        isImageError = true
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = imageAlpha
                            scaleX = imageScale
                            scaleY = imageScale
                        }
                        .privacyImageBlur(isBetaTest)
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nav_home),
                    contentDescription = "Image Error",
                    tint = palette.textSecondary.copy(alpha = 0.35f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Title strictly below the card, limited to 1 line only
        Text(
            text = item.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = palette.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
