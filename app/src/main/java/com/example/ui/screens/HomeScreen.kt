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
import com.example.ui.components.*
import com.example.ui.theme.LocalVaultPalette

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    screen: ScreenState? = null,
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val links by viewModel.filteredLinks.collectAsStateWithLifecycle()
    val actors by viewModel.allActors.collectAsStateWithLifecycle()
    val studios by viewModel.allStudios.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val currentSort by viewModel.sortMode.collectAsStateWithLifecycle()
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsStateWithLifecycle()
    val resolvingStatus by viewModel.resolvingVideoStatus.collectAsStateWithLifecycle()
    val resolvingCardId by viewModel.resolvingCardId.collectAsStateWithLifecycle()
    val videoResolutionError by viewModel.videoResolutionError.collectAsStateWithLifecycle()
    val activeInlineVideo by viewModel.activeInlineVideo.collectAsStateWithLifecycle()
    val currentScreenState by viewModel.screenState.collectAsStateWithLifecycle()
    val currentScreen = screen ?: currentScreenState
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isInitialDataLoaded by viewModel.isInitialDataLoaded.collectAsStateWithLifecycle()

    val targetActor = remember(currentScreen, actors) {
        if (currentScreen is ScreenState.ActorScenes) {
            val id = currentScreen.actorId.trim()
            actors.firstOrNull { 
                it.id == id || 
                it.name.equals(id, ignoreCase = true) ||
                (!it.stashDbId.isNullOrBlank() && it.stashDbId.equals(id, ignoreCase = true))
            } ?: ActorEntity(id = id, name = id)
        } else null
    }

    val targetStudio = remember(currentScreen, studios) {
        if (currentScreen is ScreenState.StudioScenes) {
            val id = currentScreen.studioId.trim()
            studios.firstOrNull { 
                it.id == id || 
                it.name.equals(id, ignoreCase = true) ||
                (!it.stashDbId.isNullOrBlank() && it.stashDbId.equals(id, ignoreCase = true))
            } ?: StudioEntity(id = id, name = id)
        } else null
    }

    // links (viewModel.filteredLinks) is already sorted on Dispatchers.Default; filtering preserves sort order
    val displayedLinks = remember(links, currentScreen, targetActor, targetStudio) {
        when (currentScreen) {
            is ScreenState.ActorScenes -> {
                val rawId = currentScreen.actorId.trim()
                val validIds = buildSet {
                    add(rawId)
                    add(rawId.lowercase())
                    if (targetActor != null) {
                        add(targetActor.id)
                        add(targetActor.id.lowercase())
                        add(targetActor.name)
                        add(targetActor.name.trim().lowercase())
                        targetActor.stashDbId?.let {
                            add(it)
                            add(it.lowercase())
                        }
                    }
                }
                links.filter { link ->
                    link.actorIds.any { id ->
                        validIds.contains(id) || validIds.contains(id.trim().lowercase())
                    }
                }
            }
            is ScreenState.StudioScenes -> {
                val rawId = currentScreen.studioId.trim()
                val validIds = buildSet {
                    add(rawId)
                    add(rawId.lowercase())
                    if (targetStudio != null) {
                        add(targetStudio.id)
                        add(targetStudio.id.lowercase())
                        add(targetStudio.name)
                        add(targetStudio.name.trim().lowercase())
                        targetStudio.stashDbId?.let {
                            add(it)
                            add(it.lowercase())
                        }
                    }
                }
                links.filter { link ->
                    link.studioIds.any { id ->
                        validIds.contains(id) || validIds.contains(id.trim().lowercase())
                    }
                }
            }
            else -> links
        }
    }

    // Single-Pass Precomputed Fast Lookup Maps
    val (actorsMap, fullActorsMap) = remember(actors) {
        val nameMap = mutableMapOf<String, String>()
        val entityMap = mutableMapOf<String, ActorEntity>()
        actors.forEach { actor ->
            val lowerName = actor.name.trim().lowercase()
            nameMap[actor.id] = actor.name
            nameMap[actor.name] = actor.name
            nameMap[lowerName] = actor.name
            entityMap[actor.id] = actor
            entityMap[actor.name] = actor
            entityMap[lowerName] = actor
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

    var activeOverlayCardId by remember { mutableStateOf<String?>(null) }
    var showEditActorDialog by remember { mutableStateOf(false) }
    var showEditStudioDialog by remember { mutableStateOf(false) }

    val scrollKey = remember(currentScreen, targetActor?.id, targetStudio?.id) {
        when (currentScreen) {
            is ScreenState.ActorScenes -> "actor_scenes_${targetActor?.id ?: currentScreen.actorId}"
            is ScreenState.StudioScenes -> "studio_scenes_${targetStudio?.id ?: currentScreen.studioId}"
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

    // Scroll to top immediately when the user modifies sort or search query
    var previousSort by rememberSaveable { mutableStateOf(currentSort.name) }
    var previousQuery by rememberSaveable { mutableStateOf(searchQuery) }

    LaunchedEffect(currentSort, searchQuery, scrollKey) {
        if (previousSort != currentSort.name || previousQuery != searchQuery) {
            previousSort = currentSort.name
            previousQuery = searchQuery
            viewModel.saveScrollPosition(scrollKey, 0, 0)
            if (displayedLinks.isNotEmpty()) {
                listState.scrollToItem(index = 0, scrollOffset = 0)
            }
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
        containerColor = palette.bg,
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
                },
                onOpenDrawer = onOpenDrawer,
                onNavigateBack = { viewModel.navigateBack() },
                onAddScene = { viewModel.navigateTo(ScreenState.AddEditLink()) },
                onEditActor = { showEditActorDialog = true },
                onEditStudio = { showEditStudioDialog = true }
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
                            tint = palette.textMuted,
                            modifier = Modifier.size(60.dp)
                        )
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No results for '$searchQuery'"
                                   else if (targetActor != null) "No scenes for ${targetActor.name}"
                                   else if (targetStudio != null) "No scenes for ${targetStudio.name}"
                                   else "Vault is Empty",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Try searching with different keywords"
                                   else if (targetActor != null || targetStudio != null) "No scenes associated with this ${if (targetActor != null) "actor" else "studio"} yet."
                                   else "Tap the '+' icon in the top bar to add scenes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary
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

                items(displayedLinks, key = { it.id }) { link ->
                    val isBookmarked = remember(bookmarkedIds, link.id) {
                        bookmarkedIds.contains(link.id)
                    }
                    val isActive = activeOverlayCardId == link.id

                    Box(
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                            fadeOutSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                            placementSpec = null
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
                            enableVideoPlayerGestures = settings.enableVideoPlayerGestures,
                            adaptiveCoverAspectRatio = settings.adaptiveCoverAspectRatio
                        )
                    }
                }
            }
        }
    }

    // Actor Details & Deletion Dialog
    if (showEditActorDialog && targetActor != null) {
        val otherActorNames = remember(actors, targetActor.id) {
            actors.filter { it.id != targetActor.id }.map { it.name }
        }
        ActorDetailsDialog(
            actor = targetActor,
            stashDbApiKey = settings.stashDbApiKey,
            existingNames = otherActorNames,
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
        val otherStudioNames = remember(studios, targetStudio.id) {
            studios.filter { it.id != targetStudio.id }.map { it.name }
        }
        StudioDetailsDialog(
            studio = targetStudio,
            existingNames = otherStudioNames,
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
