package com.example.ui.components.glass

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.R
import com.example.ui.ScreenState

/**
 * Model representing a single item in the Liquid Glass Navigation Bar.
 */
data class LiquidGlassNavItem(
    val id: String,
    val title: String,
    @StringRes val titleRes: Int = 0,
    @DrawableRes val iconRes: Int,
    val targetScreen: ScreenState
) {
    companion object {
        /**
         * The 5 core navigation items in the Bottom Bar:
         * 1. Home
         * 2. Actors
         * 3. Studios
         * 4. StashDB
         * 5. Settings
         */
        val defaultNavItems = listOf(
            LiquidGlassNavItem(
                id = "home",
                title = "Home",
                titleRes = R.string.nav_home,
                iconRes = R.drawable.ic_nav_home,
                targetScreen = ScreenState.Home
            ),
            LiquidGlassNavItem(
                id = "actor",
                title = "Actors",
                titleRes = R.string.nav_actors,
                iconRes = R.drawable.ic_nav_actor,
                targetScreen = ScreenState.Actors
            ),
            LiquidGlassNavItem(
                id = "studio",
                title = "Studios",
                titleRes = R.string.nav_studios,
                iconRes = R.drawable.ic_nav_studio,
                targetScreen = ScreenState.Studios
            ),
            LiquidGlassNavItem(
                id = "stashdb",
                title = "StashDB",
                titleRes = R.string.nav_stashdb,
                iconRes = R.drawable.ic_nav_stashdb,
                targetScreen = ScreenState.StashDb
            ),
            LiquidGlassNavItem(
                id = "settings",
                title = "Settings",
                titleRes = R.string.nav_settings,
                iconRes = R.drawable.ic_nav_settings,
                targetScreen = ScreenState.Settings
            )
        )
    }
}
