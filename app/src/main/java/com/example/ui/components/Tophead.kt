package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

/**
 * Modifier extension to apply solid surface and optional bottom border to screen headers.
 */
@Composable
fun Modifier.topheadBackground(
    showBorder: Boolean = false
): Modifier {
    val palette = LocalVaultPalette.current
    val borderMod = if (showBorder) {
        Modifier.drawBehind {
            drawLine(
                color = palette.border,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx()
            )
        }
    } else Modifier
    return this
        .background(palette.surface)
        .then(borderMod)
}

/**
 * Types of standard navigation icons supported by AppTophead.
 */
enum class NavIconType {
    NONE,
    BACK,
    DRAWER,
    CLOSE
}

/**
 * AppTophead - Unified Master Top Bar Component for all screens.
 * 
 * Unifies header behavior across the entire app:
 * - Standard, Drawer, Close, or Custom navigation icons with haptic feedback.
 * - Standard title, subtitle, or custom animated/branded title composable.
 * - Integrated, smooth expandable search field with clear/close actions.
 * - Center-aligned mode (e.g. for Settings).
 * - Palette-aware container, content, and optional border colors.
 * - Flexible trailing action buttons slot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTophead(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    centerTitle: Boolean = false,
    navIconType: NavIconType = NavIconType.BACK,
    onNavClick: (() -> Unit)? = null,
    navContentDescription: String? = null,
    navTestTag: String = "back_button",
    customNavigationIcon: (@Composable () -> Unit)? = null,
    // Search capability
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    isSearchExpanded: Boolean = false,
    onSearchExpandedChange: ((Boolean) -> Unit)? = null,
    searchPlaceholder: String = "Search...",
    searchTestTag: String = "search_input",
    onSearchSubmit: (() -> Unit)? = null,
    // Title styling & custom slots
    titleColor: Color? = null,
    customTitleContent: (@Composable () -> Unit)? = null,
    showBorder: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val haptic = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val primaryColor = titleColor ?: palette.textPrimary

    val borderModifier = if (showBorder) {
        Modifier.drawBehind {
            drawLine(
                color = palette.border,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx()
            )
        }
    } else Modifier

    val titleContentComposable: @Composable () -> Unit = {
        if (isSearchExpanded && searchQuery != null && onSearchQueryChange != null) {
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = palette.textPrimary,
                    fontSize = 15.sp
                ),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    onSearchSubmit?.invoke()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag(searchTestTag),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = searchPlaceholder,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 15.sp,
                                    color = palette.textSecondary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
        } else if (customTitleContent != null) {
            customTitleContent()
        } else if (subtitle.isNullOrBlank()) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = primaryColor
            )
        } else {
            Column {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = primaryColor
                )
                Text(
                    text = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
            }
        }
    }

    val navIconComposable: @Composable () -> Unit = {
        if (customNavigationIcon != null) {
            customNavigationIcon()
        } else if (isSearchExpanded && onSearchExpandedChange != null) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSearchExpandedChange(false)
                    onSearchQueryChange?.invoke("")
                },
                modifier = Modifier.testTag("close_search_nav_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Close Search",
                    tint = palette.textPrimary
                )
            }
        } else {
            when (navIconType) {
                NavIconType.BACK -> {
                    if (onNavClick != null) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNavClick()
                            },
                            modifier = Modifier.testTag(navTestTag)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = navContentDescription ?: "Back",
                                tint = primaryColor
                            )
                        }
                    }
                }
                NavIconType.DRAWER -> {
                    if (onNavClick != null) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNavClick()
                            },
                            modifier = Modifier.testTag("open_drawer_button")
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_menu),
                                contentDescription = navContentDescription ?: "Open Drawer",
                                tint = primaryColor
                            )
                        }
                    }
                }
                NavIconType.CLOSE -> {
                    if (onNavClick != null) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNavClick()
                            },
                            modifier = Modifier.testTag(navTestTag.ifEmpty { "close_button" })
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = navContentDescription ?: "Close",
                                tint = primaryColor
                            )
                        }
                    }
                }
                NavIconType.NONE -> { /* No navigation icon */ }
            }
        }
    }

    val actionsComposable: @Composable RowScope.() -> Unit = {
        if (isSearchExpanded && searchQuery != null && onSearchQueryChange != null) {
            if (searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSearchQueryChange("")
                    },
                    modifier = Modifier.testTag("clear_search_button")
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_action_cancel),
                        contentDescription = "Clear Search",
                        tint = palette.textSecondary
                    )
                }
            } else if (onSearchExpandedChange != null) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSearchExpandedChange(false)
                        onSearchQueryChange("")
                    },
                    modifier = Modifier.testTag("close_search_button")
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_action_cancel),
                        contentDescription = "Close Search",
                        tint = palette.textSecondary
                    )
                }
            }
        } else {
            actions()
        }
    }

    if (centerTitle) {
        CenterAlignedTopAppBar(
            modifier = modifier.topheadBackground(showBorder),
            title = titleContentComposable,
            navigationIcon = navIconComposable,
            actions = actionsComposable,
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                navigationIconContentColor = palette.textPrimary,
                actionIconContentColor = palette.textPrimary
            )
        )
    } else {
        TopAppBar(
            modifier = modifier.topheadBackground(showBorder),
            title = titleContentComposable,
            navigationIcon = navIconComposable,
            actions = actionsComposable,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = palette.surface,
                titleContentColor = palette.textPrimary,
                navigationIconContentColor = palette.textPrimary,
                actionIconContentColor = palette.textPrimary
            )
        )
    }
}

/**
 * Tophead - Convenience helper for standard screens (Add/Edit Scene, Details, etc.)
 */
@Composable
fun Tophead(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = "Back",
    showBorder: Boolean = false,
    titleColor: Color? = null,
    actions: @Composable (RowScope.() -> Unit) = {}
) {
    AppTophead(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        navIconType = if (onBack != null) NavIconType.BACK else NavIconType.NONE,
        onNavClick = onBack,
        navContentDescription = backContentDescription,
        showBorder = showBorder,
        titleColor = titleColor,
        actions = actions
    )
}

/**
 * SearchableTophead - Convenience helper for searchable management screens (Actors, Studios, Bookmarks)
 */
@Composable
fun SearchableTophead(
    title: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search...",
    testTag: String = "search_input",
    onBack: (() -> Unit)? = null,
    showBorder: Boolean = false,
    customTitleContent: (@Composable () -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit) = {}
) {
    AppTophead(
        title = title,
        modifier = modifier,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        isSearchExpanded = isSearchExpanded,
        onSearchExpandedChange = onSearchExpandedChange,
        searchPlaceholder = placeholder,
        searchTestTag = testTag,
        navIconType = if (onBack != null) NavIconType.BACK else NavIconType.NONE,
        onNavClick = onBack,
        showBorder = showBorder,
        customTitleContent = customTitleContent,
        actions = actions
    )
}
