package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * CyberFile Stream Scraper (cyberfile.me)
 * Resolves CyberFile codes, extracts file IDs, and fetches signed direct streaming URLs via ajax file_details.
 */
object CyberFileScraper {
    private const val TAG = "CyberFileScraper"

    private const val SCRAPER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun isCyberfileUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("cyberfile.me") || trimmed.contains("/api/cyberfile")
    }

    fun parseCode(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()

        val match = Pattern.compile("cyberfile\\.me/(?:d/|f/|file/)?([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (match.find()) return match.group(1)

        val parts = trimmed.substringBefore("?").split("/").filter { it.isNotBlank() }
        if (parts.isNotEmpty()) {
            val last = parts.last()
            if (last.matches(Regex("^[a-zA-Z0-9_-]+$"))) return last
        }
        return null
    }

    suspend fun extractStreams(url: String): List<StreamQuality> = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        val code = parseCode(trimmed) ?: throw Exception("Invalid CyberFile URL: $trimmed")
        val pageUrl = "https://cyberfile.me/$code"

        val pageReq = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", SCRAPER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val pageRes = NetworkClient.apiClient.newCall(pageReq).await()
        val pageHtml = pageRes.body?.string().orEmpty()
        val setCookie = pageRes.header("Set-Cookie").orEmpty()
        pageRes.close()

        if (pageHtml.isBlank()) {
            throw Exception("Failed to load CyberFile page: $pageUrl")
        }

        // Extract fileId from JS functions or HTML attributes
        val idMatcher = Pattern.compile(
            "(?:showFile(?:Information|Inline)?\\((\\d+)\\)|fileId\\s*=\\s*['\"]?(\\d+)['\"]?|data-file-id=['\"]?(\\d+)['\"])",
            Pattern.CASE_INSENSITIVE
        ).matcher(pageHtml)

        var fileId: String? = null
        if (idMatcher.find()) {
            fileId = idMatcher.group(1) ?: idMatcher.group(2) ?: idMatcher.group(3)
        }

        var directStreamUrl: String? = null
        var title = "CyberFile Video"

        val titleMatch = Pattern.compile("<title>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE).matcher(pageHtml)
        if (titleMatch.find()) {
            title = titleMatch.group(1)
                ?.replace(Regex("(?i)\\s*-\\s*CyberFile\\s*"), "")
                ?.replace(Regex("(?i)\\.(mp4|mkv|webm|avi|mov)$"), "")
                ?.trim() ?: title
        }

        if (!fileId.isNullOrBlank()) {
            try {
                val formBody = FormBody.Builder()
                    .add("u", fileId)
                    .build()

                val ajaxReqBuilder = Request.Builder()
                    .url("https://cyberfile.me/account/ajax/file_details")
                    .post(formBody)
                    .header("User-Agent", SCRAPER_USER_AGENT)
                    .header("Origin", "https://cyberfile.me")
                    .header("Referer", pageUrl)
                    .header("X-Requested-With", "XMLHttpRequest")

                if (setCookie.isNotBlank()) {
                    ajaxReqBuilder.header("Cookie", setCookie)
                }

                val ajaxRes = NetworkClient.apiClient.newCall(ajaxReqBuilder.build()).await()
                val ajaxBody = ajaxRes.body?.string().orEmpty()
                ajaxRes.close()

                if (ajaxBody.isNotBlank()) {
                    val ajaxJson = JSONObject(ajaxBody)
                    val dHtml = ajaxJson.optString("html")
                    val pageTitle = ajaxJson.optString("page_title")
                    if (pageTitle.isNotBlank()) {
                        title = pageTitle.replace(Regex("(?i)\\.(mp4|mkv|webm|avi|mov)$"), "").trim()
                    }

                    val srcMatcher = Pattern.compile(
                        "(?:<source[^>]+src=[\"']([^\"']+)[\"']|<video[^>]+src=[\"']([^\"']+)[\"']|href=[\"'](https?://[^\"']+\\.(?:mp4|mkv|webm)[^\"']*)[\"'])",
                        Pattern.CASE_INSENSITIVE
                    ).matcher(dHtml)

                    if (srcMatcher.find()) {
                        directStreamUrl = (srcMatcher.group(1) ?: srcMatcher.group(2) ?: srcMatcher.group(3))
                            ?.replace("&amp;", "&")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "CyberFile AJAX details fetch failed: ${e.message}")
            }
        }

        // Fallback: search main HTML for video/source tag
        if (directStreamUrl.isNullOrBlank()) {
            val fallbackMatcher = Pattern.compile(
                "(?:<source[^>]+src=[\"']([^\"']+)[\"']|<video[^>]+src=[\"']([^\"']+)[\"']|href=[\"'](https?://[^\"']+\\.(?:mp4|mkv|webm)[^\"']*)[\"'])",
                Pattern.CASE_INSENSITIVE
            ).matcher(pageHtml)

            if (fallbackMatcher.find()) {
                directStreamUrl = (fallbackMatcher.group(1) ?: fallbackMatcher.group(2) ?: fallbackMatcher.group(3))
                    ?.replace("&amp;", "&")
            }
        }

        if (directStreamUrl.isNullOrBlank()) {
            throw Exception("Direct stream URL not found on CyberFile page: $pageUrl")
        }

        val playbackHeaders = mapOf(
            "Referer" to "https://cyberfile.me/",
            "User-Agent" to SCRAPER_USER_AGENT
        )

        listOf(
            StreamQuality(
                quality = title,
                url = directStreamUrl,
                format = "MP4",
                isDefault = true,
                headers = playbackHeaders
            )
        )
    }
}
