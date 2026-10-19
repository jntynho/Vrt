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

        fun invalidateCache(key: String?) {
            if (!key.isNullOrBlank()) {
                val parsedHash = MagnetParser.parseHash(key)
                if (parsedHash != null) {
                    resolvedStreamCache.remove(parsedHash.lowercase().trim())
                }
                resolvedStreamCache.remove(key.lowercase().trim())
            }
        }
    }

    fun hasAnyProviderConfigured(): Boolean {
        return torboxProvider.isConfigured() || realDebridProvider.isConfigured()
    }

    suspend fun resolve(queryOrMagnet: String): DebridResult = coroutineScope {
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
        val cacheKey = infoHash?.lowercase() ?: queryOrMagnet.trim().lowercase()
        val cached = resolvedStreamCache[cacheKey]
        if (cached != null && (System.currentTimeMillis() - cached.timestampMs < CACHE_TTL_MS)) {
            Log.d(tag, "Returning cached Debrid stream for: $cacheKey")
            return@coroutineScope cached.result
        }

        val result = executeResolution(queryOrMagnet, torboxConfigured, rdConfigured, order)

        if (result is DebridResult.Success) {
            resolvedStreamCache[cacheKey] = CachedStream(result)
        }

        return@coroutineScope result
    }

    private suspend fun executeResolution(
        queryOrMagnet: String,
        torboxConfigured: Boolean,
        rdConfigured: Boolean,
        order: DebridOrder
    ): DebridResult = coroutineScope {
        val isTorrentOrMagnet = MagnetParser.parseHash(queryOrMagnet) != null

        // Protocol-aware routing: Direct hoster links (1fichier, rapidgator, etc.)
        // are only supported by Real-Debrid unrestrict API
        if (!isTorrentOrMagnet) {
            if (rdConfigured) {
                return@coroutineScope realDebridProvider.resolveStream(queryOrMagnet)
            } else {
                return@coroutineScope DebridResult.Error(
                    type = DebridErrorType.Unsupported,
                    message = "Hoster links require Real-Debrid configuration.",
                    providerName = "DebridManager"
                )
            }
        }

        if (torboxConfigured && !rdConfigured) {
            return@coroutineScope torboxProvider.resolveStream(queryOrMagnet)
        }
        if (!torboxConfigured && rdConfigured) {
            return@coroutineScope realDebridProvider.resolveStream(queryOrMagnet)
        }

        when (order) {
            DebridOrder.REAL_DEBRID_FIRST -> {
                val rdRes = realDebridProvider.resolveStream(queryOrMagnet)
                if (rdRes is DebridResult.Success) return@coroutineScope rdRes
                return@coroutineScope torboxProvider.resolveStream(queryOrMagnet)
            }
            DebridOrder.TORBOX_FIRST -> {
                val torRes = torboxProvider.resolveStream(queryOrMagnet)
                if (torRes is DebridResult.Success) return@coroutineScope torRes
                return@coroutineScope realDebridProvider.resolveStream(queryOrMagnet)
            }
            DebridOrder.AUTO -> {
                // High-speed parallel cache check & instant resolution
                val rdCacheDeferred = async { realDebridProvider.checkCache(queryOrMagnet) }
                val torboxCacheDeferred = async { torboxProvider.checkCache(queryOrMagnet) }

                val rdCache = rdCacheDeferred.await()
                if (rdCache == CacheState.CACHED) {
                    val rdRes = realDebridProvider.resolveStream(queryOrMagnet)
                    if (rdRes is DebridResult.Success) return@coroutineScope rdRes
                }

                val torboxCache = torboxCacheDeferred.await()
                if (torboxCache == CacheState.CACHED) {
                    val torRes = torboxProvider.resolveStream(queryOrMagnet)
                    if (torRes is DebridResult.Success) return@coroutineScope torRes
                }

                // If neither was explicitly cached, attempt Real-Debrid first as fast fallback
                if (rdCache != CacheState.NOT_CACHED) {
                    val rdRes = realDebridProvider.resolveStream(queryOrMagnet)
                    if (rdRes is DebridResult.Success) return@coroutineScope rdRes
                }

                if (torboxCache != CacheState.NOT_CACHED) {
                    val torRes = torboxProvider.resolveStream(queryOrMagnet)
                    if (torRes is DebridResult.Success) return@coroutineScope torRes
                }

                return@coroutineScope DebridResult.Error(
                    type = DebridErrorType.NotCached,
                    message = "Torrent is not cached on Real-Debrid or Torbox.",
                    providerName = "Debrid"
                )
            }
        }
    }
}
