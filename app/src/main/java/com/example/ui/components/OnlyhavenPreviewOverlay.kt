package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.network.OnlyhavenCreator
import com.example.network.OnlyhavenMediaItem
import com.example.ui.MainViewModel
import kotlinx.coroutines.launch

/**
 * Unified, gallery-grade edge-to-edge immersive preview overlay for Onlyhaven media items.
 * 100% reactive state management, soft smooth heart feedback, zIndex layer isolation,
 * and bulletproof carousel preservation on save.
 */
@Composable
fun OnlyhavenPreviewOverlay(
    parentPost: OnlyhavenMediaItem?,
    creator: OnlyhavenCreator,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    viewModel: MainViewModel
) {
    // 1. Reactive StateFlow subscription guarantees 100% immediate UI updates on save/unsave
    val savedPostKeys by viewModel.savedPostKeys.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = parentPost != null,
        enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing))
    ) {
        parentPost?.let { post ->
            val postAttachments = remember(post.id, post.sha256) {
                post.postAttachments.ifEmpty { listOf(post) }
            }
            val safeInitialPage = initialIndex.coerceIn(0, (postAttachments.size - 1).coerceAtLeast(0))
            val pagerState = rememberPagerState(
                initialPage = safeInitialPage,
                pageCount = { postAttachments.size }
            )

            val coroutineScope = rememberCoroutineScope()
            // Scoped zoom state keyed to post ID so closing/reopening never gets stuck
            val zoomedPagesState = remember(post.id) { mutableStateMapOf<Int, Boolean>() }
            val isCurrentPageZoomed = zoomedPagesState[pagerState.currentPage] == true

            var resetZoomTrigger by remember(post.id) { mutableIntStateOf(0) }
            var showControlsOverlay by remember(post.id) { mutableStateOf(true) }
            var isCaptionExpanded by remember(post.id) { mutableStateOf(false) }

            // Two-stage BackHandler: Reset zoom to 1x first, or dismiss if already 1x
            BackHandler(enabled = true) {
                if (isCurrentPageZoomed) {
                    resetZoomTrigger++
                } else {
                    onDismiss()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                // 1. Horizontal Media Pager
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !isCurrentPageZoomed,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val pageItem = postAttachments[page]
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (pageItem.kind == "video") {
                            Miniplayer(
                                videoUrl = pageItem.originalUrl,
                                title = pageItem.originalFilename.ifEmpty { "Onlyhaven Video" },
                                isActiveInPager = pagerState.currentPage == page,
                                onToggleControls = {
                                    showControlsOverlay = !showControlsOverlay
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            ZoomableImage(
                                model = pageItem.originalUrl,
                                contentDescription = "Original Quality Image",
                                resetKey = "${page}_${resetZoomTrigger}",
                                onZoomChanged = { zoomed ->
                                    zoomedPagesState[page] = zoomed
                                },
                                onTap = {
                                    showControlsOverlay = !showControlsOverlay
                                },
                                onNavigateNext = {
                                    if (pagerState.currentPage < postAttachments.size - 1) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                        }
                                    }
                                },
                                onNavigatePrevious = {
                                    if (pagerState.currentPage > 0) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // 2. Top Bar Controls with zIndex(10f) to guarantee clicks are never blocked by pager
                AnimatedVisibility(
                    visible = showControlsOverlay,
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(140)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(10f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.75f),
                                        Color.Black.copy(alpha = 0.40f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        // Back Button
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(48.dp)
                                .testTag("preview_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        // Right Action Section: Heart Save Button + Carousel Page Counter
                        val currentAttachment = postAttachments.getOrNull(pagerState.currentPage) ?: post
                        val isSaved = remember(savedPostKeys, post, currentAttachment, creator.service) {
                            val pid = post.postId.ifEmpty { post.id }
                            val curPid = currentAttachment.postId.ifEmpty { currentAttachment.id }
                            val service = creator.service
                            val k1 = if (service.isNotEmpty()) "${service}_$pid" else pid
                            val k2 = if (service.isNotEmpty()) "${service}_$curPid" else curPid
                            val k3 = if (service.isNotEmpty()) "${service}_${post.id}" else post.id
                            val k4 = if (service.isNotEmpty()) "${service}_${currentAttachment.id}" else currentAttachment.id

                            savedPostKeys.contains(k1) || savedPostKeys.contains(k2) ||
                            savedPostKeys.contains(k3) || savedPostKeys.contains(k4) ||
                            savedPostKeys.contains(pid) || savedPostKeys.contains(curPid) ||
                            savedPostKeys.contains(post.id) || savedPostKeys.contains(currentAttachment.id) ||
                            viewModel.isPostSaved(post, service) ||
                            viewModel.isPostSaved(currentAttachment, service)
                        }

                        var isPopAnimating by remember { mutableStateOf(false) }
                        val heartScale by animateFloatAsState(
                            targetValue = if (isPopAnimating) 1.14f else 1f,
                            animationSpec = tween(
                                durationMillis = 150,
                                easing = FastOutSlowInEasing
                            ),
                            label = "preview_heart_soft_pulse",
                            finishedListener = { isPopAnimating = false }
                        )

                        val animatedHeartColor by animateColorAsState(
                            targetValue = if (isSaved) Color(0xFFFF3B5C) else Color.White,
                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                            label = "preview_heart_color"
                        )

                        Row(
                            modifier = Modifier.align(Alignment.CenterEnd),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Touch-isolated high-priority Save/Unsave Button
                            IconButton(
                                onClick = {
                                    isPopAnimating = true
                                    viewModel.toggleSaveOnlyhavenPost(
                                        mediaItem = post.copy(postAttachments = postAttachments),
                                        creator = creator
                                    )
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .graphicsLayer {
                                        scaleX = heartScale
                                        scaleY = heartScale
                                    }
                                    .testTag("preview_save_button")
                            ) {
                                Icon(
                                    painter = painterResource(
                                        id = if (isSaved) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                                    ),
                                    contentDescription = if (isSaved) "Unsave Post" else "Save Post",
                                    tint = animatedHeartColor,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            if (postAttachments.size > 1) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(16.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                                ) {
                                    Text(
                                        text = "${pagerState.currentPage + 1} / ${postAttachments.size}",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Layer-free, Shadowed Description at Bottom (fades out smoothly on zoom)
                val caption = post.postCaption?.trim()
                if (!caption.isNullOrBlank()) {
                    val textShadow = remember {
                        Shadow(
                            color = Color.Black.copy(alpha = 0.85f),
                            offset = Offset(0f, 1.5f),
                            blurRadius = 5f
                        )
                    }

                    var cutIndex by remember(caption) { mutableIntStateOf(-1) }

                    AnimatedVisibility(
                        visible = showControlsOverlay && !isCurrentPageZoomed,
                        enter = fadeIn(tween(200, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(180, easing = FastOutSlowInEasing)),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .zIndex(10f)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 22.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateContentSize(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = cutIndex > 0 || isCaptionExpanded
                                ) {
                                    isCaptionExpanded = !isCaptionExpanded
                                }
                                .testTag("preview_description_box")
                        ) {
                            val displayAnnotatedText = remember(caption, isCaptionExpanded, cutIndex) {
                                if (!isCaptionExpanded && cutIndex > 0 && cutIndex < caption.length) {
                                    val visibleSubstring = caption.substring(0, cutIndex).trimEnd()
                                    buildAnnotatedString {
                                        withStyle(
                                            SpanStyle(
                                                color = Color.White,
                                                fontWeight = FontWeight.Medium,
                                                shadow = textShadow
                                            )
                                        ) {
                                            append(visibleSubstring)
                                            append("... ")
                                        }
                                        withStyle(
                                            SpanStyle(
                                                color = Color.White.copy(alpha = 0.78f),
                                                fontWeight = FontWeight.Bold,
                                                shadow = textShadow
                                            )
                                        ) {
                                            append("See More...")
                                        }
                                    }
                                } else {
                                    buildAnnotatedString {
                                        withStyle(
                                            SpanStyle(
                                                color = Color.White,
                                                fontWeight = FontWeight.Medium,
                                                shadow = textShadow
                                            )
                                        ) {
                                            append(caption)
                                        }
                                    }
                                }
                            }

                            Text(
                                text = displayAnnotatedText,
                                style = TextStyle(
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    shadow = textShadow
                                ),
                                maxLines = if (isCaptionExpanded) Int.MAX_VALUE else 2,
                                overflow = if (isCaptionExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                                onTextLayout = { layoutResult ->
                                    if (!isCaptionExpanded && cutIndex == -1 && layoutResult.hasVisualOverflow && layoutResult.lineCount >= 2) {
                                        val lineStart = layoutResult.getLineStart(1)
                                        val lineEnd = layoutResult.getLineEnd(1, visibleEnd = true)
                                        val suffixReserveChars = 15 // Room for "... See More..."
                                        val safeCut = (lineEnd - suffixReserveChars).coerceAtLeast(lineStart + 1)
                                        if (safeCut < caption.length) {
                                            cutIndex = safeCut
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
