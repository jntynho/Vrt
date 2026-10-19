package com.example.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalGoonyPalette = compositionLocalOf<GoonyThemePalette> { GoonyThemePalette.Dark }
val LocalVaultPalette = LocalGoonyPalette
typealias VaultPalette = GoonyThemePalette
val LocalAccentColor = compositionLocalOf { Color(0xFF7C4DFF) }
val LocalBetaTestPrivacy = compositionLocalOf { false }

fun Modifier.privacyImageBlur(enabled: Boolean, radius: Dp = 80.dp): Modifier {
    return if (enabled) {
        this.blur(radius = radius, edgeTreatment = BlurredEdgeTreatment.Rectangle)
    } else {
        this
    }
}

fun parseHexColor(hex: String, fallback: Color = Color(0xFF7C4DFF)): Color {
    return try {
        val clean = hex.removePrefix("#")
        val colorInt = clean.toLong(16)
        if (clean.length == 6) {
            Color(0xFF000000 or colorInt)
        } else if (clean.length == 8) {
            Color(colorInt)
        } else {
            fallback
        }
    } catch (_: Exception) {
        fallback
    }
}

/** Pure black (#000000) default background for studio logos and circular emblems */
val DefaultStudioLogoBg = Color(0xFF000000)


@Composable
fun GoonyTheme(
    paletteName: String = "Dark",
    accentColorHex: String = "tokyo_night",
    betaTestPrivacy: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val targetPalette = GoonyThemePalette.fromName(paletteName)
    val isLight = targetPalette is GoonyThemePalette.Light
    val isSystemDynamic = accentColorHex.equals(MaterialYouColorPresets.SYSTEM_DYNAMIC_ID, ignoreCase = true)

    // Smooth 260ms theme color animation spec
    val animDuration = 260
    val animSpec = tween<Color>(durationMillis = animDuration, easing = FastOutSlowInEasing)

    // Animate custom palette colors
    val animatedBg by animateColorAsState(targetPalette.bg, animSpec, label = "theme_bg")
    val animatedSurface by animateColorAsState(targetPalette.surface, animSpec, label = "theme_surface")
    val animatedCardBg by animateColorAsState(targetPalette.cardBg, animSpec, label = "theme_cardBg")
    val animatedTextPrimary by animateColorAsState(targetPalette.textPrimary, animSpec, label = "theme_textPrimary")
    val animatedTextSecondary by animateColorAsState(targetPalette.textSecondary, animSpec, label = "theme_textSecondary")
    val animatedTextMuted by animateColorAsState(targetPalette.textMuted, animSpec, label = "theme_textMuted")
    val animatedBorder by animateColorAsState(targetPalette.border, animSpec, label = "theme_border")
    val animatedSkeletonBg by animateColorAsState(targetPalette.skeletonBg, animSpec, label = "theme_skeletonBg")
    val animatedDialogBg by animateColorAsState(targetPalette.dialogBg, animSpec, label = "theme_dialogBg")

    val animatedPalette = remember(
        targetPalette.name,
        animatedBg,
        animatedSurface,
        animatedCardBg,
        animatedTextPrimary,
        animatedTextSecondary,
        animatedTextMuted,
        animatedBorder,
        animatedSkeletonBg,
        animatedDialogBg
    ) {
        GoonyThemePalette.Dynamic(
            name = targetPalette.name,
            bg = animatedBg,
            surface = animatedSurface,
            cardBg = animatedCardBg,
            textPrimary = animatedTextPrimary,
            textSecondary = animatedTextSecondary,
            textMuted = animatedTextMuted,
            border = animatedBorder,
            skeletonBg = animatedSkeletonBg,
            dialogBg = animatedDialogBg
        )
    }

    // Build Material 3 Color Scheme
    val rawColorScheme = if (isSystemDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isLight) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)
    } else {
        val preset = MaterialYouColorPresets.getPreset(accentColorHex)
        if (isLight) {
            lightColorScheme(
                primary = preset.lightPrimary,
                secondary = preset.lightSecondary,
                tertiary = preset.lightTertiary,
                primaryContainer = preset.lightContainer
            )
        } else {
            darkColorScheme(
                primary = preset.darkPrimary,
                secondary = preset.darkSecondary,
                tertiary = preset.darkTertiary,
                primaryContainer = preset.darkContainer
            )
        }
    }

    val animatedPrimary by animateColorAsState(rawColorScheme.primary, animSpec, label = "cs_primary")
    val animatedPrimaryContainer by animateColorAsState(rawColorScheme.primaryContainer, animSpec, label = "cs_primaryContainer")
    val animatedSecondary by animateColorAsState(rawColorScheme.secondary, animSpec, label = "cs_secondary")
    val animatedTertiary by animateColorAsState(rawColorScheme.tertiary, animSpec, label = "cs_tertiary")
    val animatedOnSurfaceVariant by animateColorAsState(rawColorScheme.onSurfaceVariant, animSpec, label = "cs_onSurfaceVariant")

    val colorScheme = rawColorScheme.copy(
        primary = animatedPrimary,
        primaryContainer = animatedPrimaryContainer,
        secondary = animatedSecondary,
        tertiary = animatedTertiary,
        background = animatedBg,
        surface = animatedSurface,
        onBackground = animatedTextPrimary,
        onSurface = animatedTextPrimary,
        surfaceVariant = animatedCardBg,
        onSurfaceVariant = animatedOnSurfaceVariant,
        outline = animatedBorder
    )

    val activeAccent = colorScheme.primary

    CompositionLocalProvider(
        LocalGoonyPalette provides animatedPalette,
        LocalAccentColor provides activeAccent,
        LocalBetaTestPrivacy provides betaTestPrivacy
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

// Backward-compatible alias for existing components
@Composable
fun GVJVaultTheme(
    paletteName: String = "Dark",
    accentColorHex: String = "tokyo_night",
    betaTestPrivacy: Boolean = false,
    content: @Composable () -> Unit
) = GoonyTheme(paletteName, accentColorHex, betaTestPrivacy, content)

object GoonyScrims {
    val Overlay = Color.Black.copy(alpha = 0.65f)
    val Privacy = Color.Black.copy(alpha = 0.75f)
}

val VaultScrims = GoonyScrims
