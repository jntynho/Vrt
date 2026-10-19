package com.example.network

import android.util.Log
import com.example.network.debrid.DebridManager
import com.example.network.debrid.DebridOrder
import com.example.network.debrid.DebridResult
import com.example.network.torrent.MagnetParser
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

    // 1. xHamster PRNG Hex Decryptor
    fun decodeXhamsterUrl(hexString: String): String? {
        val trimmed = hexString.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            return null
        }
        val len = trimmed.length
        if (len < 32 || (len and 1) != 0) return null
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

            val algo = bytes[0].toInt() and 0xFF
            var seed = (bytes[1].toInt() and 0xFF) or
                    ((bytes[2].toInt() and 0xFF) shl 8) or
                    ((bytes[3].toInt() and 0xFF) shl 16) or
                    ((bytes[4].toInt() and 0xFF) shl 24)

            val prng: () -> Int = when (algo) {
                1 -> { { seed = (imul(seed, 1664525) + 0x3c6ef35f); (seed and 0xFF) } }
                2 -> { { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5); (seed and 0xFF) } }
                3 -> { { seed += 0x9e3779b9.toInt(); var e = seed xor (seed ushr 16); e = imul(e, 0x85ebca77.toInt()); e = e xor (e ushr 13); e = imul(e, 0xc2b2ae3d.toInt()); (e xor (e ushr 16)) and 0xFF } }
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

    // 2. Pornhub Resolver
    suspend fun resolvePornhub(url: String): ResolvedVideo {
        val qualities = mutableListOf<StreamQuality>()
        var title = "Pornhub Video"
        var coverUrl: String? = null

        val reqHeaders = mapOf(
            "User-Agent" to DEFAULT_BROWSER_UA,
            "Referer" to "https://www.pornhub.com/"
        )

        try {
            val html = NetworkClient.getHtml(url, reqHeaders)
            val titleMatcher = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
            if (titleMatcher.find()) {
                title = titleMatcher.group(1)?.replace(" - Pornhub.com", "")?.trim() ?: title
            }

            val flashvarsMatcher = Pattern.compile("var\\s+flashvars_\\d+\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL).matcher(html)
            if (flashvarsMatcher.find()) {
                val jsonStr = flashvarsMatcher.group(1)
                if (jsonStr != null) {
                    val json = JSONObject(jsonStr)
                    coverUrl = json.optString("image_url")
                    val mediaDefs = json.optJSONArray("mediaDefinitions")
                    if (mediaDefs != null) {
                        for (i in 0 until mediaDefs.length()) {
                            val item = mediaDefs.getJSONObject(i)
                            val format = item.optString("format")
                            val videoUrl = item.optString("videoUrl")
                            val quality = item.optString("quality")
                            if (videoUrl.isNotEmpty()) {
                                qualities.add(
                                    StreamQuality(
                                        quality = if (quality.isNotEmpty()) "${quality}p" else format.uppercase(),
                                        url = videoUrl,
                                        format = if (format.equals("hls", ignoreCase = true)) "HLS" else "MP4",
                                        isDefault = quality == "1080",
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

        return ResolvedVideo(
            title = title,
            qualities = qualities,
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

    // 4. General Master Resolver
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
            decoded.contains("pornhub.com", ignoreCase = true) -> {
                resolvePornhub(decoded)
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
