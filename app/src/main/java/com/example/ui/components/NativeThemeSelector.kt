package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness2
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

data class ThemeOptionItem(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val previewBgColor: Color
)

/**
 * SELECT-UNIFY: Rebuilt with unified SelectorOptionRow system:
 * - 34dp preview circle swatch with 1.dp palette.border
 * - Removed pill surface and RadioButton
 * - Uses LocalVaultPalette and LocalAccentColor
 */
@Composable
fun NativeThemeSelector(
    selectedTheme: String,
    onSelectTheme: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current // SELECT-UNIFY
    val accent = LocalAccentColor.current // SELECT-UNIFY

    val options = listOf(
        ThemeOptionItem(
            id = "Dark",
            title = "Dark",
            icon = Icons.Outlined.DarkMode,
            previewBgColor = Color(0xFF1E1E22)
        ),
        ThemeOptionItem(
            id = "Amoled",
            title = "Amoled",
            icon = Icons.Outlined.Brightness2,
            previewBgColor = Color(0xFF000000)
        ),
        ThemeOptionItem(
            id = "Light",
            title = "Light",
            icon = Icons.Outlined.LightMode,
            previewBgColor = Color(0xFFF1F5F9)
        )
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp) // SELECT-UNIFY: 6.dp spacing
    ) {
        options.forEach { option ->
            val isSelected = selectedTheme.equals(option.id, ignoreCase = true)

            // SELECT-UNIFY: Unified SelectorOptionRow
            SelectorOptionRow(
                title = option.title,
                selected = isSelected,
                onClick = { onSelectTheme(option.id) },
                leading = {
                    Box(
                        modifier = Modifier
                            .size(34.dp) // SELECT-UNIFY: 34dp preview circle
                            .clip(CircleShape)
                            .background(option.previewBgColor)
                            .border(
                                width = 1.dp,
                                color = palette.border, // SELECT-UNIFY
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            tint = if (option.id == "Light") accent else Color(0xFFE2E8F0), // SELECT-UNIFY
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        }
    }
}
