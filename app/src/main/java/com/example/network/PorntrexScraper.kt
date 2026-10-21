package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.regex.Pattern

/**
 * Porntrex Stream Scraper (porntrex.com)
 * Extracts all available video stream qualities (4K, 1080p, 720p, 480p) from flashvars configuration.
 */
object PorntrexScraper {
    private const val TAG = "PorntrexScraper"

    private const val SCRAPER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun isPorntrexUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("porntrex.com") || trimmed.contains("/api/porntrex")
    }

    fun parseVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()

        val match = Pattern.compile("porntrex\\.com/(?:video|embed)/(\\d+)", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (match.find()) return match.group(1)

        if (trimmed.matches(Regex("^\\d{4,10}$"))) return trimmed

        return null
    }

    suspend fun resolve(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        val videoId = parseVideoId(trimmed)
            ?: throw Exception("Invalid Porntrex video URL or ID: $trimmed")

        var targetUrl = if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://www.porntrex.com/video/$videoId/"

        var pageReq = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", SCRAPER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        var pageRes = NetworkClient.apiClient.newCall(pageReq).await()
        var pageHtml = pageRes.body?.string().orEmpty()
        var code = pageRes.code
        pageRes.close()

        if (code == 404) {
            targetUrl = "https://www.porntrex.com/video/$videoId/"
            pageReq = Request.Builder().url(targetUrl).header("User-Agent", SCRAPER_USER_AGENT).build()
            pageRes = NetworkClient.apiClient.newCall(pageReq).await()
            pageHtml = pageRes.body?.string().orEmpty()
            pageRes.close()
        }

        if (pageHtml.isBlank()) {
            throw Exception("Failed to load Porntrex video page: $targetUrl")
        }

        // Parse flashvars dictionary
        val flashvars = mutableMapOf<String, String>()
        val blockMatcher = Pattern.compile("(?:var\\s+flashvars|flashvars)\\s*=\\s*\\{([\\s\\S]+?)\\};", Pattern.CASE_INSENSITIVE).matcher(pageHtml)
        if (blockMatcher.find()) {
            val block = blockMatcher.group(1).orEmpty()
            val propMatcher = Pattern.compile("([a-zA-Z0-9_]+)\\s*:\\s*['\"]([^'\"]*)['\"]").matcher(block)
            while (propMatcher.find()) {
                val k = propMatcher.group(1)
                val v = propMatcher.group(2)
                if (k != null && v != null) {
                    flashvars[k] = v
                }
            }
        }

        // Fallback: search HTML directly for flashvars properties
        if (!flashvars.containsKey("video_url") && !flashvars.containsKey("video_alt_url") && !flashvars.containsKey("video_alt_url2")) {
            val directKeys = listOf(
                "video_title", "video_url", "video_url_text",
                "video_alt_url", "video_alt_url_text",
                "video_alt_url2", "video_alt_url2_text",
                "video_alt_url3", "video_alt_url3_text",
                "video_alt_url4", "video_alt_url4_text",
                "preview_url", "preview_url1", "preview_url2", "preview_url3", "preview_url4"
            )
            for (k in directKeys) {
                val m = Pattern.compile("$k\\s*:\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE).matcher(pageHtml)
                if (m.find()) {
                    val v = m.group(1)
                    if (v != null) flashvars[k] = v
                }
            }
        }

        // Embed page fallback if still empty
        if (!flashvars.containsKey("video_url") && !flashvars.containsKey("video_alt_url") && !flashvars.containsKey("video_alt_url2")) {
            try {
                val embedUrl = "https://www.porntrex.com/embed/$videoId"
                val embedReq = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", SCRAPER_USER_AGENT)
                    .header("Referer", targetUrl)
                    .build()

                val embedRes = NetworkClient.apiClient.newCall(embedReq).await()
                val embedHtml = embedRes.body?.string().orEmpty()
                embedRes.close()

                val emBlock = Pattern.compile("(?:var\\s+flashvars|flashvars)\\s*=\\s*\\{([\\s\\S]+?)\\};", Pattern.CASE_INSENSITIVE).matcher(embedHtml)
                if (emBlock.find()) {
                    val emProps = Pattern.compile("([a-zA-Z0-9_]+)\\s*:\\s*['\"]([^'\"]*)['\"]").matcher(emBlock.group(1).orEmpty())
                    while (emProps.find()) {
                        val k = emProps.group(1)
                        val v = emProps.group(2)
                        if (k != null && v != null) {
                            flashvars[k] = v
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Porntrex embed fallback failed: ${e.message}")
            }
        }

        var title = flashvars["video_title"].orEmpty()
        if (title.isBlank()) {
            val titleMatcher = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(pageHtml)
            if (titleMatcher.find()) {
                title = titleMatcher.group(1)?.replace(Regex("(?i)[|-]\\s*Porntre.*$"), "")?.trim() ?: title
            }
        }
        if (title.isBlank()) title = "Porntrex Video"

        var coverUrl: String? = listOfNotNull(
            flashvars["preview_url4"],
            flashvars["preview_url3"],
            flashvars["preview_url2"],
            flashvars["preview_url1"],
            flashvars["preview_url"]
        ).firstOrNull { it.isNotBlank() }

        if (coverUrl != null && coverUrl.startsWith("//")) {
            coverUrl = "https:$coverUrl"
        }

        if (coverUrl.isNullOrBlank()) {
            val ogMatcher = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(pageHtml)
            if (ogMatcher.find()) {
                var c = ogMatcher.group(1)?.trim()
                if (c != null && c.startsWith("//")) c = "https:$c"
                coverUrl = c
            }
        }

        val playbackHeaders = mapOf(
            "Referer" to "https://www.porntrex.com/",
            "User-Agent" to SCRAPER_USER_AGENT
        )

        val streamQualities = mutableListOf<StreamQuality>()

        data class QualitySpec(val urlKey: String, val textKey: String, val defaultQ: String, val h: Int)
        val qualitySpecs = listOf(
            QualitySpec("video_alt_url4", "video_alt_url4_text", "2160p (4K)", 2160),
            QualitySpec("video_alt_url3", "video_alt_url3_text", "1440p (2K)", 1440),
            QualitySpec("video_alt_url2", "video_alt_url2_text", "1080p FHD", 1080),
            QualitySpec("video_alt_url", "video_alt_url_text", "720p HD", 720),
            QualitySpec("video_url", "video_url_text", "480p SD", 480)
        )

        for (spec in qualitySpecs) {
            val streamUrl = flashvars[spec.urlKey]
            if (!streamUrl.isNullOrBlank() && streamUrl.startsWith("http", ignoreCase = true)) {
                val labelText = flashvars[spec.textKey] ?: spec.defaultQ
                val formattedLabel = when {
                    labelText.contains("2160") || spec.h == 2160 -> "2160p (4K)"
                    labelText.contains("1440") || spec.h == 1440 -> "1440p (2K)"
                    labelText.contains("1080") || spec.h == 1080 -> "1080p FHD"
                    labelText.contains("720") || spec.h == 720 -> "720p HD"
                    labelText.contains("480") || spec.h == 480 -> "480p SD"
                    else -> labelText
                }

                if (streamQualities.none { it.quality == formattedLabel }) {
                    streamQualities.add(
                        StreamQuality(
                            quality = formattedLabel,
                            url = streamUrl,
                            format = "MP4",
                            isDefault = false,
                            headers = playbackHeaders
                        )
                    )
                }
            }
        }

        // Fallback: check <source src="..."> tags
        if (streamQualities.isEmpty()) {
            val sourceMatcher = Pattern.compile("<source[^>]+src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(pageHtml)
            while (sourceMatcher.find()) {
                val sUrl = sourceMatcher.group(1) ?: continue
                if (sUrl.startsWith("http", ignoreCase = true)) {
                    val hMatch = Pattern.compile("(\\d{3,4})p", Pattern.CASE_INSENSITIVE).matcher(sUrl)
                    val qualityLabel = if (hMatch.find()) "${hMatch.group(1)}p" else "Direct Stream"
                    if (streamQualities.none { it.url == sUrl }) {
                        streamQualities.add(
                            StreamQuality(
                                quality = qualityLabel,
                                url = sUrl,
                                format = "MP4",
                                isDefault = false,
                                headers = playbackHeaders
                            )
                        )
                    }
                }
            }
        }

        if (streamQualities.isEmpty()) {
            throw Exception("No playable video streams discovered for Porntrex video: $videoId")
        }

        // Sort descending by resolution (2160p -> 1440p -> 1080p -> 720p -> 480p)
        val sortedQualities = streamQualities.sortedByDescending { q ->
            when {
                q.quality.contains("2160") -> 2160
                q.quality.contains("1440") -> 1440
                q.quality.contains("1080") -> 1080
                q.quality.contains("720") -> 720
                q.quality.contains("480") -> 480
                q.quality.contains("360") -> 360
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
            headers = playbackHeaders
        )
    }

    suspend fun extractStreams(url: String): List<StreamQuality> {
        return resolve(url).qualities
    }
}
