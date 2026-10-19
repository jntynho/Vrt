package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI

sealed class ValidatedMediaResult {
    data class Valid(
        val finalUrl: String,
        val mimeType: String?,
        val detectedFormat: String, // "MP4", "HLS", "DASH", "MKV", "WEBM"
        val httpStatusCode: Int,
        val contentLength: Long?,
        val headers: Map<String, String>
    ) : ValidatedMediaResult()

    data class Invalid(
        val reason: String,
        val httpStatusCode: Int? = null
    ) : ValidatedMediaResult()
}

object MediaUrlValidator {
    private const val TAG = "MediaUrlValidator"

    fun mediaExtensionOf(url: String): String {
        try {
            val uri = URI(url)
            val path = uri.path ?: return ""
            val lastDot = path.lastIndexOf('.')
            if (lastDot != -1 && lastDot < path.length - 1) {
                return path.substring(lastDot + 1).lowercase()
            }
        } catch (_: Exception) {}
        return ""
    }

    suspend fun validate(
        rawUrl: String,
        customHeaders: Map<String, String> = emptyMap()
    ): ValidatedMediaResult = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()

        if (trimmed.isEmpty()) {
            return@withContext ValidatedMediaResult.Invalid("Media URL is empty")
        }

        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            return@withContext ValidatedMediaResult.Invalid(
                "Cannot stream raw torrent magnet without resolving via Debrid provider."
            )
        }

        try {
            val uri = URI.create(trimmed)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return@withContext ValidatedMediaResult.Invalid("Unsupported URL protocol: $scheme")
            }
        } catch (e: Exception) {
            return@withContext ValidatedMediaResult.Invalid("Malformed media URL: ${e.message}")
        }

        val ext = mediaExtensionOf(trimmed)
        val defaultFormat = when {
            ext == "mpd" || trimmed.contains(".mpd") -> "DASH"
            ext == "m3u8" || trimmed.contains(".m3u8") -> "HLS"
            ext == "mkv" -> "MKV"
            ext == "webm" -> "WEBM"
            else -> "MP4"
        }

        try {
            val reqBuilder = Request.Builder()
                .url(trimmed)
                .header("Range", "bytes=0-1")

            customHeaders.forEach { (k, v) ->
                reqBuilder.header(k, v)
            }

            val response = NetworkClient.apiClient.newCall(reqBuilder.build()).await()
            val code = response.code
            val finalUrl = response.request.url.toString()
            val contentType = response.header("Content-Type")?.lowercase()
            val contentLength = response.header("Content-Length")?.toLongOrNull()
            response.close()

            Log.d(TAG, "[VALIDATOR] Probed stream URL: code=$code, type=$contentType")

            // Handle HTTP Error status codes
            if (code in listOf(401, 403, 404, 410, 429) || code >= 500) {
                return@withContext ValidatedMediaResult.Invalid(
                    reason = "Server returned HTTP $code error",
                    httpStatusCode = code
                )
            }

            // Reject HTML / JSON error pages
            if (contentType != null && (contentType.contains("text/html") || contentType.contains("application/json"))) {
                return@withContext ValidatedMediaResult.Invalid(
                    reason = "URL returned invalid media response ($contentType)",
                    httpStatusCode = code
                )
            }

            val format = when {
                defaultFormat == "DASH" || contentType?.contains("dash+xml") == true -> "DASH"
                defaultFormat == "HLS" || contentType?.contains("mpegurl") == true -> "HLS"
                defaultFormat == "MKV" || contentType?.contains("matroska") == true -> "MKV"
                defaultFormat == "WEBM" || contentType?.contains("webm") == true -> "WEBM"
                else -> defaultFormat
            }

            return@withContext ValidatedMediaResult.Valid(
                finalUrl = finalUrl,
                mimeType = contentType,
                detectedFormat = format,
                httpStatusCode = code,
                contentLength = contentLength,
                headers = customHeaders
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Network probe warning for $trimmed: ${e.message}. Proceeding with stream playback.")
            return@withContext ValidatedMediaResult.Valid(
                finalUrl = trimmed,
                mimeType = null,
                detectedFormat = defaultFormat,
                httpStatusCode = 0,
                contentLength = null,
                headers = customHeaders
            )
        }
    }
}
