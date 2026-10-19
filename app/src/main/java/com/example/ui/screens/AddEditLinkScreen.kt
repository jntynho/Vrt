package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.entity.LinkEntity
import com.example.ui.MainViewModel
import com.example.ui.components.PasteTrailingIcon
import com.example.ui.components.SceneInputField
import com.example.ui.components.TagMultiSelectDialog
import com.example.ui.components.TagPickerItem
import com.example.ui.components.Tophead
import com.example.ui.components.VaultDialogShape
import com.example.ui.components.triggerVibrate
import com.example.ui.components.vaultTopGlow
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private val threadLocalDateFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("MMM dd, yyyy", Locale.US)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditLinkScreen(
    viewModel: MainViewModel,
    linkId: String?,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val links by viewModel.allLinks.collectAsStateWithLifecycle()
    val actors by viewModel.allActors.collectAsStateWithLifecycle()
    val studios by viewModel.allStudios.collectAsStateWithLifecycle()
    val isFetchingMagnet by viewModel.isFetchingMagnet.collectAsStateWithLifecycle()

    val actorUsageCounts by viewModel.actorSceneCounts.collectAsStateWithLifecycle()
    val studioUsageCounts by viewModel.studioSceneCounts.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose {
            viewModel.cancelMagnetFetch()
        }
    }

    val existingLink = remember(linkId, links) {
        links.firstOrNull { it.id == linkId }
    }

    var title by remember { mutableStateOf(existingLink?.title ?: "") }
    var coverImage by remember { mutableStateOf(existingLink?.coverImage ?: "") }
    var coverOffset by remember { mutableFloatStateOf(existingLink?.coverOffset ?: 50f) }
    var aspectRatio by remember { mutableStateOf(existingLink?.aspectRatio ?: "16:9") }
    var urlHD by remember { mutableStateOf(existingLink?.urlHD ?: "") }
    var url4K by remember { mutableStateOf(existingLink?.url4K ?: "") }
    var urlVR by remember { mutableStateOf(existingLink?.urlVR ?: "") }
    var magnetHD by remember { mutableStateOf(existingLink?.magnet ?: "") }
    var magnet4K by remember { mutableStateOf(existingLink?.magnet4K ?: "") }
    var magnetVR by remember { mutableStateOf(existingLink?.magnetVR ?: "") }
    var torrentUrlHD by remember { mutableStateOf(existingLink?.torrentUrlHD ?: "") }
    var torrentUrl4K by remember { mutableStateOf(existingLink?.torrentUrl4K ?: "") }
    var torrentUrlVR by remember { mutableStateOf(existingLink?.torrentUrlVR ?: "") }
    var torrentSiteName by remember { mutableStateOf(existingLink?.torrentSiteName ?: "") }
    var selectedActorIds by remember { mutableStateOf(existingLink?.actorIds ?: emptyList()) }
    var selectedStudioIds by remember { mutableStateOf(existingLink?.studioIds ?: emptyList()) }
    var assignedDate by remember {
        mutableStateOf(existingLink?.assignedDate ?: existingLink?.createdAt ?: System.currentTimeMillis())
    }

    // Populate state safely exactly once when existingLink loads asynchronously from Room
    var hasInitializedFromDb by remember(linkId) { mutableStateOf(false) }
    LaunchedEffect(existingLink) {
        if (existingLink != null && !hasInitializedFromDb) {
            hasInitializedFromDb = true
            title = existingLink.title
            coverImage = existingLink.coverImage
            coverOffset = existingLink.coverOffset
            aspectRatio = existingLink.aspectRatio
            urlHD = existingLink.urlHD ?: ""
            url4K = existingLink.url4K ?: ""
            urlVR = existingLink.urlVR ?: ""
            magnetHD = existingLink.magnet ?: ""
            magnet4K = existingLink.magnet4K ?: ""
            magnetVR = existingLink.magnetVR ?: ""
            torrentUrlHD = existingLink.torrentUrlHD ?: ""
            torrentUrl4K = existingLink.torrentUrl4K ?: ""
            torrentUrlVR = existingLink.torrentUrlVR ?: ""
            torrentSiteName = existingLink.torrentSiteName ?: ""
            selectedActorIds = existingLink.actorIds
            selectedStudioIds = existingLink.studioIds
            assignedDate = existingLink.assignedDate ?: existingLink.createdAt
        }
    }

    var showStudioPicker by remember { mutableStateOf(false) }
    var showActorPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val hasUnsavedChanges by remember(
        title, coverImage, urlHD, url4K, urlVR,
        magnetHD, magnet4K, magnetVR, torrentUrlHD, torrentUrl4K, torrentUrlVR,
        selectedActorIds, selectedStudioIds, assignedDate, existingLink
    ) {
        derivedStateOf {
            if (existingLink == null) {
                title.isNotBlank() || coverImage.isNotBlank() ||
                urlHD.isNotBlank() || url4K.isNotBlank() || urlVR.isNotBlank() ||
                magnetHD.isNotBlank() || magnet4K.isNotBlank() || magnetVR.isNotBlank() ||
                selectedActorIds.isNotEmpty() || selectedStudioIds.isNotEmpty()
            } else {
                title.trim() != existingLink.title ||
                coverImage.trim() != existingLink.coverImage ||
                (urlHD.trim().ifEmpty { null } != existingLink.urlHD) ||
                (url4K.trim().ifEmpty { null } != existingLink.url4K) ||
                (urlVR.trim().ifEmpty { null } != existingLink.urlVR) ||
                (magnetHD.trim().ifEmpty { null } != existingLink.magnet) ||
                (magnet4K.trim().ifEmpty { null } != existingLink.magnet4K) ||
                (magnetVR.trim().ifEmpty { null } != existingLink.magnetVR) ||
                (torrentUrlHD.trim().ifEmpty { null } != existingLink.torrentUrlHD) ||
                (torrentUrl4K.trim().ifEmpty { null } != existingLink.torrentUrl4K) ||
                (torrentUrlVR.trim().ifEmpty { null } != existingLink.torrentUrlVR) ||
                selectedActorIds != existingLink.actorIds ||
                selectedStudioIds != existingLink.studioIds ||
                assignedDate != (existingLink.assignedDate ?: existingLink.createdAt)
            }
        }
    }

    BackHandler(enabled = hasUnsavedChanges) {
        showDiscardDialog = true
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = {
                Text(
                    text = "Discard Changes?",
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "You have unsaved changes. Are you sure you want to discard them?",
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        viewModel.navigateBack()
                    }
                ) {
                    Text("Discard", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep Editing", color = accent)
                }
            },
            containerColor = palette.cardBg,
            shape = VaultDialogShape
        )
    }

    val formattedAssignedDate = remember(assignedDate) {
        try {
            threadLocalDateFormatter.get()?.format(Date(assignedDate)).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = assignedDate)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            modifier = Modifier.vaultTopGlow(),
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedUtcMillis ->
                            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = selectedUtcMillis
                            }
                            val localCal = Calendar.getInstance().apply {
                                set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                                set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                                set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                                set(Calendar.HOUR_OF_DAY, 12)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            assignedDate = localCal.timeInMillis
                        }
                        showDatePicker = false
                    },
                    shape = CircleShape
                ) {
                    Text("OK", color = accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    shape = CircleShape
                ) {
                    Text("Cancel", color = palette.textSecondary)
                }
            },
            shape = VaultDialogShape,
            colors = DatePickerDefaults.colors(containerColor = palette.cardBg)
        ) {
            DatePicker(
                state = datePickerState,
                colors = DatePickerDefaults.colors(
                    containerColor = palette.cardBg,
                    titleContentColor = palette.textPrimary,
                    headlineContentColor = palette.textPrimary,
                    weekdayContentColor = palette.textSecondary,
                    subheadContentColor = palette.textSecondary,
                    yearContentColor = palette.textPrimary,
                    currentYearContentColor = accent,
                    selectedYearContentColor = Color.White,
                    selectedYearContainerColor = accent,
                    dayContentColor = palette.textPrimary,
                    selectedDayContentColor = Color.White,
                    selectedDayContainerColor = accent,
                    todayContentColor = accent,
                    todayDateBorderColor = accent
                )
            )
        }
    }

    val sortedStudioItems = remember(studios, studioUsageCounts) {
        studios.map { studio ->
            TagPickerItem(
                id = studio.id,
                name = studio.name,
                usageCount = studioUsageCounts[studio.id] ?: 0
            )
        }.sortedWith(
            compareByDescending<TagPickerItem> { it.usageCount }.thenBy { it.name.lowercase() }
        )
    }

    val sortedActorItems = remember(actors, actorUsageCounts) {
        actors.map { actor ->
            TagPickerItem(
                id = actor.id,
                name = actor.name,
                usageCount = actorUsageCounts[actor.id] ?: 0
            )
        }.sortedWith(
            compareByDescending<TagPickerItem> { it.usageCount }.thenBy { it.name.lowercase() }
        )
    }

    if (showStudioPicker) {
        TagMultiSelectDialog(
            title = "Tag Studio",
            items = sortedStudioItems,
            selectedIds = selectedStudioIds.take(1).toSet(),
            singleSelection = true,
            onConfirm = { updated ->
                selectedStudioIds = updated.take(1).toList()
                showStudioPicker = false
            },
            onDismiss = { showStudioPicker = false }
        )
    }

    if (showActorPicker) {
        TagMultiSelectDialog(
            title = "Tag Actors",
            items = sortedActorItems,
            selectedIds = selectedActorIds.toSet(),
            singleSelection = false,
            onConfirm = { updated ->
                selectedActorIds = updated.toList()
                showActorPicker = false
            },
            onDismiss = { showActorPicker = false }
        )
    }

    val cardShape = RoundedCornerShape(20.dp)
    val chipShape = RoundedCornerShape(24.dp)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Tophead(
                title = if (existingLink != null) "Edit Scene" else "Add Scene",
                onBack = {
                    if (hasUnsavedChanges) {
                        showDiscardDialog = true
                    } else {
                        viewModel.navigateBack()
                    }
                },
                showBorder = false,
                actions = {
                    IconButton(
                        onClick = {
                            if (title.isNotBlank()) {
                                val targetId = linkId ?: existingLink?.id ?: UUID.randomUUID().toString()
                                val baseLink = existingLink ?: LinkEntity(id = targetId, title = "")
                                val linkToSave = baseLink.copy(
                                    id = targetId,
                                    title = title.trim(),
                                    coverImage = coverImage.trim(),
                                    coverOffset = coverOffset,
                                    aspectRatio = aspectRatio,
                                    urlHD = urlHD.trim().ifEmpty { null },
                                    url4K = url4K.trim().ifEmpty { null },
                                    urlVR = urlVR.trim().ifEmpty { null },
                                    magnet = magnetHD.trim().ifEmpty { null },
                                    magnet4K = magnet4K.trim().ifEmpty { null },
                                    magnetVR = magnetVR.trim().ifEmpty { null },
                                    torrentUrlHD = torrentUrlHD.trim().ifEmpty { null },
                                    torrentUrl4K = torrentUrl4K.trim().ifEmpty { null },
                                    torrentUrlVR = torrentUrlVR.trim().ifEmpty { null },
                                    torrentSiteName = torrentSiteName.trim().ifEmpty { null },
                                    actorIds = selectedActorIds,
                                    studioIds = selectedStudioIds,
                                    assignedDate = assignedDate
                                )
                                viewModel.saveLink(linkToSave)
                                viewModel.navigateBack()
                            }
                        },
                        enabled = title.isNotBlank(),
                        modifier = Modifier.testTag("save_scene_button")
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_action_save),
                            contentDescription = "Save",
                            tint = if (title.isNotBlank()) accent else palette.textMuted
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    keyboardController?.hide()
                }
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Title Input Row with Fetch Magnet Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SceneInputField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "Title",
                    trailingIcon = {
                        PasteTrailingIcon(onPaste = { title = it })
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "scene_title_input"
                )

                Surface(
                    shape = CircleShape,
                    color = accent.copy(alpha = if (isFetchingMagnet) 0.5f else 1f),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("fetch_magnet_button")
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                enabled = !isFetchingMagnet,
                                onClick = {
                                    triggerVibrate(context)
                                    val selectedActors = actors.filter { selectedActorIds.contains(it.id) }
                                    val selectedStudios = studios.filter { selectedStudioIds.contains(it.id) }
                                    viewModel.fetchMagnet(
                                        title = title,
                                        selectedActors = selectedActors,
                                        selectedStudios = selectedStudios,
                                        assignedDate = assignedDate,
                                        urlHD = urlHD,
                                        url4K = url4K,
                                        onSuccess = { result ->
                                            triggerVibrate(context)
                                            val oldHD = magnetHD
                                            val old4K = magnet4K
                                            val oldVR = magnetVR
                                            val oldTorrentHD = torrentUrlHD
                                            val oldTorrent4K = torrentUrl4K
                                            val oldTorrentVR = torrentUrlVR
                                            val oldSiteName = torrentSiteName
                                            var replacedAny = false
                                            val filledQualities = mutableListOf<String>()

                                            if (result.magnet1080p != null) {
                                                if (magnetHD.isNotBlank() && magnetHD != result.magnet1080p) {
                                                    replacedAny = true
                                                }
                                                magnetHD = result.magnet1080p
                                                filledQualities.add("1080p")
                                            }
                                            if (result.url1080p != null) {
                                                torrentUrlHD = result.url1080p
                                            }

                                            if (result.magnet2160p != null) {
                                                if (magnet4K.isNotBlank() && magnet4K != result.magnet2160p) {
                                                    replacedAny = true
                                                }
                                                magnet4K = result.magnet2160p
                                                filledQualities.add("4K")
                                            }
                                            if (result.url2160p != null) {
                                                torrentUrl4K = result.url2160p
                                            }

                                            if (result.magnetVR != null) {
                                                if (magnetVR.isNotBlank() && magnetVR != result.magnetVR) {
                                                    replacedAny = true
                                                }
                                                magnetVR = result.magnetVR
                                                filledQualities.add("VR")
                                            }
                                            if (result.urlVR != null) {
                                                torrentUrlVR = result.urlVR
                                            }

                                            if (result.sourceSite.isNotBlank()) {
                                                torrentSiteName = result.sourceSite
                                            }

                                            val qStr = if (filledQualities.isNotEmpty()) " (${filledQualities.joinToString(" + ")})" else ""
                                            Toast.makeText(
                                                context,
                                                "Magnet links retrieved successfully!$qStr",
                                                Toast.LENGTH_SHORT
                                            ).show()

                                            if (replacedAny) {
                                                coroutineScope.launch {
                                                    val snackResult = snackbarHostState.showSnackbar(
                                                        message = "Magnet replaced",
                                                        actionLabel = "Undo",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                    if (snackResult == SnackbarResult.ActionPerformed) {
                                                        magnetHD = oldHD
                                                        magnet4K = old4K
                                                        magnetVR = oldVR
                                                        torrentUrlHD = oldTorrentHD
                                                        torrentUrl4K = oldTorrent4K
                                                        torrentUrlVR = oldTorrentVR
                                                        torrentSiteName = oldSiteName
                                                    }
                                                }
                                            }
                                        },
                                        onNoResult = {
                                            Toast.makeText(
                                                context,
                                                "No verified torrent found for this actor/studio on this date.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        },
                                        onError = { err ->
                                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isFetchingMagnet) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_magnet),
                                contentDescription = "Fetch Magnet",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // 2. Date & Cover on the Same Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val dateInteractionSource = remember { MutableInteractionSource() }
                LaunchedEffect(dateInteractionSource) {
                    dateInteractionSource.interactions.collect { interaction ->
                        if (interaction is PressInteraction.Release) {
                            showDatePicker = true
                        }
                    }
                }

                SceneInputField(
                    value = formattedAssignedDate,
                    onValueChange = { },
                    readOnly = true,
                    placeholder = "Date",
                    interactionSource = dateInteractionSource,
                    trailingIcon = {
                        IconButton(
                            onClick = { showDatePicker = true },
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_calendar),
                                contentDescription = "Select Date",
                                tint = palette.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "scene_date_input"
                )

                SceneInputField(
                    value = coverImage,
                    onValueChange = { coverImage = it },
                    placeholder = "Cover",
                    trailingIcon = {
                        PasteTrailingIcon(onPaste = { coverImage = it })
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "cover_image_input"
                )
            }

            // 3. Preview Card (16:9 Ratio with Rounded Corners)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Preview",
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = palette.cardBg),
                    border = BorderStroke(1.dp, palette.border)
                ) {
                    var isPreviewError by remember(coverImage) { mutableStateOf(false) }

                    if (coverImage.isNotBlank() && !isPreviewError) {
                        AsyncImage(
                            model = coverImage,
                            contentDescription = "Cover Preview",
                            contentScale = ContentScale.Crop,
                            onSuccess = { isPreviewError = false },
                            onError = { isPreviewError = true },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Image,
                                    contentDescription = null,
                                    tint = palette.textMuted,
                                    modifier = Modifier.size(34.dp)
                                )
                                Text(
                                    text = if (isPreviewError) "Failed to load cover" else "No cover image",
                                    color = palette.textMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // 4. Stream Section
            Text(
                text = "Stream",
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SceneInputField(
                    value = magnetHD,
                    onValueChange = { magnetHD = it },
                    placeholder = "HD Magnet",
                    trailingIcon = {
                        PasteTrailingIcon(onPaste = { magnetHD = it })
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "hd_magnet_input"
                )
                SceneInputField(
                    value = magnet4K,
                    onValueChange = { magnet4K = it },
                    placeholder = "4K Magnet",
                    trailingIcon = {
                        PasteTrailingIcon(onPaste = { magnet4K = it })
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "4k_magnet_input"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SceneInputField(
                    value = magnetVR,
                    onValueChange = { magnetVR = it },
                    placeholder = "VR Magnet",
                    trailingIcon = {
                        PasteTrailingIcon(onPaste = { magnetVR = it })
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "vr_magnet_input"
                )
                SceneInputField(
                    value = urlVR,
                    onValueChange = { urlVR = it },
                    placeholder = "VR URL",
                    trailingIcon = {
                        PasteTrailingIcon(onPaste = { urlVR = it })
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "vr_url_input"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SceneInputField(
                    value = urlHD,
                    onValueChange = { urlHD = it },
                    placeholder = "HD URL",
                    trailingIcon = {
                        PasteTrailingIcon(onPaste = { urlHD = it })
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "hd_url_input"
                )
                SceneInputField(
                    value = url4K,
                    onValueChange = { url4K = it },
                    placeholder = "4K URL",
                    trailingIcon = {
                        PasteTrailingIcon(onPaste = { url4K = it })
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "4k_url_input"
                )
            }

            // 5. Native Tag Actors Section
            val selectedActorsList = remember(selectedActorIds, actors) {
                val idsSet = selectedActorIds.toSet()
                actors.filter { idsSet.contains(it.id) }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tag Actors ${if (selectedActorsList.isNotEmpty()) "(${selectedActorsList.size})" else ""}",
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Surface(
                        shape = CircleShape,
                        color = accent.copy(alpha = 0.14f),
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable { showActorPicker = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_add),
                                contentDescription = "Add Actor",
                                tint = accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (selectedActorsList.isEmpty()) {
                    Text(
                        text = "No actors tagged. Tap + to choose from your actors.",
                        color = palette.textMuted,
                        fontSize = 12.sp
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(selectedActorsList, key = { it.id }) { actor ->
                            InputChip(
                                selected = true,
                                onClick = {
                                    selectedActorIds = selectedActorIds - actor.id
                                },
                                label = { Text(actor.name, fontSize = 12.sp) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                shape = chipShape,
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = accent.copy(alpha = 0.18f),
                                    selectedLabelColor = palette.textPrimary
                                )
                            )
                        }
                    }
                }
            }

            // 6. Native Tag Studio Section
            val selectedStudiosList = remember(selectedStudioIds, studios) {
                val idsSet = selectedStudioIds.toSet()
                studios.filter { idsSet.contains(it.id) }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tag Studio ${if (selectedStudiosList.isNotEmpty()) "(${selectedStudiosList.size})" else ""}",
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Surface(
                        shape = CircleShape,
                        color = accent.copy(alpha = 0.14f),
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable { showStudioPicker = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_app_add),
                                contentDescription = "Add Studio",
                                tint = accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (selectedStudiosList.isEmpty()) {
                    Text(
                        text = "No studios tagged. Tap + to choose from your studios.",
                        color = palette.textMuted,
                        fontSize = 12.sp
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(selectedStudiosList, key = { it.id }) { studio ->
                            InputChip(
                                selected = true,
                                onClick = {
                                    selectedStudioIds = selectedStudioIds - studio.id
                                },
                                label = { Text(studio.name, fontSize = 12.sp) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                shape = chipShape,
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = accent.copy(alpha = 0.18f),
                                    selectedLabelColor = palette.textPrimary
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
