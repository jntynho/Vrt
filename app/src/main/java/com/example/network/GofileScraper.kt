package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * Gofile Scraper & Direct Media Resolver (gofile.io)
 * Automatically acquires guest tokens, computes Website-Token (WT) cryptographic hash,
 * and extracts all direct video download/stream links from folders or files.
 */
object GofileScraper {
    private const val TAG = "GofileScraper"

    private const val SCRAPER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val GOFILE_SALT = "12af056dacea0b"

    private val sessionMutex = Mutex()
    private var cachedToken: String? = null
    private var tokenExpiryTime: Long = 0L

    data class GofileSession(val token: String, val websiteToken: String)

    fun isGofileUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.contains("gofile.io/d/") ||
                trimmed.contains("gofile.io/download/web/") ||
                trimmed.contains("/api/gofile")
    }

    fun parseContentId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()

        val matchD = Pattern.compile("gofile\\.io/d/([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (matchD.find()) return matchD.group(1)

        val directMatch = Pattern.compile("gofile\\.io/download/web/([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (directMatch.find()) return directMatch.group(1)

        return null
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateWebsiteToken(token: String): String {
        val timeBucket = (System.currentTimeMillis() / 1000L / 14400L).toString()
        val preHash = "$SCRAPER_USER_AGENT::::$token::$timeBucket::$GOFILE_SALT"
        return sha256Hex(preHash)
    }

    suspend fun getSession(forceRefresh: Boolean = false): GofileSession = sessionMutex.withLock {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedToken != null && now < tokenExpiryTime) {
            val token = cachedToken!!
            return@withLock GofileSession(token, generateWebsiteToken(token))
        }

        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.gofile.io/accounts")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", SCRAPER_USER_AGENT)
                    .header("Origin", "https://gofile.io")
                    .header("Referer", "https://gofile.io/")
                    .header("Accept", "application/json")
                    .build()

                val res = NetworkClient.apiClient.newCall(req).await()
                val bodyStr = res.body?.string().orEmpty()
                res.close()

                if (res.isSuccessful && bodyStr.isNotBlank()) {
                    val json = JSONObject(bodyStr)
                    if (json.optString("status") == "ok") {
                        val dataObj = json.optJSONObject("data")
                        val token = dataObj?.optString("token").orEmpty()
                        if (token.isNotBlank()) {
                            cachedToken = token
                            tokenExpiryTime = now + (2 * 60 * 60 * 1000L) // 2 hours cache
                            return@withContext GofileSession(token, generateWebsiteToken(token))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gofile guest account creation failed: ${e.message}")
            }

            val fallbackToken = cachedToken ?: ""
            GofileSession(fallbackToken, if (fallbackToken.isNotBlank()) generateWebsiteToken(fallbackToken) else "")
        }
    }

    suspend fun extractStreams(url: String): List<StreamQuality> = withContext(Dispatchers.IO) {
        val trimmed = url.trim()

        var session = getSession(forceRefresh = false)
        if (session.token.isBlank()) {
            session = getSession(forceRefresh = true)
        }

        // Direct web download stream URL (e.g. store*.gofile.io/download/web/...)
        if (trimmed.contains("gofile.io/download/web/")) {
            val streamHeaders = mapOf(
                "Authorization" to "Bearer ${session.token}",
                "User-Agent" to SCRAPER_USER_AGENT,
                "Referer" to "https://gofile.io/"
            )
            return@withContext listOf(
                StreamQuality(
                    quality = "Direct Stream",
                    url = trimmed,
                    format = "MP4",
                    isDefault = true,
                    headers = streamHeaders
                )
            )
        }

        val contentId = parseContentId(trimmed)
            ?: throw Exception("Could not extract Gofile content ID from $trimmed")

        suspend fun fetchContents(tok: String, wt: String): JSONObject? {
            val contentUrl = "https://api.gofile.io/contents/$contentId?page=1&pageSize=100&sortField=name&sortDirection=1"
            val req = Request.Builder()
                .url(contentUrl)
                .header("Authorization", "Bearer $tok")
                .header("X-Website-Token", wt)
                .header("User-Agent", SCRAPER_USER_AGENT)
                .header("Origin", "https://gofile.io")
                .header("Referer", "https://gofile.io/d/$contentId")
                .header("Accept", "application/json")
                .build()

            val res = NetworkClient.apiClient.newCall(req).await()
            val body = res.body?.string().orEmpty()
            res.close()
            return if (body.isNotBlank()) JSONObject(body) else null
        }

        var json = fetchContents(session.token, session.websiteToken)
        val status = json?.optString("status")

        if (status == "error-token" || status == "error-wrongToken" || status == "error-notPremium") {
            session = getSession(forceRefresh = true)
            json = fetchContents(session.token, session.websiteToken)
        }

        if (json == null || json.optString("status") != "ok" || !json.has("data")) {
            val err = json?.optString("status") ?: "Unknown Gofile error"
            throw Exception("Failed to retrieve Gofile content metadata: $err")
        }

        val data = json.getJSONObject("data")
        val streamHeaders = mapOf(
            "Authorization" to "Bearer ${session.token}",
            "User-Agent" to SCRAPER_USER_AGENT,
            "Referer" to "https://gofile.io/d/$contentId"
        )

        val streams = mutableListOf<StreamQuality>()

        // Case A: Single file item
        if (data.optString("type") == "file") {
            val link = data.optString("link")
            val name = data.optString("name", "Gofile Video")
            if (link.isNotBlank()) {
                streams.add(
                    StreamQuality(
                        quality = name,
                        url = link,
                        format = "MP4",
                        isDefault = true,
                        headers = streamHeaders
                    )
                )
            }
        }

        // Case B: Folder containing children files
        val children = data.optJSONObject("children")
        if (children != null) {
            val keys = children.keys()
            var index = 0
            while (keys.hasNext()) {
                val childKey = keys.next()
                val child = children.optJSONObject(childKey) ?: continue
                if (child.optString("type") == "file") {
                    val link = child.optString("link")
                    val name = child.optString("name", "Gofile File")
                    if (link.isNotBlank()) {
                        streams.add(
                            StreamQuality(
                                quality = name,
                                url = link,
                                format = "MP4",
                                isDefault = index == 0,
                                headers = streamHeaders
                            )
                        )
                        index++
                    }
                }
            }
        }

        if (streams.isEmpty()) {
            throw Exception("No playable video files found in Gofile directory: $trimmed")
        }

        streams
    }
}
