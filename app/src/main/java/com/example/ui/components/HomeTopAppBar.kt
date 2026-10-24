package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.entity.ActorEntity
import com.example.data.local.entity.StudioEntity
import com.example.ui.ScreenState
import com.example.ui.SortMode
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

/**
 * HomeTopAppBar - Unified Home & Content Header powered by AppTophead.
 */
@Composable
fun HomeTopAppBar(
    currentScreen: ScreenState,
    targetActor: ActorEntity?,
    targetStudio: StudioEntity?,
    actorsMap: Map<String, String>,
    studiosMap: Map<String, String>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    currentSort: SortMode,
    onSortChange: (SortMode) -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigateBack: () -> Unit,
    onAddScene: () -> Unit,
    onEditActor: () -> Unit = {},
    onEditStudio: () -> Unit = {}
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    var isSearchExpanded by remember { mutableStateOf(searchQuery.isNotEmpty()) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Gracefully collapse search bar first on system back gesture
    androidx.activity.compose.BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        onSearchQueryChange("")
    }

    val headerTitle = when {
        targetActor != null -> {
            if (targetActor.name.isNotBlank() && targetActor.name != targetActor.id) {
                targetActor.name
            } else {
                actorsMap[targetActor.id] ?: targetActor.name
            }
        }
        targetStudio != null -> {
            if (targetStudio.name.isNotBlank() && targetStudio.name != targetStudio.id) {
                targetStudio.name
            } else {
                studiosMap[targetStudio.id] ?: targetStudio.name
            }
        }
        currentScreen is ScreenState.ActorScenes -> {
            val id = currentScreen.actorId
            actorsMap[id] ?: id
        }
        currentScreen is ScreenState.StudioScenes -> {
            val id = currentScreen.studioId
            studiosMap[id] ?: id
        }
        else -> "Goony"
    }

    val isFilteredOrDeep = targetActor != null || targetStudio != null ||
        currentScreen is ScreenState.ActorScenes || currentScreen is ScreenState.StudioScenes

    AppTophead(
        title = headerTitle,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        isSearchExpanded = isSearchExpanded,
        onSearchExpandedChange = { isSearchExpanded = it },
        searchPlaceholder = "Search scenes, actors, studios...",
        searchTestTag = "search_scenes_input",
        navIconType = if (isFilteredOrDeep) NavIconType.BACK else NavIconType.DRAWER,
        onNavClick = if (isFilteredOrDeep) onNavigateBack else onOpenDrawer,
        actions = {
            // Search Action
            IconButton(
                onClick = { isSearchExpanded = true },
                modifier = Modifier.testTag("search_action_button")
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_app_search),
                    contentDescription = "Search",
                    tint = palette.textPrimary
                )
            }

            // Sort Action with Dropdown Menu
            Box {
                IconButton(
                    onClick = { showSortMenu = true },
                    modifier = Modifier.testTag("sort_action_button")
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_app_sort),
                        contentDescription = "Sort Mode",
                        tint = palette.textPrimary
                    )
                }

                VaultSortDropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    val sortOptions = listOf(
                        SortMode.CARD_NEWEST to "New By Date",
                        SortMode.CARD_OLDEST to "Old By Date",
                        SortMode.RECENTLY_ADDED to "Recently Added",
                        SortMode.OLDEST_ADDED to "Oldest Added"
                    )

                    sortOptions.forEach { (mode, label) ->
                        val isSelected = currentSort == mode ||
                            (mode == SortMode.CARD_NEWEST && currentSort == SortMode.NEWEST) ||
                            (mode == SortMode.CARD_OLDEST && currentSort == SortMode.OLDEST)

                        VaultSortDropdownItem(
                            label = label,
                            isSelected = isSelected,
                            onClick = {
                                onSortChange(mode)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }

            // Edit Actor / Studio Action in Header
            if (targetActor != null) {
                IconButton(
                    onClick = onEditActor,
                    modifier = Modifier.testTag("edit_actor_header_button")
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_edit_pencil),
                        contentDescription = "Edit Actor",
                        tint = palette.textPrimary
                    )
                }
            } else if (targetStudio != null) {
                IconButton(
                    onClick = onEditStudio,
                    modifier = Modifier.testTag("edit_studio_header_button")
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_edit_pencil),
                        contentDescription = "Edit Studio",
                        tint = palette.textPrimary
                    )
                }
            }

            // Add Scene Action - ONLY on Main Screen (Home)
            if (currentScreen is ScreenState.Home) {
                IconButton(
                    onClick = onAddScene,
                    modifier = Modifier.testTag("add_scene_button")
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_app_add),
                        contentDescription = "Add Scene",
                        tint = palette.textPrimary
                    )
                }
            }
        }
    )
}
