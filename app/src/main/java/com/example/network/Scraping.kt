package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Data structure representing an extracted video stream.
 */
data class ExtractedStream(
    val url: String,
    val quality: String = "Auto",       // e.g. "1080p", "720p", "Auto", "Original"
    val format: StreamFormat = StreamFormat.MP4,
    val headers: Map<String, String> = emptyMap(),
    val isDirectLink: Boolean = true
)

/**
 * Supported streaming and media container formats.
 */
enum class StreamFormat {
    MP4,
    HLS,    // m3u8
    DASH,   // mpd
    WEBM,
    UNKNOWN
}

/**
 * Result of video scraping operation containing stream details and metadata.
 */
data class ScrapingResult(
    val sourceUrl: String,
    val title: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val streams: List<ExtractedStream> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val extraHeaders: Map<String, String> = emptyMap()
)

/**
 * Base interface for individual video extractors / scrapers.
 */
interface VideoExtractor {
    val name: String
    fun canHandle(url: String): Boolean
    suspend fun extract(url: String): Result<ScrapingResult>
}

/**
 * Main Scraping engine and toolkit for extracting and streaming video from custom URLs.
 */
object Scraping {
    private const val TAG = "Scraping"

    val defaultUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val extractors = mutableListOf<VideoExtractor>()

    /**
     * Register a custom extractor module.
     */
    fun registerExtractor(extractor: VideoExtractor) {
        if (!extractors.contains(extractor)) {
            extractors.add(extractor)
            Log.d(TAG, "Registered video extractor: ${extractor.name}")
        }
    }

    /**
     * Unregister an extractor.
     */
    fun unregisterExtractor(extractor: VideoExtractor) {
        extractors.remove(extractor)
    }

    /**
     * Finds a matching extractor for the given URL and extracts video stream details.
     */
    suspend fun extractVideo(url: String): Result<ScrapingResult> {
        val trimmed = url.trim()
        val extractor = extractors.firstOrNull { it.canHandle(trimmed) }
        
        return if (extractor != null) {
            extractor.extract(trimmed)
        } else {
            // Fallback generic extractor attempt
            extractGenericVideo(trimmed)
        }
    }

    /**
     * Generic extractor inspecting direct media URLs or HTML5 video/source tags.
     */
    suspend fun extractGenericVideo(url: String): Result<ScrapingResult> = withContext(Dispatchers.IO) {
        runCatching {
            val lower = url.lowercase()
            // Direct video link check
            if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")) {
                return@runCatching ScrapingResult(
                    sourceUrl = url,
                    streams = listOf(ExtractedStream(url = url, format = StreamFormat.MP4))
                )
            }
            if (lower.contains(".m3u8")) {
                return@runCatching ScrapingResult(
                    sourceUrl = url,
                    streams = listOf(ExtractedStream(url = url, format = StreamFormat.HLS))
                )
            }
            if (lower.contains(".mpd")) {
                return@runCatching ScrapingResult(
                    sourceUrl = url,
                    streams = listOf(ExtractedStream(url = url, format = StreamFormat.DASH))
                )
            }

            // Fetch and parse webpage
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", defaultUserAgent)
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string().orEmpty()
            val doc = Jsoup.parse(html, url)

            val pageTitle = doc.title().ifBlank { null }
            val ogImage = doc.select("meta[property=og:image]").attr("content").ifBlank { null }
            val streams = mutableListOf<ExtractedStream>()

            // 1. Search in <video> and <source> tags
            doc.select("video source[src], video[src]").forEach { element ->
                val src = element.absUrl("src").ifBlank { element.attr("src") }
                if (src.isNotBlank()) {
                    val type = element.attr("type")
                    val format = when {
                        type.contains("hls", true) || src.contains(".m3u8") -> StreamFormat.HLS
                        type.contains("dash", true) || src.contains(".mpd") -> StreamFormat.DASH
                        type.contains("webm", true) -> StreamFormat.WEBM
                        else -> StreamFormat.MP4
                    }
                    streams.add(ExtractedStream(url = src, format = format))
                }
            }

            // 2. Search regex for HLS playlists in JavaScript
            val m3u8Matcher = Pattern.compile("""https?://[^"'\s\\]+\.m3u8[^"'\s\\]*""").matcher(html)
            while (m3u8Matcher.find()) {
                val found = m3u8Matcher.group()
                if (streams.none { it.url == found }) {
                    streams.add(ExtractedStream(url = found, format = StreamFormat.HLS))
                }
            }

            // 3. Search regex for MP4 links
            val mp4Matcher = Pattern.compile("""https?://[^"'\s\\]+\.mp4[^"'\s\\]*""").matcher(html)
            while (mp4Matcher.find()) {
                val found = mp4Matcher.group()
                if (streams.none { it.url == found }) {
                    streams.add(ExtractedStream(url = found, format = StreamFormat.MP4))
                }
            }

            ScrapingResult(
                sourceUrl = url,
                title = pageTitle,
                thumbnail = ogImage,
                streams = streams
            )
        }
    }
}
