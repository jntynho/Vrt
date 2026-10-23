package com.example.network.debrid

import android.util.Log
import com.example.network.NetworkClient
import com.example.network.await
import com.example.network.torrent.MagnetParser
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

class RealDebridProvider(private val apiKeyProvider: () -> String) : DebridProvider {
    override val name: String = "Real-Debrid"
    private val tag = "RealDebridProvider"
    private val baseUrl = "https://api.real-debrid.com/rest/1.0"

    companion object {
        private val cacheCheckResults = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, CacheState>>()
        private const val CACHE_STATE_TTL_MS = 5 * 60 * 1000L // 5 minutes verified cache state

        fun clearCache() {
            cacheCheckResults.clear()
        }

        fun invalidateHash(hash: String?) {
            if (!hash.isNullOrBlank()) {
                cacheCheckResults.remove(hash.lowercase().trim())
            }
        }

        private fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return "0 KB/s"
            val mb = bytesPerSec / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f MB/s", mb)
            } else {
                val kb = bytesPerSec / 1024.0
                String.format(Locale.US, "%.0f KB/s", kb)
            }
        }
    }

    override fun isConfigured(): Boolean = apiKeyProvider().trim().isNotEmpty()

    override suspend fun checkCache(hash: String): CacheState {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return CacheState.UNKNOWN

        val infoHash = MagnetParser.parseHash(hash)?.lowercase() ?: return CacheState.UNKNOWN

        // Real-Debrid officially disabled /torrents/instantAvailability (HTTP 403 disabled_endpoint).
        // Serve verified cache states from in-memory map in 0ms without wasting a network round-trip.
        val cached = cacheCheckResults[infoHash]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_STATE_TTL_MS) {
            return cached.second
        }
        return CacheState.UNKNOWN
    }

    private fun classifyRdError(statusCode: Int, errorCode: Int, errorMsg: String): DebridErrorType {
        val lower = errorMsg.lowercase()
        return when {
            statusCode in listOf(401, 403) && (errorCode == 8 || lower.contains("bad_token")) -> DebridErrorType.InvalidKey
            errorCode in listOf(9, 20) || lower.contains("permission_denied") || lower.contains("not_premium") || lower.contains("premium_needed") -> DebridErrorType.NotPremium
            errorCode in listOf(21, 26, 29, 36) || lower.contains("active_downloads") || lower.contains("fair_usage") || lower.contains("traffic_exhausted") || lower.contains("ip_not_allowed") -> DebridErrorType.AccountLimit
            statusCode == 429 || errorCode == 34 || lower.contains("too_many_requests") -> DebridErrorType.RateLimited
            errorCode == 35 || lower.contains("infringing") -> DebridErrorType.Infringing
            errorCode == 24 || lower.contains("file_unavailable") || lower.contains("file_not_found") -> DebridErrorType.FileNotFound
            lower.contains("link_not_supported") || lower.contains("hoster_unsupported") || lower.contains("hoster_not_supported") -> DebridErrorType.Unsupported
            statusCode in listOf(401, 403) -> DebridErrorType.InvalidKey
            else -> DebridErrorType.Unknown
        }
    }

    override suspend fun resolveStream(
        magnetOrQuery: String,
        episodeHint: String?,
        allowUncachedDownload: Boolean,
        onProgressUpdate: ((String) -> Unit)?
    ): DebridResult = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) {
            return@withContext DebridResult.Error(
                type = DebridErrorType.InvalidKey,
                message = "Real-Debrid API key is missing. Please configure it in Settings.",
                providerName = name
            )
        }

        val trimmed = magnetOrQuery.trim()
        val infoHash = MagnetParser.parseHash(trimmed)?.lowercase()
        val isMagnetOrHash = infoHash != null

        if (!isMagnetOrHash) {
            // Direct hoster link (e.g. 1fichier, rapidgator)
            return@withContext unrestrictLink(trimmed, key, null)
        }

        val canonicalMagnet = if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            trimmed
        } else {
            MagnetParser.toCanonicalMagnet(infoHash!!)
        }
        var addedTorrentId: String? = null
        var isExisting = false
        var keepCloudDownloadAlive = false

        try {
            Log.d(tag, "[REAL-DEBRID] Resolving infoHash: ${MagnetParser.redact(infoHash)} (allowUncachedDownload=$allowUncachedDownload)")

            // Pre-check if torrent is already downloaded, waiting selection, or actively downloading in account
            val existingId = findExistingTorrentId(infoHash!!, key, allowDownloading = allowUncachedDownload)
            if (!existingId.isNullOrEmpty()) {
                addedTorrentId = existingId
                isExisting = true
                Log.d(tag, "[REAL-DEBRID] Found existing torrent in account: $existingId")
            }

            suspend fun addFreshMagnet(): DebridResult.Error? {
                val addBody = FormBody.Builder().add("magnet", canonicalMagnet).build()
                val addReq = Request.Builder()
                    .url("$baseUrl/torrents/addMagnet")
                    .header("Authorization", "Bearer $key")
                    .post(addBody)
                    .build()

                val addResp = NetworkClient.apiClient.newCall(addReq).await()
                val addStr = addResp.body?.string().orEmpty()
                val addCode = addResp.code
                addResp.close()

                val addJson = try { JSONObject(addStr) } catch (_: Exception) { JSONObject() }
                val errorMsg = addJson.optString("error", "")
                val errorCode = addJson.optInt("error_code", -1)

                if (addCode !in 200..299 || errorMsg.isNotEmpty()) {
                    val errType = classifyRdError(addCode, errorCode, errorMsg.ifEmpty { addStr })
                    if (errType != DebridErrorType.Unknown || addCode in listOf(401, 403, 429, 451)) {
                        return DebridResult.Error(
                            type = errType,
                            message = "Real-Debrid error: ${errorMsg.ifEmpty { "HTTP $addCode" }}",
                            statusCode = addCode,
                            providerName = name
                        )
                    }
                }

                addedTorrentId = addJson.optString("id", "")
                isExisting = false
                if (addedTorrentId.isNullOrEmpty()) {
                    val fallbackId = findExistingTorrentId(infoHash, key, allowDownloading = allowUncachedDownload)
                    if (!fallbackId.isNullOrEmpty()) {
                        addedTorrentId = fallbackId
                        isExisting = true
                    } else {
                        return DebridResult.Error(
                            type = classifyRdError(addCode, errorCode, errorMsg),
                            message = "Real-Debrid failed to add torrent: ${errorMsg.ifEmpty { addStr }}",
                            statusCode = addCode,
                            providerName = name
                        )
                    }
                }
                return null
            }

            if (addedTorrentId.isNullOrEmpty()) {
                val addErr = addFreshMagnet()
                if (addErr != null) return@withContext addErr
            }

            // Step 2: Adaptive Stepped Polling Loop (supports both fast cache probe and live cloud downloading)
            val startTime = System.currentTimeMillis()
            val totalTimeoutMs = if (allowUncachedDownload) 38_000L else 12_000L
            var pollDelayMs = 80L
            var selectedFileId: String? = null
            var filesSelected = false
            var reAddedForSingleFile = false
            var rawLink: String? = null
            var targetFilename: String? = null
            var targetFilesize: Long? = null

            var lastProgress = 0.0
            var lastSpeed = 0L
            var lastSeeders = 0
            var lastStatus = ""

            while (System.currentTimeMillis() - startTime < totalTimeoutMs) {
                val infoReq = Request.Builder()
                    .url("$baseUrl/torrents/info/$addedTorrentId")
                    .header("Authorization", "Bearer $key")
                    .build()

                val infoResp = NetworkClient.apiClient.newCall(infoReq).await()
                val infoStr = infoResp.body?.string().orEmpty()
                val infoCode = infoResp.code
                infoResp.close()

                if (infoCode == 429) {
                    delay(1000L)
                    continue
                }
                if (infoCode != 200 || infoStr.isEmpty()) {
                    delay(pollDelayMs)
                    pollDelayMs = (pollDelayMs + 100L).coerceAtMost(500L)
                    continue
                }

                val infoJson = JSONObject(infoStr)
                val status = infoJson.optString("status")
                lastStatus = status

                // Handle RD Error Statuses
                if (status in listOf("magnet_error", "error", "virus", "dead")) {
                    cacheCheckResults[infoHash] = Pair(System.currentTimeMillis(), CacheState.NOT_CACHED)
                    deleteTorrent(addedTorrentId, key)
                    return@withContext DebridResult.Error(
                        type = DebridErrorType.NotCached,
                        message = "Real-Debrid torrent status error: $status",
                        statusCode = infoCode,
                        providerName = name
                    )
                }

                // Handle File Selection
                if (!filesSelected && status.equals("waiting_files_selection", ignoreCase = true)) {
                    val filesArr = infoJson.optJSONArray("files")
                    val fileList = mutableListOf<DebridFileInfo>()

                    if (filesArr != null) {
                        for (i in 0 until filesArr.length()) {
                            val fObj = filesArr.optJSONObject(i) ?: continue
                            val fId = fObj.opt("id")?.toString() ?: (i + 1).toString()
                            val fPath = fObj.optString("path", "")
                            val fBytes = fObj.optLong("bytes", 0L)
                            fileList.add(DebridFileInfo(fId, fPath, fBytes))
                        }
                    }

                    val bestFile = VideoFilePicker.pickBestVideoFile(fileList, episodeHint)
                    val selectParam = bestFile?.id ?: "all"
                    selectedFileId = bestFile?.id
                    targetFilename = bestFile?.name
                    targetFilesize = bestFile?.sizeBytes

                    val selectBody = FormBody.Builder().add("files", selectParam).build()
                    val selectReq = Request.Builder()
                        .url("$baseUrl/torrents/selectFiles/$addedTorrentId")
                        .header("Authorization", "Bearer $key")
                        .post(selectBody)
                        .build()

                    val selResp = NetworkClient.apiClient.newCall(selectReq).await()
                    val selCode = selResp.code
                    selResp.close()

                    if (selCode !in 200..299) {
                        return@withContext DebridResult.Error(
                            type = DebridErrorType.Unknown,
                            message = "Real-Debrid failed to select file ID: $selectParam (HTTP $selCode)",
                            statusCode = selCode,
                            providerName = name
                        )
                    }
                    filesSelected = true
                    delay(80L)
                    continue
                }

                // Check Downloaded / Ready Links
                if (status.equals("downloaded", ignoreCase = true)) {
                    val linksArr = infoJson.optJSONArray("links")
                    if (linksArr != null && linksArr.length() > 0) {
                        val filesArr = infoJson.optJSONArray("files")
                        val allFiles = mutableListOf<DebridFileInfo>()
                        val selectedFileList = mutableListOf<Pair<Int, DebridFileInfo>>()
                        var selCount = 0

                        if (filesArr != null) {
                            for (i in 0 until filesArr.length()) {
                                val fObj = filesArr.optJSONObject(i) ?: continue
                                val fId = fObj.opt("id")?.toString() ?: (i + 1).toString()
                                val fPath = fObj.optString("path", "")
                                val fBytes = fObj.optLong("bytes", 0L)
                                val isSelected = fObj.optInt("selected", 0) == 1
                                val info = DebridFileInfo(fId, fPath, fBytes)
                                allFiles.add(info)
                                if (isSelected) {
                                    selectedFileList.add(selCount to info)
                                    selCount++
                                }
                            }
                        }

                        val idealOverallFile = VideoFilePicker.pickBestVideoFile(allFiles, episodeHint)
                        val isTargetSelected = idealOverallFile == null || selectedFileList.any { it.second.id == idealOverallFile.id }
                        val isBundledArchiveMismatch = selectedFileList.size > 1 && linksArr.length() != selectedFileList.size

                        if (isExisting && !reAddedForSingleFile && (!isTargetSelected || isBundledArchiveMismatch)) {
                            Log.d(tag, "[REAL-DEBRID] Re-adding multi-file torrent to isolate target video file: ${idealOverallFile?.name}")
                            reAddedForSingleFile = true
                            filesSelected = false
                            val addErr = addFreshMagnet()
                            if (addErr == null && !addedTorrentId.isNullOrEmpty()) {
                                delay(60L)
                                continue
                            }
                        }

                        var targetLinkIndex = 0
                        val bestFile = if (selectedFileList.isNotEmpty()) {
                            val explicitMatch = if (selectedFileId != null) selectedFileList.find { it.second.id == selectedFileId } else null
                            if (explicitMatch != null && (explicitMatch.second.sizeBytes > 50 * 1024 * 1024L || selectedFileList.size == 1)) {
                                targetLinkIndex = explicitMatch.first
                                explicitMatch.second
                            } else {
                                val candidate = VideoFilePicker.pickBestVideoFile(selectedFileList.map { it.second }, episodeHint)
                                val match = selectedFileList.find { it.second.id == candidate?.id }
                                targetLinkIndex = match?.first ?: 0
                                candidate ?: selectedFileList.first().second
                            }
                        } else null

                        if (bestFile != null) {
                            targetFilename = bestFile.name
                            targetFilesize = bestFile.sizeBytes
                        } else if (targetFilename.isNullOrEmpty()) {
                            targetFilename = infoJson.optString("filename", "RealDebrid_Stream")
                        }

                        rawLink = if (linksArr.length() == 1) {
                            linksArr.optString(0)
                        } else {
                            linksArr.optString(targetLinkIndex.coerceIn(0, linksArr.length() - 1))
                        }
                        cacheCheckResults[infoHash] = Pair(System.currentTimeMillis(), CacheState.CACHED)
                        break
                    }
                }

                // Handling Uncached Downloads:
                if (status in listOf("downloading", "queued", "compressing", "uploading")) {
                    val progress = infoJson.optDouble("progress", 0.0)
                    val speed = infoJson.optLong("speed", 0L)
                    val seeders = infoJson.optInt("seeders", 0)
                    val elapsedTime = System.currentTimeMillis() - startTime

                    lastProgress = progress
                    lastSpeed = speed
                    lastSeeders = seeders

                    if (!allowUncachedDownload) {
                        // Phase 1 fast cache probe: fail quickly so DebridManager can check if another provider has it cached
                        if (progress < 100.0 && elapsedTime > 1500L) {
                            cacheCheckResults[infoHash] = Pair(System.currentTimeMillis(), CacheState.NOT_CACHED)
                            withContext(NonCancellable) {
                                if (!isExisting) {
                                    deleteTorrent(addedTorrentId, key)
                                }
                            }
                            return@withContext DebridResult.Error(
                                type = DebridErrorType.NotCached,
                                message = "Torrent is not cached on Real-Debrid.",
                                providerName = name
                            )
                        }
                    } else {
                        // Phase 2 Active Cloud Download Mode: Keep torrent alive and stream live progress updates!
                        keepCloudDownloadAlive = true
                        cacheCheckResults.remove(infoHash)

                        val liveMsg = when (status.lowercase()) {
                            "queued" -> "Real-Debrid Cloud: Queued for download (${progress.toInt()}%)..."
                            "compressing", "uploading" -> "Real-Debrid Cloud: Finalizing stream (${String.format(Locale.US, "%.1f", progress)}%)..."
                            else -> "Downloading to Real-Debrid Cloud: ${String.format(Locale.US, "%.1f", progress)}% • ${formatSpeed(speed)} • $seeders Seeds"
                        }
                        onProgressUpdate?.invoke(liveMsg)

                        // Dead torrent protection: if 0% progress, 0 seeders, and 0 speed after 18s, clean up dead slot
                        if (elapsedTime > 18_000L && progress <= 0.0 && seeders <= 0 && speed <= 0L) {
                            keepCloudDownloadAlive = false
                            withContext(NonCancellable) {
                                deleteTorrent(addedTorrentId, key)
                            }
                            return@withContext DebridResult.Error(
                                type = DebridErrorType.NotCached,
                                message = "Torrent has 0 active seeders and could not start downloading on Real-Debrid.",
                                providerName = name
                            )
                        }

                        delay(1000L)
                        continue
                    }
                }

                // Handling Magnet Conversion:
                if (status.equals("magnet_conversion", ignoreCase = true)) {
                    val elapsedTime = System.currentTimeMillis() - startTime
                    if (allowUncachedDownload) {
                        onProgressUpdate?.invoke("Real-Debrid Cloud: Fetching torrent metadata from DHT...")
                    }
                    val conversionLimitMs = if (allowUncachedDownload) 15_000L else 6_000L
                    if (elapsedTime > conversionLimitMs) {
                        cacheCheckResults[infoHash] = Pair(System.currentTimeMillis(), CacheState.NOT_CACHED)
                        withContext(NonCancellable) {
                            deleteTorrent(addedTorrentId, key)
                        }
                        return@withContext DebridResult.Error(
                            type = DebridErrorType.NotCached,
                            message = "Torrent metadata lookup timed out (0 seeders or unreachable DHT) on Real-Debrid.",
                            providerName = name
                        )
                    }
                }

                delay(pollDelayMs)
                pollDelayMs = when {
                    pollDelayMs < 120L -> 120L
                    pollDelayMs < 180L -> 180L
                    pollDelayMs < 250L -> 250L
                    else -> 350L
                }
            }

            if (rawLink.isNullOrEmpty()) {
                if (allowUncachedDownload && keepCloudDownloadAlive) {
                    // Leave torrent actively downloading in the user's Real-Debrid cloud!
                    val pctStr = String.format(Locale.US, "%.1f", lastProgress)
                    val speedStr = formatSpeed(lastSpeed)
                    return@withContext DebridResult.Error(
                        type = DebridErrorType.NotCached,
                        message = "Cloud download started on Real-Debrid ($pctStr% • $speedStr • $lastSeeders Seeds). It is continuing in the background—tap Play again shortly once finished.",
                        providerName = name
                    )
                }

                cacheCheckResults[infoHash] = Pair(System.currentTimeMillis(), CacheState.NOT_CACHED)
                withContext(NonCancellable) {
                    if (!isExisting) {
                        deleteTorrent(addedTorrentId, key)
                    }
                }
                return@withContext DebridResult.Error(
                    type = DebridErrorType.NotCached,
                    message = "Real-Debrid stream link not ready or torrent is not cached.",
                    providerName = name
                )
            }

            return@withContext unrestrictLink(rawLink, key, targetFilename, targetFilesize)

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) {
                    // Only delete if we weren't actively downloading an uncached torrent in cloud mode
                    if (!isExisting && !keepCloudDownloadAlive) {
                        deleteTorrent(addedTorrentId, key)
                    }
                }
                throw e
            }
            Log.e(tag, "Real-Debrid resolveStream exception", e)
            return@withContext DebridResult.Error(
                type = DebridErrorType.Unknown,
                message = "Real-Debrid error: ${e.message}",
                providerName = name
            )
        }
    }

    private suspend fun findExistingTorrentId(
        infoHash: String,
        key: String,
        allowDownloading: Boolean = true
    ): String? {
        try {
            val req = Request.Builder()
                .url("$baseUrl/torrents?limit=100")
                .header("Authorization", "Bearer $key")
                .build()

            val resp = NetworkClient.apiClient.newCall(req).await()
            val body = resp.body?.string().orEmpty()
            resp.close()

            if (resp.isSuccessful && body.isNotEmpty()) {
                val arr = org.json.JSONArray(body)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val hash = item.optString("hash", "").lowercase()
                    if (hash == infoHash.lowercase()) {
                        val id = item.optString("id", "")
                        val status = item.optString("status", "")
                        if (status in listOf("downloaded", "waiting_files_selection")) {
                            return id
                        } else if (allowDownloading && status in listOf("downloading", "queued", "compressing", "uploading")) {
                            return id
                        } else if (status in listOf("error", "magnet_error", "virus", "dead")) {
                            // Purge dead/errored entry so a fresh magnet can be added cleanly
                            deleteTorrent(id, key)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "findExistingTorrentId check failed: ${e.message}")
        }
        return null
    }

    private suspend fun deleteTorrent(torrentId: String?, key: String) {
        if (torrentId.isNullOrEmpty()) return
        try {
            val req = Request.Builder()
                .url("$baseUrl/torrents/delete/$torrentId")
                .header("Authorization", "Bearer $key")
                .delete()
                .build()
            val resp = NetworkClient.apiClient.newCall(req).await()
            resp.close()
            Log.d(tag, "[REAL-DEBRID] Deleted uncached/stale torrent ID: $torrentId")
        } catch (_: Exception) {}
    }

    private suspend fun unrestrictLink(
        rawLink: String,
        key: String,
        initialFilename: String?,
        sizeBytesHint: Long? = null
    ): DebridResult {
        Log.d(tag, "[REAL-DEBRID] Unrestricting link...")
        val form = FormBody.Builder().add("link", rawLink).build()
        val unrestrictReq = Request.Builder()
            .url("$baseUrl/unrestrict/link")
            .header("Authorization", "Bearer $key")
            .post(form)
            .build()

        val resp = NetworkClient.apiClient.newCall(unrestrictReq).await()
        val bodyStr = resp.body?.string().orEmpty()
        val code = resp.code
        resp.close()

        if (code !in 200..299) {
            val errJson = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
            val errMsg = errJson.optString("error", errJson.optString("message", bodyStr))
            val errCode = errJson.optInt("error_code", -1)
            val errType = classifyRdError(code, errCode, errMsg)
            return DebridResult.Error(
                type = errType,
                message = "Real-Debrid unrestrict failed: $errMsg",
                statusCode = code,
                providerName = name
            )
        }

        val json = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
        val downloadUrl = json.optString("download", "").trim()
        val filename = json.optString("filename", initialFilename ?: "RealDebrid_Stream")
        val filesize = json.optLong("filesize", sizeBytesHint ?: 0L)

        // Guard against compressed multi-file archives (.rar, .zip, .7z) that ExoPlayer cannot decode
        val lowerName = filename.lowercase()
        val lowerUrl = downloadUrl.substringBefore("?").lowercase()
        if (lowerName.endsWith(".rar") || lowerName.endsWith(".zip") || lowerName.endsWith(".7z") ||
            lowerUrl.endsWith(".rar") || lowerName.endsWith(".zip") || lowerUrl.endsWith(".7z")
        ) {
            return DebridResult.Error(
                type = DebridErrorType.Unsupported,
                message = "Real-Debrid packaged this multi-file torrent as a compressed archive ($filename) which cannot be streamed directly.",
                providerName = name
            )
        }

        if (downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://")) {
            return DebridResult.Success(
                streamUrl = downloadUrl,
                filename = filename,
                sizeBytes = if (filesize > 0) filesize else null,
                headers = mapOf(
                    "User-Agent" to NetworkClient.DEFAULT_BROWSER_UA
                ),
                providerName = name
            )
        } else {
            return DebridResult.Error(
                type = DebridErrorType.Unknown,
                message = "Real-Debrid returned an invalid download URL: $bodyStr",
                statusCode = code,
                providerName = name
            )
        }
    }
}
