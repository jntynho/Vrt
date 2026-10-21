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

    fun isPornhubUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("pornhub.com") ||
                trimmed.contains("pornhubpremium.com") ||
                trimmed.matches(Regex(".*[?&]viewkey=[a-zA-Z0-9]+.*"))
    }

    // 1. Pornhub Resolver capturing available qualities (1080p, 720p, 480p, 360p, 240p)
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

    // 2. Debrid Resolver (Torbox & Real-Debrid)
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

    // 3. Master Universal Resolver Dispatcher
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

        return when {
            MagnetParser.parseHash(trimmed) != null || isHosterUrl(trimmed) -> {
                resolveDebrid(trimmed, torboxApiKey, realDebridApiKey, debridOrder)
            }
            GofileScraper.isGofileUrl(trimmed) -> {
                val streams = GofileScraper.extractStreams(trimmed)
                val primaryHeaders = streams.firstOrNull { it.isDefault }?.headers ?: streams.firstOrNull()?.headers ?: emptyMap()
                ResolvedVideo(
                    title = streams.firstOrNull()?.quality ?: "Gofile Video",
                    qualities = streams,
                    headers = primaryHeaders
                )
            }
            CyberFileScraper.isCyberfileUrl(trimmed) -> {
                val streams = CyberFileScraper.extractStreams(trimmed)
                val primaryHeaders = streams.firstOrNull { it.isDefault }?.headers ?: streams.firstOrNull()?.headers ?: emptyMap()
                ResolvedVideo(
                    title = streams.firstOrNull()?.quality ?: "CyberFile Video",
                    qualities = streams,
                    headers = primaryHeaders
                )
            }
            isPornhubUrl(trimmed) -> {
                resolvePornhub(trimmed)
            }
            Scraping.isSupportedUrl(trimmed) -> {
                val streams = Scraping.extractStreams(trimmed)
                val primaryHeaders = streams.firstOrNull { it.isDefault }?.headers
                    ?: streams.firstOrNull()?.headers
                    ?: emptyMap()
                ResolvedVideo(
                    title = "Media Stream",
                    qualities = streams,
                    headers = primaryHeaders
                )
            }
            trimmed.contains(".mpd", ignoreCase = true) -> {
                ResolvedVideo("DASH Stream", listOf(StreamQuality("DASH 1080p", trimmed, "DASH", true)))
            }
            trimmed.contains(".m3u8", ignoreCase = true) -> {
                ResolvedVideo("HLS Stream", listOf(StreamQuality("HLS Stream", trimmed, "HLS", true)))
            }
            else -> {
                val headers = mapOf("User-Agent" to DEFAULT_BROWSER_UA)
                val ext = MediaUrlValidator.mediaExtensionOf(trimmed)
                val format = when (ext) {
                    "mkv" -> "MKV"
                    "webm" -> "WEBM"
                    else -> "MP4"
                }
                ResolvedVideo(
                    "Media Stream",
                    listOf(StreamQuality("Direct Stream", trimmed, format, true, headers = headers)),
                    headers = headers
                )
            }
        }
    }
}
