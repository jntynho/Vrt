package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Eporner Stream Scraper (eporner.com)
 * Extracts full metadata and all available playback stream qualities (4K, 1440p, 1080p, 720p, 480p, 360p, 240p).
 */
object EpornerScraper {
    private const val TAG = "EpornerScraper"

    private const val SCRAPER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun isEpornerUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("eporner.com") || trimmed.contains("/api/eporner")
    }

    fun parseVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()

        val match = Pattern.compile("eporner\\.com/(?:video-|embed/|hd-porn/)?([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (match.find()) return match.group(1)

        val pureMatch = Pattern.compile("^video-([a-zA-Z0-9]+)$", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (pureMatch.find()) return pureMatch.group(1)

        if (trimmed.matches(Regex("^[a-zA-Z0-9]{8,15}$"))) return trimmed

        return null
    }

    fun encodeEpornerHash(rawHash: String): String {
        if (rawHash.length != 32) return rawHash
        return try {
            rawHash.substring(0, 8).toLong(16).toString(36) +
                    rawHash.substring(8, 16).toLong(16).toString(36) +
                    rawHash.substring(16, 24).toLong(16).toString(36) +
                    rawHash.substring(24, 32).toLong(16).toString(36)
        } catch (e: Exception) {
            rawHash
        }
    }

    suspend fun resolve(url: String): ResolvedVideo = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        val videoId = parseVideoId(trimmed)
            ?: throw Exception("Invalid Eporner video URL or ID: $trimmed")

        var title = "Eporner Video"
        var coverUrl: String? = null

        // 1. Fetch metadata from official Eporner JSON API
        try {
            val apiReq = Request.Builder()
                .url("https://www.eporner.com/api/v2/video/id/?id=$videoId&format=json")
                .header("User-Agent", SCRAPER_USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val apiRes = NetworkClient.apiClient.newCall(apiReq).await()
            val apiBody = apiRes.body?.string().orEmpty()
            apiRes.close()

            if (apiBody.isNotBlank()) {
                val apiJson = JSONObject(apiBody)
                val metaTitle = apiJson.optString("title")
                if (metaTitle.isNotBlank()) title = metaTitle

                val defaultThumb = apiJson.optJSONObject("default_thumb")
                if (defaultThumb != null) {
                    coverUrl = defaultThumb.optString("src").takeIf { it.isNotBlank() }
                }

                val thumbsArr = apiJson.optJSONArray("thumbs")
                if (thumbsArr != null && thumbsArr.length() > 0) {
                    var maxRes = 0
                    for (i in 0 until thumbsArr.length()) {
                        val tObj = thumbsArr.getJSONObject(i)
                        val w = tObj.optInt("width", 0)
                        val h = tObj.optInt("height", 0)
                        val src = tObj.optString("src")
                        if (w * h > maxRes && src.isNotBlank()) {
                            maxRes = w * h
                            coverUrl = src
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eporner API metadata fetch failed: ${e.message}")
        }

        // 2. Fetch Embed / Main Video page to extract player hash
        var rawHash: String? = null
        val pageUrl = "https://www.eporner.com/video-$videoId/-/"

        try {
            val pageReq = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", SCRAPER_USER_AGENT)
                .header("Referer", "https://www.eporner.com/")
                .build()

            val pageRes = NetworkClient.apiClient.newCall(pageReq).await()
            val pageHtml = pageRes.body?.string().orEmpty()
            pageRes.close()

            if (pageHtml.isNotBlank()) {
                val hashMatcher = Pattern.compile("EP\\.video\\.player\\.hash\\s*=\\s*['\"]([a-zA-Z0-9]+)['\"]").matcher(pageHtml)
                if (hashMatcher.find()) {
                    rawHash = hashMatcher.group(1)
                }

                if (title == "Eporner Video") {
                    val titleMatcher = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(pageHtml)
                    if (titleMatcher.find()) {
                        title = titleMatcher.group(1)?.replace(Regex("(?i)- EPORNER.*$"), "")?.trim() ?: title
                    }
                }

                if (coverUrl.isNullOrBlank()) {
                    val ogImgMatcher = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(pageHtml)
                    if (ogImgMatcher.find()) {
                        coverUrl = ogImgMatcher.group(1)?.trim()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Eporner main page fetch failed: ${e.message}")
        }

        if (rawHash.isNullOrBlank()) {
            try {
                val embedUrl = "https://www.eporner.com/embed/$videoId/"
                val embedReq = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", SCRAPER_USER_AGENT)
                    .header("Referer", pageUrl)
                    .build()

                val embedRes = NetworkClient.apiClient.newCall(embedReq).await()
                val embedHtml = embedRes.body?.string().orEmpty()
                embedRes.close()

                val hashMatcher = Pattern.compile("EP\\.video\\.player\\.hash\\s*=\\s*['\"]([a-zA-Z0-9]+)['\"]").matcher(embedHtml)
                if (hashMatcher.find()) {
                    rawHash = hashMatcher.group(1)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Eporner embed fetch failed: ${e.message}")
            }
        }

        if (rawHash.isNullOrBlank()) {
            throw Exception("Could not extract player authentication hash from Eporner video: $videoId")
        }

        // 3. Query XHR video endpoint for all stream qualities
        val encodedHash = encodeEpornerHash(rawHash)
        val timestamp = System.currentTimeMillis()
        val xhrUrl = "https://www.eporner.com/xhr/video/$videoId?hash=$encodedHash&device=generic&domain=www.eporner.com&fallback=false&supportedFormats=mp4%2Chls&_=$timestamp"

        val xhrReq = Request.Builder()
            .url(xhrUrl)
            .header("User-Agent", SCRAPER_USER_AGENT)
            .header("Referer", "https://www.eporner.com/embed/$videoId/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        val xhrRes = NetworkClient.apiClient.newCall(xhrReq).await()
        val xhrBody = xhrRes.body?.string().orEmpty()
        xhrRes.close()

        if (xhrBody.isBlank()) {
            throw Exception("Eporner XHR stream definition was empty")
        }

        val xhrJson = JSONObject(xhrBody)
        val sourcesObj = xhrJson.optJSONObject("sources")
        val mp4Obj = sourcesObj?.optJSONObject("mp4")
            ?: throw Exception("No MP4 video sources returned by Eporner")

        val playbackHeaders = mapOf(
            "Referer" to "https://www.eporner.com/",
            "User-Agent" to SCRAPER_USER_AGENT
        )

        val streamQualities = mutableListOf<StreamQuality>()
        val keys = mp4Obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val streamItem = mp4Obj.optJSONObject(key) ?: continue
            val src = streamItem.optString("src")
            if (src.isBlank()) continue

            val labelShort = streamItem.optString("labelShort", key.split(" ").firstOrNull().orEmpty())
            val cleanQuality = labelShort.lowercase().replace(Regex("[^0-9p]"), "").ifBlank { labelShort }
            val formattedQuality = when {
                cleanQuality.contains("2160") -> "2160p (4K UHD)"
                cleanQuality.contains("1440") -> "1440p (2K QHD)"
                cleanQuality.contains("1080") -> "1080p (Full HD)"
                cleanQuality.contains("720") -> "720p (HD)"
                cleanQuality.contains("480") -> "480p (SD)"
                cleanQuality.contains("360") -> "360p (LQ)"
                cleanQuality.contains("240") -> "240p (Low)"
                else -> labelShort
            }

            streamQualities.add(
                StreamQuality(
                    quality = formattedQuality,
                    url = src,
                    format = "MP4",
                    isDefault = false,
                    headers = playbackHeaders
                )
            )
        }

        if (streamQualities.isEmpty()) {
            throw Exception("No playable MP4 streams discovered for Eporner video: $videoId")
        }

        // Sort qualities descending from highest (4K / 2160p) down to lowest (240p)
        val sortedQualities = streamQualities.sortedByDescending { q ->
            when {
                q.quality.contains("2160") -> 2160
                q.quality.contains("1440") -> 1440
                q.quality.contains("1080") -> 1080
                q.quality.contains("720") -> 720
                q.quality.contains("480") -> 480
                q.quality.contains("360") -> 360
                q.quality.contains("240") -> 240
                else -> 100
            }
        }

        // Mark 1080p (or highest available if 1080p not found) as default
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
