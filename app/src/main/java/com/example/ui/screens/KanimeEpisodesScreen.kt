package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.network.KanimeAnimeDetails
import com.example.network.KanimeEpisodeDetails
import com.example.network.KanimeEpisodeItem
import com.example.network.KstreamMediaItem
import com.example.network.kstreamScraper
import com.example.ui.MainViewModel
import com.example.ui.components.ShimmerBrush
import com.example.ui.components.vaultTopGlow
import com.example.ui.theme.LocalAccentColor
import com.example.ui.theme.LocalBetaTestPrivacy
import com.example.ui.theme.LocalVaultPalette
import com.example.ui.theme.privacyImageBlur
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanimeEpisodesScreen(
    viewModel: MainViewModel,
    item: KstreamMediaItem,
    modifier: Modifier = Modifier
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current
    val context = LocalContext.current
    val isBetaTest = LocalBetaTestPrivacy.current

    var animeDetails by remember(item.id) { mutableStateOf<KanimeAnimeDetails?>(null) }
    var isAnimeLoading by remember(item.id) { mutableStateOf(true) }
    var selectedEpisodeUrl by remember(item.id) { mutableStateOf(item.episodeUrl) }

    // Map caching loaded details for each episode
    var episodeDetailsMap by remember(item.id) { mutableStateOf<Map<String, KanimeEpisodeDetails>>(emptyMap()) }
    var loadingEpisodeUrl by remember(item.id) { mutableStateOf<String?>(null) }

    // State for description expansion and overflow detection
    var isDescriptionExpanded by remember(item.id, selectedEpisodeUrl) { mutableStateOf(false) }
    var isDescriptionOverflowing by remember(item.id, selectedEpisodeUrl) { mutableStateOf(false) }

    // 1. Initial Load: Fetch overall anime details and initial episode
    LaunchedEffect(item.id) {
        isAnimeLoading = true
        try {
            val result = kstreamScraper.getAnimeDetails(item)
            animeDetails = result

            // Cache the initial episode details if scraped
            if (result.defaultEpisodeDetails != null) {
                episodeDetailsMap = episodeDetailsMap +
                    (result.defaultEpisodeDetails.episodeUrl to result.defaultEpisodeDetails) +
                    (item.episodeUrl to result.defaultEpisodeDetails)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.e("KanimeEpisodes", "Failed to load anime details: ${e.message}")
        } finally {
            isAnimeLoading = false
        }
    }

    // 2. Load Episode-Specific Details when selectedEpisodeUrl changes (after initial load settles)
    LaunchedEffect(selectedEpisodeUrl, isAnimeLoading) {
        if (isAnimeLoading && selectedEpisodeUrl == item.episodeUrl) return@LaunchedEffect
        if (!episodeDetailsMap.containsKey(selectedEpisodeUrl)) {
            loadingEpisodeUrl = selectedEpisodeUrl
            try {
                val epDetails = kstreamScraper.getEpisodeDetails(
                    episodeUrl = selectedEpisodeUrl,
                    fallbackTitle = item.title,
                    fallbackPoster = item.posterUrl
                )
                episodeDetailsMap = episodeDetailsMap + (selectedEpisodeUrl to epDetails)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("KanimeEpisodes", "Failed to load episode details: ${e.message}")
            } finally {
                if (loadingEpisodeUrl == selectedEpisodeUrl) {
                    loadingEpisodeUrl = null
                }
            }
        }
    }

    // Current active episode details
    val currentEpisode = episodeDetailsMap[selectedEpisodeUrl]

    // Title: Displays episode numbering as on the website
    val displayTitle = currentEpisode?.title?.ifEmpty { null }
        ?: item.title

    // Poster of current episode
    val displayPoster = currentEpisode?.posterUrl?.ifEmpty { null }
        ?: animeDetails?.posterUrl
        ?: item.posterUrl

    // Date & Censorship (fall back to title hint while loading to prevent red/green badge flash)
    val displayDate = currentEpisode?.date?.ifEmpty { null } ?: "Recently"
    val isUncensored = currentEpisode?.isUncensored
        ?: displayTitle.contains("uncensored", ignoreCase = true)

    // Description
    val displayDescription = currentEpisode?.description?.ifEmpty { null }
        ?: animeDetails?.description?.ifEmpty { null }
        ?: "No description available."

    // Gallery
    val galleryImages = currentEpisode?.gallery.orEmpty()

    // List of all episodes
    val episodeList = animeDetails?.episodes.orEmpty().ifEmpty {
        val epNum = kstreamScraper.extractEpNumber(item.episodeUrl)
        listOf(
            KanimeEpisodeItem(
                episodeNumber = epNum,
                title = "Episode $epNum",
                episodeUrl = item.episodeUrl
            )
        )
    }

    BackHandler {
        viewModel.navigateBack()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("kanime_episodes_screen"),
        containerColor = palette.bg,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Episodes",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = palette.textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.navigateBack() },
                        modifier = Modifier.testTag("kanime_episodes_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = palette.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.bg,
                    titleContentColor = palette.textPrimary
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Top Section: Episode Poster on the left, Title with Episode Number and Badges in front
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Poster image (Aspect ratio 2:3) with smooth fade animation when poster URL actually changes
                    Box(
                        modifier = Modifier
                            .width(115.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.cardBg)
                            .vaultTopGlow(cornerRadius = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = displayPoster,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)) togetherWith
                                    fadeOut(animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing))
                            },
                            modifier = Modifier.fillMaxSize(),
                            label = "ep_poster_fade_transition"
                        ) { currentPosterUrl ->
                            var isImageLoaded by remember(currentPosterUrl) { mutableStateOf(false) }
                            var isImageError by remember(currentPosterUrl) { mutableStateOf(false) }

                            val imageAlpha by animateFloatAsState(
                                targetValue = if (isImageLoaded && !isImageError) 1f else 0f,
                                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                                label = "ep_poster_alpha"
                            )

                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!isImageLoaded && !isImageError) {
                                    val shimmerBrush = ShimmerBrush(targetValue = 900f)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(palette.cardBg)
                                            .background(shimmerBrush)
                                    )
                                }

                                if (!isImageError) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(currentPosterUrl)
                                            .crossfade(false)
                                            .build(),
                                        contentDescription = displayTitle,
                                        contentScale = ContentScale.Crop,
                                        onSuccess = {
                                            isImageLoaded = true
                                            isImageError = false
                                        },
                                        onError = {
                                            isImageLoaded = true
                                            isImageError = true
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                alpha = imageAlpha
                                            }
                                            .privacyImageBlur(isBetaTest)
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_nav_home),
                                        contentDescription = "Image Error",
                                        tint = palette.textSecondary.copy(alpha = 0.35f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }

                    // In front: Episode title with number, date and censorship badges
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Title with episode numbering as on the website
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                lineHeight = 23.sp
                            ),
                            color = palette.textPrimary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Badges under title: Date and Censored/Uncensored (small & circular)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Date Badge (Small & Circular/Pill)
                            Surface(
                                color = palette.cardBg,
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = palette.textSecondary.copy(alpha = 0.15f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_app_calendar),
                                        contentDescription = null,
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = displayDate,
                                        color = palette.textSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            // Censorship Badge (Small & Circular/Pill, without dot)
                            val badgeBg = if (isUncensored) Color(0x284CAF50) else Color(0x28EF5350)
                            val badgeTextColor = if (isUncensored) Color(0xFF66BB6A) else Color(0xFFEF5350)
                            val badgeLabel = if (isUncensored) "Uncensored" else "Censored"

                            Surface(
                                color = badgeBg,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = badgeLabel,
                                    color = badgeTextColor,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Description Card: 3 lines max by default, clickable to expand/collapse when overflowing
            item {
                val canExpand = isDescriptionOverflowing || isDescriptionExpanded
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .vaultTopGlow(cornerRadius = 14.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = canExpand
                        ) { isDescriptionExpanded = !isDescriptionExpanded }
                        .testTag("kanime_desc_card"),
                    shape = RoundedCornerShape(14.dp),
                    color = palette.cardBg
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                            .animateContentSize(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            ),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Description",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                ),
                                color = accent
                            )

                            if (canExpand) {
                                Icon(
                                    imageVector = if (isDescriptionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isDescriptionExpanded) "Collapse" else "Expand",
                                    tint = palette.textSecondary.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        if (isAnimeLoading && animeDetails == null && currentEpisode == null) {
                            val shimmerBrush = ShimmerBrush(targetValue = 900f)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.96f)
                                        .height(13.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(palette.cardBg)
                                        .background(shimmerBrush)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.88f)
                                        .height(13.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(palette.cardBg)
                                        .background(shimmerBrush)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.58f)
                                        .height(13.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(palette.cardBg)
                                        .background(shimmerBrush)
                                )
                            }
                        } else {
                            val descStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp,
                                lineHeight = 20.sp
                            )
                            val textMeasurer = rememberTextMeasurer()
                            val bodyColor = palette.textPrimary.copy(alpha = 0.88f)
                            val seeMoreSuffix = " See More..."

                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val maxWidthPx = constraints.maxWidth
                                val (collapsedAnnotatedText, overflowsThreeLines) = remember(
                                    displayDescription,
                                    maxWidthPx,
                                    descStyle,
                                    bodyColor,
                                    accent
                                ) {
                                    if (maxWidthPx <= 0 || displayDescription.isBlank()) {
                                        AnnotatedString(displayDescription) to false
                                    } else {
                                        val fullLayout = textMeasurer.measure(
                                            text = AnnotatedString(displayDescription),
                                            style = descStyle,
                                            constraints = Constraints(maxWidth = maxWidthPx)
                                        )
                                        if (fullLayout.lineCount <= 3) {
                                            buildAnnotatedString {
                                                withStyle(SpanStyle(color = bodyColor)) {
                                                    append(displayDescription)
                                                }
                                            } to false
                                        } else {
                                            val line3End = fullLayout.getLineEnd(2, visibleEnd = true)
                                                .coerceAtMost(displayDescription.length)
                                            var low = 0
                                            var high = line3End
                                            var bestCut = (line3End - seeMoreSuffix.length).coerceAtLeast(0)

                                            while (low <= high) {
                                                val mid = (low + high) ushr 1
                                                val candidatePrefix = displayDescription.substring(0, mid)
                                                    .trimEnd(' ', '\t', '\n', '.', ',', ';', ':', '-', '—')
                                                val candidateAnnotated = buildAnnotatedString {
                                                    withStyle(SpanStyle(color = bodyColor)) {
                                                        append(candidatePrefix)
                                                    }
                                                    withStyle(
                                                        SpanStyle(
                                                            color = accent,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    ) {
                                                        append(seeMoreSuffix)
                                                    }
                                                }
                                                val candidateLayout = textMeasurer.measure(
                                                    text = candidateAnnotated,
                                                    style = descStyle,
                                                    constraints = Constraints(maxWidth = maxWidthPx)
                                                )
                                                if (candidateLayout.lineCount <= 3) {
                                                    bestCut = mid
                                                    low = mid + 1
                                                } else {
                                                    high = mid - 1
                                                }
                                            }

                                            // Snap back to word boundary if a space is nearby so words aren't cut mid-character
                                            val rawCutPrefix = displayDescription.substring(0, bestCut)
                                            val lastSpaceIdx = rawCutPrefix.lastIndexOf(' ')
                                            val cleanPrefix = if (lastSpaceIdx > bestCut - 10 && lastSpaceIdx > 0) {
                                                rawCutPrefix.substring(0, lastSpaceIdx)
                                            } else {
                                                rawCutPrefix
                                            }.trimEnd(' ', '\t', '\n', '.', ',', ';', ':', '-', '—')

                                            val annotated = buildAnnotatedString {
                                                withStyle(SpanStyle(color = bodyColor)) {
                                                    append(cleanPrefix)
                                                }
                                                withStyle(
                                                    SpanStyle(
                                                        color = accent,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                ) {
                                                    append(seeMoreSuffix)
                                                }
                                            }
                                            annotated to true
                                        }
                                    }
                                }

                                SideEffect {
                                    if (isDescriptionOverflowing != overflowsThreeLines) {
                                        isDescriptionOverflowing = overflowsThreeLines
                                    }
                                }

                                if (isDescriptionExpanded || !overflowsThreeLines) {
                                    Text(
                                        text = displayDescription,
                                        style = descStyle,
                                        color = bodyColor
                                    )
                                } else {
                                    Text(
                                        text = collapsedAnnotatedText,
                                        style = descStyle,
                                        maxLines = 3
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Gallery Section: Header outside the card, matching Episodes header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Gallery",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        ),
                        color = palette.textPrimary
                    )
                }
            }

            // 16:9 Aspect Ratio Card entirely displaying only shots with fade transition on episode change
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .vaultTopGlow(cornerRadius = 14.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .testTag("kanime_gallery_card"),
                    shape = RoundedCornerShape(14.dp),
                    color = palette.cardBg
                ) {
                    AnimatedContent(
                        targetState = selectedEpisodeUrl,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing))
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "kanime_gallery_fade_transition"
                    ) { targetEpUrl ->
                        val targetEpDetails = episodeDetailsMap[targetEpUrl]
                        val shots = targetEpDetails?.gallery.orEmpty()
                        val isTargetLoading = (isAnimeLoading && targetEpDetails == null) || loadingEpisodeUrl == targetEpUrl

                        if (isTargetLoading && shots.isEmpty()) {
                            val shimmerBrush = ShimmerBrush(targetValue = 900f)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(palette.cardBg)
                                    .background(shimmerBrush)
                            )
                        } else if (shots.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No shots available",
                                    color = palette.textSecondary.copy(alpha = 0.7f),
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            val pagerState = rememberPagerState(pageCount = { shots.size })

                            // Smart smooth scrolling: automatically advances smoothly when idle, pauses on user drag
                            LaunchedEffect(shots, pagerState) {
                                if (shots.size > 1) {
                                    while (true) {
                                        delay(3500)
                                        if (!pagerState.isScrollInProgress) {
                                            val nextPage = (pagerState.currentPage + 1) % shots.size
                                            pagerState.animateScrollToPage(
                                                page = nextPage,
                                                animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalPager(
                                state = pagerState,
                                beyondViewportPageCount = 1,
                                flingBehavior = PagerDefaults.flingBehavior(state = pagerState),
                                modifier = Modifier.fillMaxSize()
                            ) { pageIndex ->
                                val imgUrl = shots[pageIndex]
                                var isImageLoaded by remember(imgUrl) { mutableStateOf(false) }
                                var isImageError by remember(imgUrl) { mutableStateOf(false) }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("kanime_gallery_item_$pageIndex"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!isImageLoaded && !isImageError) {
                                        val shimmerBrush = ShimmerBrush(targetValue = 900f)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(palette.cardBg)
                                                .background(shimmerBrush)
                                        )
                                    }

                                    if (!isImageError) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(imgUrl)
                                                .crossfade(260)
                                                .build(),
                                            contentDescription = "Gallery shot ${pageIndex + 1}",
                                            contentScale = ContentScale.Crop,
                                            onSuccess = {
                                                isImageLoaded = true
                                                isImageError = false
                                            },
                                            onError = {
                                                isImageLoaded = true
                                                isImageError = true
                                            },
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .privacyImageBlur(isBetaTest)
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_nav_home),
                                            contentDescription = "Gallery Image Error",
                                            tint = palette.textSecondary.copy(alpha = 0.35f),
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Episodes Section: Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Episodes",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        ),
                        color = palette.textPrimary
                    )

                    Text(
                        text = "${episodeList.size} Available",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = palette.textSecondary
                    )
                }
            }

            // Horizontal Circular Episodes List
            item {
                if (isAnimeLoading && animeDetails == null) {
                    val shimmerBrush = ShimmerBrush(targetValue = 900f)
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(7) {
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(palette.cardBg)
                                    .background(shimmerBrush)
                            )
                        }
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(episodeList, key = { it.episodeUrl }) { ep ->
                            val isCurrent = ep.episodeUrl == selectedEpisodeUrl

                            KanimeCircleEpisodeItem(
                                episode = ep,
                                isSelected = isCurrent,
                                onSelect = {
                                    if (isCurrent) {
                                        viewModel.playKanimeEpisode(ep)
                                    } else {
                                        selectedEpisodeUrl = ep.episodeUrl
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Circular episode item displaying ONLY the episode number:
 * 1, 2, 3, ...
 * with smooth selection highlight, vault glow, and tap-to-select affordance.
 */
@Composable
private fun KanimeCircleEpisodeItem(
    episode: KanimeEpisodeItem,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val palette = LocalVaultPalette.current
    val accent = LocalAccentColor.current

    Surface(
        modifier = Modifier
            .size(50.dp)
            .vaultTopGlow(cornerRadius = 25.dp)
            .clip(CircleShape)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) accent else palette.textSecondary.copy(alpha = 0.18f),
                shape = CircleShape
            )
            .clickable(onClick = onSelect)
            .testTag("kanime_episode_item_${episode.episodeNumber}"),
        shape = CircleShape,
        color = if (isSelected) accent else palette.cardBg,
        tonalElevation = if (isSelected) 6.dp else 1.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${episode.episodeNumber}",
                color = if (isSelected) Color.White else palette.textPrimary,
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                textAlign = TextAlign.Center,
                style = LocalTextStyle.current.copy(
                    textAlign = TextAlign.Center,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )
            )
        }
    }
}
