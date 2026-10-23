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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class TorboxProvider(private val apiKeyProvider: () -> String) : DebridProvider {
    override val name: String = "Torbox"
    private val tag = "TorboxProvider"
    private val baseUrl = "https://api.torbox.app/v1/api"

    companion object {
        private val cacheCheckResults = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, CacheState>>()

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

    private fun classifyTorboxError(statusCode: Int, errorCode: String, detail: String): DebridErrorType {
        val codeUpper = errorCode.uppercase()
        val detailLower = detail.lowercase()
        return when {
            statusCode in listOf(401, 403) || codeUpper in listOf("AUTH_ERROR", "BAD_TOKEN", "INVALID_TOKEN") || detailLower.contains("invalid token") -> DebridErrorType.InvalidKey
            codeUpper in listOf("PLAN_RESTRICTED_FEATURE", "NOT_PREMIUM") || detailLower.contains("plan") -> DebridErrorType.NotPremium
            codeUpper in listOf("ACTIVE_LIMIT", "MONTHLY_LIMIT", "COOLDOWN_LIMIT", "DOWNLOAD_TOO_LARGE", "CONCURRENT_LIMIT") || detailLower.contains("active limit") || detailLower.contains("cooldown") -> DebridErrorType.AccountLimit
            statusCode == 429 || codeUpper == "RATE_LIMIT" -> DebridErrorType.RateLimited
            else -> DebridErrorType.Unknown
        }
    }

    private fun isTorrentObjectReady(torrentObj: JSONObject): Boolean {
        if (torrentObj.optBoolean("download_present", false)) return true
        if (torrentObj.optBoolean("download_finished", false)) return true
        if (torrentObj.optBoolean("cached", false)) return true
        val progress = torrentObj.optDouble("progress", 0.0)
        if (progress >= 1.0) return true
        val state = torrentObj.optString("download_state", "").lowercase().trim()
        return state in listOf("cached", "completed", "seeding", "uploading", "ready", "paused")
    }

    private fun isTorrentObjectActivelyUncached(torrentObj: JSONObject): Boolean {
        if (isTorrentObjectReady(torrentObj)) return false
        val state = torrentObj.optString("download_state", "").lowercase().trim()
        val progress = torrentObj.optDouble("progress", 0.0)
        return progress < 1.0 && (
            state in listOf("downloading", "metadl", "stalleddl", "queued", "allocating", "checkingdl", "error", "missingfiles") ||
            !torrentObj.optBoolean("download_present", false)
        )
    }

    override suspend fun checkCache(hash: String): CacheState = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext CacheState.UNKNOWN

        val infoHash = MagnetParser.parseHash(hash)?.lowercase() ?: return@withContext CacheState.UNKNOWN

        // In-memory 60s cache to avoid duplicate network latency
        val cached = cacheCheckResults[infoHash]
        if (cached != null && System.currentTimeMillis() - cached.first < 60_000L) {
            return@withContext cached.second
        }

        try {
            val url = "$baseUrl/torrents/checkcached?hash=$infoHash&format=object"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .build()

            val response = NetworkClient.apiClient.newCall(request).await()
            val body = response.body?.string().orEmpty()
            val code = response.code
            response.close()

            if (code in 200..299 && body.isNotEmpty()) {
                val json = JSONObject(body)
                val success = json.optBoolean("success", false)
                val data = json.optJSONObject("data")
                if (success && data != null) {
                    val torrentData = data.opt(infoHash) ?: data.opt(infoHash.uppercase())
                    if (torrentData != null && torrentData != JSONObject.NULL && torrentData != false) {
                        cacheCheckResults[infoHash] = Pair(System.currentTimeMillis(), CacheState.CACHED)
                        return@withContext CacheState.CACHED
                    }
                }
                cacheCheckResults[infoHash] = Pair(System.currentTimeMillis(), CacheState.NOT_CACHED)
                return@withContext CacheState.NOT_CACHED
            }
        } catch (e: Exception) {
            Log.e(tag, "Torbox checkCache failed: ${e.message}")
        }
        CacheState.UNKNOWN
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
                message = "Torbox API key is missing. Please configure it in Settings.",
                providerName = name
            )
        }

        val trimmed = magnetOrQuery.trim()
        val infoHash = MagnetParser.parseHash(trimmed)?.lowercase()
        if (infoHash == null && !trimmed.startsWith("magnet:", ignoreCase = true)) {
            return@withContext DebridResult.Error(
                type = DebridErrorType.Unsupported,
                message = "Torbox only supports magnet and torrent hashes.",
                providerName = name
            )
        }

        val canonicalMagnet = if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            trimmed
        } else {
            MagnetParser.toCanonicalMagnet(infoHash!!)
        }

        // Only short-circuit on NOT_CACHED during Phase 1 fast cache probes!
        // When allowUncachedDownload == true, we proceed to add/download the torrent on TorBox cloud.
        if (!allowUncachedDownload && infoHash != null) {
            val cacheState = checkCache(infoHash)
            if (cacheState == CacheState.NOT_CACHED) {
                return@withContext DebridResult.Error(
                    type = DebridErrorType.NotCached,
                    message = "Torrent is not cached on Torbox.",
                    providerName = name
                )
            }
        }

        var torrentId: String? = null
        var isExisting = false
        var keepCloudDownloadAlive = false

        try {
            Log.d(tag, "[TORBOX] Resolving stream (allowUncachedDownload=$allowUncachedDownload)...")

            // Check if this torrent is already in mylist (either ready or actively downloading)
            if (infoHash != null) {
                val existingId = findInMyList(infoHash, key, allowDownloading = allowUncachedDownload)
                if (!existingId.isNullOrEmpty()) {
                    torrentId = existingId
                    isExisting = true
                    Log.d(tag, "[TORBOX] Reusing existing torrent in mylist: $existingId")
                }
            }

            var selectedFileId: String? = null
            var filename: String? = null
            var sizeBytes: Long? = null
            var isTorrentReady = false
            var createErrorDetail = ""
            var createErrorCode = ""
            var createCode = 200

            // Step 1: Add torrent if not already present in mylist
            if (torrentId.isNullOrEmpty()) {
                val formBody = FormBody.Builder()
                    .add("magnet", canonicalMagnet)
                    .add("seed", "1")
                    .add("allow_zip", "false")
                    .build()

                val createReq = Request.Builder()
                    .url("$baseUrl/torrents/createtorrent")
                    .header("Authorization", "Bearer $key")
                    .post(formBody)
                    .build()

                val createResp = NetworkClient.apiClient.newCall(createReq).await()
                val createBody = createResp.body?.string().orEmpty()
                createCode = createResp.code
                createResp.close()

                if (createCode in listOf(401, 403)) {
                    return@withContext DebridResult.Error(
                        type = DebridErrorType.InvalidKey,
                        message = "Torbox API key is invalid.",
                        statusCode = createCode,
                        providerName = name
                    )
                }

                if (createBody.isNotEmpty()) {
                    val createJson = try { JSONObject(createBody) } catch (_: Exception) { JSONObject() }
                    val isSuccess = createJson.optBoolean("success", createCode in 200..299)
                    createErrorCode = createJson.optString("error", "")
                    createErrorDetail = createJson.optString("detail", "")

                    if (!isSuccess && createErrorCode.isNotEmpty()) {
                        val classified = classifyTorboxError(createCode, createErrorCode, createErrorDetail)
                        if (classified in listOf(DebridErrorType.InvalidKey, DebridErrorType.NotPremium, DebridErrorType.AccountLimit, DebridErrorType.RateLimited)) {
                            val existingId = if (infoHash != null) findInMyList(infoHash, key, allowDownloading = allowUncachedDownload) else null
                            if (!existingId.isNullOrEmpty()) {
                                torrentId = existingId
                                isExisting = true
                            } else {
                                return@withContext DebridResult.Error(
                                    type = classified,
                                    message = createErrorDetail.ifEmpty { "Torbox error: $createErrorCode" },
                                    statusCode = createCode,
                                    providerName = name
                                )
                            }
                        }
                    }

                    val dataObj = createJson.optJSONObject("data")
                    if (dataObj != null) {
                        torrentId = dataObj.optString("torrent_id", dataObj.optString("id", ""))
                        if (torrentId.isEmpty()) {
                            val qId = dataObj.opt("queued_id")?.toString()
                            if (!qId.isNullOrEmpty() && qId != "null") {
                                if (!allowUncachedDownload) {
                                    if (infoHash != null) {
                                        cacheCheckResults[infoHash] = Pair(System.currentTimeMillis(), CacheState.NOT_CACHED)
                                    }
                                    return@withContext DebridResult.Error(
                                        type = DebridErrorType.NotCached,
                                        message = "Torrent was queued (not cached) on Torbox.",
                                        providerName = name
                                    )
                                } else {
                                    // Active Cloud Download Mode: Wait briefly to see if TorBox promotes queued_id into mylist
                                    keepCloudDownloadAlive = true
                                    onProgressUpdate?.invoke("Torbox Cloud: Queued torrent for download (Queue ID #$qId)...")
                                    if (infoHash != null) {
                                        cacheCheckResults.remove(infoHash)
                                        for (attempt in 1..6) {
                                            delay(1500L)
                                            val promotedId = findInMyList(infoHash, key, allowDownloading = true)
                                            if (!promotedId.isNullOrEmpty()) {
                                                torrentId = promotedId
                                                break
                                            }
                                        }
                                    }
                                    if (torrentId.isNullOrEmpty()) {
                                        return@withContext DebridResult.Error(
                                            type = DebridErrorType.NotCached,
                                            message = "Torrent has been added to your Torbox Cloud download queue (Queue #$qId). It is downloading in the background—tap Play again shortly once finished.",
                                            providerName = name
                                        )
                                    }
                                }
                            }
                        }

                        // Fast-path: Check if cached files array was already returned directly in createtorrent response
                        val directFilesArr = dataObj.optJSONArray("files")
                        val isCachedReply = createErrorDetail.contains("cached", ignoreCase = true) || isTorrentObjectReady(dataObj)
                        if (directFilesArr != null && directFilesArr.length() > 0 && isCachedReply) {
                            val fileList = mutableListOf<DebridFileInfo>()
                            for (i in 0 until directFilesArr.length()) {
                                val fObj = directFilesArr.optJSONObject(i) ?: continue
                                val fId = fObj.opt("id")?.toString() ?: i.toString()
                                val fName = fObj.optString("name", fObj.optString("short_name", ""))
                                val fSize = fObj.optLong("size", fObj.optLong("bytes", 0L))
                                fileList.add(DebridFileInfo(fId, fName, fSize))
                            }
                            val bestFile = VideoFilePicker.pickBestVideoFile(fileList, episodeHint)
                            if (bestFile != null) {
                                selectedFileId = bestFile.id
                                filename = bestFile.name
                                sizeBytes = bestFile.sizeBytes
                                isTorrentReady = true
                            }
                        }
                    } else if (torrentId.isNullOrEmpty()) {
                        val simpleId = createJson.optString("torrent_id", createJson.optString("id", ""))
                        if (simpleId.isNotEmpty()) torrentId = simpleId
                    }

                    // Check detail message if torrent already existed
                    if (torrentId.isNullOrEmpty() && createErrorDetail.isNotEmpty()) {
                        val match = Regex("""(?:torrent_id|id)[^0-9]*([0-9]+)""", RegexOption.IGNORE_CASE).find(createErrorDetail)
                        if (match != null) {
                            torrentId = match.groupValues[1]
                            isExisting = true
                        }
                    }
                }

                // Fallback search in mylist if torrentId was not directly returned
                if (torrentId.isNullOrEmpty() && infoHash != null) {
                    val foundId = findInMyList(infoHash, key, allowDownloading = allowUncachedDownload)
                    if (!foundId.isNullOrEmpty()) {
                        torrentId = foundId
                        isExisting = true
                    }
                }
            }

            if (torrentId.isNullOrEmpty()) {
                return@withContext DebridResult.Error(
                    type = classifyTorboxError(createCode, createErrorCode, createErrorDetail),
                    message = createErrorDetail.ifEmpty { "Unable to create or locate torrent on Torbox account." },
                    statusCode = createCode,
                    providerName = name
                )
            }

            // Step 2: Poll mylist?id=$torrentId&bypass_cache=true and verify readiness (or track active cloud download)
            var lastProgressPct = 0.0
            var lastSpeed = 0L
            var lastSeeds = 0

            if (selectedFileId == null || !isTorrentReady) {
                val startTime = System.currentTimeMillis()
                val totalTimeoutMs = if (allowUncachedDownload) 38_000L else 12_000L
                var torboxPollDelay = 80L

                while (System.currentTimeMillis() - startTime < totalTimeoutMs) {
                    val infoReq = Request.Builder()
                        .url("$baseUrl/torrents/mylist?id=$torrentId&bypass_cache=true")
                        .header("Authorization", "Bearer $key")
                        .build()

                    val infoResp = NetworkClient.apiClient.newCall(infoReq).await()
                    val infoBody = infoResp.body?.string().orEmpty()
                    val infoCode = infoResp.code
                    infoResp.close()

                    if (infoCode == 429) {
                        delay(1000L)
                        continue
                    }

                    if (infoCode in 200..299 && infoBody.isNotEmpty()) {
                        val infoJson = try { JSONObject(infoBody) } catch (_: Exception) { JSONObject() }
                        val dataObj = infoJson.optJSONObject("data")
                        val dataArr = infoJson.optJSONArray("data")
                        val torrentObj = dataObj ?: if (dataArr != null && dataArr.length() > 0) dataArr.optJSONObject(0) else null

                        if (torrentObj != null) {
                            val ready = isTorrentObjectReady(torrentObj)
                            val filesArr = torrentObj.optJSONArray("files")

                            if (ready && filesArr != null && filesArr.length() > 0) {
                                val fileList = mutableListOf<DebridFileInfo>()
                                for (i in 0 until filesArr.length()) {
                                    val fObj = filesArr.optJSONObject(i) ?: continue
                                    val fId = fObj.opt("id")?.toString() ?: i.toString()
                                    val fName = fObj.optString("name", fObj.optString("short_name", ""))
                                    val fSize = fObj.optLong("size", fObj.optLong("bytes", 0L))
                                    fileList.add(DebridFileInfo(fId, fName, fSize))
                                }

                                val bestFile = VideoFilePicker.pickBestVideoFile(fileList, episodeHint)
                                if (bestFile != null) {
                                    selectedFileId = bestFile.id
                                    filename = bestFile.name
                                    sizeBytes = bestFile.sizeBytes
                                    isTorrentReady = true
                                    if (infoHash != null) {
                                        cacheCheckResults[infoHash] = Pair(System.currentTimeMillis(), CacheState.CACHED)
                                    }
                                    break
                                }
                            } else if (isTorrentObjectActivelyUncached(torrentObj)) {
                                val elapsedTime = System.currentTimeMillis() - startTime
                                val rawProg = torrentObj.optDouble("progress", 0.0)
                                val pct = if (rawProg <= 1.0) (rawProg * 100.0).coerceIn(0.0, 100.0) else rawProg.coerceIn(0.0, 100.0)
                                val speed = torrentObj.optLong("download_speed", 0L)
                                val seeds = torrentObj.optInt("seeds", torrentObj.optInt("seeders", 0))
                                val dlState = torrentObj.optString("download_state", "downloading").lowercase().trim()

                                lastProgressPct = pct
                                lastSpeed = speed
                                lastSeeds = seeds

                                if (!allowUncachedDownload) {
                                    if (elapsedTime > 1500L) {
                                        if (infoHash != null) {
                                            cacheCheckResults[infoHash] = Pair(System.currentTimeMillis(), CacheState.NOT_CACHED)
                                        }
                                        withContext(NonCancellable) {
                                            if (!isExisting) {
                                                deleteTorrent(torrentId, key)
                                            }
                                        }
                                        return@withContext DebridResult.Error(
                                            type = DebridErrorType.NotCached,
                                            message = "Torrent is not cached on Torbox.",
                                            providerName = name
                                        )
                                    }
                                } else {
                                    // Active Cloud Download Mode on TorBox: Keep torrent alive and report progress!
                                    keepCloudDownloadAlive = true
                                    if (infoHash != null) {
                                        cacheCheckResults.remove(infoHash)
                                    }

                                    if (dlState in listOf("error", "missingfiles")) {
                                        keepCloudDownloadAlive = false
                                        withContext(NonCancellable) {
                                            deleteTorrent(torrentId, key)
                                        }
                                        return@withContext DebridResult.Error(
                                            type = DebridErrorType.NotCached,
                                            message = "Torbox torrent download failed ($dlState).",
                                            providerName = name
                                        )
                                    }

                                    val liveMsg = when (dlState) {
                                        "metadl", "allocating", "checkingdl" -> "Torbox Cloud: Fetching torrent metadata ($seeds Seeds)..."
                                        "queued" -> "Torbox Cloud: Queued for download (${pct.toInt()}%)..."
                                        else -> "Downloading to Torbox Cloud: ${String.format(Locale.US, "%.1f", pct)}% • ${formatSpeed(speed)} • $seeds Seeds"
                                    }
                                    onProgressUpdate?.invoke(liveMsg)

                                    // Dead torrent protection: 0% progress, 0 seeds, 0 speed after 18s
                                    if (elapsedTime > 18_000L && pct <= 0.0 && seeds <= 0 && speed <= 0L && dlState in listOf("stalleddl", "metadl")) {
                                        keepCloudDownloadAlive = false
                                        withContext(NonCancellable) {
                                            deleteTorrent(torrentId, key)
                                        }
                                        return@withContext DebridResult.Error(
                                            type = DebridErrorType.NotCached,
                                            message = "Torrent has 0 active seeders and could not start downloading on Torbox.",
                                            providerName = name
                                        )
                                    }

                                    delay(1000L)
                                    continue
                                }
                            }
                        }
                    }
                    delay(torboxPollDelay)
                    torboxPollDelay = when {
                        torboxPollDelay < 140L -> 140L
                        torboxPollDelay < 250L -> 250L
                        else -> 400L
                    }
                }
            }

            // Guard Clause: Do not call requestdl if torrent is not ready or no video file was selected
            if (!isTorrentReady || selectedFileId.isNullOrEmpty()) {
                if (allowUncachedDownload && keepCloudDownloadAlive) {
                    val pctStr = String.format(Locale.US, "%.1f", lastProgressPct)
                    val speedStr = formatSpeed(lastSpeed)
                    return@withContext DebridResult.Error(
                        type = DebridErrorType.NotCached,
                        message = "Cloud download started on Torbox ($pctStr% • $speedStr • $lastSeeds Seeds). It is continuing in the background—tap Play again shortly once finished.",
                        providerName = name
                    )
                }

                if (infoHash != null) {
                    cacheCheckResults[infoHash] = Pair(System.currentTimeMillis(), CacheState.NOT_CACHED)
                }
                withContext(NonCancellable) {
                    if (!isExisting) {
                        deleteTorrent(torrentId, key)
                    }
                }
                return@withContext DebridResult.Error(
                    type = DebridErrorType.NotCached,
                    message = "Torrent is not cached or no playable video file found on Torbox.",
                    providerName = name
                )
            }

            // Step 3: Request Direct Link (including token query parameter for full TorBox CDN compatibility)
            val requestDlUrl = "$baseUrl/torrents/requestdl?token=$key&torrent_id=$torrentId&file_id=$selectedFileId&zip_link=false"

            val dlReq = Request.Builder()
                .url(requestDlUrl)
                .header("Authorization", "Bearer $key")
                .build()

            val dlResp = NetworkClient.apiClient.newCall(dlReq).await()
            val dlBody = dlResp.body?.string().orEmpty()
            val dlCode = dlResp.code
            dlResp.close()

            if (dlCode !in 200..299) {
                val errJson = try { JSONObject(dlBody) } catch (_: Exception) { JSONObject() }
                val errCode = errJson.optString("error", "")
                val errDetail = errJson.optString("detail", dlBody)
                return@withContext DebridResult.Error(
                    type = classifyTorboxError(dlCode, errCode, errDetail),
                    message = "Torbox requestdl failed: $errDetail",
                    statusCode = dlCode,
                    providerName = name
                )
            }

            val dlJson = try { JSONObject(dlBody) } catch (_: Exception) { JSONObject() }
            if (dlJson.optBoolean("success", false)) {
                val downloadUrl = if (dlJson.optJSONObject("data") != null) {
                    dlJson.getJSONObject("data").optString("url")
                } else {
                    dlJson.optString("data")
                }.trim()

                if (downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://")) {
                    return@withContext DebridResult.Success(
                        streamUrl = downloadUrl,
                        filename = filename ?: "Torbox_Stream",
                        sizeBytes = sizeBytes,
                        headers = mapOf(
                            "User-Agent" to NetworkClient.DEFAULT_BROWSER_UA
                        ),
                        providerName = name
                    )
                }
            }

            val errCode = dlJson.optString("error", "")
            val detail = dlJson.optString("detail", "Torbox failed to generate stream link")
            return@withContext DebridResult.Error(
                type = classifyTorboxError(dlCode, errCode, detail),
                message = detail,
                statusCode = dlCode,
                providerName = name
            )

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) {
                    if (!isExisting && !keepCloudDownloadAlive) {
                        deleteTorrent(torrentId, key)
                    }
                }
                throw e
            }
            Log.e(tag, "Torbox resolveStream error", e)
            return@withContext DebridResult.Error(
                type = DebridErrorType.Unknown,
                message = "Torbox resolution error: ${e.message}",
                providerName = name
            )
        }
    }

    private suspend fun findInMyList(
        infoHash: String,
        key: String,
        allowDownloading: Boolean = true
    ): String? {
        try {
            val listUrl = "$baseUrl/torrents/mylist?bypass_cache=true"
            val listReq = Request.Builder()
                .url(listUrl)
                .header("Authorization", "Bearer $key")
                .build()

            val listResp = NetworkClient.apiClient.newCall(listReq).await()
            val listBody = listResp.body?.string().orEmpty()
            listResp.close()

            if (listResp.isSuccessful && listBody.isNotEmpty()) {
                val listJson = JSONObject(listBody)
                val dataArr = listJson.optJSONArray("data")
                if (dataArr != null) {
                    for (i in 0 until dataArr.length()) {
                        val item = dataArr.optJSONObject(i) ?: continue
                        val itemHash = item.optString("hash", "").lowercase()
                        if (itemHash == infoHash.lowercase()) {
                            val id = item.optString("torrent_id", item.optString("id", ""))
                            if (id.isNotEmpty()) {
                                val dlState = item.optString("download_state", "").lowercase().trim()
                                if (isTorrentObjectReady(item)) {
                                    return id
                                } else if (allowDownloading && dlState !in listOf("error", "missingfiles")) {
                                    return id
                                } else if (dlState in listOf("error", "missingfiles")) {
                                    deleteTorrent(id, key)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun deleteTorrent(torrentId: String?, key: String) {
        if (torrentId.isNullOrEmpty()) return
        try {
            val payload = JSONObject().apply {
                put("torrent_id", torrentId.toLongOrNull() ?: torrentId)
                put("operation", "delete")
            }.toString()
            val req = Request.Builder()
                .url("$baseUrl/torrents/controltorrent")
                .header("Authorization", "Bearer $key")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val resp = NetworkClient.apiClient.newCall(req).await()
            resp.close()
            Log.d(tag, "[TORBOX] Deleted uncached/stale torrent ID: $torrentId")
        } catch (_: Exception) {}
    }
}
