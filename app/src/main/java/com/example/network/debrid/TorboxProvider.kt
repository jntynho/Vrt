package com.example.network.debrid

import android.util.Log
import com.example.network.NetworkClient
import com.example.network.await
import com.example.network.torrent.MagnetParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class TorboxProvider(private val apiKeyProvider: () -> String) : DebridProvider {
    override val name: String = "Torbox"
    private val tag = "TorboxProvider"
    private val baseUrl = "https://api.torbox.app/v1/api"

    companion object {
        private val cacheCheckResults = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, CacheState>>()
    }

    override fun isConfigured(): Boolean = apiKeyProvider().trim().isNotEmpty()

    override suspend fun checkCache(hash: String): CacheState = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return@withContext CacheState.UNKNOWN

        val infoHash = MagnetParser.parseHash(hash)?.lowercase() ?: return@withContext CacheState.UNKNOWN

        // In-memory 30s cache to avoid duplicate network latency
        val cached = cacheCheckResults[infoHash]
        if (cached != null && System.currentTimeMillis() - cached.first < 30_000L) {
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

    override suspend fun resolveStream(magnetOrQuery: String): DebridResult = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) {
            return@withContext DebridResult.Error(
                type = DebridErrorType.InvalidKey,
                message = "Torbox API key is missing. Please configure it in Settings.",
                providerName = name
            )
        }

        val trimmed = magnetOrQuery.trim()
        val infoHash = MagnetParser.parseHash(trimmed)
        val canonicalMagnet = if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            trimmed
        } else if (infoHash != null) {
            MagnetParser.toCanonicalMagnet(infoHash)
        } else {
            trimmed
        }

        if (infoHash != null) {
            val cacheState = checkCache(infoHash)
            if (cacheState == CacheState.NOT_CACHED) {
                return@withContext DebridResult.Error(
                    type = DebridErrorType.NotCached,
                    message = "Torrent is not cached on Torbox.",
                    providerName = name
                )
            }
        }

        try {
            Log.d(tag, "[TORBOX] Resolving stream...")

            var torrentId: String? = null
            var selectedFileId: String? = null
            var filename: String? = null
            var sizeBytes: Long? = null

            // Step 1: Add torrent
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
            val createCode = createResp.code
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
                val dataObj = createJson.optJSONObject("data")
                if (dataObj != null) {
                    torrentId = dataObj.optString("torrent_id", dataObj.optString("id", ""))
                    if (torrentId.isEmpty()) {
                        val qId = dataObj.opt("queued_id")?.toString()
                        if (!qId.isNullOrEmpty()) torrentId = qId
                    }

                    // Fast-path: Check if cached files array was already returned directly
                    val directFilesArr = dataObj.optJSONArray("files")
                    if (directFilesArr != null && directFilesArr.length() > 0) {
                        val fileList = mutableListOf<DebridFileInfo>()
                        for (i in 0 until directFilesArr.length()) {
                            val fObj = directFilesArr.optJSONObject(i) ?: continue
                            val fId = fObj.opt("id")?.toString() ?: i.toString()
                            val fName = fObj.optString("name", fObj.optString("short_name", ""))
                            val fSize = fObj.optLong("size", fObj.optLong("bytes", 0L))
                            fileList.add(DebridFileInfo(fId, fName, fSize))
                        }
                        val bestFile = VideoFilePicker.pickBestVideoFile(fileList)
                        if (bestFile != null) {
                            selectedFileId = bestFile.id
                            filename = bestFile.name
                            sizeBytes = bestFile.sizeBytes
                        }
                    }
                } else {
                    val simpleId = createJson.optString("torrent_id", createJson.optString("id", ""))
                    if (simpleId.isNotEmpty()) torrentId = simpleId
                }

                // Check detail / error message if torrent already existed
                if (torrentId.isNullOrEmpty()) {
                    val detail = createJson.optString("detail", "")
                    val match = Regex("""(?:torrent_id|id)[^0-9]*([0-9]+)""", RegexOption.IGNORE_CASE).find(detail)
                    if (match != null) {
                        torrentId = match.groupValues[1]
                    }
                }
            }

            // Fallback search in mylist if torrentId was not directly returned
            if (torrentId.isNullOrEmpty() && infoHash != null) {
                torrentId = findInMyList(infoHash, key)
            }

            if (torrentId.isNullOrEmpty()) {
                return@withContext DebridResult.Error(
                    type = DebridErrorType.Unknown,
                    message = "Unable to create or locate torrent on Torbox account.",
                    providerName = name
                )
            }

            // Step 2: Poll mylist only if files were not already extracted in Step 1
            if (selectedFileId == null) {
                val startTime = System.currentTimeMillis()
                var torboxPollDelay = 60L // Instant first poll at 60ms
                while (System.currentTimeMillis() - startTime < 18_000L) {
                    val infoReq = Request.Builder()
                        .url("$baseUrl/torrents/mylist?id=$torrentId")
                        .header("Authorization", "Bearer $key")
                        .build()

                    val infoResp = NetworkClient.apiClient.newCall(infoReq).await()
                    val infoBody = infoResp.body?.string().orEmpty()
                    val infoCode = infoResp.code
                    infoResp.close()

                    if (infoCode in 200..299 && infoBody.isNotEmpty()) {
                        val infoJson = JSONObject(infoBody)
                        val dataObj = infoJson.optJSONObject("data")
                        val dataArr = infoJson.optJSONArray("data")
                        
                        val filesArr = if (dataObj != null) {
                            dataObj.optJSONArray("files")
                        } else if (dataArr != null && dataArr.length() > 0) {
                            dataArr.optJSONObject(0)?.optJSONArray("files")
                        } else {
                            null
                        }

                        if (filesArr != null && filesArr.length() > 0) {
                            val fileList = mutableListOf<DebridFileInfo>()
                            for (i in 0 until filesArr.length()) {
                                val fObj = filesArr.optJSONObject(i) ?: continue
                                val fId = fObj.opt("id")?.toString() ?: i.toString()
                                val fName = fObj.optString("name", fObj.optString("short_name", ""))
                                val fSize = fObj.optLong("size", fObj.optLong("bytes", 0L))
                                fileList.add(DebridFileInfo(fId, fName, fSize))
                            }

                            val bestFile = VideoFilePicker.pickBestVideoFile(fileList)
                            if (bestFile != null) {
                                selectedFileId = bestFile.id
                                filename = bestFile.name
                                sizeBytes = bestFile.sizeBytes
                                break
                            }
                        }
                    }
                    delay(torboxPollDelay)
                    torboxPollDelay = if (torboxPollDelay < 120L) 120L else if (torboxPollDelay < 250L) 250L else (torboxPollDelay + 150L).coerceAtMost(600L)
                }
            }

            // Step 3: Request Direct Link
            val fileParam = if (!selectedFileId.isNullOrEmpty()) "&file_id=$selectedFileId" else ""
            val requestDlUrl = "$baseUrl/torrents/requestdl?torrent_id=$torrentId$fileParam&zip_link=false"

            val dlReq = Request.Builder()
                .url(requestDlUrl)
                .header("Authorization", "Bearer $key")
                .build()

            val dlResp = NetworkClient.apiClient.newCall(dlReq).await()
            val dlBody = dlResp.body?.string().orEmpty()
            val dlCode = dlResp.code
            dlResp.close()

            if (dlCode !in 200..299) {
                return@withContext DebridResult.Error(
                    type = if (dlCode in listOf(401, 403)) DebridErrorType.InvalidKey else DebridErrorType.Unknown,
                    message = "Torbox requestdl failed (HTTP $dlCode): $dlBody",
                    statusCode = dlCode,
                    providerName = name
                )
            }

            val dlJson = JSONObject(dlBody)
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
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                        ),
                        providerName = name
                    )
                }
            }

            val detail = dlJson.optString("detail", "Torbox failed to generate stream link")
            return@withContext DebridResult.Error(
                type = DebridErrorType.Unknown,
                message = detail,
                statusCode = dlCode,
                providerName = name
            )

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(tag, "Torbox resolveStream error", e)
            return@withContext DebridResult.Error(
                type = DebridErrorType.Unknown,
                message = "Torbox resolution error: ${e.message}",
                providerName = name
            )
        }
    }

    private suspend fun findInMyList(infoHash: String, key: String): String? {
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
                            if (id.isNotEmpty()) return id
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
