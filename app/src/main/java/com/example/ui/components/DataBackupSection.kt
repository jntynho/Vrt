package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SELECT-UNIFY: Data & Backup Section unified into a single MUSE-REF GroupedCard.
 * - Replaced separate cards and full-width buttons with unified clickable row design
 * - 44dp leading icon circles in accent
 * - Status messages placed inline with Color(0xFF30D158) for success
 */
@Composable
fun DataBackupSection(
    onExportJson: suspend () -> String,
    onImportJson: suspend (String) -> Result<Int>,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current // SELECT-UNIFY
    val accent = LocalAccentColor.current // SELECT-UNIFY
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    var pendingExportData by remember { mutableStateOf("") }
    var exportStatusMessage by remember { mutableStateOf<String?>(null) }
    var isExportError by remember { mutableStateOf(false) }

    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var isImportError by remember { mutableStateOf(false) }

    // SAF CreateDocument Launcher for Export (Direct JSON file download/save)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { fileUri ->
            coroutineScope.launch {
                try {
                    context.contentResolver.openOutputStream(fileUri)?.use { stream ->
                        stream.write(pendingExportData.toByteArray(Charsets.UTF_8))
                    }
                    exportStatusMessage = "JSON backup file exported and downloaded successfully!"
                    isExportError = false
                } catch (e: Exception) {
                    exportStatusMessage = "Failed to export file: ${e.localizedMessage ?: "Unknown error"}"
                    isExportError = true
                }
            }
        }
    }

    // SAF OpenDocument Launcher for Import (Direct JSON file upload/selection)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { fileUri ->
            coroutineScope.launch {
                try {
                    val jsonContent = context.contentResolver.openInputStream(fileUri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    }
                    if (!jsonContent.isNullOrBlank()) {
                        val result = onImportJson(jsonContent)
                        if (result.isSuccess) {
                            val count = result.getOrDefault(0)
                            importStatusMessage = "Successfully imported $count items from JSON file!"
                            isImportError = false
                        } else {
                            importStatusMessage = "Failed to import JSON file: Invalid database format"
                            isImportError = true
                        }
                    } else {
                        importStatusMessage = "The selected JSON file is empty"
                        isImportError = true
                    }
                } catch (e: Exception) {
                    importStatusMessage = "Failed to read file: ${e.localizedMessage ?: "Unknown error"}"
                    isImportError = true
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // SELECT-UNIFY: Single GroupedCard for both Export and Import operations
        UnifiedGroupedCard {
            // 1. Export Data Row
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        coroutineScope.launch {
                            val json = onExportJson()
                            pendingExportData = json
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            exportLauncher.launch("goony_backup_$timestamp.json")
                        }
                    }
                    .padding(vertical = 18.dp, horizontal = 20.dp) // SELECT-UNIFY
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Leading 44dp circle
                        Box(
                            modifier = Modifier
                                .size(44.dp) // SELECT-UNIFY: 44dp icon circle
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.15f)), // SELECT-UNIFY
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                contentDescription = null,
                                tint = accent, // SELECT-UNIFY
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Export Data",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium, // SELECT-UNIFY
                                    fontSize = 16.sp // SELECT-UNIFY
                                ),
                                color = palette.textPrimary // SELECT-UNIFY
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Save database backup directly as a downloadable JSON file",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 13.sp // SELECT-UNIFY
                                ),
                                color = palette.textSecondary // SELECT-UNIFY
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = "Export",
                        tint = palette.textMuted, // SELECT-UNIFY
                        modifier = Modifier.size(20.dp) // SELECT-UNIFY
                    )
                }

                // Export status message under the row
                exportStatusMessage?.let { status ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 60.dp) // Indented to align with text
                    ) {
                        Icon(
                            imageVector = if (isExportError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = if (isExportError) MaterialTheme.colorScheme.error else Color(0xFF30D158), // SELECT-UNIFY: 0xFF30D158
                            modifier = Modifier.size(16.dp) // SELECT-UNIFY: 16dp
                        )
                        Text(
                            text = status,
                            fontSize = 12.sp, // SELECT-UNIFY: 12sp
                            color = if (isExportError) MaterialTheme.colorScheme.error else Color(0xFF30D158) // SELECT-UNIFY
                        )
                    }
                }
            }

            // Hairline divider between Export and Import
            UnifiedSettingsDivider() // SELECT-UNIFY

            // 2. Import Data Row
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    }
                    .padding(vertical = 18.dp, horizontal = 20.dp) // SELECT-UNIFY
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Leading 44dp circle
                        Box(
                            modifier = Modifier
                                .size(44.dp) // SELECT-UNIFY: 44dp icon circle
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.15f)), // SELECT-UNIFY
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileUpload,
                                contentDescription = null,
                                tint = accent, // SELECT-UNIFY
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Import Data",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium, // SELECT-UNIFY
                                    fontSize = 16.sp // SELECT-UNIFY
                                ),
                                color = palette.textPrimary // SELECT-UNIFY
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Restore or add records to database by uploading a JSON file",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 13.sp // SELECT-UNIFY
                                ),
                                color = palette.textSecondary // SELECT-UNIFY
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Icon(
                        imageVector = Icons.Outlined.Upload,
                        contentDescription = "Import",
                        tint = palette.textMuted, // SELECT-UNIFY
                        modifier = Modifier.size(20.dp) // SELECT-UNIFY
                    )
                }

                // Import status message under the row
                importStatusMessage?.let { status ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 60.dp) // Indented to align with text
                    ) {
                        Icon(
                            imageVector = if (isImportError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = if (isImportError) MaterialTheme.colorScheme.error else Color(0xFF30D158), // SELECT-UNIFY: 0xFF30D158
                            modifier = Modifier.size(16.dp) // SELECT-UNIFY: 16dp
                        )
                        Text(
                            text = status,
                            fontSize = 12.sp, // SELECT-UNIFY: 12sp
                            color = if (isImportError) MaterialTheme.colorScheme.error else Color(0xFF30D158) // SELECT-UNIFY
                        )
                    }
                }
            }
        }
    }
}
