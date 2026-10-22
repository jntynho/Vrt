package com.example.network.debrid

import android.util.Log
import com.example.network.torrent.MagnetParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentHashMap

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

    suspend fun resolve(queryOrMagnet: String, episodeHint: String? = null): DebridResult = coroutineScope {
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

        val result = executeResolution(queryOrMagnet, episodeHint, torboxConfigured, rdConfigured, order)

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
        order: DebridOrder
    ): DebridResult = coroutineScope {
        val isTorrentOrMagnet = MagnetParser.parseHash(queryOrMagnet) != null

        // Protocol-aware routing: Direct hoster links (1fichier, rapidgator, etc.)
        // are only supported by Real-Debrid unrestrict API
        if (!isTorrentOrMagnet) {
            if (rdConfigured) {
                return@coroutineScope realDebridProvider.resolveStream(queryOrMagnet, episodeHint)
            } else {
                return@coroutineScope DebridResult.Error(
                    type = DebridErrorType.Unsupported,
                    message = "Hoster links require Real-Debrid configuration.",
                    providerName = "DebridManager"
                )
            }
        }

        if (torboxConfigured && !rdConfigured) {
            return@coroutineScope torboxProvider.resolveStream(queryOrMagnet, episodeHint)
        }
        if (!torboxConfigured && rdConfigured) {
            return@coroutineScope realDebridProvider.resolveStream(queryOrMagnet, episodeHint)
        }

        when (order) {
            DebridOrder.REAL_DEBRID_FIRST -> {
                val rdRes = realDebridProvider.resolveStream(queryOrMagnet, episodeHint)
                if (rdRes is DebridResult.Success) return@coroutineScope rdRes
                return@coroutineScope torboxProvider.resolveStream(queryOrMagnet, episodeHint)
            }
            DebridOrder.TORBOX_FIRST -> {
                val torRes = torboxProvider.resolveStream(queryOrMagnet, episodeHint)
                if (torRes is DebridResult.Success) return@coroutineScope torRes
                return@coroutineScope realDebridProvider.resolveStream(queryOrMagnet, episodeHint)
            }
            DebridOrder.AUTO -> {
                // Fast-path cache check: Real-Debrid checks in-memory verified state (0ms) while TorBox checks /checkcached
                val rdCache = realDebridProvider.checkCache(queryOrMagnet)
                if (rdCache == CacheState.CACHED) {
                    val rdRes = realDebridProvider.resolveStream(queryOrMagnet, episodeHint)
                    if (rdRes is DebridResult.Success) return@coroutineScope rdRes
                }

                val torboxCache = torboxProvider.checkCache(queryOrMagnet)
                if (torboxCache == CacheState.CACHED) {
                    val torRes = torboxProvider.resolveStream(queryOrMagnet, episodeHint)
                    if (torRes is DebridResult.Success) return@coroutineScope torRes
                }

                // Attempt Real-Debrid first if not known to be uncached (fast-fails in ~1.5s if uncached)
                var lastError: DebridResult.Error? = null
                if (rdCache != CacheState.NOT_CACHED) {
                    val rdRes = realDebridProvider.resolveStream(queryOrMagnet, episodeHint)
                    if (rdRes is DebridResult.Success) return@coroutineScope rdRes
                    if (rdRes is DebridResult.Error) lastError = rdRes
                }

                if (torboxCache != CacheState.NOT_CACHED) {
                    val torRes = torboxProvider.resolveStream(queryOrMagnet, episodeHint)
                    if (torRes is DebridResult.Success) return@coroutineScope torRes
                    if (torRes is DebridResult.Error && lastError == null) lastError = torRes
                }

                return@coroutineScope lastError ?: DebridResult.Error(
                    type = DebridErrorType.NotCached,
                    message = "Torrent is not cached on Real-Debrid or Torbox.",
                    providerName = "Debrid"
                )
            }
        }
    }
}
