package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

data class TagPickerItem(
    val id: String,
    val name: String,
    val usageCount: Int = 0
)

@Composable
fun TagMultiSelectDialog(
    title: String,
    items: List<TagPickerItem>,
    selectedIds: Set<String>,
    singleSelection: Boolean = false,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    var currentSelected by remember { mutableStateOf(selectedIds) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredItems = remember(items, searchQuery) {
        if (searchQuery.isBlank()) items
        else items.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.vaultTopGlow(),
        title = {
            Text(title, fontWeight = FontWeight.Bold, color = palette.textPrimary, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Sleek Compact Fully-Rounded Search Input matching Add Scene style
                SceneInputField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search...",
                    backgroundColor = palette.cardBg,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_app_search),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = palette.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else null,
                    testTag = "tag_dialog_search_input"
                )

                // List of items with Circular Selection Indicators
                if (filteredItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No items found", color = palette.textMuted, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredItems, key = { it.id }) { item ->
                            val isChecked = currentSelected.contains(item.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        currentSelected = if (singleSelection) {
                                            if (isChecked) emptySet() else setOf(item.id)
                                        } else {
                                            if (isChecked) currentSelected - item.id else currentSelected + item.id
                                        }
                                    }
                                    .background(if (isChecked) accent.copy(alpha = 0.08f) else Color.Transparent)
                                    .padding(vertical = 10.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(if (isChecked) accent else Color.Transparent)
                                        .border(
                                            width = if (isChecked) 0.dp else 1.8.dp,
                                            color = if (isChecked) accent else palette.border,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isChecked) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Text(
                                    text = item.name,
                                    color = if (isChecked) accent else palette.textPrimary,
                                    fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(currentSelected) },
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = CircleShape,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    text = if (singleSelection) "Done" else if (currentSelected.isNotEmpty()) "Done (${currentSelected.size})" else "Done",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = CircleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border)
            ) {
                Text("Cancel", color = palette.textPrimary)
            }
        },
        shape = VaultDialogShape,
        containerColor = palette.dialogBg
    )
}
