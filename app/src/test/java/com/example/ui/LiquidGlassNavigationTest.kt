package com.example.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.glass.LiquidGlassNavigationBar
import com.example.ui.components.glass.LiquidGlassNavItem
import com.example.ui.screens.MainAppShell
import com.example.ui.theme.GVJVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiquidGlassNavigationTest {

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
    fun testClickingHomeWhenHomeIsSelectedTriggersReselected() {
        var selectedId by mutableStateOf("home")
        var selectedItem: LiquidGlassNavItem? = null
        var reselectedItem: LiquidGlassNavItem? = null

        composeTestRule.setContent {
            GVJVaultTheme {
                LiquidGlassNavigationBar(
                    selectedId = selectedId,
                    onItemSelected = { item ->
                        selectedItem = item
                        selectedId = item.id
                    },
                    onItemReselected = { item ->
                        reselectedItem = item
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Click on Home item
        composeTestRule.onNodeWithTag("nav_item_home").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        println("TEST RESULT: reselectedItem=${reselectedItem?.id}, selectedItem=${selectedItem?.id}")
        assertTrue("reselectedItem should be home when home is clicked while active", reselectedItem?.id == "home")
    }

    @Test
    fun testClickingHomeWhenActorsIsSelectedTriggersSelected() {
        var selectedId by mutableStateOf("actor")
        var selectedItem: LiquidGlassNavItem? = null
        var reselectedItem: LiquidGlassNavItem? = null

        composeTestRule.setContent {
            GVJVaultTheme {
                LiquidGlassNavigationBar(
                    selectedId = selectedId,
                    onItemSelected = { item ->
                        selectedItem = item
                        selectedId = item.id
                    },
                    onItemReselected = { item ->
                        reselectedItem = item
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Click on Home item
        composeTestRule.onNodeWithTag("nav_item_home").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        println("TEST RESULT: selectedItem=${selectedItem?.id}")
        assertEquals("home", selectedItem?.id)
    }

    @Test
    fun testTouchInputOnHomeItemInMainAppShell() {
        composeTestRule.setContent {
            GVJVaultTheme {
                MainAppShell(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Initially on Home screen
        assertEquals(ScreenState.Home, viewModel.screenState.value)

        // 1. Touch-click on Actors tab
        composeTestRule.onNodeWithTag("nav_item_actor").assertIsDisplayed().performTouchInput { click() }
        composeTestRule.waitForIdle()
        assertEquals(ScreenState.Actors, viewModel.screenState.value)

        // 2. Touch-click on Home tab from Actors
        composeTestRule.onNodeWithTag("nav_item_home").assertIsDisplayed().performTouchInput { click() }
        composeTestRule.waitForIdle()
        assertEquals(ScreenState.Home, viewModel.screenState.value)

        // 3. Touch-click on Home tab while ALREADY ON HOME
        val initialScrollTrigger = viewModel.scrollToTopTrigger.value
        composeTestRule.onNodeWithTag("nav_item_home").assertIsDisplayed().performTouchInput { click() }
        composeTestRule.waitForIdle()
        val afterScrollTrigger = viewModel.scrollToTopTrigger.value
        println("ScrollTrigger before=$initialScrollTrigger after=$afterScrollTrigger")
        assertTrue("Scroll to top trigger should increment when Home is clicked while active", afterScrollTrigger > initialScrollTrigger)
    }
}
