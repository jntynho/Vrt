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
 * Unified Scraper Dispatcher for supported direct video hosters and streaming services:
 * - pixeldrain.com
 * - filester
 * - bunkr
 * - gofile.io
 * - cyberfile.me
 * - eporner.com
 * - porntrex.com
 * - xhamster.com
 * - pornhub.com
 */
object Scraping {

    fun isSupportedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return PixeldrainScraper.isPixeldrainUrl(url) ||
                FilesterScraper.isFilesterUrl(url) ||
                BunkrScraper.isBunkrUrl(url) ||
                GofileScraper.isGofileUrl(url) ||
                CyberFileScraper.isCyberfileUrl(url) ||
                EpornerScraper.isEpornerUrl(url) ||
                PorntrexScraper.isPorntrexUrl(url) ||
                VideoResolvers.isXhamsterUrl(url) ||
                VideoResolvers.isPornhubUrl(url)
    }

    suspend fun extractStreams(url: String): List<StreamQuality> {
        val trimmed = url.trim()
        return when {
            PixeldrainScraper.isPixeldrainUrl(trimmed) -> PixeldrainScraper.extractStreams(trimmed)
            FilesterScraper.isFilesterUrl(trimmed) -> FilesterScraper.extractStreams(trimmed)
            BunkrScraper.isBunkrUrl(trimmed) -> BunkrScraper.extractStreams(trimmed)
            GofileScraper.isGofileUrl(trimmed) -> GofileScraper.extractStreams(trimmed)
            CyberFileScraper.isCyberfileUrl(trimmed) -> CyberFileScraper.extractStreams(trimmed)
            EpornerScraper.isEpornerUrl(trimmed) -> EpornerScraper.extractStreams(trimmed)
            PorntrexScraper.isPorntrexUrl(trimmed) -> PorntrexScraper.extractStreams(trimmed)
            VideoResolvers.isXhamsterUrl(trimmed) -> VideoResolvers.resolveXhamster(trimmed).qualities
            VideoResolvers.isPornhubUrl(trimmed) -> VideoResolvers.resolvePornhub(trimmed).qualities
            else -> throw Exception("Unsupported scraper URL: $trimmed")
        }
    }
}

