package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.entity.ActorEntity
import com.example.data.local.entity.StudioEntity
import com.example.ui.MainViewModel
import com.example.ui.ScreenState
import com.example.ui.SortMode
import com.example.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val links by viewModel.filteredLinks.collectAsStateWithLifecycle()
    val actors by viewModel.allActors.collectAsStateWithLifecycle()
    val studios by viewModel.allStudios.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val currentSort by viewModel.sortMode.collectAsStateWithLifecycle()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsStateWithLifecycle()
    val viewFilter by viewModel.viewFilter.collectAsStateWithLifecycle()
    val resolvingStatus by viewModel.resolvingVideoStatus.collectAsStateWithLifecycle()
    val resolvingCardId by viewModel.resolvingCardId.collectAsStateWithLifecycle()
    val videoResolutionError by viewModel.videoResolutionError.collectAsStateWithLifecycle()
    val activeInlineVideo by viewModel.activeInlineVideo.collectAsStateWithLifecycle()
    val currentScreen by viewModel.screenState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isInitialDataLoaded by viewModel.isInitialDataLoaded.collectAsStateWithLifecycle()

    val targetActor = remember(currentScreen, actors) {
        if (currentScreen is ScreenState.ActorScenes) {
            val id = (currentScreen as ScreenState.ActorScenes).actorId
            actors.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) }
                ?: ActorEntity(id = id, name = id)
        } else null
    }

    val targetStudio = remember(currentScreen, studios) {
        if (currentScreen is ScreenState.StudioScenes) {
            val id = (currentScreen as ScreenState.StudioScenes).studioId
            studios.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) }
                ?: StudioEntity(id = id, name = id)
        } else null
    }

    val displayedLinks = remember(links, currentScreen, targetActor, targetStudio, currentSort) {
        val filtered = when (currentScreen) {
            is ScreenState.ActorScenes -> {
                val actorId = (currentScreen as ScreenState.ActorScenes).actorId
                links.filter { it.actorIds.contains(actorId) || (targetActor != null && it.actorIds.contains(targetActor.name)) }
            }
            is ScreenState.StudioScenes -> {
                val studioId = (currentScreen as ScreenState.StudioScenes).studioId
                links.filter { it.studioIds.contains(studioId) || (targetStudio != null && it.studioIds.contains(targetStudio.name)) }
            }
            else -> links
        }
        when (currentSort) {
            SortMode.CARD_NEWEST, SortMode.NEWEST -> filtered.sortedByDescending { it.assignedDate ?: it.createdAt }
            SortMode.CARD_OLDEST, SortMode.OLDEST -> filtered.sortedBy { it.assignedDate ?: it.createdAt }
            SortMode.RECENTLY_ADDED -> filtered.sortedByDescending { it.createdAt }
            SortMode.OLDEST_ADDED -> filtered.sortedBy { it.createdAt }
            SortMode.TITLE_AZ -> filtered.sortedBy { it.title.lowercase() }
            SortMode.TITLE_ZA -> filtered.sortedByDescending { it.title.lowercase() }
        }
    }

    // Precomputed Fast Lookup Maps
    val actorsMap = remember(actors) {
        val map = mutableMapOf<String, String>()
        actors.forEach { actor ->
            map[actor.id] = actor.name
            map[actor.name] = actor.name
            map[actor.name.trim().lowercase()] = actor.name
            if (!actor.stashDbId.isNullOrBlank()) {
                map[actor.stashDbId] = actor.name
            }
        }
        map
    }
    val fullActorsMap = remember(actors) {
        val map = mutableMapOf<String, ActorEntity>()
        actors.forEach { actor ->
            map[actor.id] = actor
            map[actor.name] = actor
            map[actor.name.trim().lowercase()] = actor
            if (!actor.stashDbId.isNullOrBlank()) {
                map[actor.stashDbId] = actor
            }
        }
        map
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

    var activeOverlayCardId by remember { mutableStateOf<String?>(null) }
    var showEditActorDialog by remember { mutableStateOf(false) }
    var showEditStudioDialog by remember { mutableStateOf(false) }

    val scrollKey = remember(currentScreen, targetActor?.id, targetStudio?.id) {
        when {
            targetActor != null -> "actor_scenes_${targetActor.id}"
            targetStudio != null -> "studio_scenes_${targetStudio.id}"
            currentScreen is ScreenState.ActorScenes -> "actor_scenes_${(currentScreen as ScreenState.ActorScenes).actorId}"
            currentScreen is ScreenState.StudioScenes -> "studio_scenes_${(currentScreen as ScreenState.StudioScenes).studioId}"
            else -> "feed_home"
        }
    }

    val initialScroll = remember(scrollKey) { viewModel.getScrollPosition(scrollKey) }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScroll.first,
        initialFirstVisibleItemScrollOffset = initialScroll.second
    )

    // Continuously remember the user's exact scroll position in ViewModel for this specific screen/feed
    LaunchedEffect(listState, scrollKey) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.saveScrollPosition(scrollKey, index, offset)
            }
    }

    // Scroll to top immediately when the user modifies sort, filter, or search query
    var previousSort by rememberSaveable { mutableStateOf(currentSort.name) }
    var previousFilter by rememberSaveable { mutableStateOf(viewFilter) }
    var previousQuery by rememberSaveable { mutableStateOf(searchQuery) }

    LaunchedEffect(currentSort, viewFilter, searchQuery, scrollKey) {
        if (previousSort != currentSort.name || previousFilter != viewFilter || previousQuery != searchQuery) {
            previousSort = currentSort.name
            previousFilter = viewFilter
            previousQuery = searchQuery
            viewModel.saveScrollPosition(scrollKey, 0, 0)
            listState.scrollToItem(
                index = 0,
                scrollOffset = 0
            )
        }
        activeOverlayCardId = null
    }

    // Smoothly scroll to top when Home active tab is tapped / reselected
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            if (displayedLinks.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
            viewModel.saveScrollPosition(scrollKey, 0, 0)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            HomeTopAppBar(
                currentScreen = currentScreen,
                targetActor = targetActor,
                targetStudio = targetStudio,
                actorsMap = actorsMap,
                studiosMap = studiosMap,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.searchQuery.value = it },
                currentSort = currentSort,
                onSortChange = { newSort ->
                    viewModel.sortMode.value = newSort
                    viewModel.saveScrollPosition(scrollKey, 0, 0)
                    coroutineScope.launch {
                        listState.scrollToItem(0, 0)
                    }
                },
                onOpenDrawer = onOpenDrawer,
                onNavigateBack = { viewModel.navigateBack() },
                onAddScene = { viewModel.navigateTo(ScreenState.AddEditLink()) },
                onEditActor = { showEditActorDialog = true },
                onEditStudio = { showEditStudioDialog = true },
                onSearchActorStash = {
                    if (targetActor != null) {
                        viewModel.searchStashForActor(targetActor.name, settings.stashDbApiKey)
                    }
                },
                onSearchStudioStash = {
                    if (targetStudio != null) {
                        viewModel.searchStashForStudio(targetStudio.name, settings.stashDbApiKey)
                    }
                }
            )
        }
    ) { paddingValues ->
        if (!isInitialDataLoaded) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(3) {
                    SkeletonCard(height = 240)
                }
            }
        } else if (displayedLinks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
            ) {
                if (targetActor != null || targetStudio != null) {
                    ActorStudioHeaderBanner(
                        actor = targetActor,
                        studio = targetStudio,
                        sceneCount = 0,
                        onAvatarClick = {
                            if (targetActor != null) {
                                viewModel.searchStashForActor(targetActor.name, settings.stashDbApiKey)
                            } else if (targetStudio != null) {
                                viewModel.searchStashForStudio(targetStudio.name, settings.stashDbApiKey)
                            }
                        }
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(60.dp)
                        )
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No results for '$searchQuery'"
                                   else if (targetActor != null) "No scenes for ${targetActor.name}"
                                   else if (targetStudio != null) "No scenes for ${targetStudio.name}"
                                   else "Vault is Empty",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Try searching with different keywords"
                                   else if (targetActor != null || targetStudio != null) "Tap the '+' icon to link scenes to this entity."
                                   else "Tap the '+' icon in the top bar to add scenes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = PaddingValues(bottom = 116.dp)
            ) {
                if (targetActor != null || targetStudio != null) {
                    item(key = "actor_studio_header_banner") {
                        ActorStudioHeaderBanner(
                            actor = targetActor,
                            studio = targetStudio,
                            sceneCount = displayedLinks.size,
                            onAvatarClick = {
                                if (targetActor != null) {
                                    viewModel.searchStashForActor(targetActor.name, settings.stashDbApiKey)
                                } else if (targetStudio != null) {
                                    viewModel.searchStashForStudio(targetStudio.name, settings.stashDbApiKey)
                                }
                            }
                        )
                    }
                }

                items(displayedLinks, key = { "${currentSort.name}_${it.id}" }) { link ->
                    val isBookmarked = remember(bookmarkedIds, link.id) {
                        bookmarkedIds.contains(link.id)
                    }
                    val isActive = activeOverlayCardId == link.id

                    Box(
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                            fadeOutSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    ) {
                        LinkCard(
                            link = link,
                            actorsMap = actorsMap,
                            studiosMap = studiosMap,
                            fullActorsMap = fullActorsMap,
                            preferredActorId = targetActor?.id ?: targetActor?.name,
                            isBookmarked = isBookmarked,
                            isActiveCard = isActive,
                            onActivate = { activeOverlayCardId = link.id },
                            onDismissActive = {
                                if (activeOverlayCardId == link.id) {
                                    activeOverlayCardId = null
                                }
                            },
                            onToggleBookmark = { viewModel.toggleBookmark(link.id) },
                            onPlay = { url -> viewModel.playVideo(url, link.title, cardId = link.id, scene = link) },
                            onEdit = {
                                viewModel.navigateTo(ScreenState.AddEditLink(link.id))
                            },
                            onDelete = {
                                viewModel.deleteLink(link.id)
                            },
                            onActorClick = { actorId ->
                                viewModel.navigateTo(ScreenState.ActorScenes(actorId))
                            },
                            onStudioClick = { studioId ->
                                viewModel.navigateTo(ScreenState.StudioScenes(studioId))
                            },
                            onImageError = { viewModel.autoRefreshSexMexCoverIfNeeded(link) },
                            resolvingStatus = resolvingStatus,
                            isResolvingThisCard = resolvingCardId == link.id,
                            resolutionError = if (resolvingCardId == link.id) videoResolutionError else null,
                            onDismissResolutionError = { viewModel.dismissVideoError() },
                            inlinePlayback = if (activeInlineVideo?.cardId == link.id) activeInlineVideo else null,
                            onCloseInlineVideo = { viewModel.closeInlineVideo(link.id) },
                            onFullscreenInlineVideo = { currentPos ->
                                viewModel.openFullscreenFromInline(link.id, currentPos, startInLandscape = true)
                            },
                            onFullscreenInlineVideoWithMode = { currentPos, startInLandscape ->
                                viewModel.openFullscreenFromInline(link.id, currentPos, startInLandscape = startInLandscape)
                            },
                            onEnterPipInlineVideo = { currentPos ->
                                viewModel.enterPipFromInline(link.id, currentPos)
                            },
                            exoPlayer = if (activeInlineVideo?.cardId == link.id) viewModel.sharedPlayerManager.getPlayer() else null,
                            enableVideoPlayerGestures = settings.enableVideoPlayerGestures
                        )
                    }
                }
            }
        }
    }

    // Actor Details & Deletion Dialog
    if (showEditActorDialog && targetActor != null) {
        ActorDetailsDialog(
            actor = targetActor,
            stashDbApiKey = settings.stashDbApiKey,
            onDismiss = { showEditActorDialog = false },
            onSave = { updatedActor ->
                viewModel.saveActor(updatedActor)
                showEditActorDialog = false
            },
            onDeleteCascade = { actorId ->
                showEditActorDialog = false
                viewModel.deleteActorWithCascade(actorId)
                viewModel.navigateBack()
            }
        )
    }

    // Studio Details & Deletion Dialog
    if (showEditStudioDialog && targetStudio != null) {
        StudioDetailsDialog(
            studio = targetStudio,
            onDismiss = { showEditStudioDialog = false },
            onSave = { updatedStudio ->
                viewModel.saveStudio(updatedStudio)
                showEditStudioDialog = false
            },
            onDeleteCascade = { studioId ->
                showEditStudioDialog = false
                viewModel.deleteStudioWithCascade(studioId)
                viewModel.navigateBack()
            }
        )
    }
}
