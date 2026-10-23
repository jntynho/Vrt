package com.example.network.debrid

import android.util.Log
import com.example.network.torrent.MagnetParser
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.coroutineScope

enum class DebridOrder {
    AUTO,
    REAL_DEBRID_FIRST,
    TORBOX_FIRST
}

data class CachedStream(
    val result: DebridResult.Success,
    val timestampMs: Long = System.currentTimeMillis()
)

class DebridManager(
    private val torboxKeyProvider: () -> String,
    private val realDebridKeyProvider: () -> String,
    private val debridOrderProvider: () -> DebridOrder = { DebridOrder.AUTO }
) {
    private val tag = "DebridManager"

    val torboxProvider = TorboxProvider(torboxKeyProvider)
    val realDebridProvider = RealDebridProvider(realDebridKeyProvider)

    companion object {
        private val resolvedStreamCache = ConcurrentHashMap<String, CachedStream>()
        private const val CACHE_TTL_MS = 8 * 60 * 1000L // 8 minutes TTL to prevent expired CDN tokens

        fun invalidateCache(keyOrUrl: String?) {
            if (!keyOrUrl.isNullOrBlank()) {
                val trimmed = keyOrUrl.trim()
                val normalized = trimmed.lowercase()
                val parsedHash = MagnetParser.parseHash(trimmed)?.lowercase()?.trim()
                if (parsedHash != null) {
                    resolvedStreamCache.keys.removeIf { it == parsedHash || it.startsWith("$parsedHash|") }
                    RealDebridProvider.invalidateHash(parsedHash)
                    TorboxProvider.invalidateHash(parsedHash)
                }
                resolvedStreamCache.keys.removeIf { it == normalized || it.startsWith("$normalized|") }
                // Also evict any entry whose resolved CDN streamUrl matches keyOrUrl (e.g. when called from Player Retry)
                resolvedStreamCache.entries.removeIf { entry ->
                    val matched = entry.value.result.streamUrl.equals(trimmed, ignoreCase = true)
                    if (matched) {
                        val entryHash = entry.key.substringBefore("|")
                        RealDebridProvider.invalidateHash(entryHash)
                        TorboxProvider.invalidateHash(entryHash)
                    }
                    matched
                }
            }
        }

        fun clearAllCaches() {
            resolvedStreamCache.clear()
            RealDebridProvider.clearCache()
            TorboxProvider.clearCache()
        }
    }

    fun hasAnyProviderConfigured(): Boolean {
        return torboxProvider.isConfigured() || realDebridProvider.isConfigured()
    }

    suspend fun resolve(
        queryOrMagnet: String,
        episodeHint: String? = null,
        onProgressUpdate: ((String) -> Unit)? = null
    ): DebridResult = coroutineScope {
        val torboxConfigured = torboxProvider.isConfigured()
        val rdConfigured = realDebridProvider.isConfigured()
        val order = debridOrderProvider()

        if (!torboxConfigured && !rdConfigured) {
            return@coroutineScope DebridResult.Error(
                type = DebridErrorType.InvalidKey,
                message = "No Debrid provider is configured. Please add your API key in Settings.",
                providerName = "None"
            )
        }

        val infoHash = MagnetParser.parseHash(queryOrMagnet)
        val baseKey = infoHash?.lowercase() ?: queryOrMagnet.trim().lowercase()
        val hintSuffix = episodeHint?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "|$it" } ?: ""
        val cacheKey = "$baseKey$hintSuffix"

        val cached = resolvedStreamCache[cacheKey]
        if (cached != null && (System.currentTimeMillis() - cached.timestampMs < CACHE_TTL_MS)) {
            Log.d(tag, "Returning cached Debrid stream for: $cacheKey")
            return@coroutineScope cached.result
        }

        val result = executeResolution(
            queryOrMagnet = queryOrMagnet,
            episodeHint = episodeHint,
            torboxConfigured = torboxConfigured,
            rdConfigured = rdConfigured,
            order = order,
            onProgressUpdate = onProgressUpdate
        )

        if (result is DebridResult.Success) {
            resolvedStreamCache[cacheKey] = CachedStream(result)
        }

        return@coroutineScope result
    }

    private suspend fun executeResolution(
        queryOrMagnet: String,
        episodeHint: String?,
        torboxConfigured: Boolean,
        rdConfigured: Boolean,
        order: DebridOrder,
        onProgressUpdate: ((String) -> Unit)?
    ): DebridResult = coroutineScope {
        val isTorrentOrMagnet = MagnetParser.parseHash(queryOrMagnet) != null

        // Protocol-aware routing: Direct hoster links (1fichier, rapidgator, etc.)
        // are only supported by Real-Debrid unrestrict API
        if (!isTorrentOrMagnet) {
            if (rdConfigured) {
                return@coroutineScope realDebridProvider.resolveStream(
                    magnetOrQuery = queryOrMagnet,
                    episodeHint = episodeHint,
                    allowUncachedDownload = true,
                    onProgressUpdate = onProgressUpdate
                )
            } else {
                return@coroutineScope DebridResult.Error(
                    type = DebridErrorType.Unsupported,
                    message = "Hoster links require Real-Debrid configuration.",
                    providerName = "DebridManager"
                )
            }
        }

        // Single provider configured: directly enable both instant cache resolution AND cloud downloading
        if (torboxConfigured && !rdConfigured) {
            return@coroutineScope torboxProvider.resolveStream(
                magnetOrMagnetOrQuery = queryOrMagnet,
                episodeHint = episodeHint,
                allowUncachedDownload = true,
                onProgressUpdate = onProgressUpdate
            )
        }
        if (!torboxConfigured && rdConfigured) {
            return@coroutineScope realDebridProvider.resolveStream(
                magnetOrQuery = queryOrMagnet,
                episodeHint = episodeHint,
                allowUncachedDownload = true,
                onProgressUpdate = onProgressUpdate
            )
        }

        // Both providers configured:
        // Phase 1: Fast Cache Probe across both providers (allowUncachedDownload = false) so if EITHER
        // provider already has the torrent 100% cached, it plays immediately in ~1s!
        // Phase 2: If NEITHER provider has it cached, start an Active Cloud Download (allowUncachedDownload = true)
        // on the preferred primary provider (with fallback to secondary if account/slot limits occur).
        when (order) {
            DebridOrder.REAL_DEBRID_FIRST -> {
                val rdProbe = realDebridProvider.resolveStream(
                    magnetOrQuery = queryOrMagnet,
                    episodeHint = episodeHint,
                    allowUncachedDownload = false,
                    onProgressUpdate = onProgressUpdate
                )
                if (rdProbe is DebridResult.Success) return@coroutineScope rdProbe

                val torProbe = torboxProvider.resolveStream(
                    magnetOrQuery = queryOrMagnet,
                    episodeHint = episodeHint,
                    allowUncachedDownload = false,
                    onProgressUpdate = onProgressUpdate
                )
                if (torProbe is DebridResult.Success) return@coroutineScope torProbe

                // Neither cached -> Start Active Cloud Download on Real-Debrid first, fallback to Torbox
                val rdCloud = realDebridProvider.resolveStream(
                    magnetOrQuery = queryOrMagnet,
                    episodeHint = episodeHint,
                    allowUncachedDownload = true,
                    onProgressUpdate = onProgressUpdate
                )
                if (rdCloud is DebridResult.Success || (rdCloud is DebridResult.Error && rdCloud.message.contains("Cloud download started", ignoreCase = true))) {
                    return@coroutineScope rdCloud
                }
                return@coroutineScope torboxProvider.resolveStream(
                    magnetOrQuery = queryOrMagnet,
                    episodeHint = episodeHint,
                    allowUncachedDownload = true,
                    onProgressUpdate = onProgressUpdate
                )
            }

            DebridOrder.TORBOX_FIRST -> {
                val torProbe = torboxProvider.resolveStream(
                    magnetOrQuery = queryOrMagnet,
                    episodeHint = episodeHint,
                    allowUncachedDownload = false,
                    onProgressUpdate = onProgressUpdate
                )
                if (torProbe is DebridResult.Success) return@coroutineScope torProbe

                val rdProbe = realDebridProvider.resolveStream(
                    magnetOrQuery = queryOrMagnet,
                    episodeHint = episodeHint,
                    allowUncachedDownload = false,
                    onProgressUpdate = onProgressUpdate
                )
                if (rdProbe is DebridResult.Success) return@coroutineScope rdProbe

                // Neither cached -> Start Active Cloud Download on Torbox first, fallback to Real-Debrid
                val torCloud = torboxProvider.resolveStream(
                    magnetOrQuery = queryOrMagnet,
                    episodeHint = episodeHint,
                    allowUncachedDownload = true,
                    onProgressUpdate = onProgressUpdate
                )
                if (torCloud is DebridResult.Success || (torCloud is DebridResult.Error && torCloud.message.contains("Cloud download", ignoreCase = true) || (torCloud is DebridResult.Error && torCloud.message.contains("download queue", ignoreCase = true)))) {
                    return@coroutineScope torCloud
                }
                return@coroutineScope realDebridProvider.resolveStream(
                    magnetOrQuery = queryOrMagnet,
                    episodeHint = episodeHint,
                    allowUncachedDownload = true,
                    onProgressUpdate = onProgressUpdate
                )
            }

            DebridOrder.AUTO -> {
                // Fast-path cache check: Real-Debrid checks in-memory verified state (0ms) while TorBox checks /checkcached
                val rdCache = realDebridProvider.checkCache(queryOrMagnet)
                if (rdCache == CacheState.CACHED) {
                    val rdRes = realDebridProvider.resolveStream(
                        magnetOrQuery = queryOrMagnet,
                        episodeHint = episodeHint,
                        allowUncachedDownload = false,
                        onProgressUpdate = onProgressUpdate
                    )
                    if (rdRes is DebridResult.Success) return@coroutineScope rdRes
                }

                val torboxCache = torboxProvider.checkCache(queryOrMagnet)
                if (torboxCache == CacheState.CACHED) {
                    val torRes = torboxProvider.resolveStream(
                        magnetOrQuery = queryOrMagnet,
                        episodeHint = episodeHint,
                        allowUncachedDownload = false,
                        onProgressUpdate = onProgressUpdate
                    )
                    if (torRes is DebridResult.Success) return@coroutineScope torRes
                }

                // Probe Real-Debrid cache if not known to be uncached
                if (rdCache != CacheState.NOT_CACHED) {
                    val rdProbe = realDebridProvider.resolveStream(
                        magnetOrQuery = queryOrMagnet,
                        episodeHint = episodeHint,
                        allowUncachedDownload = false,
                        onProgressUpdate = onProgressUpdate
                    )
                    if (rdProbe is DebridResult.Success) return@coroutineScope rdProbe
                }

                // Probe TorBox cache if not known to be uncached
                if (torboxCache != CacheState.NOT_CACHED) {
                    val torProbe = torboxProvider.resolveStream(
                        magnetOrQuery = queryOrMagnet,
                        episodeHint = episodeHint,
                        allowUncachedDownload = false,
                        onProgressUpdate = onProgressUpdate
                    )
                    if (torProbe is DebridResult.Success) return@coroutineScope torProbe
                }

                // Neither provider has it cached -> Start Active Cloud Download on Real-Debrid (or TorBox if RD fails)
                val rdCloud = realDebridProvider.resolveStream(
                    magnetOrQuery = queryOrMagnet,
                    episodeHint = episodeHint,
                    allowUncachedDownload = true,
                    onProgressUpdate = onProgressUpdate
                )
                if (rdCloud is DebridResult.Success || (rdCloud is DebridResult.Error && rdCloud.message.contains("Cloud download started", ignoreCase = true))) {
                    return@coroutineScope rdCloud
                }

                val torCloud = torboxProvider.resolveStream(
                    magnetOrQuery = queryOrMagnet,
                    episodeHint = episodeHint,
                    allowUncachedDownload = true,
                    onProgressUpdate = onProgressUpdate
                )
                return@coroutineScope torCloud
            }
        }
    }

    private suspend fun TorboxProvider.resolveStream(
        magnetOrMagnetOrQuery: String,
        episodeHint: String?,
        allowUncachedDownload: Boolean,
        onProgressUpdate: ((String) -> Unit)?
    ): DebridResult = this.resolveStream(
        magnetOrQuery = magnetOrMagnetOrQuery,
        episodeHint = episodeHint,
        allowUncachedDownload = allowUncachedDownload,
        onProgressUpdate = onProgressUpdate
    )
}
