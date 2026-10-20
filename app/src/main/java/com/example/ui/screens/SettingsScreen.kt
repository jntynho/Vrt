package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.entity.SettingsEntity
import com.example.ui.MainViewModel
import com.example.ui.ScreenState
import com.example.ui.SettingsSection
import com.example.ui.components.*
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalVaultPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val currentSettingsRaw by viewModel.settings.collectAsStateWithLifecycle()
    val currentSettings = currentSettingsRaw ?: SettingsEntity()

    var themeName by remember(currentSettings) { mutableStateOf(currentSettings.currentTheme) }
    var accentHex by remember(currentSettings) { mutableStateOf(currentSettings.accentColorHex) }
    var torboxKey by remember(currentSettings) { mutableStateOf(currentSettings.torboxApiKey) }
    var rdKey by remember(currentSettings) { mutableStateOf(currentSettings.realDebridApiKey) }
    var stashDbKey by remember(currentSettings) { mutableStateOf(currentSettings.stashDbApiKey) }
    var showStashDbKey by remember { mutableStateOf(false) }

    var sampleDataStatus by remember { mutableStateOf("") }
    val initialSection = remember {
        val sec = when (viewModel.initialSettingsSection) {
            "DISPLAY" -> SettingsSection.DISPLAY
            "PRIVACY" -> SettingsSection.PRIVACY
            "INTEGRATIONS" -> SettingsSection.INTEGRATIONS
            "FILTER" -> SettingsSection.FILTER
            "DATA_BACKUP" -> SettingsSection.DATA_BACKUP
            "SAMPLE_DATA" -> SettingsSection.SAMPLE_DATA
            else -> SettingsSection.MAIN_MENU
        }
        viewModel.initialSettingsSection = null
        sec
    }
    var currentSection by remember { mutableStateOf(initialSection) }
    val sectionBackStack = remember { mutableStateListOf<SettingsSection>() }

    fun navigateToSection(target: SettingsSection) {
        if (target != currentSection) {
            sectionBackStack.add(currentSection)
            currentSection = target
        }
    }

    fun handleBack() {
        if (sectionBackStack.isNotEmpty()) {
            currentSection = sectionBackStack.removeAt(sectionBackStack.size - 1)
        } else if (currentSection != SettingsSection.MAIN_MENU) {
            currentSection = SettingsSection.MAIN_MENU
        } else {
            val handled = viewModel.navigateBack()
            if (!handled) {
                viewModel.navigateTo(ScreenState.Home)
            }
        }
    }

    BackHandler(enabled = true) {
        handleBack()
    }

    Scaffold(
        modifier = modifier,
        containerColor = palette.bg,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    AnimatedContent(
                        targetState = currentSection,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(180, easing = LinearOutSlowInEasing)) togetherWith
                                    fadeOut(animationSpec = tween(140, easing = FastOutLinearInEasing))
                        },
                        label = "settings_topbar_title"
                    ) { sec ->
                        Text(
                            sec.title(),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            ),
                            color = palette.textPrimary
                        )
                    }
                },
                navigationIcon = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3B3C3E))
                            .testTag("back_button")
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { handleBack() }
                            )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_player_back),
                            contentDescription = "Back",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = palette.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            AnimatedContent(
                targetState = currentSection,
                modifier = Modifier.clipToBounds(),
                transitionSpec = settingsTransitionSpec(),
                label = "settings_navigation"
            ) { section ->
                when (section) {
                    SettingsSection.MAIN_MENU -> {
                        SettingsMainMenu(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 116.dp),
                            currentSettings = currentSettings,
                            onNavigateTo = { navigateToSection(it) }
                        )
                    }
                    SettingsSection.DISPLAY -> {
                        SettingsDisplaySection(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .padding(bottom = 116.dp),
                            themeName = themeName,
                            onThemeChange = {
                                themeName = it
                                viewModel.updateSettings(currentSettings.copy(currentTheme = it))
                            },
                            accentHex = accentHex,
                            onAccentChange = {
                                accentHex = it
                                viewModel.updateSettings(currentSettings.copy(accentColorHex = it))
                            },
                            showManagementCards = currentSettings.showManagementCards,
                            onShowManagementCardsChange = {
                                viewModel.updateSettings(currentSettings.copy(showManagementCards = it))
                            },
                            adaptiveCoverAspectRatio = currentSettings.adaptiveCoverAspectRatio,
                            onAdaptiveCoverAspectRatioChange = {
                                viewModel.updateSettings(currentSettings.copy(adaptiveCoverAspectRatio = it))
                            },
                            enableVideoPlayerGestures = currentSettings.enableVideoPlayerGestures,
                            onEnableVideoPlayerGesturesChange = {
                                viewModel.updateSettings(currentSettings.copy(enableVideoPlayerGestures = it))
                            },
                            navBarTransparency = currentSettings.navBarTransparency,
                            onNavBarTransparencyChange = {
                                viewModel.updateSettings(currentSettings.copy(navBarTransparency = it))
                            },
                            navBarBlurDp = currentSettings.navBarBlurDp,
                            onNavBarBlurDpChange = {
                                viewModel.updateSettings(currentSettings.copy(navBarBlurDp = it))
                            },
                            navBarActiveTabBlurDp = currentSettings.navBarActiveTabBlurDp,
                            onNavBarActiveTabBlurDpChange = {
                                viewModel.updateSettings(currentSettings.copy(navBarActiveTabBlurDp = it))
                            },
                            navBarActiveTabIntensity = currentSettings.navBarActiveTabIntensity,
                            onNavBarActiveTabIntensityChange = {
                                viewModel.updateSettings(currentSettings.copy(navBarActiveTabIntensity = it))
                            }
                        )
                    }
                    SettingsSection.PRIVACY -> {
                        SettingsPrivacySection(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .padding(bottom = 116.dp),
                            betaTestPrivacy = currentSettings.betaTestPrivacy,
                            onBetaTestPrivacyChange = {
                                viewModel.updateSettings(currentSettings.copy(betaTestPrivacy = it))
                            },
                            isPasscodeEnabled = currentSettings.isPasscodeEnabled,
                            passcodeHash = currentSettings.passcodeHash,
                            onEnablePasscode = { pin -> viewModel.enablePasscode(pin) },
                            onDisablePasscode = { viewModel.disablePasscode() },
                            onChangePasscode = { pin -> viewModel.changePasscode(pin) }
                        )
                    }
                    SettingsSection.INTEGRATIONS -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .padding(bottom = 116.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            IntegrationsDropdownDebridSection(
                                modifier = Modifier.fillMaxWidth(),
                                realDebridKey = rdKey,
                                onRealDebridKeyChange = {
                                    rdKey = it
                                    viewModel.updateSettings(currentSettings.copy(realDebridApiKey = it.trim(), torboxApiKey = torboxKey.trim()))
                                },
                                torboxKey = torboxKey,
                                onTorboxKeyChange = {
                                    torboxKey = it
                                    viewModel.updateSettings(currentSettings.copy(torboxApiKey = it.trim(), realDebridApiKey = rdKey.trim()))
                                },
                                debridOrder = currentSettings.debridOrder,
                                onDebridOrderChange = {
                                    viewModel.updateSettings(currentSettings.copy(debridOrder = it))
                                }
                            )

                            SettingsSectionHeader(text = "Metadata")
                            GroupedCard {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp, horizontal = 20.dp)
                                ) {
                                    Text(
                                        text = "StashDB API Key",
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = palette.textPrimary
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    OutlinedTextField(
                                        value = stashDbKey,
                                        onValueChange = {
                                            stashDbKey = it
                                            viewModel.updateSettings(currentSettings.copy(stashDbApiKey = it.trim()))
                                        },
                                        placeholder = {
                                            Text(
                                                "Paste StashDB API token here...",
                                                fontSize = 14.sp,
                                                color = palette.textSecondary
                                            )
                                        },
                                        textStyle = LocalTextStyle.current.copy(
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = palette.textPrimary
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = if (isLight) Color.Black.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.05f),
                                            unfocusedContainerColor = if (isLight) Color.Black.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.05f),
                                            focusedBorderColor = accent,
                                            unfocusedBorderColor = palette.border,
                                            focusedTextColor = palette.textPrimary,
                                            unfocusedTextColor = palette.textPrimary,
                                            cursorColor = accent
                                        ),
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_settings_integrations),
                                                contentDescription = null,
                                                tint = accent,
                                                modifier = Modifier
                                                    .padding(start = 12.dp)
                                                    .size(22.dp)
                                            )
                                        },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { showStashDbKey = !showStashDbKey },
                                                modifier = Modifier.padding(end = 4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (showStashDbKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                    contentDescription = if (showStashDbKey) "Hide API Key" else "Show API Key",
                                                    tint = palette.textSecondary
                                                )
                                            }
                                        },
                                        visualTransformation = if (showStashDbKey) VisualTransformation.None else PasswordVisualTransformation(),
                                        singleLine = true,
                                        maxLines = 1,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(56.dp)
                                            .testTag("stashdb_api_key_input")
                                    )

                                    Text(
                                        text = "Get API key from stashdb.org profile",
                                        fontSize = 12.sp,
                                        color = palette.textSecondary,
                                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    SettingsSection.FILTER -> {
                        SettingsFilterSection(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .padding(bottom = 116.dp),
                            enableStudioFilter = currentSettings.enableStudioFilter,
                            onEnableStudioFilterChange = {
                                viewModel.updateSettings(currentSettings.copy(enableStudioFilter = it))
                            },
                            blockMalePerformers = currentSettings.blockMalePerformers,
                            onBlockMalePerformersChange = {
                                viewModel.updateSettings(currentSettings.copy(blockMalePerformers = it))
                            },
                            blockedStudioNames = currentSettings.blockedStudioNames,
                            onBlockStudio = { name -> viewModel.blockStudio(null, name) },
                            onUnblockStudio = { name -> viewModel.unblockStudio(name) },
                            onClearAll = { viewModel.clearAllBlockedStudios() }
                        )
                    }
                    SettingsSection.DATA_BACKUP -> {
                        DataBackupSection(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .padding(bottom = 116.dp),
                            onExportJson = { viewModel.exportDataJson() },
                            onImportJson = { jsonStr -> viewModel.importJsonData(jsonStr) }
                        )
                    }
                    SettingsSection.SAMPLE_DATA -> {
                        SettingsSampleDataSection(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = padding.calculateTopPadding())
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .padding(bottom = 116.dp),
                            sampleDataStatus = sampleDataStatus,
                            accentColor = accent,
                            onLoadSample = {
                                viewModel.importSampleDataset { count ->
                                    sampleDataStatus = "Loaded $count sample scenes successfully!"
                                }
                            },
                            onClearSample = {
                                viewModel.clearSampleDataset { count ->
                                    sampleDataStatus = "Cleared $count sample scenes!"
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
