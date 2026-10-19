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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.data.local.entity.StudioEntity
import com.example.ui.MainViewModel
import com.example.ui.ScreenState
import com.example.ui.components.SearchableTophead
import com.example.ui.components.VaultDialogShape
import com.example.ui.components.vaultTopGlow
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalBetaTestPrivacy
import com.example.ui.theme.LocalVaultPalette
import com.example.ui.theme.parseHexColor
import com.example.ui.theme.privacyImageBlur
import java.util.UUID

private val PrivacyScrim = Color.Black.copy(alpha = 0.75f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioManagementScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val isLight = palette.name.equals("light", ignoreCase = true)
    val circleBorderColor = if (isLight) Color.Black else Color.White

    val studios by viewModel.allStudios.collectAsStateWithLifecycle()
    val studioSceneCounts by viewModel.studioSceneCounts.collectAsStateWithLifecycle()
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

    // Auto-open Add Dialog if navigated via ScreenState.AddEditStudio
    LaunchedEffect(currentScreen) {
        if (currentScreen is ScreenState.AddEditStudio) {
            showAddDialog = true
        }
    }

    val scrollKey = "management_studios"
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

    val sortedStudios = remember(studios, sortOption) {
        when (sortOption) {
            ManagementSortOption.NAME_AZ -> studios.sortedBy { it.name.lowercase() }
            ManagementSortOption.NAME_ZA -> studios.sortedByDescending { it.name.lowercase() }
            ManagementSortOption.NEWEST -> studios.sortedByDescending { it.createdAt }
            ManagementSortOption.OLDEST -> studios.sortedBy { it.createdAt }
        }
    }

    // Instant in-memory search filtering
    val filteredStudios = remember(sortedStudios, searchQuery) {
        if (searchQuery.isBlank()) sortedStudios
        else {
            val q = searchQuery.trim().lowercase()
            sortedStudios.filter { it.name.lowercase().contains(q) }
        }
    }

    Scaffold(
        containerColor = palette.bg,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            SearchableTophead(
                title = "Studios (${studios.size})",
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { isSearchExpanded = it },
                placeholder = "Search studios...",
                testTag = "search_studios_input",
                onBack = { viewModel.navigateBack() },
                showBorder = false,
                actions = {
                    if (!isSearchExpanded) {
                        // Search Button
                        IconButton(
                            onClick = { isSearchExpanded = true },
                            modifier = Modifier.testTag("search_studios_action_button")
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
                                modifier = Modifier.testTag("sort_studios_button")
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

                        // Add Studio Button
                        IconButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.testTag("add_studio_button")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_add),
                                contentDescription = "Add Studio",
                                tint = palette.textPrimary
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (studios.isEmpty()) {
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
                        painter = painterResource(id = R.drawable.ic_nav_studio),
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(54.dp)
                    )
                    Text("No studios in library", color = palette.textPrimary, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { showAddDialog = true },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {
                        Text("Add Studio", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (filteredStudios.isEmpty()) {
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
                        text = "No studios found for \"$searchQuery\"",
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
                items(filteredStudios, key = { it.id }) { studio ->
                    // Resilient scene count: lookup by ID first, then fallback to studio name
                    val sceneCount = studioSceneCounts[studio.id] ?: studioSceneCounts[studio.name] ?: 0
                    val itemContent = @Composable {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 18.dp, bottom = 18.dp, start = 8.dp, end = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val isBetaTest = LocalBetaTestPrivacy.current

                            val studioCustomBg = remember(studio.logoBgColor) {
                                if (!studio.logoBgColor.isNullOrBlank()) {
                                    parseHexColor(studio.logoBgColor, Color(0xFF000000))
                                } else {
                                    Color(0xFF000000)
                                }
                            }

                            if (!studio.logoUrl.isNullOrEmpty()) {
                                val context = LocalContext.current
                                val imageRequest = remember(studio.logoUrl, context) {
                                    ImageRequest.Builder(context)
                                        .data(studio.logoUrl)
                                        .size(200, 200)
                                        .crossfade(true)
                                        .crossfade(150)
                                        .build()
                                }

                                Box(
                                    modifier = Modifier
                                        .size(70.dp)
                                        .clip(CircleShape)
                                        .background(studioCustomBg)
                                        .border(1.5.dp, circleBorderColor, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = imageRequest,
                                        contentDescription = studio.name,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(6.dp)
                                            .privacyImageBlur(isBetaTest)
                                    )
                                    if (isBetaTest) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(PrivacyScrim)
                                        )
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(70.dp)
                                        .clip(CircleShape)
                                        .background(palette.cardBg.copy(alpha = 0.85f))
                                        .border(1.5.dp, circleBorderColor, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_nav_studio),
                                        contentDescription = null,
                                        tint = palette.textMuted,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = studio.name,
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
                                    viewModel.navigateTo(ScreenState.StudioScenes(studio.id))
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
                                    viewModel.navigateTo(ScreenState.StudioScenes(studio.id))
                                }
                        ) {
                            itemContent()
                        }
                    }
                }
            }
        }
    }

    // Add Studio Dialog with Keyboard / IME Scroll Protection
    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var logoUrl by remember { mutableStateOf("") }

        val isDuplicate = remember(name, studios) {
            val trimmed = name.trim()
            trimmed.isNotEmpty() && studios.any { it.name.equals(trimmed, ignoreCase = true) }
        }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            modifier = Modifier.vaultTopGlow(),
            shape = VaultDialogShape,
            containerColor = palette.dialogBg,
            title = {
                Text(
                    text = "Add Studio",
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
                        label = { Text("Studio Name") },
                        isError = isDuplicate,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = palette.cardBg,
                            unfocusedContainerColor = palette.cardBg
                        ),
                        supportingText = {
                            if (isDuplicate) {
                                Text(
                                    text = "A studio with this name already exists",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp
                                )
                            }
                        },
                        shape = RoundedCornerShape(32.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("studio_name_input")
                    )

                    OutlinedTextField(
                        value = logoUrl,
                        onValueChange = { logoUrl = it },
                        label = { Text("Logo Image URL") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = palette.cardBg,
                            unfocusedContainerColor = palette.cardBg
                        ),
                        shape = RoundedCornerShape(32.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("studio_logo_url_input")
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
                                .background(Color(0xFF000000))
                                .border(1.5.dp, circleBorderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (logoUrl.trim().isNotEmpty()) {
                                AsyncImage(
                                    model = logoUrl.trim(),
                                    contentDescription = "Preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_nav_studio),
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
                                text = if (logoUrl.trim().isNotEmpty()) "Live studio logo preview" else "No logo URL",
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
                            val newStudio = StudioEntity(
                                id = UUID.randomUUID().toString(),
                                name = name.trim(),
                                logoUrl = logoUrl.trim().ifEmpty { null }
                            )
                            viewModel.saveStudio(newStudio)
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
