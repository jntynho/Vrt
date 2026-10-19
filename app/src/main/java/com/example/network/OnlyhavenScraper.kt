package com.example.network

import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder

data class OnlyhavenCreator(
    val id: String,
    val name: String,
    val displayName: String,
    val service: String,
    val profileUrl: String,
    val avatarUrl: String,
    val headerUrl: String,
    val postCount: Int = 0,
    val videoCount: Int = 0,
    val imageCount: Int = 0,
    val savedAt: Long = System.currentTimeMillis()
)

typealias OnlyhavenModel = OnlyhavenCreator

data class OnlyhavenMediaItem(
    val id: String,
    val sha256: String,
    val kind: String, // "image" or "video"
    val mimeType: String,
    val originalFilename: String,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val thumbnailUrl: String,
    val originalUrl: String,
    val postCaption: String? = null,
    val postId: String = "",
    val postAttachments: List<OnlyhavenMediaItem> = emptyList()
) {
    val isMultiMedia: Boolean get() = postAttachments.size > 1
    val totalAttachmentsCount: Int get() = if (postAttachments.isNotEmpty()) postAttachments.size else 1
}

data class OnlyhavenSavedPost(
    val id: String,
    val mediaItem: OnlyhavenMediaItem,
    val creatorId: String = "",
    val creatorName: String = "",
    val creatorDisplayName: String = "",
    val creatorService: String = "",
    val creatorAvatarUrl: String = "",
    val savedAt: Long = System.currentTimeMillis()
)

enum class OnlyhavenSortMode(val apiValue: String, val displayName: String) {
    POPULARITY("bookmarked", "Popularity"),
    DATE_INDEXED("indexed", "Date indexed"),
    DATE_UPDATED("updated", "Date updated"),
    ALPHABETICAL("name", "Alphabetical"),
    BY_SERVICE("service", "By service"),
    DM_COUNT("dm_count", "DM count"),
    POST_COUNT("post_count", "Post count")
}

object OnlyhavenScraper {
    private const val TAG = "OnlyhavenScraper"
    private const val API_BASE = "https://cum.st/api/v1/creators"
    private const val POSTS_API = "https://cum.st/api/v1/posts"
    private const val WEB_BASE = "https://cum.st"
    private const val IMG_BASE = "https://img.cum.st"
    private const val MEDIA_BASE = "https://e1.cum.st/media"

    suspend fun getModels(
        page: Int = 1,
        query: String = "",
        sort: String = "bookmarked"
    ): List<OnlyhavenModel> = getCreators(page = page, query = query, sort = sort)

    suspend fun getModelMedia(
        service: String = "onlyfans",
        modelId: String = "",
        modelName: String = "",
        page: Int = 1,
        limit: Int = 40
    ): List<OnlyhavenMediaItem> = getCreatorMedia(
        service = service,
        creatorId = modelId,
        creatorName = modelName,
        page = page,
        limit = limit
    )

    suspend fun getCreators(
        page: Int = 1,
        query: String = "",
        sort: String = "bookmarked"
    ): List<OnlyhavenCreator> {
        val creators = mutableListOf<OnlyhavenCreator>()
        val cleanQuery = query.trim()
        val limit = 50
        val offset = (page - 1).coerceAtLeast(0) * limit

        try {
            val urlBuilder = StringBuilder(API_BASE)
            urlBuilder.append("?o=").append(offset)
            urlBuilder.append("&n=").append(limit)
            if (sort.isNotBlank()) {
                urlBuilder.append("&sort=").append(URLEncoder.encode(sort, "UTF-8"))
            }
            if (cleanQuery.isNotEmpty()) {
                urlBuilder.append("&q=").append(URLEncoder.encode(cleanQuery, "UTF-8"))
            }

            val requestUrl = urlBuilder.toString()
            Log.d(TAG, "Fetching Onlyhaven API: $requestUrl")

            val headers = mapOf(
                "Accept" to "application/json",
                "Referer" to "$WEB_BASE/creators"
            )

            val jsonStr = NetworkClient.getHtml(requestUrl, headers)
            if (jsonStr.isEmpty()) return emptyList()

            val jsonObject = JSONObject(jsonStr)
            val creatorsArray = jsonObject.optJSONArray("creators") ?: return emptyList()

            for (i in 0 until creatorsArray.length()) {
                val item = creatorsArray.getJSONObject(i)
                val id = item.optString("id", "")
                val name = item.optString("name", "")
                val displayName = item.optString("displayName", name).ifEmpty { name }
                val service = item.optString("service", "onlyfans")
                val updated = item.optLong("updated", 0L)
                val postCount = item.optInt("postCount", 0)
                val videoCount = item.optInt("videoCount", 0)
                val imageCount = item.optInt("imageCount", 0)

                val profileUrl = "$WEB_BASE/creators/$service/$id?display=collages"
                val avatarUrl = if (updated > 0) {
                    "$IMG_BASE/creator/$service/$id/avatar.webp?v=$updated"
                } else {
                    "$IMG_BASE/creator/$service/$id/avatar.webp"
                }
                val headerUrl = if (updated > 0) {
                    "$IMG_BASE/creator/$service/$id/header.webp?v=$updated"
                } else {
                    "$IMG_BASE/creator/$service/$id/header.webp"
                }

                creators.add(
                    OnlyhavenCreator(
                        id = id,
                        name = name,
                        displayName = displayName,
                        service = service,
                        profileUrl = profileUrl,
                        avatarUrl = avatarUrl,
                        headerUrl = headerUrl,
                        postCount = postCount,
                        videoCount = videoCount,
                        imageCount = imageCount
                    )
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error fetching Onlyhaven creators", e)
        }

        return creators
    }

    suspend fun getCreatorMedia(
        service: String = "onlyfans",
        creatorId: String = "",
        creatorName: String = "",
        page: Int = 1,
        limit: Int = 40
    ): List<OnlyhavenMediaItem> {
        val items = mutableListOf<OnlyhavenMediaItem>()
        val offset = (page - 1).coerceAtLeast(0) * limit
        val cleanService = service.trim().lowercase().ifEmpty { "onlyfans" }
        val cleanId = creatorId.trim()
        val cleanName = creatorName.trim()

        try {
            // 1. First attempt: Direct creator posts endpoint /api/v1/{service}/user/{id}/posts
            val directUrl = if (cleanId.isNotEmpty()) {
                "$WEB_BASE/api/v1/$cleanService/user/$cleanId/posts?o=$offset&n=$limit"
            } else null

            var jsonStr = ""
            val headers = mapOf(
                "Accept" to "application/json",
                "Referer" to "$WEB_BASE/creators"
            )

            if (directUrl != null) {
                Log.d(TAG, "Fetching creator posts by ID: $directUrl")
                jsonStr = try {
                    NetworkClient.getHtml(directUrl, headers)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Direct creator posts call failed: ${e.message}, falling back to query search")
                    ""
                }
            }

            // 2. Fallback attempt: if direct ID wasn't available or returned empty, search by query
            if (jsonStr.isEmpty() && cleanName.isNotEmpty()) {
                val searchUrl = "$POSTS_API?q=${URLEncoder.encode(cleanName, "UTF-8")}&o=$offset&n=$limit"
                Log.d(TAG, "Fetching creator posts fallback: $searchUrl")
                jsonStr = try {
                    NetworkClient.getHtml(searchUrl, headers)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Fallback search also failed: ${e.message}")
                    ""
                }
            }

            if (jsonStr.isEmpty()) return emptyList()

            val jsonObject = JSONObject(jsonStr)
            val postsArray = jsonObject.optJSONArray("posts") ?: return emptyList()

            for (i in 0 until postsArray.length()) {
                val post = postsArray.getJSONObject(i)
                val postId = post.optString("id", "")
                val caption = post.optString("captionText", "").takeIf { it.isNotBlank() }
                val attachments = post.optJSONArray("attachments") ?: continue

                val postMediaList = mutableListOf<OnlyhavenMediaItem>()

                for (j in 0 until attachments.length()) {
                    val att = attachments.getJSONObject(j)
                    val isLocked = att.optBoolean("locked", false)
                    if (isLocked) continue

                    val sha256 = att.optString("sha256", "")
                    if (sha256.isEmpty() || sha256 == "null") continue

                    val attId = att.optString("id", "").ifEmpty { "${postId}_$j" }
                    val kind = att.optString("kind", "image")
                    val mimeType = att.optString("mimeType", if (kind == "video") "video/mp4" else "image/jpeg")
                    val originalFilename = att.optString("originalFilename", "")
                    val width = att.optInt("width", 0)
                    val height = att.optInt("height", 0)
                    val durationMs = if (att.has("durationMs") && !att.isNull("durationMs")) att.optLong("durationMs") else null

                    // Capture thumbnail webp for grid, and original for preview
                    val thumbUrl = "$IMG_BASE/thumbnail/$sha256/preview.webp"
                    val ext = when {
                        mimeType.contains("png", ignoreCase = true) || originalFilename.endsWith(".png", ignoreCase = true) -> "png"
                        mimeType.contains("webp", ignoreCase = true) || originalFilename.endsWith(".webp", ignoreCase = true) -> "webp"
                        mimeType.contains("gif", ignoreCase = true) || originalFilename.endsWith(".gif", ignoreCase = true) -> "gif"
                        else -> "jpg"
                    }
                    val origUrl = if (kind == "video") {
                        "$MEDIA_BASE/$sha256/original.mp4"
                    } else {
                        "$MEDIA_BASE/$sha256/original.$ext?response-content-disposition=inline"
                    }

                    postMediaList.add(
                        OnlyhavenMediaItem(
                            id = attId,
                            sha256 = sha256,
                            kind = kind,
                            mimeType = mimeType,
                            originalFilename = originalFilename,
                            width = width,
                            height = height,
                            durationMs = durationMs,
                            thumbnailUrl = thumbUrl,
                            originalUrl = origUrl,
                            postCaption = caption,
                            postId = postId
                        )
                    )
                }

                if (postMediaList.isNotEmpty()) {
                    val cover = postMediaList.first()
                    items.add(
                        cover.copy(
                            postAttachments = postMediaList
                        )
                    )
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error fetching creator media items", e)
        }
        return items
    }
}
