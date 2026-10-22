package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.entity.*
import com.example.data.repository.VaultRepository
import com.example.network.*
import com.example.ui.player.SharedPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SortMode {
    CARD_NEWEST,      // New (Card Date - Newest)
    CARD_OLDEST,      // Old (Card Date - Oldest)
    RECENTLY_ADDED,   // Recently Added (Added to app date - Newest)
    OLDEST_ADDED,     // Oldest Added (Added to app date - Oldest)
    NEWEST,           // Backward compatibility (maps to CARD_NEWEST)
    OLDEST,           // Backward compatibility (maps to CARD_OLDEST)
    TITLE_AZ,
    TITLE_ZA
}

enum class StashSearchType {
    ACTORS,
    STUDIO,
    SEXMEX
}

enum class SettingsSection {
    MAIN_MENU,
    DISPLAY,
    PRIVACY,
    INTEGRATIONS,
    FILTER,
    DATA_BACKUP,
    SAMPLE_DATA
}

sealed class ScreenState {
    object Home : ScreenState()
    object Bookmarks : ScreenState()
    data class AddEditLink(val linkId: String? = null) : ScreenState()
    object Actors : ScreenState()
    data class ActorScenes(val actorId: String) : ScreenState()
    object Studios : ScreenState()
    data class StudioScenes(val studioId: String) : ScreenState()
    object StashDb : ScreenState()
    object Onlyhaven : ScreenState()
    data class OnlyhavenCreatorDetails(val creator: com.example.network.OnlyhavenCreator) : ScreenState()
    object OnlyhavenSavedCreators : ScreenState()
    object Settings : ScreenState()
    object Kanime : ScreenState()
    data class KanimeEpisodes(val item: com.example.network.KstreamMediaItem) : ScreenState()
}

data class ActiveVideoPlayback(
    val title: String,
    val qualities: List<StreamQuality>,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val initialPositionMs: Long = 0L,
    val startInLandscape: Boolean = false,
    val startInVrMode: Boolean = false
)

data class ActiveInlineVideoPlayback(
    val cardId: String,
    val title: String,
    val qualities: List<StreamQuality>,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val initialPositionMs: Long = 0L
)

private data class StashTabCache(
    val query: String = "",
    val performerResults: List<StashPerformer> = emptyList(),
    val studioResults: List<StashStudio> = emptyList(),
    val scenes: List<StashScene> = emptyList(),
    val rawScenes: List<StashScene> = emptyList(),
    val selectedPerformer: StashPerformer? = null,
    val selectedStudio: StashStudio? = null,
    val totalCount: Int = 0,
    val currentPage: Int = 1,
    val canLoadMore: Boolean = false,
    val sortMode: StashSceneSortMode = StashSceneSortMode.RELEASE_DATE,
    val sexMexNextPageUrl: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: VaultRepository

    // App Initial Resource Loading Screen States
    private val _isAppResourcesLoading = MutableStateFlow(true)
    val isAppResourcesLoading: StateFlow<Boolean> = _isAppResourcesLoading.asStateFlow()

    private val _resourceLoadingStatus = MutableStateFlow("Checking and preparing resources...")
    val resourceLoadingStatus: StateFlow<String> = _resourceLoadingStatus.asStateFlow()

    fun triggerReloadResources() {
        seedInitialDataIfEmpty()
    }

    // Saved Onlyhaven Creators
    private val _savedOnlyhavenCreators = MutableStateFlow<List<OnlyhavenCreator>>(emptyList())
    val savedOnlyhavenCreators: StateFlow<List<OnlyhavenCreator>> = _savedOnlyhavenCreators.asStateFlow()

    private val _savedCreatorKeys = MutableStateFlow<Set<String>>(emptySet())
    val savedCreatorKeys: StateFlow<Set<String>> = _savedCreatorKeys.asStateFlow()

    // Saved Onlyhaven Posts
    private val _savedOnlyhavenPosts = MutableStateFlow<List<OnlyhavenSavedPost>>(emptyList())
    val savedOnlyhavenPosts: StateFlow<List<OnlyhavenSavedPost>> = _savedOnlyhavenPosts.asStateFlow()

    private val _savedPostKeys = MutableStateFlow<Set<String>>(emptySet())
    val savedPostKeys: StateFlow<Set<String>> = _savedPostKeys.asStateFlow()

    init {
        val db = AppDatabase.getInstance(application)
        repository = VaultRepository(db)
        seedInitialDataIfEmpty()
        loadSavedOnlyhavenCreators()
        loadSavedOnlyhavenPosts()
    }

    private fun seedInitialDataIfEmpty() {
        viewModelScope.launch(Dispatchers.IO) {
            _isAppResourcesLoading.value = true
            _resourceLoadingStatus.value = "Initializing local database..."
            delay(450L)

            val existingLinks = repository.allLinks.first()
            _resourceLoadingStatus.value = "Verifying links, scenes, and resources..."

            // Clean obsolete test & demo scenes
            val demoLinksToDelete = existingLinks.filter {
                it.id.startsWith("demo_") || it.id.startsWith("test_scene_") || it.id.startsWith("scene_raissa_")
            }
            demoLinksToDelete.forEach { repository.deleteLinkById(it.id) }

            // Seed default API Keys ONLY ONCE on first install using defaultKeysSeeded flag
            val currentSett = repository.settings.first() ?: SettingsEntity()
            if (!currentSett.defaultKeysSeeded) {
                var updatedSett = currentSett.copy(defaultKeysSeeded = true)
                if (updatedSett.realDebridApiKey.isBlank()) {
                    updatedSett = updatedSett.copy(realDebridApiKey = com.example.data.TestDefaults.RD_KEY)
                }
                if (updatedSett.stashDbApiKey.isBlank()) {
                    updatedSett = updatedSett.copy(stashDbApiKey = com.example.data.TestDefaults.STASHDB_KEY)
                }
                repository.updateSettings(updatedSett)
            }

            // Insert sample test dataset scenes, actors, and studios atomically if empty
            val existingLinkIds = existingLinks.map { it.id }.toSet()
            val existingActors = repository.allActors.first()
            if (existingActors.isEmpty()) {
                repository.insertActors(com.example.data.util.SampleTestDataset.sampleActors)
            }
            val existingStudios = repository.allStudios.first()
            if (existingStudios.isEmpty()) {
                repository.insertStudios(com.example.data.util.SampleTestDataset.sampleStudios)
            }
            val missingSampleScenes = com.example.data.util.SampleTestDataset.sampleScenes.filter { it.id !in existingLinkIds }
            if (missingSampleScenes.isNotEmpty()) {
                repository.insertLinks(missingSampleScenes)
            }

            // 1. Background ExoPlayer & MediaCodec Pre-Warming
            launch {
                try {
                    com.example.ui.components.PlayerFactory.prewarmPlayerPipeline(getApplication())
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }

            // 2. Silent Debrid Handshake & TLS Connection Pre-Warming (Real-Debrid & TorBox)
            launch {
                try {
                    val activeSett = repository.settings.first() ?: currentSett
                    val rdKey = activeSett.realDebridApiKey.trim()
                    if (rdKey.isNotEmpty()) {
                        val pingReq = okhttp3.Request.Builder()
                            .url("https://api.real-debrid.com/rest/1.0/user")
                            .header("Authorization", "Bearer $rdKey")
                            .build()
                        com.example.network.NetworkClient.apiClient.newCall(pingReq).enqueue(object : okhttp3.Callback {
                            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                response.close()
                            }
                        })
                    }
                    val tbKey = activeSett.torboxApiKey.trim()
                    if (tbKey.isNotEmpty()) {
                        val tbPingReq = okhttp3.Request.Builder()
                            .url("https://api.torbox.app/v1/api/user/me")
                            .header("Authorization", "Bearer $tbKey")
                            .build()
                        com.example.network.NetworkClient.apiClient.newCall(tbPingReq).enqueue(object : okhttp3.Callback {
                            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                response.close()
                            }
                        })
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }

            // 3. Priority Bitmap Pre-Decoding into Coil Memory Cache (Reusing in-memory instances)
            try {
                val context = getApplication<Application>()
                val imageLoader = coil.Coil.imageLoader(context)
                val allLinksSample = if (missingSampleScenes.isNotEmpty()) existingLinks + missingSampleScenes else existingLinks
                val allActorsSample = if (existingActors.isEmpty()) com.example.data.util.SampleTestDataset.sampleActors else existingActors
                val allStudiosSample = if (existingStudios.isEmpty()) com.example.data.util.SampleTestDataset.sampleStudios else existingStudios

                val urlsToCache = mutableListOf<String>()
                allLinksSample.take(20).forEach { link ->
                    if (link.coverImage.isNotBlank()) urlsToCache.add(link.coverImage)
                    if (link.galleryUrls.isNotEmpty()) urlsToCache.addAll(link.galleryUrls.take(2))
                }
                allActorsSample.take(15).forEach { actor ->
                    if (actor.imageUrl.isNotBlank()) urlsToCache.add(actor.imageUrl)
                }
                allStudiosSample.take(15).forEach { studio ->
                    studio.logoUrl?.takeIf { it.isNotBlank() }?.let { urlsToCache.add(it) }
                    studio.imageUrl?.takeIf { it.isNotBlank() }?.let { urlsToCache.add(it) }
                }

                urlsToCache.distinct().forEach { url ->
                    val request = coil.request.ImageRequest.Builder(context)
                        .data(url)
                        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .build()
                    imageLoader.enqueue(request)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }

            // 4. Silent Orphaned Cache Cleanup
            launch {
                try {
                    val context = getApplication<Application>()
                    val cacheDir = context.cacheDir
                    val now = System.currentTimeMillis()
                    cacheDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.name.startsWith("temp_") && (now - file.lastModified() > 24 * 3600 * 1000L)) {
                            file.delete()
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }

            // 5. Security Gate: Verify passcode status BEFORE dismissing loading screen to prevent any UI leakage
            try {
                val latestSettings = repository.settings.first() ?: SettingsEntity()
                if (latestSettings.isPasscodeEnabled && latestSettings.passcodeHash.isNotBlank()) {
                    if (!_hasUnlockedThisSession) {
                        _isAppLocked.value = true
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }

            delay(150L)
            _isAppResourcesLoading.value = false
        }
    }

    // Navigation Stack / Current Screen & Direction
    enum class NavigationDirection { FORWARD, BACK }

    private val _navDirection = MutableStateFlow(NavigationDirection.FORWARD)
    val navDirection: StateFlow<NavigationDirection> = _navDirection.asStateFlow()

    private val _screenState = MutableStateFlow<ScreenState>(ScreenState.Home)
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

    private val screenStack = Collections.synchronizedList(mutableListOf<ScreenState>(ScreenState.Home))

    // Feed / Home Screen Scroll to Top Trigger (e.g., when active Home tab is reselected)
    private val _scrollToTopTrigger = MutableStateFlow(0)
    val scrollToTopTrigger: StateFlow<Int> = _scrollToTopTrigger.asStateFlow()

    fun triggerScrollToTop() {
        _scrollToTopTrigger.value++
    }

    // App Lock State (PIN Security)
    private val _isAppLocked = MutableStateFlow(false)
    val isAppLocked: StateFlow<Boolean> = _isAppLocked.asStateFlow()

    private var _hasUnlockedThisSession = false

    fun unlockApp(enteredPin: String): Boolean {
        val currentSettings = settings.value ?: return false
        if (!currentSettings.isPasscodeEnabled || currentSettings.passcodeHash.isBlank()) {
            _isAppLocked.value = false
            _hasUnlockedThisSession = true
            return true
        }
        val isValid = com.example.util.PasscodeManager.verifyPasscode(enteredPin, currentSettings.passcodeHash)
        if (isValid) {
            _hasUnlockedThisSession = true
            _isAppLocked.value = false
        }
        return isValid
    }

    fun lockApp() {
        val currentSettings = settings.value ?: return
        if (currentSettings.isPasscodeEnabled && currentSettings.passcodeHash.isNotBlank()) {
            _hasUnlockedThisSession = false
            _isAppLocked.value = true
        }
    }

    fun enablePasscode(newPin: String) {
        val hash = com.example.util.PasscodeManager.hashPasscode(newPin)
        val current = settings.value ?: SettingsEntity()
        updateSettings(current.copy(isPasscodeEnabled = true, passcodeHash = hash))
        _hasUnlockedThisSession = true
        _isAppLocked.value = false
    }

    fun disablePasscode() {
        val current = settings.value ?: SettingsEntity()
        updateSettings(current.copy(isPasscodeEnabled = false, passcodeHash = ""))
        _hasUnlockedThisSession = true
        _isAppLocked.value = false
    }

    fun changePasscode(newPin: String) {
        val hash = com.example.util.PasscodeManager.hashPasscode(newPin)
        val current = settings.value ?: SettingsEntity()
        updateSettings(current.copy(isPasscodeEnabled = true, passcodeHash = hash))
    }

    // Multi-Key Scroll Position Memory Registry
    private val scrollPositionRegistry = ConcurrentHashMap<String, Pair<Int, Int>>()

    fun saveScrollPosition(key: String, index: Int, offset: Int) {
        scrollPositionRegistry[key] = Pair(index, offset)
    }

    fun getScrollPosition(key: String): Pair<Int, Int> = scrollPositionRegistry[key] ?: Pair(0, 0)

    var initialSettingsSection: String? = null

    private fun onScreenChanged(previous: ScreenState, next: ScreenState) {
        if (previous != next) {
            closeInlineVideo()
            val isSameFeedContext = (previous is ScreenState.AddEditLink || next is ScreenState.AddEditLink)
            if (!isSameFeedContext && searchQuery.value.isNotEmpty()) {
                searchQuery.value = ""
            }
        }
    }

    fun navigateTo(screen: ScreenState) {
        val previous = _screenState.value
        if (screen == previous) return
        onScreenChanged(previous, screen)
        synchronized(screenStack) {
            val existingIndex = screenStack.indexOf(screen)
            if (existingIndex >= 0 && existingIndex < screenStack.size - 1) {
                _navDirection.value = NavigationDirection.BACK
                while (screenStack.size > existingIndex + 1) {
                    screenStack.removeAt(screenStack.size - 1)
                }
            } else {
                _navDirection.value = NavigationDirection.FORWARD
                screenStack.add(screen)
            }
        }
        _screenState.value = screen
    }

    fun navigateBack(): Boolean {
        val previous = _screenState.value
        synchronized(screenStack) {
            if (screenStack.size > 1) {
                _navDirection.value = NavigationDirection.BACK
                screenStack.removeAt(screenStack.size - 1)
                val target = screenStack.last()
                onScreenChanged(previous, target)
                _screenState.value = target
                return true
            } else if (previous != ScreenState.Home) {
                _navDirection.value = NavigationDirection.BACK
                screenStack.clear()
                screenStack.add(ScreenState.Home)
                onScreenChanged(previous, ScreenState.Home)
                _screenState.value = ScreenState.Home
                return true
            }
        }
        return false
    }

    // Video Player Overlay & Inline State
    private val _activeVideo = MutableStateFlow<ActiveVideoPlayback?>(null)
    val activeVideo: StateFlow<ActiveVideoPlayback?> = _activeVideo.asStateFlow()

    private val _activeInlineVideo = MutableStateFlow<ActiveInlineVideoPlayback?>(null)
    val activeInlineVideo: StateFlow<ActiveInlineVideoPlayback?> = _activeInlineVideo.asStateFlow()

    val sharedPlayerManager by lazy { SharedPlayerManager(application) }
    private var lastInlineCardId: String? = null

    private val _resolvingCardId = MutableStateFlow<String?>(null)
    val resolvingCardId: StateFlow<String?> = _resolvingCardId.asStateFlow()

    private val _resolvingVideoStatus = MutableStateFlow<String?>(null)
    val resolvingVideoStatus: StateFlow<String?> = _resolvingVideoStatus.asStateFlow()

    private val _videoResolutionError = MutableStateFlow<String?>(null)
    val videoResolutionError: StateFlow<String?> = _videoResolutionError.asStateFlow()

    private var resolveVideoJob: Job? = null

    fun playVideo(
        rawUrl: String,
        title: String = "Media Stream",
        cardId: String? = null,
        initialPositionMs: Long = 0L,
        startInLandscape: Boolean = false,
        scene: com.example.data.local.entity.LinkEntity? = null,
        subtitles: List<SubtitleTrack> = emptyList()
    ) {
        resolveVideoJob?.cancel()
        _videoResolutionError.value = null
        _resolvingCardId.value = cardId

        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            _videoResolutionError.value = "Cannot play empty stream URL."
            _resolvingCardId.value = null
            return
        }

        resolveVideoJob = viewModelScope.launch(Dispatchers.IO) {
            _resolvingVideoStatus.value = if (com.example.network.torrent.MagnetParser.parseHash(trimmed) != null) {
                "Resolving torrent magnet via Debrid..."
            } else {
                "Resolving media stream..."
            }

            try {
                val settingsEntity = repository.getSettingsOnce()
                val orderEnum = when (settingsEntity.debridOrder) {
                    "REAL_DEBRID_FIRST" -> com.example.network.debrid.DebridOrder.REAL_DEBRID_FIRST
                    "TORBOX_FIRST" -> com.example.network.debrid.DebridOrder.TORBOX_FIRST
                    else -> com.example.network.debrid.DebridOrder.AUTO
                }

                val episodeHint = scene?.title?.takeIf { it.isNotBlank() }
                    ?: title.takeIf { it.isNotBlank() && it != "Media Stream" }

                val resolved = withTimeout(45_000L) {
                    VideoResolvers.resolve(
                        rawUrl = trimmed,
                        torboxApiKey = settingsEntity.torboxApiKey,
                        realDebridApiKey = settingsEntity.realDebridApiKey,
                        debridOrder = orderEnum,
                        episodeHint = episodeHint
                    )
                }

                val combinedQualities = resolved.qualities.toMutableList()

                val vrCandidate = scene?.urlVR?.trim()?.takeIf { it.isNotBlank() }
                    ?: scene?.magnetVR?.trim()?.takeIf { it.isNotBlank() }
                    ?: scene?.torrentUrlVR?.trim()?.takeIf { it.isNotBlank() }

                val isVrSelection = scene != null && (
                    trimmed == scene.urlVR?.trim() ||
                    trimmed == scene.magnetVR?.trim() ||
                    trimmed == scene.torrentUrlVR?.trim()
                )

                if (isVrSelection) {
                    for (i in combinedQualities.indices) {
                        val q = combinedQualities[i]
                        if (!q.quality.contains("vr", ignoreCase = true)) {
                            combinedQualities[i] = q.copy(quality = "VR 360")
                        }
                    }
                }

                if (scene != null) {
                    // 1. Check for 4K / 2160p candidate
                    val fourKCandidate = scene.url4K?.trim()?.takeIf { it.isNotBlank() }
                        ?: scene.magnet4K?.trim()?.takeIf { it.isNotBlank() }
                        ?: scene.torrentUrl4K?.trim()?.takeIf { it.isNotBlank() }

                    if (fourKCandidate != null && trimmed != fourKCandidate && combinedQualities.none { it.quality.contains("2160") || it.quality.contains("4k", ignoreCase = true) }) {
                        combinedQualities.add(
                            StreamQuality(
                                quality = "2160p",
                                url = fourKCandidate,
                                format = if (fourKCandidate.contains(".m3u8")) "HLS" else "MP4",
                                isDefault = false
                            )
                        )
                    }

                    // 2. Check for HD / 1080p candidate
                    val hdCandidate = scene.urlHD?.trim()?.takeIf { it.isNotBlank() }
                        ?: scene.magnet?.trim()?.takeIf { it.isNotBlank() }
                        ?: scene.torrentUrlHD?.trim()?.takeIf { it.isNotBlank() }

                    if (hdCandidate != null && trimmed != hdCandidate && combinedQualities.none { it.quality.contains("1080") || it.quality.equals("hd", ignoreCase = true) }) {
                        combinedQualities.add(
                            StreamQuality(
                                quality = "1080p",
                                url = hdCandidate,
                                format = if (hdCandidate.contains(".m3u8")) "HLS" else "MP4",
                                isDefault = false
                            )
                        )
                    }

                    // 3. Check for VR candidate
                    if (vrCandidate != null && !isVrSelection && combinedQualities.none { it.quality.contains("vr", ignoreCase = true) }) {
                        combinedQualities.add(
                            StreamQuality(
                                quality = "VR 360",
                                url = vrCandidate,
                                format = if (vrCandidate.contains(".m3u8")) "HLS" else "MP4",
                                isDefault = false
                            )
                        )
                    }
                }

                // Normalize, filter to allowed tiers (VR 360, 2160p, 1440p, 1080p, 720p), deduplicate, and sort descending
                val finalQualities = normalizeAndSortQualities(combinedQualities)

                if (finalQualities.isEmpty()) {
                    _videoResolutionError.value = "No playable media qualities found for this source."
                    _resolvingVideoStatus.value = null
                    return@launch
                }

                val primaryQuality = finalQualities.firstOrNull { it.isDefault } ?: finalQualities.first()
                val isTrustedFastStream = com.example.network.torrent.MagnetParser.parseHash(trimmed) != null ||
                    VideoResolvers.isHosterUrl(trimmed) ||
                    GofileScraper.isGofileUrl(trimmed) ||
                    CyberFileScraper.isCyberfileUrl(trimmed) ||
                    VideoResolvers.isPornhubUrl(trimmed) ||
                    com.example.network.Scraping.isSupportedUrl(trimmed) ||
                    primaryQuality.url.contains("real-debrid.com", ignoreCase = true) ||
                    primaryQuality.url.contains("rdeb.io", ignoreCase = true) ||
                    primaryQuality.url.contains("rdb.so", ignoreCase = true) ||
                    primaryQuality.url.contains("torbox.app", ignoreCase = true) ||
                    primaryQuality.url.contains("tb-cdn.st", ignoreCase = true) ||
                    primaryQuality.format.equals("HLS", ignoreCase = true) ||
                    primaryQuality.format.equals("DASH", ignoreCase = true)

                val displayTitle = if (resolved.title.isNotBlank() && resolved.title != "Media Stream") resolved.title else title
                val isVrModeLaunch = isVrSelection || primaryQuality.quality.contains("vr", ignoreCase = true)
                val shouldPlayInline = cardId != null && !isVrModeLaunch

                val combinedSubtitles = (resolved.subtitles + subtitles).distinctBy { it.url }

                withContext(Dispatchers.Main) {
                    com.example.ui.components.PlayerSessionTracker.clearSession(sharedPlayerManager.getPlayer())
                }

                fun launchTargetPlayer() {
                    if (shouldPlayInline) {
                        _activeInlineVideo.value = ActiveInlineVideoPlayback(
                            cardId = cardId!!,
                            title = displayTitle,
                            qualities = finalQualities,
                            subtitles = combinedSubtitles,
                            headers = resolved.headers,
                            initialPositionMs = initialPositionMs
                        )
                    } else {
                        if (cardId != null) {
                            _activeInlineVideo.value = null
                            lastInlineCardId = null
                        }
                        _activeVideo.value = ActiveVideoPlayback(
                            title = displayTitle,
                            qualities = finalQualities,
                            subtitles = combinedSubtitles,
                            headers = resolved.headers,
                            initialPositionMs = initialPositionMs,
                            startInLandscape = startInLandscape || isVrModeLaunch,
                            startInVrMode = isVrModeLaunch
                        )
                    }
                }

                if (isTrustedFastStream) {
                    // Instant playback for verified Debrid/CDN/HLS streams: bypass redundant network probing
                    launchTargetPlayer()
                } else {
                    _resolvingVideoStatus.value = "Verifying media stream..."

                    val validation = MediaUrlValidator.validate(
                        primaryQuality.url,
                        resolved.headers + primaryQuality.headers
                    )

                    when (validation) {
                        is ValidatedMediaResult.Valid -> {
                            launchTargetPlayer()
                        }
                        is ValidatedMediaResult.Invalid -> {
                            val reason = validation.reason
                            val isDefinitiveFailure = reason.contains("HTML", ignoreCase = true) ||
                                reason.contains("HTTP 404", ignoreCase = true) ||
                                reason.contains("HTTP 410", ignoreCase = true) ||
                                reason.contains("HTTP 401", ignoreCase = true)
                            if (!isDefinitiveFailure &&
                                (primaryQuality.url.startsWith("http://", ignoreCase = true) ||
                                    primaryQuality.url.startsWith("https://", ignoreCase = true))
                            ) {
                                launchTargetPlayer()
                            } else {
                                _videoResolutionError.value = reason
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _videoResolutionError.value = e.message ?: "Failed to resolve media stream"
            } finally {
                _resolvingVideoStatus.value = null
                _resolvingCardId.value = null
            }
        }
    }

    fun dismissVideoError() {
        _videoResolutionError.value = null
        _resolvingCardId.value = null
    }

    fun closeVideo() {
        val currentVideo = _activeVideo.value
        val inlineId = lastInlineCardId
        val resumePosMs = sharedPlayerManager.getPlayer().currentPosition.coerceAtLeast(0L)
        _activeVideo.value = null

        if (inlineId != null && currentVideo != null) {
            _activeInlineVideo.value = ActiveInlineVideoPlayback(
                cardId = inlineId,
                title = currentVideo.title,
                qualities = currentVideo.qualities,
                subtitles = currentVideo.subtitles,
                headers = currentVideo.headers,
                initialPositionMs = resumePosMs
            )
            lastInlineCardId = null
        } else {
            sharedPlayerManager.stopPlayer()
        }
    }

    fun closeInlineVideo(cardId: String? = null) {
        if (cardId == null || _activeInlineVideo.value?.cardId == cardId) {
            _activeInlineVideo.value = null
            lastInlineCardId = null
            sharedPlayerManager.stopPlayer()
        }
    }

    fun terminateAllVideoPlayback() {
        _activeVideo.value = null
        _activeInlineVideo.value = null
        lastInlineCardId = null
        enteredPipFromInlineCard = false
        sharedPlayerManager.stopPlayer()
    }

    var enteredPipFromInlineCard: Boolean = false
        private set

    fun enterPipFromInline(cardId: String, currentPositionMs: Long = 0L) {
        enteredPipFromInlineCard = true
        openFullscreenFromInline(cardId, currentPositionMs, startInLandscape = false)
    }

    fun returnToInlineFromPip() {
        if (enteredPipFromInlineCard) {
            enteredPipFromInlineCard = false
            closeVideo()
        }
    }

    fun openFullscreenFromInline(cardId: String, currentPositionMs: Long = 0L, startInLandscape: Boolean = true) {
        val inline = _activeInlineVideo.value ?: return
        if (inline.cardId == cardId) {
            lastInlineCardId = cardId
            _activeInlineVideo.value = null
            _activeVideo.value = ActiveVideoPlayback(
                title = inline.title,
                qualities = inline.qualities,
                subtitles = inline.subtitles,
                headers = inline.headers,
                initialPositionMs = currentPositionMs,
                startInLandscape = startInLandscape
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        sharedPlayerManager.release()
    }

    // ==========================================
    // STASHDB STATE & CACHING
    // ==========================================
    private val _stashSearchQuery = MutableStateFlow("")
    val stashSearchQuery: StateFlow<String> = _stashSearchQuery.asStateFlow()

    private val _stashActiveType = MutableStateFlow(StashSearchType.ACTORS)
    val stashActiveType: StateFlow<StashSearchType> = _stashActiveType.asStateFlow()

    private val _stashSortMode = MutableStateFlow(StashSceneSortMode.RELEASE_DATE)
    val stashSortMode: StateFlow<StashSceneSortMode> = _stashSortMode.asStateFlow()

    private val _stashPerformerResults = MutableStateFlow<List<StashPerformer>>(emptyList())
    val stashPerformerResults: StateFlow<List<StashPerformer>> = _stashPerformerResults.asStateFlow()

    private val _stashStudioResults = MutableStateFlow<List<StashStudio>>(emptyList())
    val stashStudioResults: StateFlow<List<StashStudio>> = _stashStudioResults.asStateFlow()

    private val _stashScenesList = MutableStateFlow<List<StashScene>>(emptyList())
    val stashScenesList: StateFlow<List<StashScene>> = _stashScenesList.asStateFlow()

    private val _rawStashScenesList = MutableStateFlow<List<StashScene>>(emptyList())
    private val blockedStudioNameIdMap = ConcurrentHashMap<String, String>()

    private val _stashSelectedPerformer = MutableStateFlow<StashPerformer?>(null)
    val stashSelectedPerformer: StateFlow<StashPerformer?> = _stashSelectedPerformer.asStateFlow()

    private val _stashSelectedStudio = MutableStateFlow<StashStudio?>(null)
    val stashSelectedStudio: StateFlow<StashStudio?> = _stashSelectedStudio.asStateFlow()

    private val _stashSelectedSceneIds = MutableStateFlow<Set<String>>(emptySet())
    val stashSelectedSceneIds: StateFlow<Set<String>> = _stashSelectedSceneIds.asStateFlow()

    private val _stashTotalScenesCount = MutableStateFlow(0)
    val stashTotalScenesCount: StateFlow<Int> = _stashTotalScenesCount.asStateFlow()

    private val _stashCurrentPage = MutableStateFlow(1)
    val stashCurrentPage: StateFlow<Int> = _stashCurrentPage.asStateFlow()

    private val _stashCanLoadMore = MutableStateFlow(false)
    val stashCanLoadMore: StateFlow<Boolean> = _stashCanLoadMore.asStateFlow()

    private val _isStashLoadingEntities = MutableStateFlow(false)
    val isStashLoadingEntities: StateFlow<Boolean> = _isStashLoadingEntities.asStateFlow()

    private val _isStashLoadingScenes = MutableStateFlow(false)
    val isStashLoadingScenes: StateFlow<Boolean> = _isStashLoadingScenes.asStateFlow()

    private val _isStashLoadingMore = MutableStateFlow(false)
    val isStashLoadingMore: StateFlow<Boolean> = _isStashLoadingMore.asStateFlow()

    private val _stashSearchError = MutableStateFlow<String?>(null)
    val stashSearchError: StateFlow<String?> = _stashSearchError.asStateFlow()

    private val _isStashSearchExpanded = MutableStateFlow(false)
    val isStashSearchExpanded: StateFlow<Boolean> = _isStashSearchExpanded.asStateFlow()

    private var stashSearchJob: Job? = null
    private var stashScenesJob: Job? = null
    private var stashLoadMoreJob: Job? = null
    private var currentSexMexNextPageUrl: String? = null

    // Clean Tab State Cache Map
    private val stashTabCacheMap = ConcurrentHashMap<StashSearchType, StashTabCache>()

    private fun syncCurrentTabToCache() {
        val currentType = _stashActiveType.value
        stashTabCacheMap[currentType] = StashTabCache(
            query = _stashSearchQuery.value,
            performerResults = if (currentType == StashSearchType.STUDIO) emptyList() else _stashPerformerResults.value,
            studioResults = if (currentType == StashSearchType.STUDIO) _stashStudioResults.value else emptyList(),
            scenes = _stashScenesList.value,
            rawScenes = _rawStashScenesList.value,
            selectedPerformer = if (currentType == StashSearchType.STUDIO) null else _stashSelectedPerformer.value,
            selectedStudio = if (currentType == StashSearchType.STUDIO) _stashSelectedStudio.value else null,
            totalCount = _stashTotalScenesCount.value,
            currentPage = _stashCurrentPage.value,
            canLoadMore = _stashCanLoadMore.value,
            sortMode = _stashSortMode.value,
            sexMexNextPageUrl = if (currentType == StashSearchType.SEXMEX) currentSexMexNextPageUrl else null
        )
    }

    fun setStashSearchQuery(query: String) {
        _stashSearchQuery.value = query
        syncCurrentTabToCache()
    }

    fun setStashSearchExpanded(expanded: Boolean) {
        _isStashSearchExpanded.value = expanded
    }

    fun setStashSortMode(mode: StashSceneSortMode, apiKey: String) {
        if (_stashSortMode.value == mode) return
        _stashSortMode.value = mode
        stashLoadMoreJob?.cancel()
        _isStashLoadingMore.value = false
        syncCurrentTabToCache()
        val effectiveApiKey = apiKey.ifBlank { settings.value.stashDbApiKey }
        when (_stashActiveType.value) {
            StashSearchType.ACTORS -> {
                val performer = _stashSelectedPerformer.value
                if (performer != null) {
                    selectStashPerformer(performer, effectiveApiKey, forceReload = true)
                }
            }
            StashSearchType.STUDIO -> {
                val studio = _stashSelectedStudio.value
                if (studio != null) {
                    selectStashStudio(studio, effectiveApiKey, forceReload = true)
                }
            }
            StashSearchType.SEXMEX -> Unit
        }
    }

    fun setStashActiveType(type: StashSearchType, apiKey: String) {
        if (_stashActiveType.value == type) return

        // Cancel any in-flight search or scene loading jobs from previous tab so results never bleed across tabs
        stashSearchJob?.cancel()
        stashScenesJob?.cancel()
        stashLoadMoreJob?.cancel()
        _isStashLoadingEntities.value = false
        _isStashLoadingScenes.value = false
        _isStashLoadingMore.value = false

        // 1. Cache current tab's isolated state
        syncCurrentTabToCache()

        _stashActiveType.value = type
        _stashSearchError.value = null
        _stashSelectedSceneIds.value = emptySet()

        // 2. Restore target tab's isolated cached state
        val cached = stashTabCacheMap[type] ?: StashTabCache()
        val currentSettings = settings.value
        val restoredRaw = cached.rawScenes.ifEmpty { cached.scenes }
        _stashSearchQuery.value = cached.query
        _stashPerformerResults.value = if (type == StashSearchType.STUDIO) emptyList() else cached.performerResults
        _stashStudioResults.value = if (type == StashSearchType.STUDIO) cached.studioResults else emptyList()
        _rawStashScenesList.value = restoredRaw
        _stashScenesList.value = applyStudioFilter(restoredRaw, currentSettings)
        _stashTotalScenesCount.value = cached.totalCount
        _stashCurrentPage.value = cached.currentPage
        _stashCanLoadMore.value = cached.canLoadMore
        _stashSelectedPerformer.value = if (type == StashSearchType.STUDIO) null else cached.selectedPerformer
        _stashSelectedStudio.value = if (type == StashSearchType.STUDIO) cached.selectedStudio else null
        _stashSortMode.value = cached.sortMode
        currentSexMexNextPageUrl = if (type == StashSearchType.SEXMEX) cached.sexMexNextPageUrl else null
    }

    fun toggleStashSceneSelection(sceneId: String) {
        val current = _stashSelectedSceneIds.value
        _stashSelectedSceneIds.value = if (current.contains(sceneId)) current - sceneId else current + sceneId
    }

    fun clearStashSelection() {
        _stashSelectedSceneIds.value = emptySet()
    }

    fun resetStashState() {
        stashSearchJob?.cancel()
        stashScenesJob?.cancel()
        stashLoadMoreJob?.cancel()
        stashTabCacheMap.clear()
        currentSexMexNextPageUrl = null
        _stashSearchQuery.value = ""
        _isStashSearchExpanded.value = false
        _stashPerformerResults.value = emptyList()
        _stashStudioResults.value = emptyList()
        _rawStashScenesList.value = emptyList()
        _stashScenesList.value = emptyList()
        _stashSelectedPerformer.value = null
        _stashSelectedStudio.value = null
        _stashSelectedSceneIds.value = emptySet()
        _stashSearchError.value = null
        _stashCurrentPage.value = 1
        _stashCanLoadMore.value = false
        _stashSortMode.value = StashSceneSortMode.RELEASE_DATE
        _isStashLoadingEntities.value = false
        _isStashLoadingScenes.value = false
        _isStashLoadingMore.value = false
    }

    private fun scoreSearchMatch(name: String, aliases: List<String>, query: String): Int {
        val q = query.lowercase()
        val n = name.trim().lowercase()
        val a = aliases.map { it.trim().lowercase() }
        return when {
            n == q -> 100
            a.contains(q) -> 90
            n.startsWith(q) -> 80
            a.any { it.startsWith(q) } -> 70
            n.contains(q) -> 60
            a.any { it.contains(q) } -> 50
            else -> 10
        }
    }

    fun performStashSearch(apiKey: String, query: String? = null) {
        val q = (query ?: _stashSearchQuery.value).trim()
        if (q.isBlank()) return
        val effectiveApiKey = apiKey.ifBlank { settings.value.stashDbApiKey }
        _stashSearchQuery.value = q

        stashSearchJob?.cancel()
        stashScenesJob?.cancel()
        stashLoadMoreJob?.cancel()
        _isStashLoadingMore.value = false
        val searchTargetType = _stashActiveType.value

        stashSearchJob = viewModelScope.launch(Dispatchers.IO) {
            _isStashLoadingEntities.value = true
            _isStashLoadingScenes.value = (searchTargetType == StashSearchType.SEXMEX)
            _stashSearchError.value = null
            _stashPerformerResults.value = emptyList()
            _stashStudioResults.value = emptyList()
            _stashSelectedPerformer.value = null
            _stashSelectedStudio.value = null
            _stashSelectedSceneIds.value = emptySet()
            _rawStashScenesList.value = emptyList()
            _stashScenesList.value = emptyList()
            _stashTotalScenesCount.value = 0
            _stashCurrentPage.value = 1
            _stashCanLoadMore.value = false
            if (searchTargetType == StashSearchType.SEXMEX) {
                currentSexMexNextPageUrl = null
            }
            syncCurrentTabToCache()

            when (searchTargetType) {
                StashSearchType.ACTORS -> {
                    val res = StashDbApiService.searchPerformers(
                        query = q,
                        apiKey = effectiveApiKey,
                        blockMalePerformers = settings.value.blockMalePerformers
                    )
                    res.onSuccess { rawPerformers ->
                        val sorted = rawPerformers.sortedWith(
                            compareByDescending<StashPerformer> { scoreSearchMatch(it.name, it.aliases, q) }
                                .thenByDescending { if (!it.imageUrl.isNullOrBlank()) 1 else 0 }
                                .thenBy { it.name.lowercase() }
                        )
                        _stashPerformerResults.value = sorted
                        stashTabCacheMap[StashSearchType.ACTORS] = StashTabCache(
                            query = q,
                            performerResults = sorted,
                            selectedPerformer = sorted.firstOrNull(),
                            sortMode = _stashSortMode.value
                        )
                        if (sorted.isNotEmpty()) {
                            _isStashLoadingScenes.value = true
                            _isStashLoadingEntities.value = false
                            selectStashPerformer(sorted.first(), effectiveApiKey, forceReload = true)
                        } else {
                            _isStashLoadingEntities.value = false
                        }
                    }.onFailure { err ->
                        _stashSearchError.value = err.message ?: "Failed to search actors"
                        _isStashLoadingEntities.value = false
                        syncCurrentTabToCache()
                    }
                }
                StashSearchType.STUDIO -> {
                    val res = StashDbApiService.searchStudios(q, effectiveApiKey)
                    res.onSuccess { rawStudios ->
                        val sorted = rawStudios.sortedWith(
                            compareByDescending<StashStudio> { scoreSearchMatch(it.name, emptyList(), q) }
                                .thenByDescending { if (!it.logoUrl.isNullOrBlank()) 1 else 0 }
                                .thenBy { it.name.lowercase() }
                        )
                        _stashStudioResults.value = sorted
                        stashTabCacheMap[StashSearchType.STUDIO] = StashTabCache(
                            query = q,
                            studioResults = sorted,
                            selectedStudio = sorted.firstOrNull(),
                            sortMode = _stashSortMode.value
                        )
                        if (sorted.isNotEmpty()) {
                            _isStashLoadingScenes.value = true
                            _isStashLoadingEntities.value = false
                            selectStashStudio(sorted.first(), effectiveApiKey, forceReload = true)
                        } else {
                            _isStashLoadingEntities.value = false
                        }
                    }.onFailure { err ->
                        _stashSearchError.value = err.message ?: "Failed to search studios"
                        _isStashLoadingEntities.value = false
                        syncCurrentTabToCache()
                    }
                }
                StashSearchType.SEXMEX -> {
                    try {
                        val result = com.example.network.SexMexScraper.searchSexMex(q)
                        val filtered = applyStudioFilter(result.scenes, settings.value)
                        val hasMore = result.scenes.isNotEmpty() && !result.nextPageUrl.isNullOrBlank()
                        currentSexMexNextPageUrl = result.nextPageUrl
                        _stashPerformerResults.value = result.models
                        _stashSelectedPerformer.value = result.models.firstOrNull()
                        _rawStashScenesList.value = result.scenes
                        _stashScenesList.value = filtered
                        _stashTotalScenesCount.value = result.scenes.size
                        _stashCurrentPage.value = 1
                        _stashCanLoadMore.value = hasMore
                        stashTabCacheMap[StashSearchType.SEXMEX] = StashTabCache(
                            query = q,
                            performerResults = result.models,
                            selectedPerformer = result.models.firstOrNull(),
                            scenes = filtered,
                            rawScenes = result.scenes,
                            totalCount = result.scenes.size,
                            currentPage = 1,
                            canLoadMore = hasMore,
                            sexMexNextPageUrl = result.nextPageUrl
                        )
                        _isStashLoadingEntities.value = false
                        _isStashLoadingScenes.value = false
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        _stashSearchError.value = e.message ?: "Failed to search SexMex"
                        _isStashLoadingEntities.value = false
                        _isStashLoadingScenes.value = false
                    }
                }
            }
        }
    }

    fun searchStashForActor(actorName: String, apiKey: String) {
        setStashActiveType(StashSearchType.ACTORS, apiKey)
        setStashSearchQuery(actorName)
        performStashSearch(apiKey = apiKey, query = actorName)
        navigateTo(ScreenState.StashDb)
    }

    fun searchStashForStudio(studioName: String, apiKey: String) {
        setStashActiveType(StashSearchType.STUDIO, apiKey)
        setStashSearchQuery(studioName)
        performStashSearch(apiKey = apiKey, query = studioName)
        navigateTo(ScreenState.StashDb)
    }

    fun exploreLatestSexMex() {
        stashSearchJob?.cancel()
        stashScenesJob?.cancel()
        stashLoadMoreJob?.cancel()
        _isStashLoadingEntities.value = false
        _isStashLoadingMore.value = false
        stashSearchJob = viewModelScope.launch(Dispatchers.IO) {
            _isStashLoadingScenes.value = true
            _stashSearchError.value = null
            _stashPerformerResults.value = emptyList()
            _stashSelectedPerformer.value = null
            _stashSelectedSceneIds.value = emptySet()
            _stashCurrentPage.value = 1
            _stashCanLoadMore.value = false
            currentSexMexNextPageUrl = null
            _stashSearchQuery.value = ""
            try {
                val pageResult = com.example.network.SexMexScraper.getLatestSexMexScenesWithPagination()
                val latest = pageResult.scenes
                val filtered = applyStudioFilter(latest, settings.value)
                val hasMore = latest.isNotEmpty() && !pageResult.nextPageUrl.isNullOrBlank()
                currentSexMexNextPageUrl = pageResult.nextPageUrl
                _rawStashScenesList.value = latest
                _stashScenesList.value = filtered
                _stashTotalScenesCount.value = latest.size
                _stashCanLoadMore.value = hasMore
                stashTabCacheMap[StashSearchType.SEXMEX] = StashTabCache(
                    scenes = filtered,
                    rawScenes = latest,
                    totalCount = latest.size,
                    currentPage = 1,
                    canLoadMore = hasMore,
                    sexMexNextPageUrl = pageResult.nextPageUrl
                )
                _isStashLoadingScenes.value = false
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _rawStashScenesList.value = emptyList()
                _stashScenesList.value = emptyList()
                _stashSearchError.value = e.message ?: "Failed to fetch latest SexMex scenes"
                _isStashLoadingScenes.value = false
            }
        }
    }

    private val sexmexCoverRefreshTimestamps = ConcurrentHashMap<String, Long>()
    private val sexmexRefreshSemaphore = kotlinx.coroutines.sync.Semaphore(2)

    fun autoRefreshSexMexCoverIfNeeded(link: LinkEntity) {
        val sceneUrl = link.stashDbId?.trim() ?: return
        if (!sceneUrl.contains("sexmex.xxx", ignoreCase = true) && !sceneUrl.contains("sexmex.com", ignoreCase = true)) {
            return
        }

        val now = System.currentTimeMillis()
        val lastAttempt = sexmexCoverRefreshTimestamps[link.id] ?: 0L
        if (now - lastAttempt < 10 * 60 * 1000L) return
        sexmexCoverRefreshTimestamps[link.id] = now

        viewModelScope.launch(Dispatchers.IO) {
            sexmexRefreshSemaphore.acquire()
            try {
                val freshUrl = com.example.network.SexMexScraper.fetchFreshCoverUrl(sceneUrl)
                if (!freshUrl.isNullOrBlank() && freshUrl != link.coverImage) {
                    repository.updateLink(link.copy(coverImage = freshUrl))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("MainViewModel", "Failed to auto-refresh SexMex cover for ${link.id}", e)
            } finally {
                sexmexRefreshSemaphore.release()
            }
        }
    }

    fun isStudioBlocked(
        studioId: String?,
        studioName: String?,
        currentSettings: SettingsEntity = settings.value
    ): Boolean {
        if (!currentSettings.enableStudioFilter) return false
        val sName = studioName?.trim()
        val sId = studioId?.trim()
        if (!sName.isNullOrBlank()) {
            if (!sId.isNullOrBlank()) {
                blockedStudioNameIdMap[sName.lowercase()] = sId
            }
            if (currentSettings.blockedStudioNames.any { it.equals(sName, ignoreCase = true) }) return true
        }
        if (!sId.isNullOrBlank() && currentSettings.blockedStudioIds.contains(sId)) {
            return true
        }
        return false
    }

    private fun applyStudioFilter(
        rawScenes: List<StashScene>,
        currentSettings: SettingsEntity = settings.value
    ): List<StashScene> {
        rawScenes.forEach { scene ->
            val sName = scene.studioName?.trim()
            val sId = scene.studioId?.trim()
            if (!sName.isNullOrBlank() && !sId.isNullOrBlank()) {
                blockedStudioNameIdMap[sName.lowercase()] = sId
            }
            val pName = scene.parentStudioName?.trim()
            val pId = scene.parentStudioId?.trim()
            if (!pName.isNullOrBlank() && !pId.isNullOrBlank()) {
                blockedStudioNameIdMap[pName.lowercase()] = pId
            }
        }
        return if (currentSettings.enableStudioFilter) {
            rawScenes.filterNot { scene ->
                isStudioBlocked(scene.studioId, scene.studioName, currentSettings) ||
                    isStudioBlocked(scene.parentStudioId, scene.parentStudioName, currentSettings)
            }.distinctBy { it.id }
        } else {
            rawScenes.distinctBy { it.id }
        }
    }

    private fun refreshStashScenesFilter(currentSettings: SettingsEntity = settings.value) {
        val raw = _rawStashScenesList.value
        if (raw.isNotEmpty() || _stashScenesList.value.isNotEmpty()) {
            val filtered = applyStudioFilter(raw, currentSettings)
            _stashScenesList.value = filtered
            val visibleIds = filtered.map { it.id }.toSet()
            if (_stashSelectedSceneIds.value.isNotEmpty()) {
                _stashSelectedSceneIds.value = _stashSelectedSceneIds.value.intersect(visibleIds)
            }
        }
        stashTabCacheMap.entries.forEach { (type, cached) ->
            val tabRaw = cached.rawScenes.ifEmpty { cached.scenes }
            if (tabRaw.isNotEmpty()) {
                stashTabCacheMap[type] = cached.copy(
                    scenes = applyStudioFilter(tabRaw, currentSettings),
                    rawScenes = tabRaw
                )
            }
        }
    }

    fun blockStudio(studioId: String?, studioName: String?) {
        val name = studioName?.trim() ?: return
        if (name.isBlank()) return
        val sId = studioId?.trim()?.takeIf { it.isNotBlank() }
        if (sId != null) {
            blockedStudioNameIdMap[name.lowercase()] = sId
        }
        val current = settings.value
        val updatedNames = (current.blockedStudioNames + name).distinct()
        val updatedIds = if (sId != null) (current.blockedStudioIds + sId).distinct() else current.blockedStudioIds
        val updatedSettings = current.copy(blockedStudioNames = updatedNames, blockedStudioIds = updatedIds)
        updateSettings(updatedSettings)
        refreshStashScenesFilter(updatedSettings)
    }

    fun unblockStudio(studioName: String) {
        val name = studioName.trim()
        val current = settings.value
        val updatedNames = current.blockedStudioNames.filterNot { it.equals(name, ignoreCase = true) }
        val allStudiosList = allStudios.value
        val unblockedIds = mutableSetOf<String>()
        blockedStudioNameIdMap.remove(name.lowercase())?.let { unblockedIds.add(it) }
        allStudiosList.filter { it.name.trim().equals(name, ignoreCase = true) }.forEach { studio ->
            unblockedIds.add(studio.id)
            studio.stashDbId?.let { unblockedIds.add(it) }
        }
        _rawStashScenesList.value.filter { it.studioName?.trim().equals(name, ignoreCase = true) }.forEach { scene ->
            scene.studioId?.let { unblockedIds.add(it) }
        }
        val updatedIds = current.blockedStudioIds.filterNot { it in unblockedIds }
        val updatedSettings = current.copy(blockedStudioNames = updatedNames, blockedStudioIds = updatedIds)
        updateSettings(updatedSettings)
        refreshStashScenesFilter(updatedSettings)
    }

    fun clearAllBlockedStudios() {
        val current = settings.value
        blockedStudioNameIdMap.clear()
        val updatedSettings = current.copy(blockedStudioNames = emptyList(), blockedStudioIds = emptyList())
        updateSettings(updatedSettings)
        refreshStashScenesFilter(updatedSettings)
    }

    fun selectStashPerformer(performer: StashPerformer, apiKey: String, forceReload: Boolean = false) {
        if (!forceReload &&
            _stashSelectedPerformer.value?.id == performer.id &&
            _stashSearchError.value == null &&
            (_isStashLoadingScenes.value || _rawStashScenesList.value.isNotEmpty())
        ) {
            return
        }

        _stashSelectedPerformer.value = performer
        _stashSelectedStudio.value = null
        _stashSelectedSceneIds.value = emptySet()
        stashLoadMoreJob?.cancel()
        _isStashLoadingMore.value = false
        _isStashLoadingScenes.value = true
        _stashCurrentPage.value = 1
        _stashSearchError.value = null

        if (_stashActiveType.value == StashSearchType.SEXMEX) {
            stashScenesJob?.cancel()
            stashScenesJob = viewModelScope.launch(Dispatchers.IO) {
                currentSexMexNextPageUrl = null
                try {
                    val pageResult = com.example.network.SexMexScraper.scrapeSexMexPageWithPagination(performer.id)
                    val scenes = pageResult.scenes
                    val filtered = applyStudioFilter(scenes, settings.value)
                    val hasMore = scenes.isNotEmpty() && !pageResult.nextPageUrl.isNullOrBlank()
                    currentSexMexNextPageUrl = pageResult.nextPageUrl
                    _stashTotalScenesCount.value = scenes.size
                    _rawStashScenesList.value = scenes
                    _stashScenesList.value = filtered
                    _stashCanLoadMore.value = hasMore
                    syncCurrentTabToCache()
                    _isStashLoadingScenes.value = false
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _rawStashScenesList.value = emptyList()
                    _stashScenesList.value = emptyList()
                    _stashSearchError.value = e.message ?: "Failed to load SexMex scenes"
                    _isStashLoadingScenes.value = false
                }
            }
            return
        }

        val effectiveApiKey = apiKey.ifBlank { settings.value.stashDbApiKey }
        val activeSort = _stashSortMode.value

        stashScenesJob?.cancel()
        stashScenesJob = viewModelScope.launch(Dispatchers.IO) {
            val res = StashDbApiService.queryPerformerScenes(
                performerId = performer.id,
                apiKey = effectiveApiKey,
                page = 1,
                perPage = 30,
                blockMalePerformers = settings.value.blockMalePerformers,
                sortMode = activeSort
            )
            res.onSuccess { queryResult ->
                var currentPage = 1
                var totalCount = queryResult.count
                var accumulatedRaw = queryResult.scenes.distinctBy { it.id }
                var filteredScenes = applyStudioFilter(accumulatedRaw, settings.value)
                var canMore = queryResult.scenes.isNotEmpty() && (currentPage * 30 < totalCount)

                var autoPagesFetched = 0
                while (filteredScenes.size < 6 && canMore && autoPagesFetched < 2) {
                    ensureActive()
                    val nextP = currentPage + 1
                    val nextRes = StashDbApiService.queryPerformerScenes(
                        performerId = performer.id,
                        apiKey = effectiveApiKey,
                        page = nextP,
                        perPage = 30,
                        blockMalePerformers = settings.value.blockMalePerformers,
                        sortMode = activeSort
                    )
                    val nextData = nextRes.getOrNull() ?: break
                    currentPage = nextP
                    autoPagesFetched++
                    totalCount = nextData.count
                    accumulatedRaw = (accumulatedRaw + nextData.scenes).distinctBy { it.id }
                    filteredScenes = applyStudioFilter(accumulatedRaw, settings.value)
                    canMore = nextData.scenes.isNotEmpty() && (currentPage * 30 < totalCount)
                }

                _stashCurrentPage.value = currentPage
                _stashTotalScenesCount.value = totalCount
                _rawStashScenesList.value = accumulatedRaw
                _stashScenesList.value = filteredScenes
                _stashCanLoadMore.value = canMore
                syncCurrentTabToCache()
                _isStashLoadingScenes.value = false
            }.onFailure { err ->
                _rawStashScenesList.value = emptyList()
                _stashScenesList.value = emptyList()
                _stashSearchError.value = err.message ?: "Failed to load scenes"
                _isStashLoadingScenes.value = false
                syncCurrentTabToCache()
            }
        }
    }

    fun selectStashStudio(studio: StashStudio, apiKey: String, forceReload: Boolean = false) {
        if (!forceReload &&
            _stashSelectedStudio.value?.id == studio.id &&
            _stashSearchError.value == null &&
            (_isStashLoadingScenes.value || _rawStashScenesList.value.isNotEmpty())
        ) {
            return
        }

        _stashSelectedStudio.value = studio
        _stashSelectedPerformer.value = null
        _stashSelectedSceneIds.value = emptySet()
        stashLoadMoreJob?.cancel()
        _isStashLoadingMore.value = false
        _isStashLoadingScenes.value = true
        _stashCurrentPage.value = 1
        _stashSearchError.value = null
        val effectiveApiKey = apiKey.ifBlank { settings.value.stashDbApiKey }
        val activeSort = _stashSortMode.value

        stashScenesJob?.cancel()
        stashScenesJob = viewModelScope.launch(Dispatchers.IO) {
            val res = StashDbApiService.queryStudioScenes(
                studioId = studio.id,
                apiKey = effectiveApiKey,
                page = 1,
                perPage = 30,
                providedChildIds = studio.childIds,
                blockMalePerformers = settings.value.blockMalePerformers,
                sortMode = activeSort
            )
            res.onSuccess { queryResult ->
                var currentPage = 1
                var totalCount = queryResult.count
                var accumulatedRaw = queryResult.scenes.distinctBy { it.id }
                var filteredScenes = applyStudioFilter(accumulatedRaw, settings.value)
                var canMore = queryResult.scenes.isNotEmpty() && (currentPage * 30 < totalCount)

                var autoPagesFetched = 0
                while (filteredScenes.size < 6 && canMore && autoPagesFetched < 2) {
                    ensureActive()
                    val nextP = currentPage + 1
                    val nextRes = StashDbApiService.queryStudioScenes(
                        studioId = studio.id,
                        apiKey = effectiveApiKey,
                        page = nextP,
                        perPage = 30,
                        providedChildIds = studio.childIds,
                        blockMalePerformers = settings.value.blockMalePerformers,
                        sortMode = activeSort
                    )
                    val nextData = nextRes.getOrNull() ?: break
                    currentPage = nextP
                    autoPagesFetched++
                    totalCount = nextData.count
                    accumulatedRaw = (accumulatedRaw + nextData.scenes).distinctBy { it.id }
                    filteredScenes = applyStudioFilter(accumulatedRaw, settings.value)
                    canMore = nextData.scenes.isNotEmpty() && (currentPage * 30 < totalCount)
                }

                _stashCurrentPage.value = currentPage
                _stashTotalScenesCount.value = totalCount
                _rawStashScenesList.value = accumulatedRaw
                _stashScenesList.value = filteredScenes
                _stashCanLoadMore.value = canMore
                syncCurrentTabToCache()
                _isStashLoadingScenes.value = false
            }.onFailure { err ->
                _rawStashScenesList.value = emptyList()
                _stashScenesList.value = emptyList()
                _stashSearchError.value = err.message ?: "Failed to load studio scenes"
                _isStashLoadingScenes.value = false
                syncCurrentTabToCache()
            }
        }
    }

    private fun appendStashScenes(newScenes: List<StashScene>, count: Int, page: Int): Int {
        _stashCurrentPage.value = page
        val previousVisibleCount = _stashScenesList.value.size
        val updatedRaw = (_rawStashScenesList.value + newScenes).distinctBy { it.id }
        _rawStashScenesList.value = updatedRaw
        val updatedFiltered = applyStudioFilter(updatedRaw, settings.value)
        _stashScenesList.value = updatedFiltered
        _stashTotalScenesCount.value = count
        _stashCanLoadMore.value = newScenes.isNotEmpty() && (page * 30 < count)
        syncCurrentTabToCache()
        return (updatedFiltered.size - previousVisibleCount).coerceAtLeast(0)
    }

    fun loadMoreStashScenes(apiKey: String) {
        if (_isStashLoadingMore.value || _isStashLoadingScenes.value || !_stashCanLoadMore.value) return
        val currentType = _stashActiveType.value
        val selectedPerf = _stashSelectedPerformer.value
        val selectedStud = _stashSelectedStudio.value
        val effectiveApiKey = apiKey.ifBlank { settings.value.stashDbApiKey }
        val activeSort = _stashSortMode.value

        if (currentType == StashSearchType.ACTORS && selectedPerf == null) return
        if (currentType == StashSearchType.STUDIO && selectedStud == null) return
        if (currentType == StashSearchType.SEXMEX && currentSexMexNextPageUrl.isNullOrBlank()) return

        stashLoadMoreJob?.cancel()
        stashLoadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            _isStashLoadingMore.value = true
            try {
                var nextPage = _stashCurrentPage.value + 1
                var pagesAttempted = 0
                var addedVisible = 0

                while (pagesAttempted < 3 && addedVisible == 0) {
                    ensureActive()
                    pagesAttempted++
                    if (currentType == StashSearchType.ACTORS && selectedPerf != null) {
                        val res = StashDbApiService.queryPerformerScenes(
                            performerId = selectedPerf.id,
                            apiKey = effectiveApiKey,
                            page = nextPage,
                            perPage = 30,
                            blockMalePerformers = settings.value.blockMalePerformers,
                            sortMode = activeSort
                        )
                        var shouldContinue = false
                        res.onSuccess { queryResult ->
                            addedVisible += appendStashScenes(queryResult.scenes, queryResult.count, nextPage)
                            shouldContinue = _stashCanLoadMore.value
                        }.onFailure {
                            shouldContinue = false
                        }
                        if (!shouldContinue) break
                    } else if (currentType == StashSearchType.STUDIO && selectedStud != null) {
                        val res = StashDbApiService.queryStudioScenes(
                            studioId = selectedStud.id,
                            apiKey = effectiveApiKey,
                            page = nextPage,
                            perPage = 30,
                            providedChildIds = selectedStud.childIds,
                            blockMalePerformers = settings.value.blockMalePerformers,
                            sortMode = activeSort
                        )
                        var shouldContinue = false
                        res.onSuccess { queryResult ->
                            addedVisible += appendStashScenes(queryResult.scenes, queryResult.count, nextPage)
                            shouldContinue = _stashCanLoadMore.value
                        }.onFailure {
                            shouldContinue = false
                        }
                        if (!shouldContinue) break
                    } else if (currentType == StashSearchType.SEXMEX) {
                        val targetUrl = currentSexMexNextPageUrl ?: break
                        val pageResult = com.example.network.SexMexScraper.scrapeSexMexPageWithPagination(targetUrl)
                        if (pageResult.scenes.isEmpty()) {
                            currentSexMexNextPageUrl = null
                            _stashCanLoadMore.value = false
                            syncCurrentTabToCache()
                            break
                        }
                        currentSexMexNextPageUrl = pageResult.nextPageUrl
                        _stashCurrentPage.value = nextPage
                        val previousVisibleCount = _stashScenesList.value.size
                        val updatedRaw = (_rawStashScenesList.value + pageResult.scenes).distinctBy { it.id }
                        _rawStashScenesList.value = updatedRaw
                        val updatedFiltered = applyStudioFilter(updatedRaw, settings.value)
                        _stashScenesList.value = updatedFiltered
                        _stashTotalScenesCount.value = updatedRaw.size
                        val hasMore = !pageResult.nextPageUrl.isNullOrBlank() && updatedRaw.size > previousVisibleCount
                        _stashCanLoadMore.value = hasMore
                        syncCurrentTabToCache()
                        addedVisible += (updatedFiltered.size - previousVisibleCount).coerceAtLeast(0)
                        if (!hasMore) break
                    } else {
                        break
                    }
                    nextPage = _stashCurrentPage.value + 1
                }
            } finally {
                _isStashLoadingMore.value = false
            }
        }
    }

    // Batch save progress state
    private val _isSavingWithProgress = MutableStateFlow(false)
    val isSavingWithProgress: StateFlow<Boolean> = _isSavingWithProgress.asStateFlow()

    private val _saveProgressCurrent = MutableStateFlow(0)
    val saveProgressCurrent: StateFlow<Int> = _saveProgressCurrent.asStateFlow()

    private val _saveProgressTotal = MutableStateFlow(0)
    val saveProgressTotal: StateFlow<Int> = _saveProgressTotal.asStateFlow()

    private val _saveCurrentTitle = MutableStateFlow("")
    val saveCurrentTitle: StateFlow<String> = _saveCurrentTitle.asStateFlow()

    private val _saveCurrentPhase = MutableStateFlow("")
    val saveCurrentPhase: StateFlow<String> = _saveCurrentPhase.asStateFlow()

    private val _saveSavedWithTorrentsCount = MutableStateFlow(0)
    val saveSavedWithTorrentsCount: StateFlow<Int> = _saveSavedWithTorrentsCount.asStateFlow()

    private var batchSaveJob: Job? = null

    fun cancelBatchSave() {
        batchSaveJob?.cancel()
        _isSavingWithProgress.value = false
    }

    fun saveSelectedStashScenesWithProgress(
        fetchTorrents: Boolean = true,
        onComplete: (savedCount: Int, torrentsCount: Int) -> Unit
    ) {
        batchSaveJob?.cancel()
        batchSaveJob = viewModelScope.launch(Dispatchers.IO) {
            val selectedIds = _stashSelectedSceneIds.value
            if (selectedIds.isEmpty()) return@launch

            val scenesToSave = _stashScenesList.value.filter { selectedIds.contains(it.id) }
            if (scenesToSave.isEmpty()) return@launch

            _isSavingWithProgress.value = true
            _saveProgressCurrent.value = 0
            _saveProgressTotal.value = scenesToSave.size
            _saveSavedWithTorrentsCount.value = 0
            _saveCurrentTitle.value = ""
            _saveCurrentPhase.value = ""

            var totalSaved = 0
            var torrentsCount = 0

            val selectedPerf = _stashSelectedPerformer.value

            // Optimized: Fetch DB entities once before loop
            val allExistingActors = repository.allActors.first().toMutableList()
            val allExistingStudios = repository.allStudios.first().toMutableList()
            val allExistingLinks = repository.allLinks.first().toMutableList()

            val newActorsToInsert = mutableListOf<ActorEntity>()
            val newStudiosToInsert = mutableListOf<StudioEntity>()
            val linksToSaveBatch = mutableListOf<LinkEntity>()

            try {
                for ((index, scene) in scenesToSave.withIndex()) {
                    ensureActive()

                    _saveProgressCurrent.value = index + 1
                    _saveCurrentTitle.value = scene.title
                    _saveCurrentPhase.value = if (fetchTorrents) "Fetching torrent..." else "Preparing scene..."

                    // 1. Process female performers
                    val sortedPerformers = if (selectedPerf != null) {
                        val matching = scene.femalePerformers.filter {
                            it.id == selectedPerf.id || it.name.trim().equals(selectedPerf.name.trim(), ignoreCase = true)
                        }
                        val others = scene.femalePerformers.filterNot {
                            it.id == selectedPerf.id || it.name.trim().equals(selectedPerf.name.trim(), ignoreCase = true)
                        }
                        matching + others
                    } else scene.femalePerformers

                    val actorEntitiesForSearch = mutableListOf<ActorEntity>()
                    val actorIds = mutableListOf<String>()

                    for (perf in sortedPerformers) {
                        val pName = perf.name.trim()
                        if (pName.isBlank()) continue
                        val validPerformerStashId = perf.id.takeIf {
                            !it.startsWith("sexmex:", ignoreCase = true) && !it.startsWith("http", ignoreCase = true)
                        }

                        val existing = allExistingActors.find {
                            (validPerformerStashId != null && it.stashDbId == validPerformerStashId) ||
                            it.name.trim().equals(pName, ignoreCase = true)
                        }

                        if (existing != null) {
                            actorIds.add(existing.id)
                            actorEntitiesForSearch.add(existing)
                        } else {
                            val newActorId = UUID.randomUUID().toString()
                            val actor = ActorEntity(
                                id = newActorId,
                                stashDbId = validPerformerStashId,
                                name = perf.name,
                                imageUrl = perf.imageUrl ?: "",
                                originalImageUrl = perf.imageUrl
                            )
                            allExistingActors.add(actor)
                            newActorsToInsert.add(actor)
                            actorIds.add(newActorId)
                            actorEntitiesForSearch.add(actor)
                        }
                    }

                    // 2. Process Studio
                    val studioEntitiesForSearch = mutableListOf<StudioEntity>()
                    val studioIds = mutableListOf<String>()

                    if (!scene.studioName.isNullOrBlank()) {
                        val sName = scene.studioName.trim()
                        val validStudioStashId = scene.studioId?.takeIf {
                            !it.equals("sexmex", ignoreCase = true) && !it.startsWith("http", ignoreCase = true)
                        }
                        val existingStudio = allExistingStudios.find {
                            (validStudioStashId != null && it.stashDbId == validStudioStashId) ||
                            it.name.trim().equals(sName, ignoreCase = true)
                        }

                        if (existingStudio != null) {
                            studioIds.add(existingStudio.id)
                            studioEntitiesForSearch.add(existingStudio)
                        } else {
                            val newStudioId = UUID.randomUUID().toString()
                            val studio = StudioEntity(
                                id = newStudioId,
                                stashDbId = validStudioStashId,
                                name = scene.studioName,
                                logoUrl = scene.studioLogo,
                                imageUrl = scene.studioLogo
                            )
                            allExistingStudios.add(studio)
                            newStudiosToInsert.add(studio)
                            studioIds.add(newStudioId)
                            studioEntitiesForSearch.add(studio)
                        }
                    }

                    // 3. Parse date
                    val parsedDate = StashDbApiService.parseDateToMillis(scene.date)

                    // 4. Fetch torrent magnet if requested
                    val torrentResult = if (fetchTorrents) {
                        try {
                            fetchMagnetInternal(
                                title = scene.title,
                                selectedActors = actorEntitiesForSearch,
                                selectedStudios = studioEntitiesForSearch,
                                assignedDate = parsedDate,
                                urlHD = scene.coverUrl ?: "",
                                url4K = ""
                            )
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            null
                        }
                    } else null

                    val hasTorrent = torrentResult != null && torrentResult.hasResult
                    if (hasTorrent) {
                        torrentsCount++
                        _saveSavedWithTorrentsCount.value = torrentsCount
                    }

                    _saveCurrentPhase.value = "Saving..."

                    // 5. Build & insert LinkEntity safely
                    val existingLink = allExistingLinks.find {
                        (!scene.id.isNullOrBlank() && it.stashDbId == scene.id) ||
                        (scene.title.isNotBlank() && it.title.trim().equals(scene.title.trim(), ignoreCase = true) && parsedDate != null && it.assignedDate != null && it.assignedDate == parsedDate)
                    }

                    val linkToSave = if (existingLink != null) {
                        existingLink.copy(
                            stashDbId = scene.id,
                            title = scene.title,
                            coverImage = if (existingLink.coverImage.isBlank()) (scene.coverUrl ?: "") else existingLink.coverImage,
                            actorIds = (actorIds + existingLink.actorIds).distinct(),
                            studioIds = (studioIds + existingLink.studioIds).distinct(),
                            assignedDate = existingLink.assignedDate ?: parsedDate,
                            magnet = torrentResult?.magnet1080p ?: existingLink.magnet,
                            magnet4K = torrentResult?.magnet2160p ?: existingLink.magnet4K,
                            torrentUrlHD = torrentResult?.url1080p ?: existingLink.torrentUrlHD,
                            torrentUrl4K = torrentResult?.url2160p ?: existingLink.torrentUrl4K,
                            torrentSiteName = torrentResult?.sourceSite ?: existingLink.torrentSiteName
                        )
                    } else {
                        LinkEntity(
                            id = UUID.randomUUID().toString(),
                            stashDbId = scene.id,
                            title = scene.title,
                            coverImage = scene.coverUrl ?: "",
                            actorIds = actorIds.distinct(),
                            studioIds = studioIds.distinct(),
                            assignedDate = parsedDate,
                            magnet = torrentResult?.magnet1080p,
                            magnet4K = torrentResult?.magnet2160p,
                            torrentUrlHD = torrentResult?.url1080p,
                            torrentUrl4K = torrentResult?.url2160p,
                            torrentSiteName = torrentResult?.sourceSite
                        )
                    }

                    allExistingLinks.add(linkToSave)
                    linksToSaveBatch.add(linkToSave)
                    totalSaved++
                }

                // Batch insert new actors, studios, and links atomically
                if (newActorsToInsert.isNotEmpty()) repository.insertActors(newActorsToInsert)
                if (newStudiosToInsert.isNotEmpty()) repository.insertStudios(newStudiosToInsert)
                if (linksToSaveBatch.isNotEmpty()) repository.insertLinks(linksToSaveBatch)

                _stashSelectedSceneIds.value = emptySet()
                withContext(Dispatchers.Main) {
                    onComplete(totalSaved, torrentsCount)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("MainViewModel", "Batch save error", e)
            } finally {
                _isSavingWithProgress.value = false
            }
        }
    }

    fun saveSelectedStashScenesWithProgress(
        fetchTorrents: Boolean = true,
        onComplete: (Int) -> Unit
    ) = saveSelectedStashScenesWithProgress(fetchTorrents) { savedCount, _ -> onComplete(savedCount) }

    fun saveSelectedStashScenes(onComplete: (Int) -> Unit) =
        saveSelectedStashScenesWithProgress { savedCount, _ -> onComplete(savedCount) }

    // Search and Sort
    val searchQuery = MutableStateFlow("")
    val sortMode = MutableStateFlow(SortMode.CARD_NEWEST)
    val bookmarkedIds = MutableStateFlow<Set<String>>(emptySet())

    fun toggleBookmark(id: String) {
        val curr = bookmarkedIds.value
        bookmarkedIds.value = if (curr.contains(id)) curr - id else curr + id
    }

    // Data Flows
    val allLinks = repository.allLinks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allActors = repository.allActors.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allStudios = repository.allStudios.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isInitialDataLoaded: StateFlow<Boolean> = repository.allLinks
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val actorSceneCounts: StateFlow<Map<String, Int>> = allLinks
        .map { links ->
            withContext(Dispatchers.Default) {
                links.flatMap { it.actorIds }.groupingBy { it }.eachCount()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val studioSceneCounts: StateFlow<Map<String, Int>> = allLinks
        .map { links ->
            withContext(Dispatchers.Default) {
                links.flatMap { it.studioIds }.groupingBy { it }.eachCount()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val settings: StateFlow<SettingsEntity> = repository.settings
        .map { it ?: SettingsEntity() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsEntity())

    init {
        // Monitor settings to lock app on startup if passcode protection is active and reactively apply StashDB studio filter
        viewModelScope.launch {
            settings.collect { currentSettings ->
                if (currentSettings.isPasscodeEnabled && currentSettings.passcodeHash.isNotBlank()) {
                    if (!_hasUnlockedThisSession) {
                        _isAppLocked.value = true
                    }
                } else if (!currentSettings.isPasscodeEnabled) {
                    _isAppLocked.value = false
                }
                refreshStashScenesFilter(currentSettings)
            }
        }
    }

    // High performance filtered & sorted scenes
    val filteredLinks: StateFlow<List<LinkEntity>> = combine(
        combine(allLinks, searchQuery, allActors, allStudios) { links, query, actors, studios ->
            if (query.isNotBlank()) {
                val q = query.trim().lowercase()
                val matchingActorIds = actors.filter { it.name.lowercase().contains(q) }.map { it.id }.toSet()
                val matchingStudioIds = studios.filter { it.name.lowercase().contains(q) }.map { it.id }.toSet()
                links.filter { link ->
                    link.title.lowercase().contains(q) ||
                    link.actorIds.any { it in matchingActorIds } ||
                    link.studioIds.any { it in matchingStudioIds }
                }
            } else links
        },
        sortMode
    ) { searchedLinks, sort ->
        withContext(Dispatchers.Default) {
            when (sort) {
                SortMode.CARD_NEWEST, SortMode.NEWEST -> searchedLinks.sortedByDescending { it.assignedDate ?: it.createdAt }
                SortMode.CARD_OLDEST, SortMode.OLDEST -> searchedLinks.sortedBy { it.assignedDate ?: it.createdAt }
                SortMode.RECENTLY_ADDED -> searchedLinks.sortedByDescending { it.createdAt }
                SortMode.OLDEST_ADDED -> searchedLinks.sortedBy { it.createdAt }
                SortMode.TITLE_AZ -> searchedLinks.sortedBy { it.title.lowercase() }
                SortMode.TITLE_ZA -> searchedLinks.sortedByDescending { it.title.lowercase() }
            }
        }
    }.distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // CRUD operations
    fun saveLink(link: LinkEntity) {
        viewModelScope.launch { repository.insertLink(link) }
    }

    fun deleteLink(id: String) {
        viewModelScope.launch { repository.deleteLinkById(id) }
    }

    fun saveActor(actor: ActorEntity) {
        viewModelScope.launch { repository.insertActor(actor) }
    }

    fun deleteActorWithCascade(actorId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val links = repository.allLinks.first()
                val linksToUpdate = mutableListOf<LinkEntity>()
                links.forEach { link ->
                    if (link.actorIds.contains(actorId)) {
                        val updatedActors = link.actorIds.filter { it != actorId }
                        if (updatedActors.isEmpty()) {
                            repository.deleteLinkById(link.id)
                        } else {
                            linksToUpdate.add(link.copy(actorIds = updatedActors))
                        }
                    }
                }
                if (linksToUpdate.isNotEmpty()) {
                    repository.insertLinks(linksToUpdate)
                }
                repository.deleteActorById(actorId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                repository.deleteActorById(actorId)
            }
        }
    }

    fun saveStudio(studio: StudioEntity) {
        viewModelScope.launch { repository.insertStudio(studio) }
    }

    fun deleteStudioWithCascade(studioId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val links = repository.allLinks.first()
                links.forEach { link ->
                    if (link.studioIds.contains(studioId)) {
                        repository.deleteLinkById(link.id)
                    }
                }
                repository.deleteStudioById(studioId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                repository.deleteStudioById(studioId)
            }
        }
    }

    fun updateSettings(newSettings: SettingsEntity) {
        val prev = settings.value
        if (prev == null ||
            prev.realDebridApiKey != newSettings.realDebridApiKey ||
            prev.torboxApiKey != newSettings.torboxApiKey ||
            prev.debridOrder != newSettings.debridOrder
        ) {
            com.example.network.debrid.DebridManager.clearAllCaches()
        }
        viewModelScope.launch { repository.updateSettings(newSettings) }
    }

    // Complete JSON Export preserving all essential fields
    suspend fun exportDataJson(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        val linksArr = JSONArray()
        repository.allLinks.first().forEach { l ->
            val obj = JSONObject()
            obj.put("id", l.id)
            obj.put("stashDbId", l.stashDbId ?: JSONObject.NULL)
            obj.put("title", l.title)
            obj.put("coverImage", l.coverImage)
            obj.put("urlHD", l.urlHD ?: JSONObject.NULL)
            obj.put("url4K", l.url4K ?: JSONObject.NULL)
            obj.put("magnet", l.magnet ?: JSONObject.NULL)
            obj.put("magnet4K", l.magnet4K ?: JSONObject.NULL)
            obj.put("torrentUrlHD", l.torrentUrlHD ?: JSONObject.NULL)
            obj.put("torrentUrl4K", l.torrentUrl4K ?: JSONObject.NULL)
            obj.put("torrentSiteName", l.torrentSiteName ?: JSONObject.NULL)
            obj.put("aspectRatio", l.aspectRatio)
            obj.put("assignedDate", l.assignedDate ?: JSONObject.NULL)
            obj.put("actorIds", JSONArray(l.actorIds))
            obj.put("studioIds", JSONArray(l.studioIds))
            linksArr.put(obj)
        }
        root.put("links", linksArr)

        val actorsArr = JSONArray()
        repository.allActors.first().forEach { a ->
            val obj = JSONObject()
            obj.put("id", a.id)
            obj.put("stashDbId", a.stashDbId ?: JSONObject.NULL)
            obj.put("name", a.name)
            obj.put("imageUrl", a.imageUrl)
            obj.put("originalImageUrl", a.originalImageUrl ?: JSONObject.NULL)
            obj.put("imagePositionX", a.imagePositionX.toDouble())
            obj.put("imagePositionY", a.imagePositionY.toDouble())
            obj.put("imageZoom", a.imageZoom.toDouble())
            actorsArr.put(obj)
        }
        root.put("actors", actorsArr)

        val studiosArr = JSONArray()
        repository.allStudios.first().forEach { s ->
            val obj = JSONObject()
            obj.put("id", s.id)
            obj.put("stashDbId", s.stashDbId ?: JSONObject.NULL)
            obj.put("name", s.name)
            obj.put("logoUrl", s.logoUrl ?: JSONObject.NULL)
            obj.put("logoBgColor", s.logoBgColor ?: JSONObject.NULL)
            obj.put("imageUrl", s.imageUrl ?: JSONObject.NULL)
            obj.put("originalImageUrl", s.originalImageUrl ?: JSONObject.NULL)
            studiosArr.put(obj)
        }
        root.put("studios", studiosArr)

        root.toString(2)
    }

    suspend fun importJsonData(jsonString: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString.trim())

            // 1. Import actors if present in backup
            val actorsArr = root.optJSONArray("actors")
            if (actorsArr != null && actorsArr.length() > 0) {
                val importedActors = mutableListOf<ActorEntity>()
                for (i in 0 until actorsArr.length()) {
                    val obj = actorsArr.getJSONObject(i)
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                    val stashDbId = if (obj.isNull("stashDbId")) null else obj.optString("stashDbId")
                    val name = obj.optString("name", "Unknown Actor")
                    val imageUrl = obj.optString("imageUrl", "")
                    val originalImageUrl = if (obj.isNull("originalImageUrl")) null else obj.optString("originalImageUrl")
                    val imagePositionX = obj.optDouble("imagePositionX", 50.0).toFloat().coerceIn(0f, 100f)
                    val imagePositionY = obj.optDouble("imagePositionY", 0.0).toFloat().coerceIn(0f, 100f)
                    val imageZoom = obj.optDouble("imageZoom", 1.0).toFloat().coerceIn(1.0f, 3.0f)
                    importedActors.add(
                        ActorEntity(
                            id = id,
                            stashDbId = stashDbId,
                            name = name,
                            imageUrl = imageUrl,
                            originalImageUrl = originalImageUrl,
                            imagePositionX = imagePositionX,
                            imagePositionY = imagePositionY,
                            imageZoom = imageZoom
                        )
                    )
                }
                if (importedActors.isNotEmpty()) {
                    repository.insertActors(importedActors)
                }
            }

            // 2. Import studios if present in backup
            val studiosArr = root.optJSONArray("studios")
            if (studiosArr != null && studiosArr.length() > 0) {
                val importedStudios = mutableListOf<StudioEntity>()
                for (i in 0 until studiosArr.length()) {
                    val obj = studiosArr.getJSONObject(i)
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                    val stashDbId = if (obj.isNull("stashDbId")) null else obj.optString("stashDbId")
                    val name = obj.optString("name", "Unknown Studio")
                    val logoUrl = if (obj.isNull("logoUrl")) null else obj.optString("logoUrl")
                    val logoBgColor = if (obj.isNull("logoBgColor")) null else obj.optString("logoBgColor").takeIf { it.isNotBlank() }
                    val imageUrl = if (obj.isNull("imageUrl")) null else obj.optString("imageUrl")
                    val originalImageUrl = if (obj.isNull("originalImageUrl")) null else obj.optString("originalImageUrl").takeIf { it.isNotBlank() }
                    importedStudios.add(
                        StudioEntity(
                            id = id,
                            stashDbId = stashDbId,
                            name = name,
                            logoUrl = logoUrl,
                            logoBgColor = logoBgColor,
                            imageUrl = imageUrl,
                            originalImageUrl = originalImageUrl
                        )
                    )
                }
                if (importedStudios.isNotEmpty()) {
                    repository.insertStudios(importedStudios)
                }
            }

            // 3. Import links
            val linksArr = root.optJSONArray("links") ?: JSONArray()
            val importedList = mutableListOf<LinkEntity>()
            for (i in 0 until linksArr.length()) {
                val obj = linksArr.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val stashDbId = if (obj.isNull("stashDbId")) null else obj.optString("stashDbId")
                val title = obj.optString("title", "Imported Scene")
                val coverImage = obj.optString("coverImage", "")
                val urlHD = if (obj.isNull("urlHD")) null else obj.optString("urlHD")
                val url4K = if (obj.isNull("url4K")) null else obj.optString("url4K")
                val magnet = if (obj.isNull("magnet")) null else obj.optString("magnet")
                val magnet4K = if (obj.isNull("magnet4K")) null else obj.optString("magnet4K")
                val torrentUrlHD = if (obj.isNull("torrentUrlHD")) null else obj.optString("torrentUrlHD")
                val torrentUrl4K = if (obj.isNull("torrentUrl4K")) null else obj.optString("torrentUrl4K")
                val torrentSiteName = if (obj.isNull("torrentSiteName")) null else obj.optString("torrentSiteName")
                val aspectRatio = obj.optString("aspectRatio", "16:9")
                val assignedDate = if (obj.isNull("assignedDate")) null else obj.optLong("assignedDate")

                val actorIds = mutableListOf<String>()
                obj.optJSONArray("actorIds")?.let { arr ->
                    for (j in 0 until arr.length()) actorIds.add(arr.getString(j))
                }

                val studioIds = mutableListOf<String>()
                obj.optJSONArray("studioIds")?.let { arr ->
                    for (j in 0 until arr.length()) studioIds.add(arr.getString(j))
                }

                importedList.add(
                    LinkEntity(
                        id = id,
                        stashDbId = stashDbId,
                        title = title,
                        coverImage = coverImage,
                        urlHD = urlHD,
                        url4K = url4K,
                        magnet = magnet,
                        magnet4K = magnet4K,
                        torrentUrlHD = torrentUrlHD,
                        torrentUrl4K = torrentUrl4K,
                        torrentSiteName = torrentSiteName,
                        aspectRatio = aspectRatio,
                        assignedDate = assignedDate,
                        actorIds = actorIds,
                        studioIds = studioIds
                    )
                )
            }
            if (importedList.isNotEmpty()) {
                repository.insertLinks(importedList)
            }
            Result.success(importedList.size)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    fun importSampleDataset(onDone: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertActors(com.example.data.util.SampleTestDataset.sampleActors)
            repository.insertStudios(com.example.data.util.SampleTestDataset.sampleStudios)
            repository.insertLinks(com.example.data.util.SampleTestDataset.sampleScenes)
            withContext(Dispatchers.Main) {
                onDone(com.example.data.util.SampleTestDataset.sampleScenes.size)
            }
        }
    }

    fun clearSampleDataset(onDone: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val sceneIds = com.example.data.util.SampleTestDataset.sampleScenes.map { it.id }
            var count = 0
            sceneIds.forEach { id ->
                repository.deleteLinkById(id)
                count++
            }
            withContext(Dispatchers.Main) {
                onDone(count)
            }
        }
    }

    // Torrent Magnet Fetching
    private val _isFetchingMagnet = MutableStateFlow(false)
    val isFetchingMagnet: StateFlow<Boolean> = _isFetchingMagnet.asStateFlow()

    private var fetchMagnetJob: Job? = null

    fun cancelMagnetFetch() {
        fetchMagnetJob?.cancel()
        _isFetchingMagnet.value = false
    }

    suspend fun fetchMagnetInternal(
        title: String,
        selectedActors: List<ActorEntity>,
        selectedStudios: List<StudioEntity>,
        assignedDate: Long?,
        urlHD: String = "",
        url4K: String = ""
    ): com.example.network.torrent.TorrentSearchResult? {
        val cleanTitle = com.example.network.torrent.QueryBuilder.cleanTitle(title)
        val javMatch = com.example.network.torrent.QueryBuilder.extractJavMatch(title)

        val directExtractCandidate = listOf(title, urlHD, url4K)
            .firstOrNull { it.contains("xxxclub.to/torrents/details/", ignoreCase = true) }

        if (directExtractCandidate == null &&
            selectedActors.isEmpty() &&
            selectedStudios.isEmpty() &&
            assignedDate == null &&
            cleanTitle.isBlank() &&
            javMatch == null
        ) return null

        // Step 1: Direct extract shortcut
        if (directExtractCandidate != null) {
            val directUrlMatch = Regex("https?://[^\\s<>\"']*(?:xxxclub\\.to/torrents/details/\\d+[^\\s<>\"']*)")
                .find(directExtractCandidate)?.value ?: directExtractCandidate
            val directRes = com.example.network.torrent.TorrentScraper.directExtract(directUrlMatch, getApplication())
            if (directRes != null && directRes.hasResult) return directRes
        }

        // Step 2: JAV mode
        if (javMatch != null) {
            val sukebeiRes = com.example.network.torrent.TorrentScraper.searchSukebei(javMatch, getApplication())
            return if (sukebeiRes.hasResult) sukebeiRes else null
        }

        // Step 3: XXXClub smart search (western)
        val actorNames = selectedActors.map { it.name }
        val studioNames = selectedStudios.map { it.name }
        val dateStr = com.example.network.torrent.DatePatterns.formatDateStr(assignedDate)
        val datePatterns = com.example.network.torrent.DatePatterns.generatePatterns(
            dateStr = dateStr,
            targetDateMs = assignedDate
        )
        val queries = com.example.network.torrent.QueryBuilder.buildCascadeQueries(
            title = title,
            actors = actorNames,
            studios = studioNames,
            dateStr = dateStr,
            extraSearchText = "$urlHD $url4K".trim()
        )

        val searchRes = com.example.network.torrent.TorrentScraper.executeQueriesLoop(
            queries = queries,
            datePatterns = datePatterns,
            studios = studioNames,
            actors = actorNames,
            context = getApplication()
        )

        return if (searchRes != null && searchRes.hasResult) searchRes else null
    }

    fun fetchMagnet(
        title: String,
        selectedActors: List<ActorEntity>,
        selectedStudios: List<StudioEntity>,
        assignedDate: Long?,
        urlHD: String,
        url4K: String,
        onSuccess: (com.example.network.torrent.TorrentSearchResult) -> Unit,
        onNoResult: () -> Unit,
        onError: (String) -> Unit
    ) {
        fetchMagnetJob?.cancel()

        val cleanTitle = com.example.network.torrent.QueryBuilder.cleanTitle(title)
        val javMatch = com.example.network.torrent.QueryBuilder.extractJavMatch(title)
        val directExtractCandidate = listOf(title, urlHD, url4K)
            .firstOrNull { it.contains("xxxclub.to/torrents/details/", ignoreCase = true) }

        if (directExtractCandidate == null &&
            selectedActors.isEmpty() &&
            selectedStudios.isEmpty() &&
            assignedDate == null &&
            cleanTitle.isBlank() &&
            javMatch == null
        ) {
            onError("Please select a studio, actor, date, or enter a title to search")
            return
        }

        fetchMagnetJob = viewModelScope.launch(Dispatchers.IO) {
            _isFetchingMagnet.value = true
            try {
                val res = fetchMagnetInternal(
                    title = title,
                    selectedActors = selectedActors,
                    selectedStudios = selectedStudios,
                    assignedDate = assignedDate,
                    urlHD = urlHD,
                    url4K = url4K
                )
                withContext(Dispatchers.Main) {
                    if (res != null && res.hasResult) {
                        onSuccess(res)
                    } else {
                        onNoResult()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) {
                    onError("Torrent search failed: ${e.message}")
                }
            } finally {
                _isFetchingMagnet.value = false
            }
        }
    }

    // Onlyhaven State & Pagination
    private val _onlyhavenCreators = MutableStateFlow<List<OnlyhavenCreator>>(emptyList())
    val onlyhavenCreators: StateFlow<List<OnlyhavenCreator>> = _onlyhavenCreators.asStateFlow()

    private val _onlyhavenSearchQuery = MutableStateFlow("")
    val onlyhavenSearchQuery: StateFlow<String> = _onlyhavenSearchQuery.asStateFlow()

    private val _isOnlyhavenLoading = MutableStateFlow(false)
    val isOnlyhavenLoading: StateFlow<Boolean> = _isOnlyhavenLoading.asStateFlow()

    private val _isOnlyhavenLoadingMore = MutableStateFlow(false)
    val isOnlyhavenLoadingMore: StateFlow<Boolean> = _isOnlyhavenLoadingMore.asStateFlow()

    private val _onlyhavenCanLoadMore = MutableStateFlow(true)
    val onlyhavenCanLoadMore: StateFlow<Boolean> = _onlyhavenCanLoadMore.asStateFlow()

    private val _onlyhavenCurrentPage = MutableStateFlow(1)
    val onlyhavenCurrentPage: StateFlow<Int> = _onlyhavenCurrentPage.asStateFlow()

    private val _onlyhavenErrorMessage = MutableStateFlow<String?>(null)
    val onlyhavenErrorMessage: StateFlow<String?> = _onlyhavenErrorMessage.asStateFlow()

    private val _onlyhavenSortMode = MutableStateFlow(OnlyhavenSortMode.POPULARITY)
    val onlyhavenSortMode: StateFlow<OnlyhavenSortMode> = _onlyhavenSortMode.asStateFlow()

    private var onlyhavenSearchJob: Job? = null

    fun setOnlyhavenSortMode(sortMode: OnlyhavenSortMode) {
        if (_onlyhavenSortMode.value != sortMode) {
            _onlyhavenSortMode.value = sortMode
            _onlyhavenCreators.value = emptyList()
            loadOnlyhavenCreators(
                query = _onlyhavenSearchQuery.value,
                sort = sortMode.apiValue,
                page = 1,
                append = false
            )
        }
    }

    fun setOnlyhavenSearchQuery(query: String) {
        _onlyhavenSearchQuery.value = query
        loadOnlyhavenCreators(query = query, page = 1, append = false, isUserTyping = true)
    }

    fun loadOnlyhavenCreators(
        query: String = _onlyhavenSearchQuery.value,
        sort: String = _onlyhavenSortMode.value.apiValue,
        page: Int = 1,
        append: Boolean = false,
        isUserTyping: Boolean = false
    ) {
        if (!append) {
            onlyhavenSearchJob?.cancel()
        }
        onlyhavenSearchJob = viewModelScope.launch(Dispatchers.IO) {
            if (isUserTyping && query.isNotBlank()) {
                delay(350L) // Debounce rapid keystrokes
            }
            if (append) {
                _isOnlyhavenLoadingMore.value = true
            } else {
                _isOnlyhavenLoading.value = true
                _onlyhavenErrorMessage.value = null
            }

            try {
                val results = OnlyhavenScraper.getCreators(page = page, query = query, sort = sort)
                if (append) {
                    val existingIds = _onlyhavenCreators.value.map { it.id }.toSet()
                    val newUnique = results.filter { it.id !in existingIds }
                    _onlyhavenCreators.value = _onlyhavenCreators.value + newUnique
                } else {
                    _onlyhavenCreators.value = results
                }
                _onlyhavenCurrentPage.value = page
                _onlyhavenCanLoadMore.value = results.isNotEmpty()
                _onlyhavenErrorMessage.value = null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("MainViewModel", "Error fetching Onlyhaven creators", e)
                if (!append) {
                    _onlyhavenErrorMessage.value = e.message ?: "Failed to connect to Onlyhaven"
                }
            } finally {
                _isOnlyhavenLoading.value = false
                _isOnlyhavenLoadingMore.value = false
            }
        }
    }

    // Saved Onlyhaven Creators

    private fun loadSavedOnlyhavenCreators() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("onlyhaven_saved_creators_prefs", Context.MODE_PRIVATE)
                val jsonStr = prefs.getString("saved_creators_json", null)
                if (!jsonStr.isNullOrBlank()) {
                    val jsonArray = JSONArray(jsonStr)
                    val list = mutableListOf<OnlyhavenCreator>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        list.add(
                            OnlyhavenCreator(
                                id = obj.optString("id"),
                                name = obj.optString("name"),
                                displayName = obj.optString("displayName"),
                                service = obj.optString("service"),
                                profileUrl = obj.optString("profileUrl"),
                                avatarUrl = obj.optString("avatarUrl"),
                                headerUrl = obj.optString("headerUrl"),
                                postCount = obj.optInt("postCount", 0),
                                videoCount = obj.optInt("videoCount", 0),
                                imageCount = obj.optInt("imageCount", 0),
                                savedAt = obj.optLong("savedAt", System.currentTimeMillis() - i * 1000L)
                            )
                        )
                    }
                    _savedOnlyhavenCreators.value = list
                    _savedCreatorKeys.value = list.map { "${it.service}_${it.id}" }.toSet()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error loading saved creators", e)
            }
        }
    }

    private fun persistSavedOnlyhavenCreators(list: List<OnlyhavenCreator>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("onlyhaven_saved_creators_prefs", Context.MODE_PRIVATE)
                val jsonArray = JSONArray()
                for (c in list) {
                    val obj = JSONObject()
                    obj.put("id", c.id)
                    obj.put("name", c.name)
                    obj.put("displayName", c.displayName)
                    obj.put("service", c.service)
                    obj.put("profileUrl", c.profileUrl)
                    obj.put("avatarUrl", c.avatarUrl)
                    obj.put("headerUrl", c.headerUrl)
                    obj.put("postCount", c.postCount)
                    obj.put("videoCount", c.videoCount)
                    obj.put("imageCount", c.imageCount)
                    obj.put("savedAt", c.savedAt)
                    jsonArray.put(obj)
                }
                prefs.edit().putString("saved_creators_json", jsonArray.toString()).apply()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error saving creators", e)
            }
        }
    }

    fun toggleSaveOnlyhavenCreator(creator: OnlyhavenCreator) {
        val key = "${creator.service}_${creator.id}"
        val current = _savedOnlyhavenCreators.value
        val exists = current.any { "${it.service}_${it.id}" == key || (it.id == creator.id && it.service == creator.service) }
        val updated = if (exists) {
            current.filterNot { "${it.service}_${it.id}" == key || (it.id == creator.id && it.service == creator.service) }
        } else {
            val entry = creator.copy(savedAt = System.currentTimeMillis())
            listOf(entry) + current
        }
        _savedOnlyhavenCreators.value = updated
        _savedCreatorKeys.value = updated.map { "${it.service}_${it.id}" }.toSet()
        persistSavedOnlyhavenCreators(updated)
    }

    fun isCreatorSaved(creator: OnlyhavenCreator): Boolean {
        val key = "${creator.service}_${creator.id}"
        return _savedCreatorKeys.value.contains(key) || _savedOnlyhavenCreators.value.any { it.id == creator.id && it.service == creator.service }
    }

    private fun loadSavedOnlyhavenPosts() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("onlyhaven_saved_posts_prefs", Context.MODE_PRIVATE)
                val jsonStr = prefs.getString("saved_posts_json", null)
                if (!jsonStr.isNullOrBlank()) {
                    val jsonArray = JSONArray(jsonStr)
                    val list = mutableListOf<OnlyhavenSavedPost>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val mediaObj = obj.optJSONObject("mediaItem") ?: continue
                        val mediaItem = jsonToMediaItem(mediaObj)
                        list.add(
                            OnlyhavenSavedPost(
                                id = obj.optString("id", mediaItem.id),
                                mediaItem = mediaItem,
                                creatorId = obj.optString("creatorId"),
                                creatorName = obj.optString("creatorName"),
                                creatorDisplayName = obj.optString("creatorDisplayName"),
                                creatorService = obj.optString("creatorService"),
                                creatorAvatarUrl = obj.optString("creatorAvatarUrl"),
                                savedAt = obj.optLong("savedAt", System.currentTimeMillis())
                            )
                        )
                    }
                    _savedOnlyhavenPosts.value = list
                    _savedPostKeys.value = list.flatMap { post ->
                        val s = post.creatorService
                        val pid = post.mediaItem.postId.ifEmpty { post.mediaItem.id }
                        val id = post.mediaItem.id
                        listOfNotNull(
                            if (s.isNotEmpty()) "${s}_$pid" else null,
                            pid,
                            id,
                            if (s.isNotEmpty()) "${s}_$id" else null,
                            post.id
                        )
                    }.toSet()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error loading saved posts", e)
            }
        }
    }

    private fun persistSavedOnlyhavenPosts(list: List<OnlyhavenSavedPost>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("onlyhaven_saved_posts_prefs", Context.MODE_PRIVATE)
                val jsonArray = JSONArray()
                for (p in list) {
                    val obj = JSONObject()
                    obj.put("id", p.id)
                    obj.put("creatorId", p.creatorId)
                    obj.put("creatorName", p.creatorName)
                    obj.put("creatorDisplayName", p.creatorDisplayName)
                    obj.put("creatorService", p.creatorService)
                    obj.put("creatorAvatarUrl", p.creatorAvatarUrl)
                    obj.put("savedAt", p.savedAt)
                    obj.put("mediaItem", mediaItemToJson(p.mediaItem))
                    jsonArray.put(obj)
                }
                prefs.edit().putString("saved_posts_json", jsonArray.toString()).apply()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error saving posts", e)
            }
        }
    }

    fun toggleSaveOnlyhavenPost(mediaItem: OnlyhavenMediaItem, creator: OnlyhavenCreator? = null) {
        val service = creator?.service ?: ""
        val postId = mediaItem.postId.ifEmpty { mediaItem.id }
        val targetKey = if (service.isNotEmpty()) "${service}_$postId" else postId

        val current = _savedOnlyhavenPosts.value
        val exists = current.any {
            val s = it.creatorService
            val pid = it.mediaItem.postId.ifEmpty { it.mediaItem.id }
            val k = if (s.isNotEmpty()) "${s}_$pid" else pid
            k == targetKey || pid == postId || it.id == targetKey || it.id == postId
        }

        val updated = if (exists) {
            current.filterNot {
                val s = it.creatorService
                val pid = it.mediaItem.postId.ifEmpty { it.mediaItem.id }
                val k = if (s.isNotEmpty()) "${s}_$pid" else pid
                k == targetKey || pid == postId || it.id == targetKey || it.id == postId
            }
        } else {
            val newEntry = OnlyhavenSavedPost(
                id = targetKey,
                mediaItem = mediaItem,
                creatorId = creator?.id ?: "",
                creatorName = creator?.name ?: "",
                creatorDisplayName = creator?.displayName ?: creator?.name ?: "",
                creatorService = creator?.service ?: "",
                creatorAvatarUrl = creator?.avatarUrl ?: "",
                savedAt = System.currentTimeMillis()
            )
            listOf(newEntry) + current
        }

        _savedOnlyhavenPosts.value = updated
        _savedPostKeys.value = updated.flatMap { post ->
            val s = post.creatorService
            val pid = post.mediaItem.postId.ifEmpty { post.mediaItem.id }
            val id = post.mediaItem.id
            listOfNotNull(
                if (s.isNotEmpty()) "${s}_$pid" else null,
                pid,
                id,
                if (s.isNotEmpty()) "${s}_$id" else null,
                post.id
            )
        }.toSet()
        persistSavedOnlyhavenPosts(updated)
    }

    fun isPostSaved(mediaItem: OnlyhavenMediaItem, service: String = ""): Boolean {
        val postId = mediaItem.postId.ifEmpty { mediaItem.id }
        val key = if (service.isNotEmpty()) "${service}_$postId" else postId
        val idKey = if (service.isNotEmpty()) "${service}_${mediaItem.id}" else mediaItem.id
        return _savedPostKeys.value.contains(key) ||
                _savedPostKeys.value.contains(postId) ||
                _savedPostKeys.value.contains(idKey) ||
                _savedPostKeys.value.contains(mediaItem.id) ||
                _savedOnlyhavenPosts.value.any {
                    val s = it.creatorService
                    val pid = it.mediaItem.postId.ifEmpty { it.mediaItem.id }
                    (if (s.isNotEmpty()) "${s}_$pid" else pid) == key || pid == postId || it.mediaItem.id == mediaItem.id || it.id == key
                }
    }

    private fun mediaItemToJson(item: OnlyhavenMediaItem): JSONObject {
        val obj = JSONObject()
        obj.put("id", item.id)
        obj.put("sha256", item.sha256)
        obj.put("kind", item.kind)
        obj.put("mimeType", item.mimeType)
        obj.put("originalFilename", item.originalFilename)
        obj.put("width", item.width)
        obj.put("height", item.height)
        if (item.durationMs != null) obj.put("durationMs", item.durationMs)
        obj.put("thumbnailUrl", item.thumbnailUrl)
        obj.put("originalUrl", item.originalUrl)
        if (item.postCaption != null) obj.put("postCaption", item.postCaption)
        obj.put("postId", item.postId)
        if (item.postAttachments.isNotEmpty()) {
            val arr = JSONArray()
            for (att in item.postAttachments) {
                val attObj = JSONObject()
                attObj.put("id", att.id)
                attObj.put("sha256", att.sha256)
                attObj.put("kind", att.kind)
                attObj.put("mimeType", att.mimeType)
                attObj.put("originalFilename", att.originalFilename)
                attObj.put("width", att.width)
                attObj.put("height", att.height)
                if (att.durationMs != null) attObj.put("durationMs", att.durationMs)
                attObj.put("thumbnailUrl", att.thumbnailUrl)
                attObj.put("originalUrl", att.originalUrl)
                arr.put(attObj)
            }
            obj.put("postAttachments", arr)
        }
        return obj
    }

    private fun jsonToMediaItem(obj: JSONObject): OnlyhavenMediaItem {
        val attList = mutableListOf<OnlyhavenMediaItem>()
        val arr = obj.optJSONArray("postAttachments")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                attList.add(
                    OnlyhavenMediaItem(
                        id = a.optString("id"),
                        sha256 = a.optString("sha256"),
                        kind = a.optString("kind", "image"),
                        mimeType = a.optString("mimeType", "image/jpeg"),
                        originalFilename = a.optString("originalFilename"),
                        width = a.optInt("width", 0),
                        height = a.optInt("height", 0),
                        durationMs = if (a.has("durationMs") && !a.isNull("durationMs")) a.optLong("durationMs") else null,
                        thumbnailUrl = a.optString("thumbnailUrl"),
                        originalUrl = a.optString("originalUrl")
                    )
                )
            }
        }
        return OnlyhavenMediaItem(
            id = obj.optString("id"),
            sha256 = obj.optString("sha256"),
            kind = obj.optString("kind", "image"),
            mimeType = obj.optString("mimeType", "image/jpeg"),
            originalFilename = obj.optString("originalFilename"),
            width = obj.optInt("width", 0),
            height = obj.optInt("height", 0),
            durationMs = if (obj.has("durationMs") && !obj.isNull("durationMs")) obj.optLong("durationMs") else null,
            thumbnailUrl = obj.optString("thumbnailUrl"),
            originalUrl = obj.optString("originalUrl"),
            postCaption = if (obj.has("postCaption") && !obj.isNull("postCaption")) obj.optString("postCaption") else null,
            postId = obj.optString("postId"),
            postAttachments = attList
        )
    }

    // ==========================================
    // KANIME / HSTREAM STATE & ACTIONS
    // ==========================================
    private val _kanimeFeed = MutableStateFlow<List<com.example.network.KstreamMediaItem>>(emptyList())
    val kanimeFeed: StateFlow<List<com.example.network.KstreamMediaItem>> = _kanimeFeed.asStateFlow()

    private val _kanimeSortOrder = MutableStateFlow(com.example.network.KanimeSortOrder.RECENTLY_UPLOADED)
    val kanimeSortOrder: StateFlow<com.example.network.KanimeSortOrder> = _kanimeSortOrder.asStateFlow()

    private val _kanimeSearchQuery = MutableStateFlow("")
    val kanimeSearchQuery: StateFlow<String> = _kanimeSearchQuery.asStateFlow()

    private val _isKanimeLoading = MutableStateFlow(false)
    val isKanimeLoading: StateFlow<Boolean> = _isKanimeLoading.asStateFlow()

    private val _isKanimeLoadingMore = MutableStateFlow(false)
    val isKanimeLoadingMore: StateFlow<Boolean> = _isKanimeLoadingMore.asStateFlow()

    private val _kanimeError = MutableStateFlow<String?>(null)
    val kanimeError: StateFlow<String?> = _kanimeError.asStateFlow()

    private val _kanimeCurrentPage = MutableStateFlow(1)

    private val _kanimeCanLoadMore = MutableStateFlow(true)
    val kanimeCanLoadMore: StateFlow<Boolean> = _kanimeCanLoadMore.asStateFlow()

    private var kanimeJob: Job? = null
    private var kanimeLoadMoreJob: Job? = null
    private var kanimeStreamJob: Job? = null

    fun setKanimeSortOrder(order: com.example.network.KanimeSortOrder) {
        if (_kanimeSortOrder.value == order) return
        _kanimeSortOrder.value = order
        loadKanimeFeed(forceRefresh = true)
    }

    fun setKanimeSearchQuery(query: String) {
        val clean = query.trim()
        if (_kanimeSearchQuery.value == clean) return
        _kanimeSearchQuery.value = clean
        loadKanimeFeed(forceRefresh = true)
    }

    fun loadKanimeFeed(forceRefresh: Boolean = false) {
        if (!forceRefresh && _kanimeFeed.value.isNotEmpty()) return

        kanimeJob?.cancel()
        kanimeLoadMoreJob?.cancel()
        _isKanimeLoading.value = true
        _kanimeError.value = null
        _kanimeCurrentPage.value = 1
        _kanimeCanLoadMore.value = true

        val order = _kanimeSortOrder.value.apiValue
        val query = _kanimeSearchQuery.value

        kanimeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = com.example.network.kstreamScraper.getFeed(
                    page = 1,
                    query = query,
                    order = order
                )
                _kanimeFeed.value = items
                _kanimeCanLoadMore.value = items.isNotEmpty()
                _isKanimeLoading.value = false
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _kanimeError.value = e.message ?: "Failed to load Kanime feed"
                _isKanimeLoading.value = false
            }
        }
    }

    fun loadMoreKanime() {
        if (_isKanimeLoading.value || _isKanimeLoadingMore.value || !_kanimeCanLoadMore.value) return

        val nextPage = _kanimeCurrentPage.value + 1
        val order = _kanimeSortOrder.value.apiValue
        val query = _kanimeSearchQuery.value
        _isKanimeLoadingMore.value = true

        kanimeLoadMoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val newItems = com.example.network.kstreamScraper.getFeed(
                    page = nextPage,
                    query = query,
                    order = order
                )
                if (newItems.isEmpty()) {
                    _kanimeCanLoadMore.value = false
                } else {
                    val existingIds = _kanimeFeed.value.map { it.id }.toSet()
                    val filtered = newItems.filter { it.id !in existingIds }
                    _kanimeFeed.value = _kanimeFeed.value + filtered
                    _kanimeCurrentPage.value = nextPage
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("MainViewModel", "Failed to load more Kanime: ${e.message}", e)
            } finally {
                _isKanimeLoadingMore.value = false
            }
        }
    }

    fun openKanimeItem(item: com.example.network.KstreamMediaItem) {
        navigateTo(ScreenState.KanimeEpisodes(item))
    }

    fun playKanimeEpisode(episode: com.example.network.KanimeEpisodeItem) {
        kanimeStreamJob?.cancel()
        _resolvingVideoStatus.value = "Resolving stream for ${episode.title}..."
        kanimeStreamJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolved = com.example.network.kstreamScraper.resolveStream(episode.episodeUrl)
                if (resolved != null && resolved.qualities.isNotEmpty()) {
                    val streamTitle = if (resolved.title.isNotBlank()) resolved.title else episode.title
                    val cleanEpisodeQualities = normalizeAndSortQualities(resolved.qualities)
                    withContext(Dispatchers.Main) {
                        com.example.ui.components.PlayerSessionTracker.clearSession(sharedPlayerManager.getPlayer())
                    }
                    val primaryReferer = cleanEpisodeQualities.firstOrNull()?.headers?.get("Referer")
                        ?: resolved.qualities.firstOrNull()?.headers?.get("Referer")
                        ?: "https://hstream.moe/"
                    _activeVideo.value = ActiveVideoPlayback(
                        title = streamTitle,
                        qualities = cleanEpisodeQualities,
                        subtitles = resolved.subtitles,
                        headers = mapOf(
                            "Referer" to primaryReferer,
                            "User-Agent" to com.example.ui.components.PlayerFactory.DEFAULT_UA
                        ),
                        startInLandscape = true
                    )
                } else {
                    _videoResolutionError.value = "Failed to extract video stream from episode."
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _videoResolutionError.value = e.message ?: "Failed to extract video stream."
            } finally {
                _resolvingVideoStatus.value = null
            }
        }
    }

    private fun normalizeAndSortQualities(rawQualities: List<StreamQuality>): List<StreamQuality> {
        if (rawQualities.isEmpty()) return emptyList()

        val mapped = rawQualities.mapNotNull { q ->
            val tierLabel = com.example.ui.components.resolveAllowedQualityLabel(q.quality, q.url)
            if (tierLabel != null) {
                q.copy(quality = tierLabel)
            } else {
                null
            }
        }

        val candidates = if (mapped.isNotEmpty()) {
            mapped
        } else {
            val bestFallback = rawQualities.firstOrNull { it.isDefault } ?: rawQualities.first()
            listOf(bestFallback.copy(quality = "720p", isDefault = true))
        }

        val deduplicated = candidates
            .groupBy { it.quality }
            .map { (tierLabel, group) ->
                val hasDefault = group.any { it.isDefault }
                val primary = group.firstOrNull { it.isDefault } ?: group.first()
                primary.copy(quality = tierLabel, isDefault = hasDefault)
            }
            .sortedByDescending { q ->
                when (q.quality) {
                    "VR 360" -> 2880
                    "2160p" -> 2160
                    "1440p" -> 1440
                    "1080p" -> 1080
                    "720p" -> 720
                    else -> 0
                }
            }

        return if (deduplicated.any { it.isDefault }) {
            deduplicated
        } else {
            val targetDefaultIdx = deduplicated.indexOfFirst { !it.quality.contains("vr", ignoreCase = true) }
                .let { if (it != -1) it else 0 }
            deduplicated.mapIndexed { index, item ->
                if (index == targetDefaultIdx) item.copy(isDefault = true) else item
            }
        }
    }
}

