package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.entity.ActorEntity
import com.example.ui.MainViewModel
import com.example.ui.ScreenState
import com.example.ui.SortMode
import com.example.ui.components.LinkCard
import com.example.ui.components.SearchableTophead
import com.example.ui.components.SkeletonCard
import com.example.ui.components.vaultTopGlow
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

private data class SortOptionItem(
    val label: String,
    val mode: SortMode,
    val matches: (SortMode) -> Boolean
)

private val SORT_OPTIONS = listOf(
    SortOptionItem("New By Date", SortMode.CARD_NEWEST) { it == SortMode.CARD_NEWEST || it == SortMode.NEWEST },
    SortOptionItem("Old By Date", SortMode.CARD_OLDEST) { it == SortMode.CARD_OLDEST || it == SortMode.OLDEST },
    SortOptionItem("Recently Added", SortMode.RECENTLY_ADDED) { it == SortMode.RECENTLY_ADDED },
    SortOptionItem("Oldest Added", SortMode.OLDEST_ADDED) { it == SortMode.OLDEST_ADDED }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current

    val allLinks by viewModel.allLinks.collectAsStateWithLifecycle()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsStateWithLifecycle()
    val actors by viewModel.allActors.collectAsStateWithLifecycle()
    val studios by viewModel.allStudios.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val currentSort by viewModel.sortMode.collectAsStateWithLifecycle()
    val resolvingStatus by viewModel.resolvingVideoStatus.collectAsStateWithLifecycle()
    val resolvingCardId by viewModel.resolvingCardId.collectAsStateWithLifecycle()
    val videoResolutionError by viewModel.videoResolutionError.collectAsStateWithLifecycle()
    val activeInlineVideo by viewModel.activeInlineVideo.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isInitialDataLoaded by viewModel.isInitialDataLoaded.collectAsStateWithLifecycle()

    val (actorsMap, fullActorsMap) = remember(actors) {
        val nameMap = mutableMapOf<String, String>()
        val entityMap = mutableMapOf<String, ActorEntity>()
        actors.forEach { actor ->
            val trimmedLower = actor.name.trim().lowercase()
            nameMap[actor.id] = actor.name
            nameMap[actor.name] = actor.name
            nameMap[trimmedLower] = actor.name
            entityMap[actor.id] = actor
            entityMap[actor.name] = actor
            entityMap[trimmedLower] = actor
            if (!actor.stashDbId.isNullOrBlank()) {
                nameMap[actor.stashDbId] = actor.name
                entityMap[actor.stashDbId] = actor
            }
        }
        nameMap to entityMap
    }

    val studiosMap = remember(studios) {
        val map = mutableMapOf<String, String>()
        studios.forEach { studio ->
            map[studio.id] = studio.name
            map[studio.name] = studio.name
            map[studio.name.trim().lowercase()] = studio.name
            if (!studio.stashDbId.isNullOrBlank()) {
                map[studio.stashDbId] = studio.name
            }
        }
        map
    }

    var isSearchExpanded by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var activeOverlayCardId by remember { mutableStateOf<String?>(null) }

    val scrollKey = "feed_bookmarks"
    val initialScroll = remember { viewModel.getScrollPosition(scrollKey) }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScroll.first,
        initialFirstVisibleItemScrollOffset = initialScroll.second
    )

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.saveScrollPosition(scrollKey, index, offset)
            }
    }

    var previousSort by rememberSaveable { mutableStateOf(currentSort.name) }
    var previousQuery by rememberSaveable { mutableStateOf(searchQuery) }

    val bookmarkedLinks = remember(allLinks, bookmarkedIds, searchQuery, currentSort, actors, studios) {
        val bookmarked = allLinks.filter { bookmarkedIds.contains(it.id) }
        val searched = if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            val matchingActorIds = actors.filter { it.name.lowercase().contains(q) }.map { it.id }.toSet()
            val matchingStudioIds = studios.filter { it.name.lowercase().contains(q) }.map { it.id }.toSet()
            bookmarked.filter { link ->
                link.title.lowercase().contains(q) ||
                link.actorIds.any { matchingActorIds.contains(it) } ||
                link.studioIds.any { matchingStudioIds.contains(it) }
            }
        } else {
            bookmarked
        }

        when (currentSort) {
            SortMode.CARD_NEWEST, SortMode.NEWEST -> searched.sortedByDescending { it.assignedDate ?: it.createdAt }
            SortMode.CARD_OLDEST, SortMode.OLDEST -> searched.sortedBy { it.assignedDate ?: it.createdAt }
            SortMode.RECENTLY_ADDED -> searched.sortedByDescending { it.createdAt }
            SortMode.OLDEST_ADDED -> searched.sortedBy { it.createdAt }
            SortMode.TITLE_AZ -> searched.sortedBy { it.title.lowercase() }
            SortMode.TITLE_ZA -> searched.sortedByDescending { it.title.lowercase() }
        }
    }

    LaunchedEffect(currentSort, searchQuery) {
        if (previousSort != currentSort.name || previousQuery != searchQuery) {
            previousSort = currentSort.name
            previousQuery = searchQuery
            viewModel.saveScrollPosition(scrollKey, 0, 0)
            if (bookmarkedLinks.isNotEmpty()) {
                listState.scrollToItem(0)
            }
        }
        activeOverlayCardId = null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = palette.bg,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            SearchableTophead(
                title = "Bookmarks (${bookmarkedLinks.size})",
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.searchQuery.value = it },
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { isSearchExpanded = it },
                placeholder = "Search bookmarks...",
                testTag = "search_bookmarks_input",
                onBack = { viewModel.navigateBack() },
                showBorder = true,
                actions = {
                    IconButton(
                        onClick = { isSearchExpanded = true },
                        modifier = Modifier.testTag("search_action_button")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_app_search),
                            contentDescription = "Search"
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showSortMenu = true },
                            modifier = Modifier.testTag("sort_action_button")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_sort),
                                contentDescription = "Sort Mode"
                            )
                        }

                        com.example.ui.components.VaultSortDropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            SORT_OPTIONS.forEach { option ->
                                val isSelected = option.matches(currentSort)
                                com.example.ui.components.VaultSortDropdownItem(
                                    label = option.label,
                                    isSelected = isSelected,
                                    onClick = {
                                        viewModel.sortMode.value = option.mode
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!isInitialDataLoaded) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(3) {
                    SkeletonCard(height = 240)
                }
            }
        } else if (bookmarkedLinks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = "No bookmarked scenes yet",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Tap the Save button on any link card to bookmark it for quick access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(bottom = 116.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(bookmarkedLinks, key = { it.id }) { link ->
                    LinkCard(
                        link = link,
                        actorsMap = actorsMap,
                        studiosMap = studiosMap,
                        fullActorsMap = fullActorsMap,
                        isBookmarked = true,
                        isActiveCard = activeOverlayCardId == link.id,
                        onActivate = { activeOverlayCardId = link.id },
                        onDismissActive = {
                            if (activeOverlayCardId == link.id) activeOverlayCardId = null
                        },
                        onToggleBookmark = { viewModel.toggleBookmark(link.id) },
                        onPlay = { url -> viewModel.playVideo(url, link.title, link.id, scene = link) },
                        onEdit = { viewModel.navigateTo(ScreenState.AddEditLink(link.id)) },
                        onDelete = { viewModel.deleteLink(link.id) },
                        onActorClick = { actorId -> viewModel.navigateTo(ScreenState.ActorScenes(actorId)) },
                        onStudioClick = { studioId -> viewModel.navigateTo(ScreenState.StudioScenes(studioId)) },
                        onImageError = { viewModel.autoRefreshSexMexCoverIfNeeded(link) },
                        resolvingStatus = resolvingStatus,
                        isResolvingThisCard = resolvingCardId == link.id,
                        resolutionError = if (resolvingCardId == link.id) videoResolutionError else null,
                        onDismissResolutionError = { viewModel.dismissVideoError() },
                        inlinePlayback = if (activeInlineVideo?.cardId == link.id) activeInlineVideo else null,
                        onCloseInlineVideo = { viewModel.closeInlineVideo(link.id) },
                        onFullscreenInlineVideo = { pos -> viewModel.openFullscreenFromInline(link.id, pos, startInLandscape = true) },
                        onFullscreenInlineVideoWithMode = { pos, startInLandscape -> viewModel.openFullscreenFromInline(link.id, pos, startInLandscape = startInLandscape) },
                        onEnterPipInlineVideo = { pos -> viewModel.enterPipFromInline(link.id, pos) },
                        exoPlayer = if (activeInlineVideo?.cardId == link.id) viewModel.sharedPlayerManager.getPlayer() else null,
                        enableVideoPlayerGestures = settings.enableVideoPlayerGestures,
                        lockInlinePlayerPortrait = settings.lockInlinePlayerPortrait,
                        adaptiveCoverAspectRatio = settings.adaptiveCoverAspectRatio
                    )
                }
            }
        }
    }
}
