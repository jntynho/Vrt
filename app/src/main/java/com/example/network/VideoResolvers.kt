package com.example.network

import android.util.Log
import com.example.network.debrid.DebridManager
import com.example.network.debrid.DebridOrder
import com.example.network.debrid.DebridResult
import com.example.network.torrent.MagnetParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

data class StreamQuality(
    val quality: String, // "4K", "1080p60", "1080p", "720p", "Auto", "Source"
    val url: String,
    val format: String = "MP4", // "MP4", "HLS", "DASH", "MKV", "WEBM"
    val isDefault: Boolean = false,
    val headers: Map<String, String> = emptyMap()
)

data class SubtitleTrack(
    val language: String,
    val label: String,
    val url: String
)

data class ResolvedVideo(
    val title: String,
    val qualities: List<StreamQuality>,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val coverUrl: String? = null,
    val headers: Map<String, String> = emptyMap()
)

object VideoResolvers {
    private const val TAG = "VideoResolvers"

    private const val DEFAULT_BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private fun imul(a: Int, b: Int): Int = a * b

    // 1. xHamster PRNG Hex Decryptor supporting all 7 official xplayer.js algorithms
    fun decodeXhamsterUrl(hexString: String): String? {
        val trimmed = hexString.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            return null
        }
        val len = trimmed.length
        if (len < 12 || (len and 1) != 0) return null
        if (MagnetParser.parseHash(trimmed) != null) return null

        for (i in 0 until len) {
            val c = trimmed[i]
            if (!((c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F'))) {
                return null
            }
        }

        return try {
            val bytes = ByteArray(len shr 1)
            for (i in 0 until len step 2) {
                bytes[i shr 1] = trimmed.substring(i, i + 2).toInt(16).toByte()
            }
            if (bytes.size < 5) return null

            val algo = bytes[0].toInt() and 0xFF
            var seed = (bytes[1].toInt() and 0xFF) or
                    ((bytes[2].toInt() and 0xFF) shl 8) or
                    ((bytes[3].toInt() and 0xFF) shl 16) or
                    ((bytes[4].toInt() and 0xFF) shl 24)

            val prng: () -> Int = when (algo) {
                1 -> { { seed = imul(seed, 1664525) + 0x3c6ef35f; seed and 0xFF } }
                2 -> { { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5); seed and 0xFF } }
                3 -> {
                    {
                        seed += 0x9e3779b9.toInt()
                        var e = seed xor (seed ushr 16)
                        e = imul(e, 0x85ebca77.toInt())
                        e = e xor (e ushr 13)
                        e = imul(e, 0xc2b2ae3d.toInt())
                        (e xor (e ushr 16)) and 0xFF
                    }
                }
                4 -> {
                    {
                        seed += 0x6d2b79f5.toInt()
                        var e = (seed shl 7) or (seed ushr 25)
                        e += 0x9e3779b9.toInt()
                        e = e xor (e ushr 11)
                        e = imul(e, 0x27d4eb2d.toInt())
                        e and 0xFF
                    }
                }
                5 -> {
                    {
                        seed = seed xor (seed shl 7)
                        seed = seed xor (seed ushr 9)
                        seed = seed xor (seed shl 8)
                        seed += 0xa5a5a5a5.toInt()
                        seed and 0xFF
                    }
                }
                6 -> {
                    {
                        seed = imul(seed, 0x2c9277b5.toInt()) + 0xac564b05.toInt()
                        val intermediate = seed xor (seed ushr 18)
                        val shift = (seed ushr 27) and 31
                        (intermediate ushr shift) and 0xFF
                    }
                }
                7 -> {
                    {
                        seed += 0x9e3779b9.toInt()
                        var e = seed xor (seed shl 5)
                        e = imul(e, 0x7feb352d.toInt())
                        e = e xor (e ushr 15)
                        e = imul(e, 0x846ca68b.toInt())
                        e and 0xFF
                    }
                }
                else -> return null
            }

            val out = ByteArray(bytes.size - 5)
            for (i in 0 until out.size) {
                out[i] = (bytes[i + 5].toInt() xor prng()).toByte()
            }
            val result = String(out, Charsets.UTF_8)
            if (result.startsWith("http://") || result.startsWith("https://")) {
                result
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "xHamster decode skipped: ${e.message}")
            null
        }
    }

    fun isXhamsterUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("xhamster.com") ||
                trimmed.contains("xhamster.desi") ||
                trimmed.contains("xhamster.one") ||
                trimmed.contains("xhamster2.com") ||
                trimmed.contains("xhcdn.com") ||
                trimmed.matches(Regex(".*(?:xhamster[0-9]*\\.[a-z]{2,})/(?:videos|embed)/.*")) ||
                trimmed.matches(Regex("^xh[a-zA-Z0-9]+$"))
    }

    suspend fun resolveXhamster(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        val targetUrl = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("xh") -> "https://xhamster.com/videos/$trimmed"
            else -> "https://xhamster.com/videos/$trimmed"
        }

        val reqHeaders = mapOf(
            "User-Agent" to DEFAULT_BROWSER_UA,
            "Referer" to "https://xhamster.com/",
            "Origin" to "https://xhamster.com"
        )

        val pageReq = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", DEFAULT_BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val pageRes = NetworkClient.apiClient.newCall(pageReq).await()
        val html = pageRes.body?.string().orEmpty()
        pageRes.close()

        if (html.isBlank()) {
            throw Exception("Failed to load xHamster video page: $targetUrl")
        }

        var title = "xHamster Video"
        var coverUrl: String? = null

        val initialsMatcher = Pattern.compile("window\\.initials\\s*=\\s*(\\{[\\s\\S]*?\\});").matcher(html)
        var initialsJson: JSONObject? = null
        if (initialsMatcher.find()) {
            val jsonStr = initialsMatcher.group(1)
            if (jsonStr != null) {
                try {
                    initialsJson = JSONObject(jsonStr)
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing xHamster window.initials JSON: ${e.message}")
                }
            }
        }

        val videoModel = initialsJson?.optJSONObject("videoModel")
        val videoEntity = initialsJson?.optJSONObject("videoEntity")
        val parsedTitle = videoModel?.optString("title")?.takeIf { it.isNotBlank() }
            ?: videoEntity?.optString("title")?.takeIf { it.isNotBlank() }

        if (!parsedTitle.isNullOrBlank()) {
            title = parsedTitle
        } else {
            val titleMatcher = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
            if (titleMatcher.find()) {
                title = titleMatcher.group(1)?.replace(Regex("(?i)- xHamster.*$"), "")?.trim() ?: title
            }
        }

        val ogImageMatcher = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (ogImageMatcher.find()) {
            coverUrl = ogImageMatcher.group(1)?.trim()
        }
        if (coverUrl.isNullOrBlank()) {
            coverUrl = videoModel?.optString("spriteURL")?.takeIf { it.isNotBlank() }
        }

        val xplayerSettings = initialsJson?.optJSONObject("xplayerSettings")
        val sources = xplayerSettings?.optJSONObject("sources")

        val rawQualities = mutableListOf<StreamQuality>()

        // A. Extract & decode HLS Master Playlist URL
        val hlsObj = sources?.optJSONObject("hls")
        val rawHlsUrl = hlsObj?.optJSONObject("h264")?.optString("url")?.takeIf { it.isNotBlank() }
            ?: hlsObj?.optJSONObject("av1")?.optString("url")?.takeIf { it.isNotBlank() }
            ?: hlsObj?.optJSONObject("h264")?.optString("fallback")?.takeIf { it.isNotBlank() }

        val hlsMasterUrl = if (!rawHlsUrl.isNullOrBlank()) decodeXhamsterUrl(rawHlsUrl) else null

        if (!hlsMasterUrl.isNullOrBlank()) {
            try {
                val hlsReq = Request.Builder()
                    .url(hlsMasterUrl)
                    .header("User-Agent", DEFAULT_BROWSER_UA)
                    .header("Referer", targetUrl)
                    .header("Origin", "https://xhamster.com")
                    .build()

                val hlsRes = NetworkClient.apiClient.newCall(hlsReq).await()
                val playlistText = hlsRes.body?.string().orEmpty()
                hlsRes.close()

                if (playlistText.isNotBlank()) {
                    val baseUrl = hlsMasterUrl.substring(0, hlsMasterUrl.lastIndexOf('/') + 1)
                    val lines = playlistText.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotBlank() }
                    for (i in 0 until lines.size) {
                        val line = lines[i]
                        if (line.startsWith("#EXT-X-STREAM-INF:")) {
                            val uri = lines.getOrNull(i + 1)
                            if (uri == null || uri.startsWith("#")) continue

                            val resMatch = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE).matcher(line)
                            val height = if (resMatch.find()) resMatch.group(2)?.toIntOrNull() else null

                            val qualityName = when {
                                height != null && height >= 2160 -> "2160p (4K)"
                                height != null && height >= 1440 -> "1440p (2K)"
                                height != null && height >= 1080 -> "1080p"
                                height != null && height >= 720 -> "720p"
                                height != null && height >= 480 -> "480p"
                                height != null && height >= 360 -> "360p"
                                height != null && height >= 240 -> "240p"
                                height != null -> "${height}p"
                                else -> "Auto"
                            }

                            val variantUrl = if (uri.startsWith("http://") || uri.startsWith("https://")) uri else "$baseUrl$uri"
                            rawQualities.add(
                                StreamQuality(
                                    quality = qualityName,
                                    url = variantUrl,
                                    format = "HLS",
                                    isDefault = false,
                                    headers = reqHeaders
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "xHamster master HLS playlist fetch failed: ${e.message}")
            }

            // Always add master playlist as fallback HLS Auto option
            rawQualities.add(
                StreamQuality(
                    quality = "Auto (Adaptive)",
                    url = hlsMasterUrl,
                    format = "HLS",
                    isDefault = false,
                    headers = reqHeaders
                )
            )
        }

        // B. Extract & decode all standard MP4 sources (sources.standard.h264 & sources.standard.av1)
        val standardObj = sources?.optJSONObject("standard")
        val stdSourcesList = mutableListOf<JSONObject>()
        val h264Arr = standardObj?.optJSONArray("h264")
        if (h264Arr != null) {
            for (i in 0 until h264Arr.length()) {
                val it = h264Arr.optJSONObject(i)
                if (it != null) stdSourcesList.add(it)
            }
        }
        val av1Arr = standardObj?.optJSONArray("av1")
        if (av1Arr != null) {
            for (i in 0 until av1Arr.length()) {
                val it = av1Arr.optJSONObject(i)
                if (it != null) stdSourcesList.add(it)
            }
        }

        for (srcItem in stdSourcesList) {
            val q = srcItem.optString("quality").ifBlank { srcItem.optString("label") }
            if (q.equals("auto", ignoreCase = true)) continue
            val rawUrl = srcItem.optString("url")
            if (rawUrl.isBlank()) continue

            val decodedUrl = decodeXhamsterUrl(rawUrl)
            if (!decodedUrl.isNullOrBlank() && decodedUrl.startsWith("http", ignoreCase = true)) {
                val label = "${q} MP4"
                if (rawQualities.none { it.url == decodedUrl }) {
                    rawQualities.add(
                        StreamQuality(
                            quality = label,
                            url = decodedUrl,
                            format = "MP4",
                            isDefault = false,
                            headers = reqHeaders
                        )
                    )
                }
            }
        }

        if (rawQualities.isEmpty()) {
            throw Exception("No playable video streams discovered for xHamster URL: $targetUrl")
        }

        // Sort descending by resolution (2160p -> 1440p -> 1080p -> 720p -> 480p -> 360p -> 240p)
        val sortedQualities = rawQualities.sortedByDescending { q ->
            when {
                q.quality.contains("2160") || q.quality.contains("4k", ignoreCase = true) -> 2160
                q.quality.contains("1440") || q.quality.contains("2k", ignoreCase = true) -> 1440
                q.quality.contains("1080") -> 1080
                q.quality.contains("720") -> 720
                q.quality.contains("480") -> 480
                q.quality.contains("360") -> 360
                q.quality.contains("240") -> 240
                q.quality.contains("auto", ignoreCase = true) -> 1
                else -> 100
            }
        }

        val has1080 = sortedQualities.any { it.quality.contains("1080") }
        val finalQualities = sortedQualities.map { q ->
            if (has1080 && q.quality.contains("1080")) {
                q.copy(isDefault = true)
            } else if (!has1080 && q == sortedQualities.first()) {
                q.copy(isDefault = true)
            } else {
                q
            }
        }

        ResolvedVideo(
            title = title,
            qualities = finalQualities,
            coverUrl = coverUrl,
            headers = reqHeaders
        )
    }

    fun isPornhubUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("pornhub.com") ||
                trimmed.contains("pornhubpremium.com") ||
                trimmed.matches(Regex(".*[?&]viewkey=[a-zA-Z0-9]+.*"))
    }

    // 2. Pornhub Resolver capturing all available qualities (1080p, 720p, 480p, 360p, 240p)
    suspend fun resolvePornhub(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        val qualities = mutableListOf<StreamQuality>()
        var title = "Pornhub Video"
        var coverUrl: String? = null

        val reqHeaders = mapOf(
            "User-Agent" to DEFAULT_BROWSER_UA,
            "Referer" to "https://www.pornhub.com/"
        )

        try {
            val pageReq = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_BROWSER_UA)
                .header("Referer", "https://www.pornhub.com/")
                .build()

            val pageRes = NetworkClient.apiClient.newCall(pageReq).await()
            val html = pageRes.body?.string().orEmpty()
            val setCookie = pageRes.header("Set-Cookie").orEmpty()
            pageRes.close()

            val titleMatcher = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
            if (titleMatcher.find()) {
                title = titleMatcher.group(1)?.replace(Regex("(?i)\\s*-\\s*Pornhub\\.com"), "")?.trim() ?: title
            }

            val flashvarsMatcher = Pattern.compile("var\\s+flashvars_\\d+\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL).matcher(html)
            var flashvarsJson: JSONObject? = null
            if (flashvarsMatcher.find()) {
                val jsonStr = flashvarsMatcher.group(1)
                if (jsonStr != null) {
                    try {
                        flashvarsJson = JSONObject(jsonStr)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse Pornhub flashvars JSON: ${e.message}")
                    }
                }
            }

            if (flashvarsJson != null) {
                coverUrl = flashvarsJson.optString("image_url").takeIf { it.isNotBlank() }
                val videoTitle = flashvarsJson.optString("video_title")
                if (videoTitle.isNotBlank()) {
                    title = videoTitle.replace("+", " ").trim()
                }

                val mediaDefs = flashvarsJson.optJSONArray("mediaDefinitions")
                if (mediaDefs != null) {
                    // Check for direct MP4 media definitions via get_media endpoint
                    var mp4GetMediaUrl: String? = null
                    for (i in 0 until mediaDefs.length()) {
                        val item = mediaDefs.getJSONObject(i)
                        val format = item.optString("format")
                        val vUrl = item.optString("videoUrl")
                        if (format.equals("mp4", ignoreCase = true) && vUrl.isNotBlank()) {
                            mp4GetMediaUrl = vUrl
                            break
                        }
                    }

                    if (!mp4GetMediaUrl.isNullOrBlank()) {
                        try {
                            val mediaReqBuilder = Request.Builder()
                                .url(mp4GetMediaUrl)
                                .header("User-Agent", DEFAULT_BROWSER_UA)
                                .header("Referer", url)

                            if (setCookie.isNotBlank()) {
                                mediaReqBuilder.header("Cookie", setCookie)
                            }

                            val mediaRes = NetworkClient.apiClient.newCall(mediaReqBuilder.build()).await()
                            val mediaBody = mediaRes.body?.string().orEmpty()
                            mediaRes.close()

                            if (mediaBody.isNotBlank()) {
                                val mediaArray = JSONArray(mediaBody)
                                for (j in 0 until mediaArray.length()) {
                                    val mObj = mediaArray.getJSONObject(j)
                                    val vUrl = mObj.optString("videoUrl")
                                    val q = mObj.optString("quality")
                                    if (vUrl.isNotBlank()) {
                                        val qualityLabel = if (q.matches(Regex("^\\d+$"))) "${q}p" else q.ifBlank { "MP4" }
                                        qualities.add(
                                            StreamQuality(
                                                quality = qualityLabel,
                                                url = vUrl,
                                                format = "MP4",
                                                isDefault = false,
                                                headers = reqHeaders
                                            )
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Pornhub get_media request failed: ${e.message}")
                        }
                    }

                    // Also check HLS media definitions if present
                    for (i in 0 until mediaDefs.length()) {
                        val item = mediaDefs.getJSONObject(i)
                        val format = item.optString("format")
                        val videoUrl = item.optString("videoUrl")
                        val quality = item.optString("quality")
                        if (videoUrl.isNotEmpty()) {
                            val isHls = format.equals("hls", ignoreCase = true)
                            val qLabel = if (quality.isNotEmpty()) "${quality}p" else format.uppercase()
                            if (qualities.none { it.url == videoUrl }) {
                                qualities.add(
                                    StreamQuality(
                                        quality = if (isHls) "$qLabel (HLS)" else qLabel,
                                        url = videoUrl,
                                        format = if (isHls) "HLS" else "MP4",
                                        isDefault = false,
                                        headers = reqHeaders
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pornhub resolver error", e)
        }

        if (qualities.isEmpty()) {
            qualities.add(StreamQuality("Source", url, "MP4", true, headers = reqHeaders))
        }

        // Sort descending by resolution (2160p -> 1080p -> 720p -> 480p -> 360p -> 240p)
        val sortedQualities = qualities.sortedByDescending { q ->
            when {
                q.quality.contains("2160") || q.quality.contains("4k", ignoreCase = true) -> 2160
                q.quality.contains("1440") || q.quality.contains("2k", ignoreCase = true) -> 1440
                q.quality.contains("1080") -> 1080
                q.quality.contains("720") -> 720
                q.quality.contains("480") -> 480
                q.quality.contains("360") -> 360
                q.quality.contains("240") -> 240
                else -> 100
            }
        }

        val has1080 = sortedQualities.any { it.quality.contains("1080") }
        val finalQualities = sortedQualities.map { q ->
            if (has1080 && q.quality.contains("1080")) {
                q.copy(isDefault = true)
            } else if (!has1080 && q == sortedQualities.first()) {
                q.copy(isDefault = true)
            } else {
                q
            }
        }

        ResolvedVideo(
            title = title,
            qualities = finalQualities,
            coverUrl = coverUrl,
            headers = reqHeaders
        )
    }

    // 3. Debrid Resolver (Torbox & Real-Debrid)
    suspend fun resolveDebrid(
        queryOrMagnet: String,
        torboxApiKey: String,
        realDebridApiKey: String,
        debridOrder: DebridOrder = DebridOrder.AUTO
    ): ResolvedVideo {
        val debridManager = DebridManager(
            torboxKeyProvider = { torboxApiKey },
            realDebridKeyProvider = { realDebridApiKey },
            debridOrderProvider = { debridOrder }
        )

        val result = debridManager.resolve(queryOrMagnet)
        when (result) {
            is DebridResult.Success -> {
                val filename = result.filename ?: "Debrid Stream"
                val ext = MediaUrlValidator.mediaExtensionOf(filename)
                val format = when (ext) {
                    "mkv" -> "MKV"
                    "webm" -> "WEBM"
                    "m3u8" -> "HLS"
                    "mpd" -> "DASH"
                    else -> "MP4"
                }

                val qualityLabel = when {
                    filename.contains("2160p", ignoreCase = true) || filename.contains("4k", ignoreCase = true) -> "4K Stream"
                    filename.contains("1080p", ignoreCase = true) -> "1080p Stream"
                    filename.contains("720p", ignoreCase = true) -> "720p Stream"
                    else -> "Source Stream"
                }

                return ResolvedVideo(
                    title = filename,
                    qualities = listOf(
                        StreamQuality(
                            quality = qualityLabel,
                            url = result.streamUrl,
                            format = format,
                            isDefault = true,
                            headers = result.headers
                        )
                    ),
                    headers = result.headers
                )
            }
            is DebridResult.Error -> {
                throw Exception("${result.providerName}: ${result.message}")
            }
        }
    }

    private fun isHosterUrl(url: String): Boolean {
        val lower = url.lowercase()
        val hosterDomains = listOf("1fichier.com", "rapidgator.net", "mega.nz", "turbobit.net", "uploaded.net", "uptobox.com")
        return hosterDomains.any { lower.contains(it) }
    }

    // 4. Master Universal Resolver Dispatcher
    suspend fun resolve(
        rawUrl: String,
        torboxApiKey: String = "",
        realDebridApiKey: String = "",
        debridOrder: DebridOrder = DebridOrder.AUTO
    ): ResolvedVideo {
        val trimmed = rawUrl.trim()

        if (MagnetParser.parseHash(trimmed) != null || isHosterUrl(trimmed)) {
            return resolveDebrid(trimmed, torboxApiKey, realDebridApiKey, debridOrder)
        }

        val decoded = decodeXhamsterUrl(trimmed) ?: trimmed

        return when {
            MagnetParser.parseHash(decoded) != null || isHosterUrl(decoded) -> {
                resolveDebrid(decoded, torboxApiKey, realDebridApiKey, debridOrder)
            }
            GofileScraper.isGofileUrl(decoded) -> {
                val streams = GofileScraper.extractStreams(decoded)
                val primaryHeaders = streams.firstOrNull { it.isDefault }?.headers ?: streams.firstOrNull()?.headers ?: emptyMap()
                ResolvedVideo(
                    title = streams.firstOrNull()?.quality ?: "Gofile Video",
                    qualities = streams,
                    headers = primaryHeaders
                )
            }
            CyberFileScraper.isCyberfileUrl(decoded) -> {
                val streams = CyberFileScraper.extractStreams(decoded)
                val primaryHeaders = streams.firstOrNull { it.isDefault }?.headers ?: streams.firstOrNull()?.headers ?: emptyMap()
                ResolvedVideo(
                    title = streams.firstOrNull()?.quality ?: "CyberFile Video",
                    qualities = streams,
                    headers = primaryHeaders
                )
            }
            EpornerScraper.isEpornerUrl(decoded) -> {
                EpornerScraper.resolve(decoded)
            }
            PorntrexScraper.isPorntrexUrl(decoded) -> {
                PorntrexScraper.resolve(decoded)
            }
            isXhamsterUrl(decoded) -> {
                resolveXhamster(decoded)
            }
            isPornhubUrl(decoded) -> {
                resolvePornhub(decoded)
            }
            Scraping.isSupportedUrl(decoded) -> {
                val streams = Scraping.extractStreams(decoded)
                val primaryHeaders = streams.firstOrNull { it.isDefault }?.headers
                    ?: streams.firstOrNull()?.headers
                    ?: emptyMap()
                ResolvedVideo(
                    title = "Media Stream",
                    qualities = streams,
                    headers = primaryHeaders
                )
            }
            decoded.contains(".mpd", ignoreCase = true) -> {
                ResolvedVideo("DASH Stream", listOf(StreamQuality("DASH 1080p", decoded, "DASH", true)))
            }
            decoded.contains(".m3u8", ignoreCase = true) -> {
                ResolvedVideo("HLS Stream", listOf(StreamQuality("HLS Stream", decoded, "HLS", true)))
            }
            else -> {
                val headers = mapOf("User-Agent" to DEFAULT_BROWSER_UA)
                val ext = MediaUrlValidator.mediaExtensionOf(decoded)
                val format = when (ext) {
                    "mkv" -> "MKV"
                    "webm" -> "WEBM"
                    else -> "MP4"
                }
                ResolvedVideo(
                    "Media Stream",
                    listOf(StreamQuality("Direct Stream", decoded, format, true, headers = headers)),
                    headers = headers
                )
            }
        }
    }
}
