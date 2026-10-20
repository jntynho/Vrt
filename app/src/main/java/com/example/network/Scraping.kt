package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.regex.Pattern

private const val SCRAPER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

private fun detectStreamFormat(urlOrName: String): String {
    val ext = MediaUrlValidator.mediaExtensionOf(urlOrName)
    return when {
        ext == "m3u8" || urlOrName.contains(".m3u8", ignoreCase = true) -> "HLS"
        ext == "mpd" || urlOrName.contains(".mpd", ignoreCase = true) -> "DASH"
        ext == "mkv" || urlOrName.contains(".mkv", ignoreCase = true) -> "MKV"
        ext == "webm" || urlOrName.contains(".webm", ignoreCase = true) -> "WEBM"
        else -> "MP4"
    }
}

/**
 * 1. Pixeldrain Stream Scraper (pixeldrain.com / pixeldrain.net)
 * Extracts ONLY the direct playable stream URL(s).
 */
object PixeldrainScraper {
    private const val TAG = "PixeldrainScraper"

    fun isPixeldrainUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("pixeldrain.com") ||
                trimmed.contains("pixeldrain.net") ||
                trimmed.contains("/api/pixeldrain")
    }

    fun parseFileId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val matcher = Pattern.compile(
            "(?:pixeldrain\\.(?:com|net)/(?:u|file|api/file)/)([a-zA-Z0-9_-]+)",
            Pattern.CASE_INSENSITIVE
        ).matcher(url.trim())
        return if (matcher.find()) matcher.group(1) else null
    }

    fun parseListId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val matcher = Pattern.compile(
            "(?:pixeldrain\\.(?:com|net)/(?:l|list|api/list)/)([a-zA-Z0-9_-]+)",
            Pattern.CASE_INSENSITIVE
        ).matcher(url.trim())
        return if (matcher.find()) matcher.group(1) else null
    }

    suspend fun extractStreams(url: String): List<StreamQuality> = withContext(Dispatchers.IO) {
        val headers = mapOf(
            "User-Agent" to SCRAPER_USER_AGENT,
            "Referer" to "https://pixeldrain.com/"
        )

        val fileId = parseFileId(url)
        if (!fileId.isNullOrBlank()) {
            val directStreamUrl = "https://pixeldrain.com/api/file/$fileId"
            return@withContext listOf(
                StreamQuality(
                    quality = "Direct Stream",
                    url = directStreamUrl,
                    format = "MP4",
                    isDefault = true,
                    headers = headers
                )
            )
        }

        val listId = parseListId(url)
        if (!listId.isNullOrBlank()) {
            try {
                val req = Request.Builder()
                    .url("https://pixeldrain.com/api/list/$listId")
                    .header("User-Agent", SCRAPER_USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                val response = NetworkClient.apiClient.newCall(req).await()
                val bodyStr = response.body?.string().orEmpty()
                response.close()

                if (response.isSuccessful && bodyStr.isNotBlank()) {
                    val json = JSONObject(bodyStr)
                    val filesArr = json.optJSONArray("files")
                    if (filesArr != null && filesArr.length() > 0) {
                        val streams = mutableListOf<StreamQuality>()
                        for (i in 0 until filesArr.length()) {
                            val f = filesArr.optJSONObject(i) ?: continue
                            val id = f.optString("id")
                            val mime = f.optString("mime_type")
                            val name = f.optString("name")
                            if (id.isNotBlank() && (mime.isEmpty() || mime.startsWith("video/") || name.matches(Regex(".*\\.(mp4|mkv|webm|mov|avi)$", RegexOption.IGNORE_CASE)))) {
                                val streamUrl = "https://pixeldrain.com/api/file/$id"
                                streams.add(
                                    StreamQuality(
                                        quality = if (streams.isEmpty()) "Direct Stream" else "Stream ${streams.size + 1}",
                                        url = streamUrl,
                                        format = detectStreamFormat(name),
                                        isDefault = streams.isEmpty(),
                                        headers = headers
                                    )
                                )
                            }
                        }
                        if (streams.isNotEmpty()) {
                            return@withContext streams
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract Pixeldrain list $listId: ${e.message}")
            }
        }

        throw Exception("Could not extract playable stream from Pixeldrain URL")
    }
}

/**
 * 2. Filester Stream Scraper (filester.gg / filester.sh / filester.si / filester.me)
 * Extracts ONLY the signed direct stream URL.
 */
object FilesterScraper {
    private const val TAG = "FilesterScraper"

    fun isFilesterUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("filester.") || trimmed.contains("/api/filester")
    }

    fun extractSlug(url: String): String? {
        val trimmed = url.trim()
        try {
            val normalized = if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
            val uri = URI(normalized)
            val parts = (uri.path ?: "").split("/").filter { it.isNotBlank() }
            if (parts.size >= 2 && parts[0].lowercase() in listOf("d", "v", "f", "file", "watch")) {
                return parts[1]
            }
            if (parts.isNotEmpty()) {
                return parts.last()
            }
        } catch (_: Exception) {
            val matcher = Pattern.compile(
                "filester\\.[a-z]+/(?:d|v|f|file|watch)?/?([a-zA-Z0-9_-]+)",
                Pattern.CASE_INSENSITIVE
            ).matcher(trimmed)
            if (matcher.find()) return matcher.group(1)
        }
        return null
    }

    suspend fun extractStreams(url: String): List<StreamQuality> = withContext(Dispatchers.IO) {
        val rawUrl = url.trim()

        val playbackHeaders = mapOf(
            "User-Agent" to SCRAPER_USER_AGENT,
            "Referer" to "https://filester.sh/"
        )

        // If already a signed direct CDN stream URL
        if (rawUrl.contains("/v2/") && rawUrl.contains("token=")) {
            return@withContext listOf(
                StreamQuality(
                    quality = "Direct Stream",
                    url = rawUrl,
                    format = detectStreamFormat(rawUrl),
                    isDefault = true,
                    headers = playbackHeaders
                )
            )
        }

        val slug = extractSlug(rawUrl)
            ?: throw Exception("Could not extract Filester file slug")

        val hostOrigin = try {
            val uri = URI(if (rawUrl.startsWith("http", ignoreCase = true)) rawUrl else "https://$rawUrl")
            if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                "${uri.scheme}://${uri.host}"
            } else {
                "https://filester.gg"
            }
        } catch (_: Exception) {
            "https://filester.gg"
        }

        val apiEndpoints = listOf(
            "$hostOrigin/v2/api/public/view",
            "https://filester.gg/v2/api/public/view",
            "https://filester.sh/v2/api/public/view",
            "https://filester.si/v2/api/public/view",
            "$hostOrigin/v2/api/public/download",
            "https://filester.gg/v2/api/public/download",
            "https://filester.sh/v2/api/public/download",
            "https://filester.si/v2/api/public/download"
        ).distinct()

        val payload = JSONObject().put("file_slug", slug).toString()
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()

        var tokenData: JSONObject? = null

        for (endpoint in apiEndpoints) {
            try {
                val endpointOrigin = try {
                    val u = URI(endpoint)
                    "${u.scheme}://${u.host}"
                } catch (_: Exception) {
                    hostOrigin
                }

                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toRequestBody(jsonMediaType))
                    .header("User-Agent", SCRAPER_USER_AGENT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Origin", endpointOrigin)
                    .header("Referer", rawUrl)
                    .build()

                val response = NetworkClient.apiClient.newCall(req).await()
                val bodyStr = response.body?.string().orEmpty()
                response.close()

                if (response.isSuccessful && bodyStr.isNotBlank()) {
                    val data = JSONObject(bodyStr)
                    val token = data.optString("token")
                    val file = data.optString("file")
                    if (token.isNotBlank() && file.isNotBlank()) {
                        tokenData = data
                        break
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Filester endpoint $endpoint failed: ${e.message}")
            }
        }

        val resolvedData = tokenData
            ?: throw Exception("Could not generate Filester media stream token")

        val baseServer = resolvedData.optString("server").takeIf { it.isNotBlank() } ?: "https://fsc2.cdn.cr"
        val file = resolvedData.optString("file")
        val token = resolvedData.optString("token")
        val encodedToken = URLEncoder.encode(token, "UTF-8")

        val streamUrl = "$baseServer/v2/$file?token=$encodedToken"

        listOf(
            StreamQuality(
                quality = "Direct Stream",
                url = streamUrl,
                format = detectStreamFormat(file),
                isDefault = true,
                headers = playbackHeaders
            )
        )
    }
}

/**
 * 3. Bunkr Stream Scraper (bunkr.* / bunkrr.* / cdn.cr/storage/media)
 * Extracts ONLY the signed direct stream URL.
 */
object BunkrScraper {
    private const val TAG = "BunkrScraper"

    fun isBunkrUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("bunkr.") ||
                trimmed.contains("bunkrr.") ||
                trimmed.contains("cdn.cr/storage/media") ||
                trimmed.contains("/api/bunkr")
    }

    suspend fun extractStreams(url: String): List<StreamQuality> = withContext(Dispatchers.IO) {
        val rawUrl = url.trim()
        val playbackHeaders = mapOf(
            "User-Agent" to SCRAPER_USER_AGENT,
            "Referer" to rawUrl
        )

        // If already a signed CDN URL with token and ex
        if (rawUrl.contains("token=") && rawUrl.contains("ex=")) {
            return@withContext listOf(
                StreamQuality(
                    quality = "Direct Stream",
                    url = rawUrl,
                    format = detectStreamFormat(rawUrl),
                    isDefault = true,
                    headers = playbackHeaders
                )
            )
        }

        val pageReq = Request.Builder()
            .url(rawUrl)
            .header("User-Agent", SCRAPER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val pageRes = NetworkClient.okHttpClient.newCall(pageReq).await()
        val html = pageRes.body?.string().orEmpty()
        val status = pageRes.code
        pageRes.close()

        if (status !in 200..299 || html.isBlank()) {
            throw Exception("Failed to fetch Bunkr page: HTTP $status")
        }

        val jsCDNMatcher = Pattern.compile("""var\s+jsCDN\s*=\s*"([^"]+)"""").matcher(html)
        val signUrlMatcher = Pattern.compile("""var\s+signUrl\s*=\s*"([^"]+)"""").matcher(html)

        if (!jsCDNMatcher.find()) {
            // Fallback: check direct <source src="..."> or <video src="...">
            val videoSrcMatcher = Pattern.compile(
                """<(?:source|video)\s+[^>]*src=["']([^"']+)["']""",
                Pattern.CASE_INSENSITIVE
            ).matcher(html)
            if (videoSrcMatcher.find()) {
                val directUrl = videoSrcMatcher.group(1)?.replace("\\/", "/")?.trim().orEmpty()
                if (directUrl.isNotBlank()) {
                    return@withContext listOf(
                        StreamQuality(
                            quality = "Direct Stream",
                            url = directUrl,
                            format = detectStreamFormat(directUrl),
                            isDefault = true,
                            headers = playbackHeaders
                        )
                    )
                }
            }
            throw Exception("Could not find media source in Bunkr page")
        }

        val jsCDN = (jsCDNMatcher.group(1) ?: "").replace("\\/", "/").trim()
        val signUrl = if (signUrlMatcher.find()) {
            (signUrlMatcher.group(1) ?: "").replace("\\/", "/").trim()
        } else {
            "https://glb-apisign.cdn.cr/sign"
        }

        val origUri = URI(jsCDN)
        val rawPath = origUri.path ?: throw Exception("Invalid Bunkr CDN path")
        val encodedPath = URLEncoder.encode(rawPath, "UTF-8")
        val signEndpoint = "$signUrl?path=$encodedPath"

        val signReq = Request.Builder()
            .url(signEndpoint)
            .header("User-Agent", SCRAPER_USER_AGENT)
            .header("Referer", rawUrl)
            .header("Accept", "application/json")
            .build()

        val signRes = NetworkClient.apiClient.newCall(signReq).await()
        val signBody = signRes.body?.string().orEmpty()
        val signStatus = signRes.code
        signRes.close()

        if (!signRes.isSuccessful || signBody.isBlank()) {
            throw Exception("Failed to sign Bunkr media: HTTP $signStatus")
        }

        val signJson = JSONObject(signBody)
        val token = signJson.optString("token")
        val ex = signJson.optString("ex")
        if (token.isBlank()) {
            throw Exception("Invalid sign response from Bunkr signer")
        }

        val separator = if (jsCDN.contains("?")) "&" else "?"
        val signedStreamUrl = buildString {
            append(jsCDN)
            append(separator)
            append("token=").append(URLEncoder.encode(token, "UTF-8"))
            if (ex.isNotBlank()) {
                append("&ex=").append(URLEncoder.encode(ex, "UTF-8"))
            }
        }

        listOf(
            StreamQuality(
                quality = "Direct Stream",
                url = signedStreamUrl,
                format = detectStreamFormat(rawPath),
                isDefault = true,
                headers = playbackHeaders
            )
        )
    }
}

/**
 * 4. Gofile Stream Scraper (gofile.io)
 * Extracts ONLY the playable stream URL(s) and required session auth headers.
 */
object GofileScraper {
    private const val TAG = "GofileScraper"
    private const val GOFILE_LANGUAGE = "en-US"

    private val FALLBACK_GUEST_TOKENS = listOf(
        "6sX8fan7UHsOPIj9IQhG7V55agPuLBti",
        "097942702b470c704f85a6f333a27a480d33e8969594b76e179f11358be69f2f"
    )

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedTokenExpiry: Long = 0L

    @Volatile
    private var cachedDynamicSalt: String? = null

    @Volatile
    private var serverTimeOffsetMs: Long? = null

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun updateServerTimeFromResponse(response: okhttp3.Response) {
        try {
            val serverDate = response.headers.getDate("Date")
            if (serverDate != null) {
                serverTimeOffsetMs = serverDate.time - System.currentTimeMillis()
            }
        } catch (_: Exception) {}
    }

    private suspend fun getAccurateEpochSeconds(): Long {
        val offset = serverTimeOffsetMs
        if (offset != null) {
            return (System.currentTimeMillis() + offset) / 1000L
        }
        for (probeUrl in listOf("https://api.gofile.io/accounts", "https://www.google.com")) {
            try {
                val req = Request.Builder()
                    .url(probeUrl)
                    .head()
                    .header("User-Agent", SCRAPER_USER_AGENT)
                    .build()
                val res = NetworkClient.apiClient.newCall(req).await()
                updateServerTimeFromResponse(res)
                res.close()
                val updatedOffset = serverTimeOffsetMs
                if (updatedOffset != null) {
                    return (System.currentTimeMillis() + updatedOffset) / 1000L
                }
            } catch (_: Exception) {}
        }
        return System.currentTimeMillis() / 1000L
    }

    private fun generateWebsiteToken(token: String, salt: String, timeBucket: Long): String {
        val raw = "$SCRAPER_USER_AGENT::$GOFILE_LANGUAGE::$token::$timeBucket::$salt"
        return sha256Hex(raw)
    }

    private suspend fun resolveCandidateSalts(): List<String> {
        val salts = linkedSetOf("4fd6sg89d7s6", "12af056dacea0b")
        cachedDynamicSalt?.let { if (it.isNotBlank()) salts.add(it) }

        if (cachedDynamicSalt == null) {
            for (scriptUrl in listOf("https://gofile.io/js/wt.obf.js", "https://gofile.io/dist/js/config.js")) {
                try {
                    val req = Request.Builder()
                        .url(scriptUrl)
                        .get()
                        .header("User-Agent", SCRAPER_USER_AGENT)
                        .header("Referer", "https://gofile.io/")
                        .build()
                    val res = NetworkClient.apiClient.newCall(req).await()
                    updateServerTimeFromResponse(res)
                    val body = res.body?.string().orEmpty()
                    res.close()
                    if (body.isNotBlank()) {
                        val m1 = Pattern.compile("::([a-zA-Z0-9]{8,24})\"?").matcher(body)
                        while (m1.find()) {
                            val s = m1.group(1).orEmpty()
                            if (s.isNotBlank() && !s.contains("en-US")) {
                                cachedDynamicSalt = s
                                salts.add(s)
                            }
                        }
                        val m2 = Pattern.compile("wt\\s*[:=]\\s*[\"']([^\"']+)[\"']").matcher(body)
                        if (m2.find()) {
                            val s = m2.group(1).orEmpty()
                            if (s.isNotBlank()) {
                                cachedDynamicSalt = s
                                salts.add(s)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return salts.toList()
    }

    fun isGofileUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("gofile.io") || trimmed.contains("/api/gofile")
    }

    fun extractContentId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()

        val matchD = Pattern.compile("gofile\\.io/d/([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (matchD.find()) return matchD.group(1)

        val matchDirect = Pattern.compile("gofile\\.io/download/web/([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (matchDirect.find()) return matchDirect.group(1)

        try {
            val normalized = if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
            val u = URI(normalized)
            val parts = (u.path ?: "").split("/").filter { it.isNotBlank() }
            if (parts.size >= 2 && parts[0].equals("d", ignoreCase = true)) {
                return parts[1]
            }
            if (parts.size == 1 && parts[0].length >= 5) {
                return parts[0]
            }
        } catch (_: Exception) {}

        return null
    }

    private suspend fun getOrRefreshGuestToken(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        val existing = cachedToken
        if (!forceRefresh && !existing.isNullOrBlank() && now < cachedTokenExpiry) {
            return existing
        }

        // Strategy 1: POST https://api.gofile.io/accounts with empty body (matches browser fetch)
        // Strategy 2: GET https://api.gofile.io/accounts
        val requests = listOf(
            Request.Builder()
                .url("https://api.gofile.io/accounts")
                .post("".toRequestBody(null))
                .header("User-Agent", SCRAPER_USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "https://gofile.io")
                .header("Referer", "https://gofile.io/")
                .build(),
            Request.Builder()
                .url("https://api.gofile.io/accounts")
                .get()
                .header("User-Agent", SCRAPER_USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "https://gofile.io")
                .header("Referer", "https://gofile.io/")
                .build()
        )

        for (req in requests) {
            try {
                val res = NetworkClient.apiClient.newCall(req).await()
                updateServerTimeFromResponse(res)
                val bodyStr = res.body?.string().orEmpty()
                val isOk = res.isSuccessful
                res.close()

                if (isOk && bodyStr.isNotBlank()) {
                    val json = JSONObject(bodyStr)
                    if (json.optString("status") == "ok") {
                        val token = json.optJSONObject("data")?.optString("token").orEmpty()
                        if (token.isNotBlank()) {
                            cachedToken = token
                            cachedTokenExpiry = System.currentTimeMillis() + (2 * 60 * 60 * 1000L)
                            seedGofileCookies(token)
                            return token
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Gofile account request failed (${req.method}): ${e.message}")
            }
        }

        // Strategy 3: If rate-limited (HTTP 429) or transient error, reuse existing or verified guest token pool
        if (!existing.isNullOrBlank() && !forceRefresh) {
            seedGofileCookies(existing)
            return existing
        }
        val fallback = FALLBACK_GUEST_TOKENS.firstOrNull { it != existing } ?: FALLBACK_GUEST_TOKENS.first()
        cachedToken = fallback
        cachedTokenExpiry = System.currentTimeMillis() + (30 * 60 * 1000L)
        seedGofileCookies(fallback)
        return fallback
    }

    private fun seedGofileCookies(token: String) {
        NetworkClient.seedCookie("https://gofile.io/", "accountToken", token, "gofile.io")
        NetworkClient.seedCookie("https://api.gofile.io/", "accountToken", token, "api.gofile.io")
    }

    private fun buildStreamHeaders(token: String): Map<String, String> = mapOf(
        "User-Agent" to SCRAPER_USER_AGENT,
        "Referer" to "https://gofile.io/",
        "Origin" to "https://gofile.io",
        "Authorization" to "Bearer $token",
        "Cookie" to "accountToken=$token"
    )

    private data class GofileCandidate(
        val name: String,
        val size: Long,
        val mimeType: String,
        val link: String,
        val qualityLabel: String?,
        val isSample: Boolean
    )

    private fun collectFilesFromData(
        data: JSONObject,
        out: MutableList<Triple<String, Long, Pair<String, String>>>
    ) {
        if (data.optString("type") == "file") {
            val link = data.optString("link")
            if (link.isNotBlank()) {
                out.add(
                    Triple(
                        data.optString("name", "video.mp4"),
                        data.optLong("size", 0L),
                        Pair(data.optString("mimetype", "video/mp4"), link)
                    )
                )
            }
        }
        val children = data.optJSONObject("children") ?: return
        val keys = children.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = children.optJSONObject(key) ?: continue
            val childType = child.optString("type")
            if (childType == "file") {
                val link = child.optString("link")
                if (link.isNotBlank()) {
                    out.add(
                        Triple(
                            child.optString("name", "video.mp4"),
                            child.optLong("size", 0L),
                            Pair(child.optString("mimetype", "video/mp4"), link)
                        )
                    )
                }
            } else if (childType == "folder" && child.optJSONObject("children") != null) {
                collectFilesFromData(child, out)
            }
        }
    }

    suspend fun extractStreams(url: String): List<StreamQuality> = withContext(Dispatchers.IO) {
        val rawUrl = url.trim()

        val initialToken = getOrRefreshGuestToken(forceRefresh = false)

        // Direct web download link (store*.gofile.io/download/web/...)
        if (rawUrl.contains("gofile.io/download/web/", ignoreCase = true)) {
            seedGofileCookies(initialToken)
            try {
                val u = URI(rawUrl)
                if (!u.host.isNullOrBlank()) {
                    NetworkClient.seedCookie(rawUrl, "accountToken", initialToken, u.host)
                }
            } catch (_: Exception) {}
            val headers = buildStreamHeaders(initialToken)
            return@withContext listOf(
                StreamQuality(
                    quality = "Direct Stream",
                    url = rawUrl,
                    format = detectStreamFormat(rawUrl),
                    isDefault = true,
                    headers = headers
                )
            )
        }

        val contentId = extractContentId(rawUrl)
            ?: throw Exception("Could not extract Gofile content ID")

        val serverEpochSeconds = getAccurateEpochSeconds()
        val serverTimeBucket = serverEpochSeconds / 14400L
        val localTimeBucket = (System.currentTimeMillis() / 1000L) / 14400L
        val timeBuckets = linkedSetOf(serverTimeBucket, serverTimeBucket - 1L, localTimeBucket).toList()

        val salts = resolveCandidateSalts()
        val candidateTokens = linkedSetOf<String>().apply {
            add(initialToken)
            addAll(FALLBACK_GUEST_TOKENS)
        }.toList()

        var contentData: JSONObject? = null
        var activeToken: String = initialToken

        outerLoop@ for (token in candidateTokens) {
            seedGofileCookies(token)
            for (salt in salts) {
                for (bucket in timeBuckets) {
                    val wt = generateWebsiteToken(token, salt, bucket)
                    val encodedId = URLEncoder.encode(contentId, "UTF-8")
                    val endpoint = "https://api.gofile.io/contents/$encodedId?wt=$wt&contentFilter=&page=1&pageSize=1000&sortField=name&sortDirection=1"
                    val req = Request.Builder()
                        .url(endpoint)
                        .get()
                        .header("Authorization", "Bearer $token")
                        .header("X-Website-Token", wt)
                        .header("Cookie", "accountToken=$token")
                        .header("User-Agent", SCRAPER_USER_AGENT)
                        .header("Accept", "*/*")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Origin", "https://gofile.io")
                        .header("Referer", "https://gofile.io/")
                        .build()

                    try {
                        val res = NetworkClient.apiClient.newCall(req).await()
                        updateServerTimeFromResponse(res)
                        val bodyStr = res.body?.string().orEmpty()
                        res.close()
                        if (bodyStr.isNotBlank()) {
                            val json = JSONObject(bodyStr)
                            val status = json.optString("status")
                            if (status == "ok" && json.optJSONObject("data") != null) {
                                contentData = json.optJSONObject("data")
                                activeToken = token
                                cachedToken = token
                                cachedTokenExpiry = System.currentTimeMillis() + (2 * 60 * 60 * 1000L)
                                break@outerLoop
                            }
                            // If server returned error-notFound, no need to retry other salts/tokens
                            if (status.contains("notFound", ignoreCase = true)) {
                                throw Exception("Gofile content not found ($contentId)")
                            }
                        }
                    } catch (e: Exception) {
                        if (e.message?.contains("not found", ignoreCase = true) == true) throw e
                        Log.d(TAG, "Gofile contents fetch attempt failed: ${e.message}")
                    }
                }
            }
        }

        val data = contentData
            ?: throw Exception("Failed to retrieve Gofile stream link")

        val rawCandidates = mutableListOf<Triple<String, Long, Pair<String, String>>>()
        collectFilesFromData(data, rawCandidates)

        if (rawCandidates.isEmpty()) {
            throw Exception("No playable files found in Gofile link")
        }

        val classified = rawCandidates.map { (name, size, mimeAndLink) ->
            val lower = name.lowercase()
            val isSample = lower.contains("sample") || lower.contains("trailer") || lower.contains("preview") ||
                    (size in 1..(40L * 1024 * 1024) && rawCandidates.size > 1)
            val quality = when {
                Regex("2160p|4k|uhd|3840x2160", RegexOption.IGNORE_CASE).containsMatchIn(lower) -> "4K"
                Regex("1080p|fhd|1920x1080", RegexOption.IGNORE_CASE).containsMatchIn(lower) -> "1080p"
                Regex("720p|1280x720", RegexOption.IGNORE_CASE).containsMatchIn(lower) -> "720p"
                Regex("480p|540p|sd", RegexOption.IGNORE_CASE).containsMatchIn(lower) -> "480p"
                else -> null
            }
            GofileCandidate(
                name = name,
                size = size,
                mimeType = mimeAndLink.first,
                link = mimeAndLink.second,
                qualityLabel = quality,
                isSample = isSample
            )
        }

        val videoFiles = classified.filter {
            it.mimeType.startsWith("video/") ||
                    it.name.matches(Regex(".*\\.(mp4|mkv|webm|avi|mov)$", RegexOption.IGNORE_CASE))
        }.ifEmpty { classified }

        val mainVideos = videoFiles.filter { !it.isSample }.ifEmpty { videoFiles }
        val streamHeaders = buildStreamHeaders(activeToken)

        val streams = mutableListOf<StreamQuality>()
        for ((idx, item) in mainVideos.withIndex()) {
            try {
                val u = URI(item.link)
                if (!u.host.isNullOrBlank()) {
                    NetworkClient.seedCookie(item.link, "accountToken", activeToken, u.host)
                }
            } catch (_: Exception) {}
            val label = item.qualityLabel ?: if (mainVideos.size == 1) "Direct Stream" else "Stream ${idx + 1}"
            streams.add(
                StreamQuality(
                    quality = label,
                    url = item.link,
                    format = detectStreamFormat(item.name),
                    isDefault = idx == 0,
                    headers = streamHeaders
                )
            )
        }

        streams
    }
}

/**
 * Unified Scraper Dispatcher for supported direct video hosters:
 * - pixeldrain.com
 * - gofile.io
 * - filester
 * - bunkr
 */
object Scraping {

    fun isSupportedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return PixeldrainScraper.isPixeldrainUrl(url) ||
                FilesterScraper.isFilesterUrl(url) ||
                BunkrScraper.isBunkrUrl(url) ||
                GofileScraper.isGofileUrl(url)
    }

    suspend fun extractStreams(url: String): List<StreamQuality> {
        val trimmed = url.trim()
        return when {
            PixeldrainScraper.isPixeldrainUrl(trimmed) -> PixeldrainScraper.extractStreams(trimmed)
            FilesterScraper.isFilesterUrl(trimmed) -> FilesterScraper.extractStreams(trimmed)
            BunkrScraper.isBunkrUrl(trimmed) -> BunkrScraper.extractStreams(trimmed)
            GofileScraper.isGofileUrl(trimmed) -> GofileScraper.extractStreams(trimmed)
            else -> throw Exception("Unsupported scraper URL: $trimmed")
        }
    }
}
