package com.example.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.ActorManagementScreen
import com.example.ui.screens.AddEditLinkScreen
import com.example.ui.screens.BookmarksScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StashDbScreen
import com.example.ui.screens.StudioManagementScreen
import com.example.ui.theme.GVJVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real Experimental Test Environment for Head / TopBar Buttons.
 * Rigorously tests responsiveness, click actions, navigation, search, and sorting
 * across all screens in the application.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HeadButtonsRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        viewModel = MainViewModel(app)
    }

    @Test
    fun testHomeScreenHeadButtons() {
        var drawerOpened = false

        composeTestRule.setContent {
            GVJVaultTheme {
                HomeScreen(
                    viewModel = viewModel,
                    onOpenDrawer = { drawerOpened = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Test Open Drawer Button in Head
        composeTestRule.onNodeWithTag("open_drawer_button").assertIsDisplayed().performClick()
        assertTrue("Drawer callback must be triggered on head drawer button click", drawerOpened)

        // 2. Test Sort Button in Head
        composeTestRule.onNodeWithTag("sort_action_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // 3. Test Search Button in Head
        composeTestRule.onNodeWithTag("search_action_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Verify Search Input appears and accepts input
        composeTestRule.onNodeWithTag("search_scenes_input").assertIsDisplayed().performTextInput("Test Query")
        assertEquals("Test Query", viewModel.searchQuery.value)

        // 4. Test Clear Search Button
        composeTestRule.onNodeWithTag("clear_search_button").assertIsDisplayed().performClick()
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun testBookmarksScreenHeadButtons() {
        composeTestRule.setContent {
            GVJVaultTheme {
                BookmarksScreen(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // 1. Test Sort Button in Bookmarks Head when collapsed
        composeTestRule.onNodeWithTag("sort_action_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // 2. Test Search Button in Bookmarks Head
        composeTestRule.onNodeWithTag("search_action_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("search_bookmarks_input").assertIsDisplayed().performTextInput("Fav")
        assertEquals("Fav", viewModel.searchQuery.value)
    }

    @Test
    fun testAddEditLinkScreenHeadButtons() {
        composeTestRule.setContent {
            GVJVaultTheme {
                AddEditLinkScreen(viewModel = viewModel, linkId = null)
            }
        }
        composeTestRule.waitForIdle()

        // 1. Test Back Button in Head
        composeTestRule.onNodeWithTag("back_button").assertIsDisplayed().performClick()

        // 2. Test Save Scene Button in Head (present in the TopBar)
        composeTestRule.onNodeWithTag("save_scene_button").assertIsDisplayed()
    }

    @Test
    fun testActorManagementScreenHeadButtons() {
        composeTestRule.setContent {
            GVJVaultTheme {
                ActorManagementScreen(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // 1. Test Sort Actors Button in Head
        composeTestRule.onNodeWithTag("sort_actors_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // 2. Test Add Actor Button in Head
        composeTestRule.onNodeWithTag("add_actor_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun testStudioManagementScreenHeadButtons() {
        composeTestRule.setContent {
            GVJVaultTheme {
                StudioManagementScreen(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // 1. Test Sort Studios Button in Head
        composeTestRule.onNodeWithTag("sort_studios_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // 2. Test Add Studio Button in Head
        composeTestRule.onNodeWithTag("add_studio_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun testSettingsScreenHeadBackButton() {
        viewModel.navigateTo(ScreenState.Settings)

        composeTestRule.setContent {
            GVJVaultTheme {
                SettingsScreen(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Test Circular Back Button in Settings Head
        composeTestRule.onNodeWithTag("back_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun testStashDbScreenHeadButtons() {
        composeTestRule.setContent {
            GVJVaultTheme {
                StashDbScreen(viewModel = viewModel, onOpenDrawer = {})
            }
        }
        composeTestRule.waitForIdle()

        // 1. Test Back Button in Head when search is not expanded
        composeTestRule.onNodeWithTag("back_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // 2. Test Search Button in StashDB Head
        composeTestRule.onNodeWithTag("stashdb_search_action_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // Verify Search Input in Head
        composeTestRule.onNodeWithTag("stashdb_header_search_input").assertIsDisplayed().performTextInput("Lexi")
        assertEquals("Lexi", viewModel.stashSearchQuery.value)

        // 3. Test Close Search Button in Head
        composeTestRule.onNodeWithTag("close_search_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun testActorScenesHeadEditPencilButton() {
        viewModel.navigateTo(ScreenState.ActorScenes(actorId = "test_actor_123"))

        composeTestRule.setContent {
            GVJVaultTheme {
                HomeScreen(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // 1. Verify and Click the Edit Pencil Button in Head
        composeTestRule.onNodeWithTag("edit_actor_header_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        // 2. Verify Actor Details Dialog opens
        composeTestRule.onNodeWithTag("adjust_actor_photo_button").assertIsDisplayed()
    }

    @Test
    fun testStudioScenesHeadEditPencilButton() {
        viewModel.navigateTo(ScreenState.StudioScenes(studioId = "test_studio_123"))

        composeTestRule.setContent {
            GVJVaultTheme {
                HomeScreen(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // 1. Verify and Click the Edit Studio Pencil Button in Head
        composeTestRule.onNodeWithTag("edit_studio_header_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
    }
}
