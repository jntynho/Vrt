package com.example.ui.theme

import androidx.compose.ui.graphics.Color

open class GoonyThemePalette(
    open val name: String,
    open val bg: Color,
    open val surface: Color,
    open val cardBg: Color,
    open val textPrimary: Color,
    open val textSecondary: Color,
    open val textMuted: Color,
    open val border: Color,
    open val skeletonBg: Color,
    open val dialogBg: Color = bg
) {
    class Dynamic(
        override val name: String,
        override val bg: Color,
        override val surface: Color,
        override val cardBg: Color,
        override val textPrimary: Color,
        override val textSecondary: Color,
        override val textMuted: Color,
        override val border: Color,
        override val skeletonBg: Color,
        override val dialogBg: Color = bg
    ) : GoonyThemePalette(name, bg, surface, cardBg, textPrimary, textSecondary, textMuted, border, skeletonBg, dialogBg)

    object Dark : GoonyThemePalette(
        name = "Dark",
        bg = Color(0xFF1F1F1F),
        surface = Color(0xFF1F1F1F),
        cardBg = Color(0xFF353638),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFABABAF),
        textMuted = Color(0xFF7C7C80),
        border = Color(0x1AFFFFFF),
        skeletonBg = Color(0xFF2A2A2C),
        dialogBg = Color(0xFF242527)
    )

    object Amoled : GoonyThemePalette(
        name = "Amoled",
        bg = Color(0xFF000000),
        surface = Color(0xFF000000),
        cardBg = Color(0xFF1E1E24),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFFCCCCCC),
        textMuted = Color(0xFF888888),
        border = Color(0x33FFFFFF),
        skeletonBg = Color(0xFF1B1B20),
        dialogBg = Color(0xFF111114)
    )

    object Light : GoonyThemePalette(
        name = "Light",
        bg = Color(0xFFF8FAFC),
        surface = Color(0xFFF8FAFC),
        cardBg = Color(0xFFFFFFFF),
        textPrimary = Color(0xFF0F172A),
        textSecondary = Color(0xFF475569),
        textMuted = Color(0xFF64748B),
        border = Color(0x1F0F172A),
        skeletonBg = Color(0xFFE2E8F0),
        dialogBg = Color(0xFFF1F5F9)
    )

    companion object {
        fun fromName(name: String): GoonyThemePalette {
            return when (name.lowercase()) {
                "amoled" -> Amoled
                "light" -> Light
                else -> Dark
            }
        }
    }
}

// Backward-compatible alias for existing components
typealias VaultThemePalette = GoonyThemePalette
