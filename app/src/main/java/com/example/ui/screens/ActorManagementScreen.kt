package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.local.entity.ActorEntity
import com.example.ui.MainViewModel
import com.example.ui.ScreenState
import com.example.ui.components.SearchableTophead
import com.example.ui.components.VaultDialogShape
import com.example.ui.components.vaultTopGlow
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalBetaTestPrivacy
import com.example.ui.theme.LocalVaultPalette
import com.example.ui.theme.privacyImageBlur
import java.util.UUID

private val PrivacyScrim = Color.Black.copy(alpha = 0.75f)

enum class ManagementSortOption {
    NAME_AZ,
    NAME_ZA,
    NEWEST,
    OLDEST
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActorManagementScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val isLight = palette.name.equals("light", ignoreCase = true)
    val circleBorderColor = if (isLight) Color.Black else Color.White

    val actors by viewModel.allActors.collectAsStateWithLifecycle()
    val actorSceneCounts by viewModel.actorSceneCounts.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val currentScreen by viewModel.screenState.collectAsStateWithLifecycle()
    val showCards = settings.showManagementCards

    var showAddDialog by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(ManagementSortOption.NAME_AZ) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Search state
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    // BackHandler: Gracefully collapse search bar first on system back gesture
    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        searchQuery = ""
    }

    // Auto-open Add Dialog if navigated via ScreenState.AddEditActor
    LaunchedEffect(currentScreen) {
        if (currentScreen is ScreenState.AddEditActor) {
            showAddDialog = true
        }
    }

    val scrollKey = "management_actors"
    val initialScroll = remember { viewModel.getScrollPosition(scrollKey) }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialScroll.first,
        initialFirstVisibleItemScrollOffset = initialScroll.second
    )

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.saveScrollPosition(scrollKey, index, offset)
            }
    }

    // Reset scroll smoothly when sorting changes
    var previousSort by rememberSaveable { mutableStateOf(sortOption.name) }
    LaunchedEffect(sortOption) {
        if (previousSort != sortOption.name) {
            previousSort = sortOption.name
            viewModel.saveScrollPosition(scrollKey, 0, 0)
            gridState.scrollToItem(0)
        }
    }

    // Reset scroll to top when search query changes to prevent index desync
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) {
            gridState.scrollToItem(0)
        }
    }

    val sortedActors = remember(actors, sortOption) {
        when (sortOption) {
            ManagementSortOption.NAME_AZ -> actors.sortedBy { it.name.lowercase() }
            ManagementSortOption.NAME_ZA -> actors.sortedByDescending { it.name.lowercase() }
            ManagementSortOption.NEWEST -> actors.sortedByDescending { it.createdAt }
            ManagementSortOption.OLDEST -> actors.sortedBy { it.createdAt }
        }
    }

    // Instant in-memory search filtering
    val filteredActors = remember(sortedActors, searchQuery) {
        if (searchQuery.isBlank()) sortedActors
        else {
            val q = searchQuery.trim().lowercase()
            sortedActors.filter { it.name.lowercase().contains(q) }
        }
    }

    Scaffold(
        containerColor = palette.bg,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            SearchableTophead(
                title = "Actors (${actors.size})",
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { isSearchExpanded = it },
                placeholder = "Search actors...",
                testTag = "search_actors_input",
                onBack = { viewModel.navigateBack() },
                showBorder = false,
                actions = {
                    if (!isSearchExpanded) {
                        // Search Button
                        IconButton(
                            onClick = { isSearchExpanded = true },
                            modifier = Modifier.testTag("search_actors_action_button")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_search),
                                contentDescription = "Search",
                                tint = palette.textPrimary
                            )
                        }

                        // Sort Menu Button & Dropdown
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.testTag("sort_actors_button")
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
                                DropdownMenuItem(
                                    text = { Text("A - Z", color = if (sortOption == ManagementSortOption.NAME_AZ) accent else palette.textPrimary) },
                                    leadingIcon = {
                                        if (sortOption == ManagementSortOption.NAME_AZ) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = accent)
                                        }
                                    },
                                    onClick = {
                                        sortOption = ManagementSortOption.NAME_AZ
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Z - A", color = if (sortOption == ManagementSortOption.NAME_ZA) accent else palette.textPrimary) },
                                    leadingIcon = {
                                        if (sortOption == ManagementSortOption.NAME_ZA) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = accent)
                                        }
                                    },
                                    onClick = {
                                        sortOption = ManagementSortOption.NAME_ZA
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("New", color = if (sortOption == ManagementSortOption.NEWEST) accent else palette.textPrimary) },
                                    leadingIcon = {
                                        if (sortOption == ManagementSortOption.NEWEST) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = accent)
                                        }
                                    },
                                    onClick = {
                                        sortOption = ManagementSortOption.NEWEST
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Old", color = if (sortOption == ManagementSortOption.OLDEST) accent else palette.textPrimary) },
                                    leadingIcon = {
                                        if (sortOption == ManagementSortOption.OLDEST) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = accent)
                                        }
                                    },
                                    onClick = {
                                        sortOption = ManagementSortOption.OLDEST
                                        showSortMenu = false
                                    }
                                )
                            }
                        }

                        // Add Actor Button
                        IconButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.testTag("add_actor_button")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_add),
                                contentDescription = "Add Actor",
                                tint = palette.textPrimary
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (actors.isEmpty()) {
            // Empty Library State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nav_actor),
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(54.dp)
                    )
                    Text("No actors in library", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { showAddDialog = true },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {
                        Text("Add Actor", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (filteredActors.isEmpty()) {
            // Search Results Empty State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_app_search),
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No actors found for \"$searchQuery\"",
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        onClick = {
                            searchQuery = ""
                            isSearchExpanded = false
                        }
                    ) {
                        Text("Clear Search", color = accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            // Adaptive Grid with 105.dp minimum cell size
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 105.dp),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 16.dp,
                    bottom = 116.dp,
                    start = 12.dp,
                    end = 12.dp
                ),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredActors, key = { it.id }) { actor ->
                    // Resilient scene count: lookup by ID first, then fallback to actor name
                    val sceneCount = actorSceneCounts[actor.id] ?: actorSceneCounts[actor.name] ?: 0
                    val itemContent = @Composable {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 18.dp, bottom = 18.dp, start = 8.dp, end = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val isBetaTest = LocalBetaTestPrivacy.current

                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(palette.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                if (actor.imageUrl.isNotEmpty()) {
                                    val context = LocalContext.current
                                    val imageRequest = remember(actor.imageUrl, context) {
                                        ImageRequest.Builder(context)
                                            .data(actor.imageUrl)
                                            .size(200, 200)
                                            .crossfade(true)
                                            .crossfade(150)
                                            .build()
                                    }
                                    val z = actor.imageZoom.coerceIn(1f, 3f)
                                    val biasX = (actor.imagePositionX.coerceIn(0f, 100f) - 50f) / 50f
                                    val biasY = (actor.imagePositionY.coerceIn(0f, 100f) - 50f) / 50f
                                    val hasCustomTransform = z > 1.02f || biasX != 0f || biasY != 0f

                                    AsyncImage(
                                        model = imageRequest,
                                        contentDescription = actor.name,
                                        contentScale = ContentScale.Crop,
                                        alignment = BiasAlignment(biasX, biasY),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .privacyImageBlur(isBetaTest)
                                            .then(
                                                if (hasCustomTransform) {
                                                    Modifier.graphicsLayer {
                                                        val maxX = size.width * (z - 1f) / 2f
                                                        val maxY = size.height * (z - 1f) / 2f
                                                        scaleX = z
                                                        scaleY = z
                                                        translationX = -biasX * maxX
                                                        translationY = -biasY * maxY
                                                    }
                                                } else Modifier
                                            )
                                    )
                                    if (isBetaTest) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(PrivacyScrim)
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(palette.cardBg.copy(alpha = 0.85f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_nav_actor),
                                            contentDescription = null,
                                            tint = palette.textSecondary.copy(alpha = 0.9f),
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .border(1.5.dp, circleBorderColor, CircleShape)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = actor.name,
                                color = palette.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = "$sceneCount scenes",
                                color = palette.textMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (showCards) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    viewModel.navigateTo(ScreenState.ActorScenes(actor.id))
                                },
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (isLight) 3.dp else 1.5.dp
                            ),
                            colors = CardDefaults.cardColors(containerColor = palette.cardBg)
                        ) {
                            itemContent()
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    viewModel.navigateTo(ScreenState.ActorScenes(actor.id))
                                }
                        ) {
                            itemContent()
                        }
                    }
                }
            }
        }
    }

    // Add Actor Dialog with Keyboard / IME Scroll Protection
    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var imageUrl by remember { mutableStateOf("") }

        val isDuplicate = remember(name, actors) {
            val trimmed = name.trim()
            trimmed.isNotEmpty() && actors.any { it.name.equals(trimmed, ignoreCase = true) }
        }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            modifier = Modifier.vaultTopGlow(),
            shape = VaultDialogShape,
            containerColor = palette.dialogBg,
            title = {
                Text(
                    text = "Add Actor",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Actor Name") },
                        isError = isDuplicate,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = palette.cardBg,
                            unfocusedContainerColor = palette.cardBg
                        ),
                        supportingText = {
                            if (isDuplicate) {
                                Text(
                                    text = "An actor with this name already exists",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp
                                )
                            }
                        },
                        shape = RoundedCornerShape(32.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("actor_name_input")
                    )

                    OutlinedTextField(
                        value = imageUrl,
                        onValueChange = { imageUrl = it },
                        label = { Text("Profile Image URL") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = palette.cardBg,
                            unfocusedContainerColor = palette.cardBg
                        ),
                        shape = RoundedCornerShape(32.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Live Circular Preview Section
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(palette.surface)
                                .border(1.5.dp, circleBorderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (imageUrl.trim().isNotEmpty()) {
                                AsyncImage(
                                    model = imageUrl.trim(),
                                    contentDescription = "Preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_nav_actor),
                                    contentDescription = null,
                                    tint = palette.textMuted,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Preview",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = palette.textPrimary
                            )
                            Text(
                                text = if (imageUrl.trim().isNotEmpty()) "Live actor photo preview" else "No image URL",
                                fontSize = 11.5.sp,
                                color = palette.textMuted
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank() && !isDuplicate) {
                            val newActor = ActorEntity(
                                id = UUID.randomUUID().toString(),
                                name = name.trim(),
                                imageUrl = imageUrl.trim()
                            )
                            viewModel.saveActor(newActor)
                            showAddDialog = false
                        }
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    enabled = name.isNotBlank() && !isDuplicate
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddDialog = false },
                    shape = CircleShape
                ) {
                    Text("Cancel", color = palette.textSecondary, fontWeight = FontWeight.Medium)
                }
            }
        )
    }
}
