package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * mpvRx Full Theme Suite
 * Contains all the official curated themes from https://github.com/Riteshp2001/mpvRx:
 * Dynamic (Monet), Catppuccin (Mocha & Latte & Macchiato), Nord, Tokyo Night,
 * Rosé Pine (Main, Moon, Dawn), Gruvbox (Dark & Light), Dracula, Solarized,
 * Cyberpunk, Monokai, Moonlight, Material Ocean, Sunset Glow, Forest Emerald.
 */
data class MaterialThemePalette(
    val id: String,
    val name: String,
    val seedHex: String,
    // 3-split preview colors for circular swatch (Top Half, Bottom-Left Quarter, Bottom-Right Quarter)
    val previewTop: Color,
    val previewBottomLeft: Color,
    val previewBottomRight: Color,
    val lightPrimary: Color,
    val lightSecondary: Color,
    val lightTertiary: Color,
    val lightContainer: Color,
    val darkPrimary: Color,
    val darkSecondary: Color,
    val darkTertiary: Color,
    val darkContainer: Color
)

object MaterialYouColorPresets {
    const val SYSTEM_DYNAMIC_ID = "system_dynamic"

    val Presets = listOf(
        // 1. Dynamic Material You (Monet)
        MaterialThemePalette(
            id = SYSTEM_DYNAMIC_ID,
            name = "Dynamic (System)",
            seedHex = "#6750A4",
            previewTop = Color(0xFF6750A4),
            previewBottomLeft = Color(0xFFD0BCFF),
            previewBottomRight = Color(0xFFEADDFF),
            lightPrimary = Color(0xFF6750A4),
            lightSecondary = Color(0xFF625B71),
            lightTertiary = Color(0xFF7D5260),
            lightContainer = Color(0xFFEADDFF),
            darkPrimary = Color(0xFFD0BCFF),
            darkSecondary = Color(0xFFCCC2DC),
            darkTertiary = Color(0xFFEFB8C8),
            darkContainer = Color(0xFF4F378B)
        ),
        // 2. Tokyo Night (Downtown Neon & Deep Navy) - Default Preset
        MaterialThemePalette(
            id = "tokyo_night",
            name = "Tokyo Night",
            seedHex = "#7AA2F7",
            previewTop = Color(0xFF1A1B26), // Storm Dark
            previewBottomLeft = Color(0xFF7AA2F7), // Neon Blue
            previewBottomRight = Color(0xFFBB9AF7), // Neon Purple
            lightPrimary = Color(0xFF3D59A1),
            lightSecondary = Color(0xFF7AA2F7),
            lightTertiary = Color(0xFF9D7CD8),
            lightContainer = Color(0xFFE6EDF3),
            darkPrimary = Color(0xFF7AA2F7),
            darkSecondary = Color(0xFFBB9AF7),
            darkTertiary = Color(0xFF7DCFFF),
            darkContainer = Color(0xFF24283B)
        ),
        // 3. Catppuccin Mocha (Soothing Pastel Dark)
        MaterialThemePalette(
            id = "catppuccin_mocha",
            name = "Catppuccin Mocha",
            seedHex = "#CBA6F7",
            previewTop = Color(0xFF1E1E2E), // Base
            previewBottomLeft = Color(0xFFCBA6F7), // Mauve
            previewBottomRight = Color(0xFF89B4FA), // Blue
            lightPrimary = Color(0xFF8839EF),
            lightSecondary = Color(0xFF1E66F5),
            lightTertiary = Color(0xFFEA76CB),
            lightContainer = Color(0xFFDCE0E8),
            darkPrimary = Color(0xFFCBA6F7),
            darkSecondary = Color(0xFF89B4FA),
            darkTertiary = Color(0xFFF5C2E7),
            darkContainer = Color(0xFF313244)
        ),
        // 3. Catppuccin Macchiato
        MaterialThemePalette(
            id = "catppuccin_macchiato",
            name = "Catppuccin Macchiato",
            seedHex = "#F5A97F",
            previewTop = Color(0xFF24273A),
            previewBottomLeft = Color(0xFFF5A97F), // Peach
            previewBottomRight = Color(0xFFA6DA95), // Green
            lightPrimary = Color(0xFFFE640B),
            lightSecondary = Color(0xFF40A02B),
            lightTertiary = Color(0xFFDF8E1D),
            lightContainer = Color(0xFFEFF1F5),
            darkPrimary = Color(0xFFF5A97F),
            darkSecondary = Color(0xFFA6DA95),
            darkTertiary = Color(0xFFEED49F),
            darkContainer = Color(0xFF363A4F)
        ),
        // 4. Catppuccin Frappé / Flamingo
        MaterialThemePalette(
            id = "catppuccin_frappe",
            name = "Catppuccin Flamingo",
            seedHex = "#EE99A0",
            previewTop = Color(0xFF303446),
            previewBottomLeft = Color(0xFFEE99A0), // Flamingo
            previewBottomRight = Color(0xFF85C1DC), // Sapphire
            lightPrimary = Color(0xFFD20F39),
            lightSecondary = Color(0xFF209FB5),
            lightTertiary = Color(0xFFE64553),
            lightContainer = Color(0xFFCCD0DA),
            darkPrimary = Color(0xFFEE99A0),
            darkSecondary = Color(0xFF85C1DC),
            darkTertiary = Color(0xFFF2D5CF),
            darkContainer = Color(0xFF414559)
        ),
        // 5. Nord (Arctic Blue & Frost)
        MaterialThemePalette(
            id = "nord",
            name = "Nord",
            seedHex = "#88C0D0",
            previewTop = Color(0xFF2E3440), // Polar Night
            previewBottomLeft = Color(0xFF88C0D0), // Frost
            previewBottomRight = Color(0xFF81A1C1), // Frost Blue
            lightPrimary = Color(0xFF5E81AC),
            lightSecondary = Color(0xFF88C0D0),
            lightTertiary = Color(0xFF8FBCBB),
            lightContainer = Color(0xFFECEFF4),
            darkPrimary = Color(0xFF88C0D0),
            darkSecondary = Color(0xFF81A1C1),
            darkTertiary = Color(0xFFB48EAD),
            darkContainer = Color(0xFF3B4252)
        ),
        // 6. Rosé Pine (Classy Soho Minimalist)
        MaterialThemePalette(
            id = "rose_pine",
            name = "Rosé Pine",
            seedHex = "#EB6F92",
            previewTop = Color(0xFF191724), // Base
            previewBottomLeft = Color(0xFFEB6F92), // Love / Rose
            previewBottomRight = Color(0xFFF6C177), // Gold
            lightPrimary = Color(0xFFB4637A),
            lightSecondary = Color(0xFFEA9D34),
            lightTertiary = Color(0xFF56949F),
            lightContainer = Color(0xFFFAF4ED),
            darkPrimary = Color(0xFFEB6F92),
            darkSecondary = Color(0xFFF6C177),
            darkTertiary = Color(0xFF9CCFD8),
            darkContainer = Color(0xFF26233A)
        ),
        // 8. Rosé Pine Moon
        MaterialThemePalette(
            id = "rose_pine_moon",
            name = "Rosé Pine Moon",
            seedHex = "#EA9A97",
            previewTop = Color(0xFF232136),
            previewBottomLeft = Color(0xFFEA9A97), // Iris
            previewBottomRight = Color(0xFF3E8FB0), // Pine
            lightPrimary = Color(0xFFD7827E),
            lightSecondary = Color(0xFF286983),
            lightTertiary = Color(0xFF907AA9),
            lightContainer = Color(0xFFF2E9DE),
            darkPrimary = Color(0xFFEA9A97),
            darkSecondary = Color(0xFF3E8FB0),
            darkTertiary = Color(0xFFC4A7E7),
            darkContainer = Color(0xFF2A283E)
        ),
        // 9. Dracula (Vampire Theme)
        MaterialThemePalette(
            id = "dracula",
            name = "Dracula",
            seedHex = "#BD93F9",
            previewTop = Color(0xFF282A36), // Background
            previewBottomLeft = Color(0xFFBD93F9), // Purple
            previewBottomRight = Color(0xFFFF79C6), // Pink
            lightPrimary = Color(0xFF6272A4),
            lightSecondary = Color(0xFFBD93F9),
            lightTertiary = Color(0xFFFF79C6),
            lightContainer = Color(0xFFF8F8F2),
            darkPrimary = Color(0xFFBD93F9),
            darkSecondary = Color(0xFFFF79C6),
            darkTertiary = Color(0xFF50FA7B),
            darkContainer = Color(0xFF44475A)
        ),
        // 10. Gruvbox Dark (Retro Groove)
        MaterialThemePalette(
            id = "gruvbox_dark",
            name = "Gruvbox Dark",
            seedHex = "#FE8019",
            previewTop = Color(0xFF282828), // Dark0
            previewBottomLeft = Color(0xFFFE8019), // Bright Orange
            previewBottomRight = Color(0xFFFABD2F), // Bright Yellow
            lightPrimary = Color(0xFFAF3A03),
            lightSecondary = Color(0xFFB57614),
            lightTertiary = Color(0xFF427B58),
            lightContainer = Color(0xFFEBDBB2),
            darkPrimary = Color(0xFFFE8019),
            darkSecondary = Color(0xFFFABD2F),
            darkTertiary = Color(0xFFB8BB26),
            darkContainer = Color(0xFF3C3836)
        ),
        // 11. Gruvbox Material (Forest/Warm)
        MaterialThemePalette(
            id = "gruvbox_forest",
            name = "Gruvbox Forest",
            seedHex = "#A9B665",
            previewTop = Color(0xFF1D2021),
            previewBottomLeft = Color(0xFFA9B665), // Green
            previewBottomRight = Color(0xFF7DAEA3), // Aqua
            lightPrimary = Color(0xFF6F8352),
            lightSecondary = Color(0xFF4C7A70),
            lightTertiary = Color(0xFFD8A657),
            lightContainer = Color(0xFFF2E5BC),
            darkPrimary = Color(0xFFA9B665),
            darkSecondary = Color(0xFF7DAEA3),
            darkTertiary = Color(0xFFEA6962),
            darkContainer = Color(0xFF282828)
        ),
        // 12. Cyberpunk (High-contrast Neon Yellow & Blue)
        MaterialThemePalette(
            id = "cyberpunk",
            name = "Cyberpunk Neon",
            seedHex = "#FCEE0A",
            previewTop = Color(0xFF000B1E),
            previewBottomLeft = Color(0xFFFCEE0A), // Neon Yellow
            previewBottomRight = Color(0xFF00F0FF), // Neon Cyan
            lightPrimary = Color(0xFFD6C800),
            lightSecondary = Color(0xFF00A2AD),
            lightTertiary = Color(0xFFFF003C),
            lightContainer = Color(0xFFFFFDE0),
            darkPrimary = Color(0xFFFCEE0A),
            darkSecondary = Color(0xFF00F0FF),
            darkTertiary = Color(0xFFFF003C),
            darkContainer = Color(0xFF072146)
        ),
        // 13. Monokai Pro (Spectrum of Vibrance)
        MaterialThemePalette(
            id = "monokai_pro",
            name = "Monokai Pro",
            seedHex = "#FF6188",
            previewTop = Color(0xFF2D2A2E),
            previewBottomLeft = Color(0xFFFF6188), // Red
            previewBottomRight = Color(0xFFFFD866), // Yellow
            lightPrimary = Color(0xFFCC335C),
            lightSecondary = Color(0xFFC7A000),
            lightTertiary = Color(0xFFA9DC76),
            lightContainer = Color(0xFFFCFCFA),
            darkPrimary = Color(0xFFFF6188),
            darkSecondary = Color(0xFFFFD866),
            darkTertiary = Color(0xFF78DCE8),
            darkContainer = Color(0xFF403E41)
        ),
        // 14. Solarized Dark
        MaterialThemePalette(
            id = "solarized_dark",
            name = "Solarized Dark",
            seedHex = "#268BD2",
            previewTop = Color(0xFF002B36),
            previewBottomLeft = Color(0xFF268BD2), // Blue
            previewBottomRight = Color(0xFF2AA198), // Cyan
            lightPrimary = Color(0xFF1E6FA8),
            lightSecondary = Color(0xFF1F8079),
            lightTertiary = Color(0xFF859900),
            lightContainer = Color(0xFFFDF6E3),
            darkPrimary = Color(0xFF268BD2),
            darkSecondary = Color(0xFF2AA198),
            darkTertiary = Color(0xFFB58900),
            darkContainer = Color(0xFF073642)
        ),
        // 15. Moonlight (Soft Indigo & Slate)
        MaterialThemePalette(
            id = "moonlight",
            name = "Moonlight",
            seedHex = "#82AAFF",
            previewTop = Color(0xFF1E2030),
            previewBottomLeft = Color(0xFF82AAFF), // Indigo
            previewBottomRight = Color(0xFFC099FF), // Violet
            lightPrimary = Color(0xFF4476E8),
            lightSecondary = Color(0xFF8855E0),
            lightTertiary = Color(0xFF4FD6BE),
            lightContainer = Color(0xFFE4E8F7),
            darkPrimary = Color(0xFF82AAFF),
            darkSecondary = Color(0xFFC099FF),
            darkTertiary = Color(0xFFFF757F),
            darkContainer = Color(0xFF222436)
        ),
        // 16. Sunset Glow (Warm Amber & Rose)
        MaterialThemePalette(
            id = "sunset_glow",
            name = "Sunset Glow",
            seedHex = "#FF7043",
            previewTop = Color(0xFF2D1600),
            previewBottomLeft = Color(0xFFFF7043), // Deep Orange
            previewBottomRight = Color(0xFFFFCA28), // Amber
            lightPrimary = Color(0xFFE64A19),
            lightSecondary = Color(0xFFFFA000),
            lightTertiary = Color(0xFFD81B60),
            lightContainer = Color(0xFFFBE9E7),
            darkPrimary = Color(0xFFFF8A65),
            darkSecondary = Color(0xFFFFD54F),
            darkTertiary = Color(0xFFF06292),
            darkContainer = Color(0xFF4E2600)
        ),
        // 17. Emerald Forest
        MaterialThemePalette(
            id = "emerald_forest",
            name = "Emerald Forest",
            seedHex = "#00C853",
            previewTop = Color(0xFF051B11),
            previewBottomLeft = Color(0xFF00E676), // Bright Green
            previewBottomRight = Color(0xFF1DE9B6), // Teal
            lightPrimary = Color(0xFF007E33),
            lightSecondary = Color(0xFF00897B),
            lightTertiary = Color(0xFF558B2F),
            lightContainer = Color(0xFFE8F5E9),
            darkPrimary = Color(0xFF00E676),
            darkSecondary = Color(0xFF1DE9B6),
            darkTertiary = Color(0xFF81C784),
            darkContainer = Color(0xFF0E3823)
        )
    )

    fun getPreset(idOrHex: String): MaterialThemePalette {
        return Presets.find { it.id.equals(idOrHex, ignoreCase = true) || it.seedHex.equals(idOrHex, ignoreCase = true) }
            ?: Presets[1] // Default Catppuccin Mocha
    }
}
