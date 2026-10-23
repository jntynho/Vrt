package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
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
 * 4. xHamster Stream Scraper (xhamster.com and mirrors)
 * Extracts multi-quality HLS variants (up to 2160p 4K / 1080p) and direct MP4 streams
 * by decoding window.initials.xplayerSettings.sources with xplayer's 7 PRNG algorithms,
 * applying window.initials.hostMap CDN subdomain mapping, and parsing HLS master manifests.
 */
object XhamsterScraper {
    private const val TAG = "XhamsterScraper"

    fun isXhamsterUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        if (trimmed.contains("xhcdn.com") || trimmed.contains("ahcdn.com")) return false
        return trimmed.contains("xhamster.") ||
                trimmed.contains("xhamster2.") ||
                trimmed.contains("xhamster3.") ||
                trimmed.contains("xhamster4.") ||
                trimmed.contains("xhamster19.") ||
                trimmed.contains("xhwebsite.") ||
                trimmed.contains("xhopen.") ||
                trimmed.contains("xhaccess.") ||
                Regex("https?://([a-z0-9-]+\\.)*xhamster[a-z0-9-]*\\.[a-z]{2,}").containsMatchIn(trimmed)
    }

    private fun decodeHexBytes(hexStr: String): String? {
        val len = hexStr.length
        if (len < 12 || len % 2 != 0) return null
        for (idx in 0 until len) {
            val c = hexStr[idx]
            val isHex = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
            if (!isHex) return null
        }

        val bytes = IntArray(len shr 1)
        var bIdx = 0
        var sIdx = 0
        while (sIdx < len) {
            val parsed = hexStr.substring(sIdx, sIdx + 2).toIntOrNull(16) ?: return null
            bytes[bIdx++] = parsed
            sIdx += 2
        }
        if (bytes.size < 5) return null

        val algoId = bytes[0]
        if (algoId !in 1..7) return null

        var i = (bytes[1] and 0xFF) or
                ((bytes[2] and 0xFF) shl 8) or
                ((bytes[3] and 0xFF) shl 16) or
                ((bytes[4] and 0xFF) shl 24)

        fun nextPrngByte(): Int {
            return when (algoId) {
                1 -> {
                    i = (i * 1664525) + 0x3c6ef35f
                    i and 0xFF
                }
                2 -> {
                    i = i xor (i shl 13)
                    i = i xor (i ushr 17)
                    i = i xor (i shl 5)
                    i and 0xFF
                }
                3 -> {
                    i += 0x9e3779b9.toInt()
                    var e = i
                    e = e xor (e ushr 16)
                    e *= 0x85ebca77.toInt()
                    e = e xor (e ushr 13)
                    e *= 0xc2b2ae3d.toInt()
                    e = e xor (e ushr 16)
                    e and 0xFF
                }
                4 -> {
                    i += 0x6d2b79f5
                    var e = (i shl 7) or (i ushr 25)
                    e += 0x9e3779b9.toInt()
                    e = e xor (e ushr 11)
                    e *= 0x27d4eb2d
                    e and 0xFF
                }
                5 -> {
                    i = i xor (i shl 7)
                    i = i xor (i ushr 9)
                    i = i xor (i shl 8)
                    i += 0xa5a5a5a5.toInt()
                    i and 0xFF
                }
                6 -> {
                    i = (i * 0x2c9277b5) + 0xac564b05.toInt()
                    val shifted = (i xor (i ushr 18)) ushr ((i ushr 27) and 31)
                    shifted and 0xFF
                }
                7 -> {
                    i += 0x9e3779b9.toInt()
                    var e = i xor (i shl 5)
                    e *= 0x7feb352d
                    e = e xor (e ushr 15)
                    e *= 0x846ca68b.toInt()
                    e and 0xFF
                }
                else -> 0
            }
        }

        val outBytes = ByteArray(bytes.size - 5)
        for (k in 5 until bytes.size) {
            outBytes[k - 5] = ((bytes[k] xor nextPrngByte()) and 0xFF).toByte()
        }

        val decoded = try {
            String(outBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            outBytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        }

        if (decoded.isEmpty()) return null
        for (ch in decoded) {
            if (ch.code !in 0x20..0x7E) return null
        }
        return decoded
    }

    fun decodeXhamsterUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()

        // 1. Try decoding the entire string as hex payload
        decodeHexBytes(trimmed)?.let { return it }

        // 2. Try decoding obfuscated first path segment after host
        val schemeIdx = trimmed.indexOf("://")
        val firstSlash = if (schemeIdx != -1) trimmed.indexOf('/', schemeIdx + 3) else trimmed.indexOf('/')
        if (firstSlash != -1 && firstSlash + 1 < trimmed.length) {
            val segStart = firstSlash + 1
            val nextSlash = trimmed.indexOf('/', segStart)
            val nextComma = trimmed.indexOf(',', segStart)
            val segEnd = when {
                nextSlash != -1 && nextComma != -1 -> minOf(nextSlash, nextComma)
                nextSlash != -1 -> nextSlash
                nextComma != -1 -> nextComma
                else -> -1
            }
            if (segEnd > segStart) {
                val segment = trimmed.substring(segStart, segEnd)
                val decodedSeg = decodeHexBytes(segment)
                if (decodedSeg != null) {
                    return trimmed.substring(0, segStart) + decodedSeg + trimmed.substring(segEnd)
                }
            }
        }

        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("//")
        ) {
            if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        } else {
            null
        }
    }

    fun applyHostMap(url: String?, subdomainOverride: String?): String? {
        if (url.isNullOrBlank()) return null
        val normalized = if (url.startsWith("//")) "https:$url" else url.trim()
        if (subdomainOverride.isNullOrBlank()) return normalized
        return try {
            val uri = URI(normalized)
            val host = uri.host ?: return normalized
            val parts = host.split(".").toMutableList()
            if (parts.size < 2) return normalized
            parts[0] = subdomainOverride.trim()
            val newHost = parts.joinToString(".")
            URI(
                uri.scheme ?: "https",
                uri.userInfo,
                newHost,
                uri.port,
                uri.rawPath,
                uri.rawQuery,
                uri.rawFragment
            ).toString()
        } catch (_: Exception) {
            normalized
        }
    }

    private fun extractWindowInitialsJson(html: String): JSONObject? {
        val marker = "window.initials"
        var searchStart = 0
        while (true) {
            val idx = html.indexOf(marker, searchStart)
            if (idx == -1) break
            val eqIdx = html.indexOf('=', idx + marker.length)
            if (eqIdx == -1) break
            val braceStart = html.indexOf('{', eqIdx + 1)
            if (braceStart == -1) break

            var depth = 0
            var inString = false
            var escaped = false
            var endIdx = -1

            for (i in braceStart until html.length) {
                val c = html[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '"') {
                        inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                endIdx = i
                                break
                            }
                        }
                    }
                }
            }

            if (endIdx > braceStart) {
                val jsonCandidate = html.substring(braceStart, endIdx + 1)
                try {
                    return JSONObject(jsonCandidate)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse window.initials candidate: ${e.message}")
                }
            }
            searchStart = braceStart + 1
        }
        return null
    }

    private fun normalizeResolutionLabel(heightOrLabel: String, isMp4Variant: Boolean = false): String {
        val lower = heightOrLabel.lowercase().trim()
        val height = when {
            lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> 2160
            lower.contains("1440") || lower.contains("2k") || lower.contains("qhd") -> 1440
            lower.contains("1080") || lower.contains("fhd") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            lower.contains("240") -> 240
            lower.contains("144") -> 144
            else -> lower.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
        val base = when (height) {
            2160 -> "2160p (4K)"
            1440 -> "1440p"
            1080 -> "1080p"
            720 -> "720p"
            480 -> "480p"
            360 -> "360p"
            240 -> "240p"
            144 -> "144p"
            else -> if (height > 0) "${height}p" else heightOrLabel
        }
        return if (isMp4Variant && (height == 1080 || height == 720)) "$base (MP4)" else base
    }

    private fun resolutionRank(label: String): Int {
        val lower = label.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> 2160
            lower.contains("1440") || lower.contains("2k") -> 1440
            lower.contains("1080") -> if (lower.contains("mp4")) 1079 else 1080
            lower.contains("720") -> if (lower.contains("mp4")) 719 else 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            lower.contains("240") -> 240
            lower.contains("144") -> 144
            lower.contains("auto") -> 50
            else -> 100
        }
    }

    private fun resolveRelativeUrl(baseManifestUrl: String, relativeOrAbsolute: String): String {
        val trimmed = relativeOrAbsolute.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            return "https:$trimmed"
        }
        return try {
            URI(baseManifestUrl).resolve(trimmed).toString()
        } catch (_: Exception) {
            val baseDir = baseManifestUrl.substringBeforeLast('/')
            "$baseDir/$trimmed"
        }
    }

    private suspend fun parseHlsMasterVariants(
        masterUrl: String,
        headers: Map<String, String>
    ): List<Pair<String, String>> {
        val variants = mutableListOf<Pair<String, String>>()
        try {
            val reqBuilder = Request.Builder().url(masterUrl)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            val res = NetworkClient.apiClient.newCall(reqBuilder.build()).await()
            val body = res.body?.string().orEmpty()
            val isOk = res.isSuccessful
            res.close()

            if (isOk && body.contains("#EXTM3U")) {
                val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
                var i = 0
                while (i < lines.size) {
                    val line = lines[i]
                    if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        val resMatcher = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE).matcher(line)
                        val heightFromAttr = if (resMatcher.find()) resMatcher.group(2) else null

                        var uriLine: String? = null
                        var j = i + 1
                        while (j < lines.size) {
                            if (!lines[j].startsWith("#")) {
                                uriLine = lines[j]
                                break
                            }
                            j++
                        }

                        if (!uriLine.isNullOrBlank()) {
                            val resolvedVariantUrl = resolveRelativeUrl(masterUrl, uriLine)
                            val heightLabel = if (!heightFromAttr.isNullOrBlank()) {
                                "${heightFromAttr}p"
                            } else {
                                val fileResMatcher = Pattern.compile("(\\d{3,4}p)", Pattern.CASE_INSENSITIVE).matcher(uriLine)
                                if (fileResMatcher.find()) fileResMatcher.group(1) ?: "Auto" else "Auto"
                            }
                            variants.add(normalizeResolutionLabel(heightLabel) to resolvedVariantUrl)
                            i = j
                        }
                    }
                    i++
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to fetch HLS master manifest $masterUrl: ${e.message}")
        }

        // Fallback: parse multi=.../_TPL_ pattern directly from xHamster HLS URL if manifest fetch didn't return variants
        if (variants.isEmpty() && masterUrl.contains("_TPL_") && masterUrl.contains("multi=")) {
            val multiMatcher = Pattern.compile("multi=([^/]+)").matcher(masterUrl)
            if (multiMatcher.find()) {
                val multiSpec = multiMatcher.group(1).orEmpty()
                val entries = multiSpec.split(",")
                for (entry in entries) {
                    // Format: 1920x1080:1080p: or 3840x2160:2160p.7700b: or 1920x1080:1080p
                    val parts = entry.split(":").filter { it.isNotBlank() }
                    if (parts.size >= 2) {
                        val tplReplacement = parts[1]
                        val resLabel = parts[0].substringAfter('x', tplReplacement)
                        val variantUrl = masterUrl.replace("_TPL_", tplReplacement)
                        variants.add(normalizeResolutionLabel(resLabel) to variantUrl)
                    }
                }
            }
        }

        return variants
    }

    suspend fun extractStreams(url: String): List<StreamQuality> = withContext(Dispatchers.IO) {
        val rawUrl = url.trim()
        val pageOrigin = try {
            val u = URI(if (rawUrl.startsWith("http", ignoreCase = true)) rawUrl else "https://$rawUrl")
            if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) "${u.scheme}://${u.host}" else "https://xhamster.com"
        } catch (_: Exception) {
            "https://xhamster.com"
        }

        val pageReq = Request.Builder()
            .url(rawUrl)
            .header("User-Agent", SCRAPER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "$pageOrigin/")
            .build()

        val pageRes = NetworkClient.okHttpClient.newCall(pageReq).await()
        val html = pageRes.body?.string().orEmpty()
        val status = pageRes.code
        pageRes.close()

        if (status !in 200..299 || html.isBlank()) {
            throw Exception("Failed to load xHamster page (HTTP $status)")
        }

        val playbackHeaders = mapOf(
            "User-Agent" to SCRAPER_USER_AGENT,
            "Referer" to "$pageOrigin/",
            "Origin" to pageOrigin
        )

        val hlsManifestCandidates = mutableListOf<String>()
        // Map of quality label -> direct MP4 URL
        val directMp4Streams = mutableListOf<Pair<String, String>>()

        val initials = extractWindowInitialsJson(html)
        if (initials != null) {
            val hostMap = initials.optJSONObject("hostMap")
            val xplayerSettings = initials.optJSONObject("xplayerSettings")
            val sources = xplayerSettings?.optJSONObject("sources")

            if (sources != null) {
                // 1. Extract HLS master manifests (prefer h264 first for broad hardware decoding, then av1)
                val hlsObj = sources.optJSONObject("hls")
                if (hlsObj != null) {
                    for (codec in listOf("h264", "av1")) {
                        val codecObj = hlsObj.optJSONObject(codec) ?: continue
                        val rawPrimary = codecObj.optString("url")
                        val rawFallback = codecObj.optString("fallback")

                        val decodedPrimary = decodeXhamsterUrl(rawPrimary)
                        val mappedPrimary = applyHostMap(decodedPrimary, hostMap?.optString("hls.$codec"))
                        if (!mappedPrimary.isNullOrBlank() && mappedPrimary.contains(".m3u8")) {
                            hlsManifestCandidates.add(mappedPrimary)
                        }

                        val decodedFallback = decodeXhamsterUrl(rawFallback)
                        val mappedFallback = applyHostMap(decodedFallback, hostMap?.optString("hls.$codec.fallback"))
                        if (!mappedFallback.isNullOrBlank() && mappedFallback.contains(".m3u8")) {
                            hlsManifestCandidates.add(mappedFallback)
                        }
                    }
                }

                // 2. Extract standard sources (both auto HLS manifests and direct MP4 qualities)
                val standardObj = sources.optJSONObject("standard")
                if (standardObj != null) {
                    for (codec in listOf("h264", "av1")) {
                        val arr = standardObj.optJSONArray(codec) ?: continue
                        for (idx in 0 until arr.length()) {
                            val item = arr.optJSONObject(idx) ?: continue
                            val qualityKey = item.optString("quality").ifBlank { item.optString("label") }.ifBlank { "$idx" }
                            val label = item.optString("label").ifBlank { qualityKey }

                            val rawPrimary = item.optString("url")
                            val rawFallback = item.optString("fallback")

                            val primeHostKey = "standard.$codec.$qualityKey.prime"
                            val fallbackHostKey = "standard.$codec.$qualityKey.fallback"

                            val decodedPrimary = decodeXhamsterUrl(rawPrimary)
                            val mappedPrimary = applyHostMap(decodedPrimary, hostMap?.optString(primeHostKey))

                            val decodedFallback = decodeXhamsterUrl(rawFallback)
                            val mappedFallback = applyHostMap(decodedFallback, hostMap?.optString(fallbackHostKey))

                            for (candidateUrl in listOfNotNull(mappedPrimary, mappedFallback)) {
                                if (candidateUrl.contains(".m3u8", ignoreCase = true)) {
                                    hlsManifestCandidates.add(candidateUrl)
                                } else if (candidateUrl.contains(".mp4", ignoreCase = true)) {
                                    directMp4Streams.add(label to candidateUrl)
                                    break
                                }
                            }
                        }
                    }
                }
            }

            // 3. Check videoModel fallback fields
            val videoModel = initials.optJSONObject("videoModel")
            if (videoModel != null) {
                val vmSources = videoModel.optJSONObject("sources")
                val mp4Obj = vmSources?.optJSONObject("mp4")
                if (mp4Obj != null) {
                    val keys = mp4Obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = decodeXhamsterUrl(mp4Obj.optString(k))
                        if (!v.isNullOrBlank()) {
                            if (v.contains(".m3u8", ignoreCase = true)) {
                                hlsManifestCandidates.add(v)
                            } else {
                                directMp4Streams.add(k to v)
                            }
                        }
                    }
                }
                val mp4File = decodeXhamsterUrl(videoModel.optString("mp4File"))
                if (!mp4File.isNullOrBlank()) {
                    if (mp4File.contains(".m3u8", ignoreCase = true)) {
                        hlsManifestCandidates.add(mp4File)
                    } else {
                        directMp4Streams.add("Source" to mp4File)
                    }
                }
            }
        }

        // 4. Also check <link rel="preload" href="...m3u8" as="fetch"> in HTML
        val preloadMatcher = Pattern.compile(
            """<link[^>]+rel=["']preload["'][^>]+href=["']([^"']+\.m3u8[^"']*)["']""",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        while (preloadMatcher.find()) {
            val preloadHref = preloadMatcher.group(1)?.replace("&amp;", "&")?.trim()
            val decodedPreload = decodeXhamsterUrl(preloadHref) ?: preloadHref
            if (!decodedPreload.isNullOrBlank()) {
                hlsManifestCandidates.add(decodedPreload)
            }
        }

        // Order HLS manifests so H.264 comes before AV1
        val orderedManifests = hlsManifestCandidates
            .distinct()
            .sortedBy { if (it.contains(".h264.", ignoreCase = true)) 0 else 1 }

        val extractedQualities = mutableListOf<StreamQuality>()
        val seenUrls = mutableSetOf<String>()
        val hlsResolutionsFound = mutableSetOf<Int>()

        // 5. Resolve individual quality variants from the best working HLS master manifest
        var primaryMasterManifest: String? = null
        for (manifestUrl in orderedManifests) {
            val variants = parseHlsMasterVariants(manifestUrl, playbackHeaders)
            if (variants.isNotEmpty()) {
                primaryMasterManifest = manifestUrl
                for ((qLabel, variantUrl) in variants) {
                    if (seenUrls.add(variantUrl)) {
                        val rank = resolutionRank(qLabel)
                        hlsResolutionsFound.add(rank)
                        extractedQualities.add(
                            StreamQuality(
                                quality = qLabel,
                                url = variantUrl,
                                format = "HLS",
                                isDefault = false,
                                headers = playbackHeaders
                            )
                        )
                    }
                }
                break
            }
        }

        // 6. Add direct MP4 streams
        for ((rawLabel, mp4Url) in directMp4Streams) {
            if (seenUrls.add(mp4Url)) {
                val rank = resolutionRank(rawLabel)
                val hasHlsSameRes = hlsResolutionsFound.contains(rank)
                val label = normalizeResolutionLabel(rawLabel, isMp4Variant = hasHlsSameRes)
                // Avoid duplicate label collision in PlayerControlsOverlay for 480p/240p/144p if HLS already provides that exact label
                if (hasHlsSameRes && rank !in listOf(1080, 720)) {
                    continue
                }
                extractedQualities.add(
                    StreamQuality(
                        quality = label,
                        url = mp4Url,
                        format = "MP4",
                        isDefault = false,
                        headers = playbackHeaders
                    )
                )
            }
        }

        // 7. Add Auto (HLS) master adaptive stream
        val masterToInclude = primaryMasterManifest ?: orderedManifests.firstOrNull()
        if (!masterToInclude.isNullOrBlank() && seenUrls.add(masterToInclude)) {
            extractedQualities.add(
                StreamQuality(
                    quality = "Auto (HLS)",
                    url = masterToInclude,
                    format = "HLS",
                    isDefault = false,
                    headers = playbackHeaders
                )
            )
        }

        if (extractedQualities.isEmpty()) {
            throw Exception("Could not extract any playable video streams from xHamster page")
        }

        // Sort descending by resolution (2160p 4K -> 1440p -> 1080p -> 720p -> 720p MP4 -> 480p -> 240p -> 144p -> Auto)
        val sorted = extractedQualities.sortedByDescending { resolutionRank(it.quality) }

        // Mark 1080p as default if available, otherwise highest resolution
        val defaultIndex = sorted.indexOfFirst { it.quality == "1080p" }
            .takeIf { it != -1 }
            ?: sorted.indexOfFirst { it.quality.contains("1080") }
                .takeIf { it != -1 }
            ?: 0

        sorted.mapIndexed { idx, sq ->
            sq.copy(isDefault = idx == defaultIndex)
        }
    }

    /**
     * Extracts rich scene metadata from an xHamster video URL:
     * - Title
     * - Upload / Release Date (in epoch millis)
     * - First Performer (Name + optional Avatar URL)
     * - Studio ("xHamster")
     * - Cover Image URL
     */
    suspend fun extractMetadata(rawUrl: String): PornhubSceneMetadata? = withContext(Dispatchers.IO) {
        val trimmedUrl = rawUrl.trim()
        if (!isXhamsterUrl(trimmedUrl)) return@withContext null

        val normalizedUrl = if (trimmedUrl.startsWith("http://", ignoreCase = true) ||
            trimmedUrl.startsWith("https://", ignoreCase = true)
        ) {
            trimmedUrl
        } else {
            "https://$trimmedUrl"
        }

        val pageOrigin = try {
            val u = URI(normalizedUrl)
            if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) "${u.scheme}://${u.host}" else "https://xhamster.com"
        } catch (_: Exception) {
            "https://xhamster.com"
        }

        try {
            val pageReq = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", SCRAPER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "$pageOrigin/")
                .build()

            val pageRes = NetworkClient.okHttpClient.newCall(pageReq).await()
            val html = pageRes.body?.string().orEmpty()
            pageRes.close()

            if (html.isBlank()) return@withContext null

            val doc = Jsoup.parse(html, normalizedUrl)

            var extractedTitle: String? = null
            var extractedCover: String? = null
            var extractedDateMillis: Long? = null
            var extractedDateStr: String? = null
            var extractedPerformerName: String? = null
            var extractedPerformerAvatar: String? = null

            val blockedPerformerWords = setOf(
                "pornstars", "all pornstars", "top pornstars", "female pornstars",
                "male pornstars", "creators", "all creators", "channels", "categories",
                "tags", "users", "more", "suggest", "verified", "xhamster"
            )

            // 1. Parse window.initials JSON if present
            val initials = extractWindowInitialsJson(html)
            if (initials != null) {
                val videoModel = initials.optJSONObject("videoModel")
                    ?: initials.optJSONObject("videoEntity")
                val xplayerSettings = initials.optJSONObject("xplayerSettings")

                if (videoModel != null) {
                    extractedTitle = videoModel.optString("title").trim().takeIf { it.isNotBlank() }

                    extractedCover = decodeXhamsterUrl(videoModel.optString("thumbURL"))?.takeIf { it.isNotBlank() }
                        ?: decodeXhamsterUrl(videoModel.optString("bigThumbURL"))?.takeIf { it.isNotBlank() }
                        ?: decodeXhamsterUrl(videoModel.optString("previewThumbURL"))?.takeIf { it.isNotBlank() }

                    val createdEpoch = videoModel.optLong("created", 0L)
                    if (createdEpoch > 0L) {
                        extractedDateMillis = if (createdEpoch < 100_000_000_000L) {
                            createdEpoch * 1000L
                        } else {
                            createdEpoch
                        }
                    }
                }

                if (xplayerSettings != null) {
                    val movieObj = xplayerSettings.optJSONObject("Movie")
                    if (extractedTitle.isNullOrBlank()) {
                        extractedTitle = movieObj?.optString("title")?.trim()?.takeIf { it.isNotBlank() }
                    }
                    if (extractedCover.isNullOrBlank()) {
                        extractedCover = decodeXhamsterUrl(xplayerSettings.optString("poster"))?.takeIf { it.isNotBlank() }
                            ?: decodeXhamsterUrl(movieObj?.optString("poster"))?.takeIf { it.isNotBlank() }
                    }
                }

                // Collect candidate tag/performer arrays from window.initials
                val tagArrays = listOfNotNull(
                    videoModel?.optJSONArray("pornstars"),
                    videoModel?.optJSONArray("actors"),
                    videoModel?.optJSONArray("categories"),
                    initials.optJSONObject("videoTagsComponent")?.optJSONArray("tags"),
                    initials.optJSONArray("videoTags")
                )

                // Pass 1a: Explicit Pornstar entries in window.initials
                for (arr in tagArrays) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val name = obj.optString("name").trim()
                        if (name.length < 2 || name.lowercase() in blockedPerformerWords) continue

                        val itemUrl = (obj.optString("url") + " " + obj.optString("pageURL")).lowercase()
                        val isPornstar = obj.optBoolean("isPornstar", false) ||
                            obj.optString("type").equals("pornstar", ignoreCase = true) ||
                            obj.optString("role").equals("pornstar", ignoreCase = true) ||
                            itemUrl.contains("/pornstars/") ||
                            arr === videoModel?.optJSONArray("pornstars") ||
                            arr === videoModel?.optJSONArray("actors")

                        if (isPornstar) {
                            extractedPerformerName = name
                            extractedPerformerAvatar = decodeXhamsterUrl(
                                obj.optString("thumbUrl").ifBlank {
                                    obj.optString("thumbURL").ifBlank {
                                        obj.optString("imageURL").ifBlank {
                                            obj.optString("avatar").ifBlank {
                                                obj.optString("avatarUrl")
                                            }
                                        }
                                    }
                                }
                            )?.takeIf { it.isNotBlank() && !it.endsWith(".avif", ignoreCase = true) && !it.endsWith(".heic", ignoreCase = true) }
                            break
                        }
                    }
                    if (!extractedPerformerName.isNullOrBlank()) break
                }

                // Pass 1b: Creator entries in window.initials if no pornstar found yet
                if (extractedPerformerName.isNullOrBlank()) {
                    for (arr in tagArrays) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val name = obj.optString("name").trim()
                            if (name.length < 2 || name.lowercase() in blockedPerformerWords) continue

                            val itemUrl = (obj.optString("url") + " " + obj.optString("pageURL")).lowercase()
                            val isCreator = obj.optBoolean("isCreator", false) ||
                                obj.optString("type").equals("creator", ignoreCase = true) ||
                                itemUrl.contains("/creators/")

                            if (isCreator) {
                                extractedPerformerName = name
                                extractedPerformerAvatar = decodeXhamsterUrl(
                                    obj.optString("thumbUrl").ifBlank {
                                        obj.optString("thumbURL").ifBlank {
                                            obj.optString("imageURL").ifBlank {
                                                obj.optString("avatar").ifBlank {
                                                    obj.optString("avatarUrl")
                                                }
                                            }
                                        }
                                    }
                                )?.takeIf { it.isNotBlank() && !it.endsWith(".avif", ignoreCase = true) && !it.endsWith(".heic", ignoreCase = true) }
                                break
                            }
                        }
                        if (!extractedPerformerName.isNullOrBlank()) break
                    }
                }
            }

            // 2. Parse JSON-LD (application/ld+json) for any missing fields
            val ldJsonElements = doc.select("script[type=application/ld+json]")
            for (scriptEl in ldJsonElements) {
                val rawJson = scriptEl.data().trim()
                if (rawJson.isEmpty()) continue
                try {
                    val jsonObj = if (rawJson.startsWith("[")) {
                        val arr = JSONArray(rawJson)
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                            .firstOrNull { it.optString("@type").contains("VideoObject", ignoreCase = true) }
                            ?: arr.optJSONObject(0)
                    } else {
                        JSONObject(rawJson)
                    }
                    if (jsonObj != null) {
                        if (extractedTitle.isNullOrBlank()) {
                            extractedTitle = jsonObj.optString("name").trim().takeIf { it.isNotBlank() }
                        }
                        if (extractedCover.isNullOrBlank()) {
                            val thumbAny = jsonObj.opt("thumbnailUrl")
                            extractedCover = when (thumbAny) {
                                is String -> thumbAny.trim().takeIf { it.isNotBlank() }
                                is JSONArray -> (0 until thumbAny.length())
                                    .mapNotNull { thumbAny.optString(it).trim().takeIf { s -> s.isNotBlank() } }
                                    .firstOrNull()
                                else -> null
                            }
                        }
                        if (extractedDateMillis == null && extractedDateStr.isNullOrBlank()) {
                            extractedDateStr = jsonObj.optString("uploadDate").trim().takeIf { it.isNotBlank() }
                                ?: jsonObj.optString("datePublished").trim().takeIf { it.isNotBlank() }
                        }
                        if (extractedPerformerName.isNullOrBlank()) {
                            val actorsArr = jsonObj.optJSONArray("actor")
                            if (actorsArr != null && actorsArr.length() > 0) {
                                val firstActor = actorsArr.optJSONObject(0)
                                val actorName = firstActor?.optString("name")?.trim()
                                    ?: actorsArr.optString(0).trim()
                                if (actorName.length >= 2 && actorName.lowercase() !in blockedPerformerWords) {
                                    extractedPerformerName = actorName
                                    extractedPerformerAvatar = firstActor?.optString("image")?.trim()?.takeIf { it.isNotBlank() }
                                }
                            } else {
                                val actorObj = jsonObj.optJSONObject("actor")
                                val actorName = actorObj?.optString("name")?.trim().orEmpty()
                                if (actorName.length >= 2 && actorName.lowercase() !in blockedPerformerWords) {
                                    extractedPerformerName = actorName
                                    extractedPerformerAvatar = actorObj?.optString("image")?.trim()?.takeIf { it.isNotBlank() }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore malformed JSON-LD block
                }
            }

            // 3. Fallback & cleanup for Title
            if (extractedTitle.isNullOrBlank()) {
                extractedTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("meta[name=twitter:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            }
            extractedTitle = extractedTitle
                ?.replace(Regex("""\s*[-|—]\s*xHamster(\.[a-z0-9]+)?\s*$""", RegexOption.IGNORE_CASE), "")
                ?.replace(Regex("""\s*[-|—]\s*Free Porn Videos.*$""", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            // 4. Fallback for Cover Image
            if (extractedCover.isNullOrBlank()) {
                extractedCover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("video[poster]")?.attr("poster")?.trim()?.takeIf { it.isNotBlank() }
            }

            // 5. Fallback for Upload Date
            if (extractedDateMillis == null) {
                if (extractedDateStr.isNullOrBlank()) {
                    extractedDateStr = doc.selectFirst("meta[property=video:release_date]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
                        ?: doc.selectFirst("time[datetime]")?.attr("datetime")?.trim()?.takeIf { it.isNotBlank() }
                        ?: doc.selectFirst("[data-role=video-date], .entity-info-container__date")?.text()?.trim()?.takeIf { it.isNotBlank() }
                }
                extractedDateMillis = VideoResolvers.parsePornhubDateToMillis(extractedDateStr)
            }

            // 6. Fallback for First Performer from HTML DOM (/pornstars/ first, then /creators/)
            if (extractedPerformerName.isNullOrBlank()) {
                val domGroups = listOf(
                    "a[href*='/pornstars/']" to "/pornstars/",
                    "a[href*='/creators/']" to "/creators/"
                )
                val excludedSlugs = setOf("all", "top", "female", "male", "trans", "new", "hd", "vr", "4k")

                for ((selector, marker) in domGroups) {
                    val links = doc.select(selector)
                    for (el in links) {
                        val href = el.attr("href").trim()
                        val slug = href.substringAfter(marker, "")
                            .trim('/')
                            .substringBefore('?')
                            .substringBefore('#')
                            .trim()

                        if (slug.isBlank() || slug.contains('/') || slug.lowercase() in excludedSlugs) continue

                        val candidateName = el.attr("title").trim()
                            .ifEmpty { el.text().trim() }
                            .replace(Regex("""\s+\d+(\.\d+)?[KMB]?\s*$""", RegexOption.IGNORE_CASE), "")
                            .trim()

                        if (candidateName.length >= 2 && candidateName.lowercase() !in blockedPerformerWords) {
                            extractedPerformerName = candidateName
                            val imgEl = el.selectFirst("img")
                            val avatarCandidate = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                                ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }
                            if (!avatarCandidate.isNullOrBlank() && !avatarCandidate.startsWith("data:")) {
                                extractedPerformerAvatar = avatarCandidate
                            }
                            break
                        }
                    }
                    if (!extractedPerformerName.isNullOrBlank()) break
                }
            }

            // 7. Final fallback for Performer: video author / channel / uploader
            if (extractedPerformerName.isNullOrBlank()) {
                val videoModel = initials?.optJSONObject("videoModel") ?: initials?.optJSONObject("videoEntity")
                val authorObj = videoModel?.optJSONObject("author") ?: videoModel?.optJSONObject("channel")
                val authorName = authorObj?.optString("name")?.trim()
                    ?: authorObj?.optString("title")?.trim()
                    ?: doc.selectFirst("[data-role='video-author'] a, .video-uploader a, a.entity-author-container__name")?.text()?.trim()

                if (!authorName.isNullOrBlank() &&
                    authorName.length >= 2 &&
                    authorName.lowercase() !in blockedPerformerWords
                ) {
                    extractedPerformerName = authorName
                    val authorAvatar = decodeXhamsterUrl(
                        authorObj?.optString("thumbURL")?.ifBlank {
                            authorObj.optString("avatarUrl").ifBlank {
                                authorObj.optString("avatar")
                            }
                        }
                    )?.takeIf { it.isNotBlank() }
                        ?: doc.selectFirst("[data-role='video-author'] img, .video-uploader img")?.attr("src")?.takeIf {
                            it.isNotBlank() && !it.startsWith("data:")
                        }
                    extractedPerformerAvatar = authorAvatar
                }
            }

            PornhubSceneMetadata(
                title = extractedTitle,
                coverUrl = extractedCover,
                uploadDateMillis = extractedDateMillis,
                firstPerformerName = extractedPerformerName,
                firstPerformerAvatarUrl = extractedPerformerAvatar,
                studioName = "xHamster"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract xHamster metadata: ${e.message}", e)
            null
        }
    }
}

/**
 * 5. Eporner Stream Scraper (eporner.com)
 * Extracts multi-quality HLS variants (up to 2160p 4K 60fps / 1440p / 1080p) and direct H.264 MP4 streams
 * by extracting EP.video.player.vid and EP.video.player.hash, computing the 4-chunk Base36 hash token,
 * querying /xhr/video/{vid} with supportedFormats=mp4,hls using System DNS parity with ExoPlayer,
 * and parsing both the HLS master playlist and direct MP4 CDN sources.
 */
object EpornerScraper {
    private const val TAG = "EpornerScraper"

    // Use System DNS so the IP family (IPv4/IPv6) used to sign CDN URLs in /xhr/video/
    // matches Android's DefaultHttpDataSource in ExoPlayer.
    private val systemDnsPageClient by lazy {
        NetworkClient.okHttpClient.newBuilder()
            .dns(okhttp3.Dns.SYSTEM)
            .build()
    }

    private val systemDnsApiClient by lazy {
        NetworkClient.apiClient.newBuilder()
            .dns(okhttp3.Dns.SYSTEM)
            .build()
    }

    fun isEpornerUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        // Exclude already-resolved CDN streams, HLS manifests, direct MP4s, and XHR endpoints
        // so PlayerPlatformBridge does not re-scrape an extracted stream URL.
        if (trimmed.contains("-cdn.eporner.com") ||
            trimmed.contains("cdn.eporner.com") ||
            trimmed.contains("/dload/") ||
            trimmed.contains("/xhr/") ||
            trimmed.contains("/hls/") ||
            trimmed.contains("/yhls/") ||
            trimmed.contains(".m3u8") ||
            trimmed.contains(".mp4") ||
            trimmed.contains(".webm") ||
            trimmed.contains(".mkv") ||
            trimmed.contains(".ts")
        ) {
            return false
        }
        return trimmed.contains("eporner.com")
    }

    fun encodeBase36Hash(hexHash: String): String {
        val clean = hexHash.trim()
        if (clean.length < 32) return clean
        return buildString {
            var i = 0
            while (i + 8 <= 32) {
                val chunk = clean.substring(i, i + 8)
                val value = chunk.toLongOrNull(16) ?: 0L
                append(value.toString(36))
                i += 8
            }
        }
    }

    private fun extractVidAndHash(html: String, url: String): Pair<String?, String?> {
        var vid: String? = null
        var hash: String? = null

        val vidPatterns = listOf(
            Pattern.compile("""EP\.video\.player\.vid\s*=\s*['"]([^'"]+)['"]""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""['"]?vid['"]?\s*[:=]\s*['"]([a-zA-Z0-9]{8,16})['"]"""),
            Pattern.compile("""data-vid=['"]([a-zA-Z0-9]{8,16})['"]""", Pattern.CASE_INSENSITIVE)
        )
        for (p in vidPatterns) {
            val m = p.matcher(html)
            if (m.find()) {
                val candidate = m.group(1)?.trim()
                if (!candidate.isNullOrBlank() && !candidate.contains("/") && !candidate.contains(".")) {
                    vid = candidate
                    break
                }
            }
        }

        if (vid.isNullOrBlank()) {
            val urlMatcher = Pattern.compile(
                """(?:video-|/hd-porn/|/embed/|/video/)([a-zA-Z0-9]{8,16})""",
                Pattern.CASE_INSENSITIVE
            ).matcher(url)
            if (urlMatcher.find()) {
                vid = urlMatcher.group(1)?.trim()
            }
        }

        val hashPatterns = listOf(
            Pattern.compile("""EP\.video\.player\.hash\s*=\s*['"]([0-9a-fA-F]{32})['"]""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""['"]?hash['"]?\s*[:=]\s*['"]([0-9a-fA-F]{32})['"]""", Pattern.CASE_INSENSITIVE)
        )
        for (p in hashPatterns) {
            val m = p.matcher(html)
            if (m.find()) {
                val candidate = m.group(1)?.trim()
                if (!candidate.isNullOrBlank()) {
                    hash = candidate
                    break
                }
            }
        }

        return vid to hash
    }

    private fun normalizeQualityLabel(rawLabel: String, isMp4Variant: Boolean = false): String {
        val lower = rawLabel.lowercase().trim()
        val height = when {
            lower.contains("2160") || lower.contains("4k") || lower.contains("uhd") -> 2160
            lower.contains("1440") || lower.contains("2k") || lower.contains("qhd") -> 1440
            lower.contains("1080") || lower.contains("fhd") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            lower.contains("240") -> 240
            lower.contains("144") -> 144
            else -> {
                val m = Pattern.compile("(\\d{3,4})p").matcher(lower)
                if (m.find()) m.group(1)?.toIntOrNull() ?: 0 else 0
            }
        }
        val base = when (height) {
            2160 -> "2160p (4K)"
            1440 -> "1440p"
            1080 -> "1080p"
            720 -> "720p"
            480 -> "480p"
            360 -> "360p"
            240 -> "240p"
            144 -> "144p"
            else -> if (height > 0) "${height}p" else rawLabel
        }
        return if (isMp4Variant && (height == 1080 || height == 720)) "$base (MP4)" else base
    }

    private fun resolutionRank(label: String): Int {
        val lower = label.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> 2160
            lower.contains("1440") || lower.contains("2k") -> 1440
            lower.contains("1080") -> if (lower.contains("mp4")) 1079 else 1080
            lower.contains("720") -> if (lower.contains("mp4")) 719 else 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            lower.contains("240") -> 240
            lower.contains("144") -> 144
            lower.contains("auto") -> 50
            else -> 100
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, relativeOrAbsolute: String): String {
        val trimmed = relativeOrAbsolute.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            return "https:$trimmed"
        }
        return try {
            URI(baseUrl).resolve(trimmed).toString()
        } catch (_: Exception) {
            val baseDir = baseUrl.substringBeforeLast('/')
            "$baseDir/$trimmed"
        }
    }

    private suspend fun parseHlsMasterVariants(
        masterUrl: String,
        headers: Map<String, String>
    ): List<Pair<String, String>> {
        val variants = mutableListOf<Pair<String, String>>()
        try {
            val reqBuilder = Request.Builder().url(masterUrl)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            val res = systemDnsApiClient.newCall(reqBuilder.build()).await()
            val body = res.body?.string().orEmpty()
            val isOk = res.isSuccessful
            res.close()

            if (isOk && body.contains("#EXTM3U")) {
                val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
                var i = 0
                while (i < lines.size) {
                    val line = lines[i]
                    if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        val resMatcher = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE).matcher(line)
                        val heightFromAttr = if (resMatcher.find()) resMatcher.group(2) else null

                        var uriLine: String? = null
                        var j = i + 1
                        while (j < lines.size) {
                            if (!lines[j].startsWith("#")) {
                                uriLine = lines[j]
                                break
                            }
                            j++
                        }

                        if (!uriLine.isNullOrBlank()) {
                            val resolvedVariantUrl = resolveRelativeUrl(masterUrl, uriLine)
                            val heightLabel = if (!heightFromAttr.isNullOrBlank()) {
                                "${heightFromAttr}p"
                            } else {
                                val fileResMatcher = Pattern.compile("(\\d{3,4}p)", Pattern.CASE_INSENSITIVE).matcher(uriLine)
                                if (fileResMatcher.find()) fileResMatcher.group(1) ?: "Auto" else "Auto"
                            }
                            variants.add(normalizeQualityLabel(heightLabel) to resolvedVariantUrl)
                            i = j
                        }
                    }
                    i++
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse Eporner HLS master manifest $masterUrl: ${e.message}")
        }
        return variants
    }

    suspend fun extractStreams(url: String): List<StreamQuality> = withContext(Dispatchers.IO) {
        val rawUrl = url.trim()
        val parsedUri = try {
            URI(if (rawUrl.startsWith("http", ignoreCase = true)) rawUrl else "https://$rawUrl")
        } catch (_: Exception) {
            URI("https://www.eporner.com")
        }
        val domainHost = parsedUri.host?.ifBlank { "www.eporner.com" } ?: "www.eporner.com"
        val pageOrigin = "${parsedUri.scheme ?: "https"}://$domainHost"

        val pageReq = Request.Builder()
            .url(rawUrl)
            .header("User-Agent", SCRAPER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "$pageOrigin/")
            .build()

        val pageRes = systemDnsPageClient.newCall(pageReq).await()
        val html = pageRes.body?.string().orEmpty()
        val status = pageRes.code
        pageRes.close()

        if (status !in 200..299 && html.isBlank()) {
            throw Exception("Failed to load Eporner page (HTTP $status)")
        }

        var (vid, hash) = extractVidAndHash(html, rawUrl)

        // Fallback to lightweight /embed/{vid}/ page if main page did not expose the 32-char player hash
        if (!vid.isNullOrBlank() && hash.isNullOrBlank()) {
            try {
                val embedUrl = "$pageOrigin/embed/$vid/"
                val embedReq = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", SCRAPER_USER_AGENT)
                    .header("Referer", rawUrl)
                    .build()
                val embedRes = systemDnsPageClient.newCall(embedReq).await()
                val embedHtml = embedRes.body?.string().orEmpty()
                embedRes.close()
                if (embedHtml.isNotBlank()) {
                    val (eVid, eHash) = extractVidAndHash(embedHtml, embedUrl)
                    if (!eVid.isNullOrBlank()) vid = eVid
                    if (!eHash.isNullOrBlank()) hash = eHash
                }
            } catch (e: Exception) {
                Log.d(TAG, "Embed fallback failed for $vid: ${e.message}")
            }
        }

        val resolvedVid = vid ?: throw Exception("Could not extract Eporner video ID from page")

        val playbackHeaders = mapOf(
            "User-Agent" to SCRAPER_USER_AGENT,
            "Referer" to "$pageOrigin/",
            "Origin" to pageOrigin
        )

        val hlsManifestCandidates = mutableListOf<String>()
        val directMp4Streams = mutableListOf<Pair<String, String>>()

        if (!hash.isNullOrBlank()) {
            val encodedHash = encodeBase36Hash(hash)
            // Requesting supportedFormats=mp4,hls instructs Eporner to return hardware-compatible H.264 MP4 and HLS CDN streams
            // rather than AV1/VP9 streams that fail on standard Android hardware decoders.
            val xhrUrl = "$pageOrigin/xhr/video/$resolvedVid?hash=$encodedHash&domain=$domainHost&fallback=false&embed=false&supportedFormats=mp4,hls"

            try {
                val xhrReq = Request.Builder()
                    .url(xhrUrl)
                    .header("User-Agent", SCRAPER_USER_AGENT)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", rawUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()

                val xhrRes = systemDnsApiClient.newCall(xhrReq).await()
                val xhrBody = xhrRes.body?.string().orEmpty()
                val xhrOk = xhrRes.isSuccessful
                xhrRes.close()

                if (xhrOk && xhrBody.isNotBlank()) {
                    val json = JSONObject(xhrBody)
                    val sources = json.optJSONObject("sources")
                    if (sources != null) {
                        // 1. Extract HLS master playlists
                        val hlsObj = sources.optJSONObject("hls")
                        if (hlsObj != null) {
                            val hlsKeys = hlsObj.keys()
                            while (hlsKeys.hasNext()) {
                                val k = hlsKeys.next()
                                val item = hlsObj.optJSONObject(k)
                                val src = item?.optString("src")?.trim()
                                    ?: hlsObj.optString(k).trim()
                                if (src.isNotBlank() && !src.contains(".vtt", ignoreCase = true)) {
                                    val fullUrl = resolveRelativeUrl(pageOrigin, src)
                                    hlsManifestCandidates.add(fullUrl)
                                }
                            }
                        }

                        // 2. Extract direct MP4 streams
                        val mp4Obj = sources.optJSONObject("mp4")
                        if (mp4Obj != null) {
                            val mp4Keys = mp4Obj.keys()
                            while (mp4Keys.hasNext()) {
                                val keyLabel = mp4Keys.next()
                                val item = mp4Obj.optJSONObject(keyLabel)
                                val src = item?.optString("src")?.trim()
                                    ?: mp4Obj.optString(keyLabel).trim()
                                val shortLabel = item?.optString("labelShort")?.takeIf { it.isNotBlank() } ?: keyLabel
                                if (src.isNotBlank() && !src.contains(".vtt", ignoreCase = true)) {
                                    val fullUrl = resolveRelativeUrl(pageOrigin, src)
                                    directMp4Streams.add(shortLabel to fullUrl)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Eporner XHR request failed: ${e.message}")
            }
        }

        // Fallback: Check HTML for direct /dload/ MP4 links if XHR returned nothing
        if (hlsManifestCandidates.isEmpty() && directMp4Streams.isEmpty() && html.isNotBlank()) {
            val dloadMatcher = Pattern.compile(
                """href=["'](/dload/[^"']+\.mp4[^"']*)["']""",
                Pattern.CASE_INSENSITIVE
            ).matcher(html)
            while (dloadMatcher.find()) {
                val path = dloadMatcher.group(1)?.trim() ?: continue
                val fullUrl = resolveRelativeUrl(pageOrigin, path)
                val resMatcher = Pattern.compile("""(\d{3,4}p)""", Pattern.CASE_INSENSITIVE).matcher(path)
                val label = if (resMatcher.find()) resMatcher.group(1) ?: "MP4" else "MP4"
                directMp4Streams.add(label to fullUrl)
            }
        }

        val extractedQualities = mutableListOf<StreamQuality>()
        val seenUrls = mutableSetOf<String>()
        val hlsResolutionsFound = mutableSetOf<Int>()

        // 3. Parse HLS master playlist into explicit quality variants (2160p 4K, 1440p, 1080p, 720p, 480p, 360p, 240p)
        var primaryMasterManifest: String? = null
        for (manifestUrl in hlsManifestCandidates.distinct()) {
            val variants = parseHlsMasterVariants(manifestUrl, playbackHeaders)
            if (variants.isNotEmpty()) {
                primaryMasterManifest = manifestUrl
                for ((qLabel, variantUrl) in variants) {
                    if (seenUrls.add(variantUrl)) {
                        val rank = resolutionRank(qLabel)
                        hlsResolutionsFound.add(rank)
                        extractedQualities.add(
                            StreamQuality(
                                quality = qLabel,
                                url = variantUrl,
                                format = "HLS",
                                isDefault = false,
                                headers = playbackHeaders
                            )
                        )
                    }
                }
                break
            }
        }

        // 4. Add direct MP4 streams
        for ((rawLabel, mp4Url) in directMp4Streams) {
            if (seenUrls.add(mp4Url)) {
                val rank = resolutionRank(rawLabel)
                val hasHlsSameRes = hlsResolutionsFound.contains(rank)
                val label = normalizeQualityLabel(rawLabel, isMp4Variant = hasHlsSameRes)
                if (hasHlsSameRes && rank !in listOf(1080, 720)) {
                    continue
                }
                extractedQualities.add(
                    StreamQuality(
                        quality = label,
                        url = mp4Url,
                        format = "MP4",
                        isDefault = false,
                        headers = playbackHeaders
                    )
                )
            }
        }

        // 5. Add Auto (HLS) adaptive master playlist
        val masterToInclude = primaryMasterManifest ?: hlsManifestCandidates.firstOrNull()
        if (!masterToInclude.isNullOrBlank() && seenUrls.add(masterToInclude)) {
            extractedQualities.add(
                StreamQuality(
                    quality = "Auto (HLS)",
                    url = masterToInclude,
                    format = "HLS",
                    isDefault = false,
                    headers = playbackHeaders
                )
            )
        }

        if (extractedQualities.isEmpty()) {
            throw Exception("Could not extract any playable video streams from Eporner page")
        }

        // Sort descending by resolution (2160p 4K -> 1440p -> 1080p -> 1080p MP4 -> 720p -> 720p MP4 -> 480p -> 360p -> 240p -> Auto)
        val sorted = extractedQualities.sortedByDescending { resolutionRank(it.quality) }

        // Mark 1080p as default if available, otherwise highest resolution
        val defaultIndex = sorted.indexOfFirst { it.quality == "1080p" }
            .takeIf { it != -1 }
            ?: sorted.indexOfFirst { it.quality.contains("1080") }
                .takeIf { it != -1 }
            ?: 0

        sorted.mapIndexed { idx, sq ->
            sq.copy(isDefault = idx == defaultIndex)
        }
    }
}

/**
 * 6. Porntrex Stream Scraper (porntrex.com)
 * Extracts multi-quality MP4 and HLS streams (2160p 4K, 1080p FHD, 720p HD, 480p, 360p)
 * from Kernel Video Sharing (KVS) flashvars configuration.
 * Handles:
 * - Embed-to-canonical video page upgrades (/embed/{id} only exposes 480p and sets alt URLs to the webpage).
 * - Optional KVS "function/0/" license_code hash deobfuscation when present.
 * - Pre-resolving IP-bound /get_file/ 302 redirects to direct CDN URLs (pcdn.cdntrex.com) using
 *   the exact same DNS family and session cookies as the page fetch to prevent HTTP 410 Gone errors in ExoPlayer.
 */
object PorntrexScraper {
    private const val TAG = "PorntrexScraper"

    // Client with System DNS parity for page fetching
    private val systemDnsPageClient by lazy {
        NetworkClient.okHttpClient.newBuilder()
            .dns(okhttp3.Dns.SYSTEM)
            .build()
    }

    // Non-redirecting client with System DNS to capture the 302 Location header from /get_file/
    // without downloading the multi-gigabyte MP4 payload during scraping.
    private val noRedirectClient by lazy {
        NetworkClient.apiClient.newBuilder()
            .dns(okhttp3.Dns.SYSTEM)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun isPorntrexUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        // Exclude already-resolved CDN URLs, /get_file/ endpoints, and direct media files
        // so PlayerPlatformBridge does not re-scrape an extracted stream URL.
        if (trimmed.contains("cdntrex.com") ||
            trimmed.contains("/get_file/") ||
            trimmed.contains(".mp4") ||
            trimmed.contains(".m3u8") ||
            trimmed.contains(".webm") ||
            trimmed.contains(".mkv") ||
            trimmed.contains(".ts")
        ) {
            return false
        }
        return trimmed.contains("porntrex.com") || trimmed.contains("porntrex.tv")
    }

    /**
     * Decodes KVS "function/0/" obfuscated URLs when present using flashvars.license_code.
     * If the URL does not start with "function/0/", returns it unchanged.
     */
    fun decodeKvsUrl(rawUrl: String, licenseCode: String?): String {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("function/0/", ignoreCase = true)) {
            return trimmed
        }
        val cleanUrl = trimmed.substringAfter("function/0/")
        if (licenseCode.isNullOrBlank()) return cleanUrl

        return try {
            val digitsOnly = buildString {
                for (ch in licenseCode) {
                    if (ch.isDigit()) {
                        append(if (ch == '0') '1' else ch)
                    }
                }
            }
            if (digitsOnly.length < 4) return cleanUrl

            val center = digitsOnly.length / 2
            val leftPart = digitsOnly.substring(0, center + 1).toLongOrNull() ?: return cleanUrl
            val rightPart = digitsOnly.substring(center).toLongOrNull() ?: return cleanUrl
            val diffQuad = kotlin.math.abs(rightPart - leftPart) * 4L
            val combined = "$diffQuad$diffQuad"

            val halfRange = (digitsOnly.length / 2) + 2
            val swapTable = IntArray(32)
            for (idx in 0 until 32) {
                var sum = 0
                for (n in 0 until 4) {
                    val dIdx = (idx + n) % digitsOnly.length
                    val cIdx = (idx + n) % combined.length
                    sum += (digitsOnly[dIdx] - '0') + (combined[cIdx] - '0')
                }
                swapTable[idx] = sum % halfRange
            }

            val getFileIndex = cleanUrl.indexOf("/get_file/")
            if (getFileIndex == -1) return cleanUrl

            val afterGetFile = cleanUrl.substring(getFileIndex + "/get_file/".length)
            val firstSlash = afterGetFile.indexOf('/')
            if (firstSlash == -1) return cleanUrl

            val serverSegment = afterGetFile.substring(0, firstSlash)
            val restAfterServer = afterGetFile.substring(firstSlash + 1)
            val secondSlash = restAfterServer.indexOf('/')
            if (secondSlash == -1) return cleanUrl

            val tokenSegment = restAfterServer.substring(0, secondSlash)
            val tailPath = restAfterServer.substring(secondSlash)

            if (tokenSegment.length < 32) return cleanUrl

            val hashChars = tokenSegment.substring(0, 32).toCharArray()
            val tokenSuffix = tokenSegment.substring(32)

            for (i in 31 downTo 0) {
                var offsetSum = i
                for (k in i until 32) {
                    offsetSum += swapTable[k]
                }
                val swapIdx = offsetSum % 32
                val tmp = hashChars[i]
                hashChars[i] = hashChars[swapIdx]
                hashChars[swapIdx] = tmp
            }

            val prefix = cleanUrl.substring(0, getFileIndex)
            "$prefix/get_file/$serverSegment/${String(hashChars)}$tokenSuffix$tailPath"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode KVS URL: ${e.message}")
            cleanUrl
        }
    }

    private fun isValidStreamCandidate(candidateUrl: String): Boolean {
        val lower = candidateUrl.trim().lowercase()
        if (lower.isBlank()) return false
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("//") && !lower.startsWith("function/0/")) {
            return false
        }
        // Reject thumbnails, previews, timelines, VAST ads, and HTML video page links (e.g. from /embed/ alt URLs)
        if (lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".png") ||
            lower.contains(".webp") ||
            lower.contains(".gif") ||
            lower.contains(".vtt") ||
            lower.contains(".srt") ||
            lower.contains("preview") ||
            lower.contains("timelines") ||
            lower.contains("screenshots") ||
            lower.contains("vast") ||
            lower.contains("related_videos")
        ) {
            return false
        }
        return lower.contains("/get_file/") ||
            lower.contains("cdntrex.com") ||
            lower.contains(".mp4") ||
            lower.contains(".m3u8") ||
            lower.contains(".webm")
    }

    private fun normalizeQualityLabel(rawText: String, fallbackUrl: String): String {
        val combined = "$rawText $fallbackUrl".lowercase()
        return when {
            combined.contains("2160") || combined.contains("4k") || combined.contains("uhd") -> "2160p (4K)"
            combined.contains("1440") || combined.contains("2k") || combined.contains("qhd") -> "1440p"
            combined.contains("1080") || combined.contains("fhd") -> "1080p"
            combined.contains("720") -> "720p"
            combined.contains("480") -> "480p"
            combined.contains("360") -> "360p"
            combined.contains("240") -> "240p"
            combined.contains("144") -> "144p"
            else -> {
                val m = Pattern.compile("(\\d{3,4})p").matcher(combined)
                if (m.find()) "${m.group(1)}p" else "480p"
            }
        }
    }

    private fun resolutionRank(label: String): Int {
        val lower = label.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> 2160
            lower.contains("1440") || lower.contains("2k") -> 1440
            lower.contains("1080") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            lower.contains("240") -> 240
            lower.contains("144") -> 144
            lower.contains("auto") -> 50
            else -> 100
        }
    }

    private fun resolveFullUrl(pageOrigin: String, rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$pageOrigin$trimmed"
            else -> "$pageOrigin/$trimmed"
        }
    }

    /**
     * Resolves a Porntrex /get_file/ URL to its direct signed CDN URL (https://pcdn.cdntrex.com/...?expires=...&md5=...)
     * using the same DNS family and session cookies as the HTML page request.
     * This prevents HTTP 410 Gone errors when ExoPlayer connects on dual-stack IPv4/IPv6 Android networks.
     */
    private suspend fun resolveGetFileRedirect(
        getFileUrl: String,
        refererUrl: String,
        cookieHeader: String?
    ): String {
        if (!getFileUrl.contains("/get_file/", ignoreCase = true)) {
            return getFileUrl
        }
        return try {
            val cleanGetFile = getFileUrl.replace("?embed=true", "")
            val reqBuilder = Request.Builder()
                .url(cleanGetFile)
                .header("User-Agent", SCRAPER_USER_AGENT)
                .header("Accept", "*/*")
                .header("Referer", refererUrl)
            if (!cookieHeader.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookieHeader)
            }

            val res = noRedirectClient.newCall(reqBuilder.build()).await()
            val code = res.code
            val location = res.header("Location")?.trim()
            res.close()

            if (code in 300..399 && !location.isNullOrBlank()) {
                when {
                    location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true) -> location
                    location.startsWith("//") -> "https:$location"
                    else -> location
                }
            } else {
                cleanGetFile
            }
        } catch (e: Exception) {
            Log.d(TAG, "Redirect resolution fallback for $getFileUrl: ${e.message}")
            getFileUrl.replace("?embed=true", "")
        }
    }

    private fun extractCookieHeader(responsesCookies: List<String>): String? {
        val cookieMap = linkedMapOf<String, String>()
        for (rawCookie in responsesCookies) {
            val pair = rawCookie.substringBefore(';').trim()
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) {
                val name = pair.substring(0, eqIdx).trim()
                val value = pair.substring(eqIdx + 1).trim()
                if (name.isNotBlank() && value.isNotBlank() && !value.equals("deleted", ignoreCase = true)) {
                    cookieMap[name] = value
                }
            }
        }
        if (cookieMap.isEmpty()) return null
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    suspend fun extractStreams(url: String): List<StreamQuality> = withContext(Dispatchers.IO) {
        val rawUrl = url.trim()
        val parsedUri = try {
            URI(if (rawUrl.startsWith("http", ignoreCase = true)) rawUrl else "https://$rawUrl")
        } catch (_: Exception) {
            URI("https://www.porntrex.com")
        }
        val domainHost = parsedUri.host?.ifBlank { "www.porntrex.com" } ?: "www.porntrex.com"
        val pageOrigin = "${parsedUri.scheme ?: "https"}://$domainHost"

        val collectedSetCookies = mutableListOf<String>()

        suspend fun fetchHtmlPage(targetUrl: String, referer: String): Pair<String, Int> {
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", SCRAPER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", referer)
            val existingCookies = extractCookieHeader(collectedSetCookies)
            if (!existingCookies.isNullOrBlank()) {
                reqBuilder.header("Cookie", existingCookies)
            }
            val res = systemDnsPageClient.newCall(reqBuilder.build()).await()
            collectedSetCookies.addAll(res.headers("Set-Cookie"))
            val body = res.body?.string().orEmpty()
            val code = res.code
            res.close()
            return body to code
        }

        var activePageUrl = rawUrl
        var (html, status) = fetchHtmlPage(activePageUrl, "$pageOrigin/")

        if (status !in 200..299 && html.isBlank()) {
            throw Exception("Failed to load Porntrex page (HTTP $status)")
        }

        // If the supplied URL is an /embed/{id} page, Porntrex only exposes 480p in video_url and points
        // video_alt_url* to the canonical /video/{id}/... webpage. Upgrade to the canonical video page
        // so all HD/FHD/4K streams are captured.
        val canonicalMatch = Pattern.compile(
            """https?://(?:www\.)?porntrex\.(?:com|tv)/video/\d+/[^\s'"]+""",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        if (activePageUrl.contains("/embed/", ignoreCase = true) && canonicalMatch.find()) {
            val canonicalVideoUrl = canonicalMatch.group(0)?.trim()
            if (!canonicalVideoUrl.isNullOrBlank()) {
                try {
                    val (mainHtml, mainStatus) = fetchHtmlPage(canonicalVideoUrl, activePageUrl)
                    if (mainStatus in 200..299 && mainHtml.contains("video_url")) {
                        html = mainHtml
                        activePageUrl = canonicalVideoUrl
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Canonical page upgrade failed, continuing with embed HTML: ${e.message}")
                }
            }
        }

        val licenseMatcher = Pattern.compile(
            """license_code\s*:\s*['"]([^'"]+)['"]""",
            Pattern.CASE_INSENSITIVE
        ).matcher(html)
        val licenseCode = if (licenseMatcher.find()) licenseMatcher.group(1)?.trim() else null

        // Extract all video_url and video_alt_url* entries alongside their *_text labels from flashvars
        val urlKeys = listOf(
            "video_url",
            "video_alt_url",
            "video_alt_url2",
            "video_alt_url3",
            "video_alt_url4",
            "video_alt_url5"
        )

        val rawCandidates = mutableListOf<Pair<String, String>>() // Pair(qualityLabel, decodedGetFileOrCdnUrl)

        for (key in urlKeys) {
            val urlPattern = Pattern.compile(
                """\b$key\s*:\s*['"]([^'"]+)['"]""",
                Pattern.CASE_INSENSITIVE
            ).matcher(html)
            if (urlPattern.find()) {
                val rawStreamUrl = urlPattern.group(1)?.trim().orEmpty()
                if (!isValidStreamCandidate(rawStreamUrl)) continue

                val decodedUrl = resolveFullUrl(pageOrigin, decodeKvsUrl(rawStreamUrl, licenseCode))
                if (!isValidStreamCandidate(decodedUrl)) continue

                val textPattern = Pattern.compile(
                    """\b${key}_text\s*:\s*['"]([^'"]+)['"]""",
                    Pattern.CASE_INSENSITIVE
                ).matcher(html)
                val rawLabel = if (textPattern.find()) textPattern.group(1)?.trim().orEmpty() else ""
                val normalizedLabel = normalizeQualityLabel(rawLabel, decodedUrl)

                rawCandidates.add(normalizedLabel to decodedUrl)
            }
        }

        // Fallback: Check HTML5 <video> / <source src="..."> tags if flashvars contained no valid streams
        if (rawCandidates.isEmpty()) {
            val sourceMatcher = Pattern.compile(
                """<source[^>]+src=['"]([^'"]+)['"][^>]*>""",
                Pattern.CASE_INSENSITIVE
            ).matcher(html)
            while (sourceMatcher.find()) {
                val tag = sourceMatcher.group(0).orEmpty()
                val src = sourceMatcher.group(1)?.trim().orEmpty()
                if (!isValidStreamCandidate(src)) continue
                val fullUrl = resolveFullUrl(pageOrigin, decodeKvsUrl(src, licenseCode))
                val labelMatcher = Pattern.compile("""(?:label|title|res)=['"]([^'"]+)['"]""", Pattern.CASE_INSENSITIVE).matcher(tag)
                val rawLabel = if (labelMatcher.find()) labelMatcher.group(1).orEmpty() else ""
                val normalizedLabel = normalizeQualityLabel(rawLabel, fullUrl)
                rawCandidates.add(normalizedLabel to fullUrl)
            }
        }

        if (rawCandidates.isEmpty()) {
            throw Exception("Could not extract any playable video streams from Porntrex page")
        }

        val cookieHeader = extractCookieHeader(collectedSetCookies)
        val playbackHeaders = buildMap {
            put("User-Agent", SCRAPER_USER_AGENT)
            put("Referer", activePageUrl)
            put("Origin", pageOrigin)
            if (!cookieHeader.isNullOrBlank()) {
                put("Cookie", cookieHeader)
            }
        }

        // Pre-resolve /get_file/ 302 redirects in parallel to direct signed CDN URLs (pcdn.cdntrex.com)
        val resolvedStreams = rawCandidates.map { (qLabel, candidateUrl) ->
            async {
                val finalUrl = resolveGetFileRedirect(candidateUrl, activePageUrl, cookieHeader)
                qLabel to finalUrl
            }
        }.awaitAll()

        val extractedQualities = mutableListOf<StreamQuality>()
        val seenUrls = mutableSetOf<String>()
        val seenLabels = mutableSetOf<String>()

        for ((qLabel, streamUrl) in resolvedStreams) {
            val dedupKey = streamUrl.substringBefore('?')
            if (seenUrls.add(dedupKey) && seenLabels.add(qLabel)) {
                val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
                extractedQualities.add(
                    StreamQuality(
                        quality = qLabel,
                        url = streamUrl,
                        format = if (isHls) "HLS" else "MP4",
                        isDefault = false,
                        headers = playbackHeaders
                    )
                )
            }
        }

        if (extractedQualities.isEmpty()) {
            throw Exception("Could not extract any playable video streams from Porntrex page")
        }

        // Sort descending by resolution (2160p 4K -> 1440p -> 1080p -> 720p -> 480p -> 360p -> 240p)
        val sorted = extractedQualities.sortedByDescending { resolutionRank(it.quality) }

        // Mark 1080p as default if available, otherwise highest resolution
        val defaultIndex = sorted.indexOfFirst { it.quality == "1080p" }
            .takeIf { it != -1 }
            ?: sorted.indexOfFirst { it.quality.contains("1080") }
                .takeIf { it != -1 }
            ?: 0

        sorted.mapIndexed { idx, sq ->
            sq.copy(isDefault = idx == defaultIndex)
        }
    }
}

/**
 * Unified Scraper Dispatcher for supported direct video hosters and streaming services:
 * - pixeldrain.com
 * - filester
 * - bunkr
 * - gofile.io
 * - cyberfile.me
 * - pornhub.com
 * - xhamster.com
 * - eporner.com
 * - porntrex.com
 */
object Scraping {

    fun isSupportedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return PixeldrainScraper.isPixeldrainUrl(url) ||
                FilesterScraper.isFilesterUrl(url) ||
                BunkrScraper.isBunkrUrl(url) ||
                GofileScraper.isGofileUrl(url) ||
                CyberFileScraper.isCyberfileUrl(url) ||
                VideoResolvers.isPornhubUrl(url) ||
                XhamsterScraper.isXhamsterUrl(url) ||
                EpornerScraper.isEpornerUrl(url) ||
                PorntrexScraper.isPorntrexUrl(url)
    }

    suspend fun extractStreams(url: String): List<StreamQuality> {
        val trimmed = url.trim()
        return when {
            PixeldrainScraper.isPixeldrainUrl(trimmed) -> PixeldrainScraper.extractStreams(trimmed)
            FilesterScraper.isFilesterUrl(trimmed) -> FilesterScraper.extractStreams(trimmed)
            BunkrScraper.isBunkrUrl(trimmed) -> BunkrScraper.extractStreams(trimmed)
            GofileScraper.isGofileUrl(trimmed) -> GofileScraper.extractStreams(trimmed)
            CyberFileScraper.isCyberfileUrl(trimmed) -> CyberFileScraper.extractStreams(trimmed)
            VideoResolvers.isPornhubUrl(trimmed) -> VideoResolvers.resolvePornhub(trimmed).qualities
            XhamsterScraper.isXhamsterUrl(trimmed) -> XhamsterScraper.extractStreams(trimmed)
            EpornerScraper.isEpornerUrl(trimmed) -> EpornerScraper.extractStreams(trimmed)
            PorntrexScraper.isPorntrexUrl(trimmed) -> PorntrexScraper.extractStreams(trimmed)
            else -> throw Exception("Unsupported scraper URL: $trimmed")
        }
    }
}




