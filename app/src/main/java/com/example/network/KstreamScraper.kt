package com.example.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

enum class KanimeSortOrder(val apiValue: String, val displayName: String) {
    AZ("az", "A-Z"),
    ZA("za", "Z-A"),
    RECENTLY_UPLOADED("recently-uploaded", "Recently Uploaded"),
    RECENTLY_RELEASED("recently-released", "Recently Released"),
    OLDEST_UPLOADS("oldest-uploads", "Oldest Uploads"),
    OLDEST_RELEASES("oldest-releases", "Oldest Releases"),
    VIEW_COUNT("view-count", "View Count")
}

data class KstreamMediaItem(
    val id: String,
    val title: String,
    val posterUrl: String,
    val episodeUrl: String
)

data class KanimeEpisodeItem(
    val episodeNumber: Int,
    val title: String,
    val episodeUrl: String
)

data class KanimeEpisodeDetails(
    val episodeNumber: Int,
    val title: String,
    val episodeUrl: String,
    val posterUrl: String,
    val date: String = "",
    val isUncensored: Boolean = false,
    val description: String = "",
    val gallery: List<String> = emptyList()
)

data class KanimeAnimeDetails(
    val posterUrl: String,
    val description: String,
    val episodes: List<KanimeEpisodeItem>,
    val defaultEpisodeDetails: KanimeEpisodeDetails? = null
)

data class KstreamResolvedStream(
    val title: String,
    val qualities: List<StreamQuality>,
    val subtitles: List<SubtitleTrack> = emptyList()
)

object kstreamScraper {
    private const val TAG = "kstreamScraper"
    private const val BASE_URL = "https://hstream.moe"
    private const val WATCHHENTAI_URL = "https://watchhentai.net"
    private const val HENTAIMAMA_URL = "https://hentaimama.io"

    private const val FEED_CACHE_TTL_MS = 10 * 60 * 1000L
    private const val DETAILS_CACHE_TTL_MS = 15 * 60 * 1000L
    private const val STREAM_CACHE_TTL_MS = 20 * 60 * 1000L
    private const val HSTREAM_COOLDOWN_DURATION_MS = 3 * 60 * 1000L

    @Volatile
    private var hstreamCooldownUntilMs: Long = 0L

    private data class CacheEntry<T>(
        val value: T,
        val timestampMs: Long = System.currentTimeMillis()
    ) {
        fun isFresh(ttlMs: Long): Boolean = (System.currentTimeMillis() - timestampMs) < ttlMs
    }

    private val feedCache = ConcurrentHashMap<String, CacheEntry<List<KstreamMediaItem>>>()
    private val animeDetailsCache = ConcurrentHashMap<String, CacheEntry<KanimeAnimeDetails>>()
    private val episodeDetailsCache = ConcurrentHashMap<String, CacheEntry<KanimeEpisodeDetails>>()
    private val streamCache = ConcurrentHashMap<String, CacheEntry<KstreamResolvedStream>>()

    private fun isHstreamInCooldown(): Boolean = System.currentTimeMillis() < hstreamCooldownUntilMs

    private fun markHstreamDown(reason: String) {
        Log.w(TAG, "Marking hstream.moe offline/maintenance for 3m ($reason)")
        hstreamCooldownUntilMs = System.currentTimeMillis() + HSTREAM_COOLDOWN_DURATION_MS
    }

    private fun isMaintenanceOrBlockedHtml(html: String): Boolean {
        if (html.isBlank()) return true
        val lower = html.lowercase()
        return lower.contains("temporary maintenance") ||
            lower.contains("temporarily <span>offline") ||
            lower.contains("temporarily offline") ||
            lower.contains("php security update") ||
            lower.contains("<title>just a moment...") ||
            lower.contains("cf-browser-verification")
    }

    suspend fun getFeed(
        page: Int = 1,
        query: String = "",
        order: String = "recently-uploaded"
    ): List<KstreamMediaItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val cacheKey = "feed_${page}_${order}_${cleanQuery.lowercase()}"

        feedCache[cacheKey]?.let { cached ->
            if (cached.isFresh(FEED_CACHE_TTL_MS) && cached.value.isNotEmpty()) {
                return@withContext cached.value
            }
        }

        // 1. Primary Source: hstream.moe (with multi-endpoint fallback & maintenance detection)
        if (!isHstreamInCooldown()) {
            try {
                val hstreamItems = fetchHstreamFeed(page, cleanQuery, order)
                if (hstreamItems.isNotEmpty()) {
                    feedCache[cacheKey] = CacheEntry(hstreamItems)
                    return@withContext hstreamItems
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Primary source hstream.moe failed on page $page: ${e.message}")
            }
        }

        // 2. Secondary Fallback Provider: watchhentai.net
        try {
            val whItems = fetchWatchHentaiFeed(page, cleanQuery, order)
            if (whItems.isNotEmpty()) {
                feedCache[cacheKey] = CacheEntry(whItems)
                return@withContext whItems
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Secondary provider watchhentai.net failed on page $page: ${e.message}")
        }

        // 3. Tertiary Fallback Provider: hentaimama.io
        try {
            val hmItems = fetchHentaiMamaFeed(page, cleanQuery, order)
            if (hmItems.isNotEmpty()) {
                feedCache[cacheKey] = CacheEntry(hmItems)
                return@withContext hmItems
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Tertiary provider hentaimama.io failed on page $page: ${e.message}")
        }

        // 4. Stale-While-Revalidate Cache Fallback
        feedCache[cacheKey]?.value?.takeIf { it.isNotEmpty() }?.let { stale ->
            Log.i(TAG, "Returning stale cached feed for $cacheKey")
            return@withContext stale
        }

        emptyList()
    }

    private suspend fun fetchHstreamFeed(
        page: Int,
        cleanQuery: String,
        order: String
    ): List<KstreamMediaItem> {
        val encodedQuery = if (cleanQuery.isNotEmpty()) URLEncoder.encode(cleanQuery, "UTF-8") else ""
        val candidateUrls = buildList {
            if (encodedQuery.isNotEmpty()) {
                add("$BASE_URL/search?view=poster&search=$encodedQuery&order=$order&page=$page")
                add("$BASE_URL/search?search=$encodedQuery&order=$order&page=$page")
            } else {
                add("$BASE_URL/search?view=poster&order=$order&page=$page")
                add("$BASE_URL/search?order=$order&page=$page")
                if (page == 1) add("$BASE_URL/")
            }
        }

        for (url in candidateUrls) {
            try {
                val html = NetworkClient.getHtml(url, mapOf("Referer" to "$BASE_URL/"))
                if (isMaintenanceOrBlockedHtml(html)) {
                    markHstreamDown("Maintenance or empty response on $url")
                    return emptyList()
                }
                val parsed = parseHstreamCatalogHtml(html)
                if (parsed.isNotEmpty()) return parsed
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = e.message.orEmpty()
                if (msg.contains("404") || msg.contains("502") || msg.contains("503")) {
                    markHstreamDown("HTTP error on $url: $msg")
                }
            }
        }
        return emptyList()
    }

    private fun parseHstreamCatalogHtml(html: String): List<KstreamMediaItem> {
        val items = mutableListOf<KstreamMediaItem>()
        val doc = Jsoup.parse(html, BASE_URL)
        val cardElements = doc.select("a[href*='/hentai/']")

        val seenUrls = mutableSetOf<String>()
        for (a in cardElements) {
            val href = a.attr("href").trim()
            if (href.isEmpty() || !href.contains("/hentai/")) continue
            val fullUrl = if (href.startsWith("http")) href else "$BASE_URL${if (href.startsWith("/")) "" else "/"}$href"
            if (fullUrl.endsWith("/hentai/list") || fullUrl.endsWith("/hentai")) continue
            if (!seenUrls.add(fullUrl)) continue

            val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            var title = a.selectFirst("h3, h2, .title, span.font-semibold")?.text()?.trim().orEmpty()
            if (title.isEmpty()) {
                title = img?.attr("alt")?.trim().orEmpty()
            }
            if (title.isEmpty()) {
                title = a.attr("title").trim()
            }
            if (title.isEmpty()) {
                title = fullUrl.substringAfterLast("/").replace("-", " ")
            }

            var imgSrc = img?.attr("src")?.trim().orEmpty()
            if (imgSrc.isEmpty() || imgSrc.startsWith("data:")) {
                imgSrc = img?.attr("data-src")?.trim().orEmpty()
            }
            var posterUrl = if (imgSrc.startsWith("http")) imgSrc else if (imgSrc.isNotEmpty()) "$BASE_URL${if (imgSrc.startsWith("/")) "" else "/"}$imgSrc" else ""

            if (posterUrl.contains("gallery-ep-") && posterUrl.contains("-thumbnail.webp")) {
                posterUrl = posterUrl.replace(Regex("""gallery-ep-(\d+)-\d+-thumbnail\.webp"""), "cover-ep-$1.webp")
            }

            val id = fullUrl.substringAfterLast("/hentai/").substringBefore("?").trim('/').ifEmpty {
                java.util.UUID.randomUUID().toString()
            }

            items.add(
                KstreamMediaItem(
                    id = id,
                    title = title,
                    posterUrl = posterUrl,
                    episodeUrl = fullUrl
                )
            )
        }
        return items
    }

    // =========================================================================
    // SECONDARY PROVIDER: WATCHHENTAI.NET
    // =========================================================================

    private suspend fun fetchWatchHentaiFeed(
        page: Int,
        cleanQuery: String,
        order: String
    ): List<KstreamMediaItem> {
        val pageSegment = if (page > 1) "page/$page/" else ""
        val url = when {
            cleanQuery.isNotEmpty() -> {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                "$WATCHHENTAI_URL/${pageSegment}?s=$encoded"
            }
            order == KanimeSortOrder.VIEW_COUNT.apiValue -> {
                "$WATCHHENTAI_URL/trending/$pageSegment"
            }
            order == KanimeSortOrder.RECENTLY_RELEASED.apiValue -> {
                "$WATCHHENTAI_URL/series/$pageSegment"
            }
            order == KanimeSortOrder.OLDEST_UPLOADS.apiValue || order == KanimeSortOrder.OLDEST_RELEASES.apiValue -> {
                val reversePage = (12 - page).coerceAtLeast(1)
                "$WATCHHENTAI_URL/series/page/$reversePage/"
            }
            else -> {
                "$WATCHHENTAI_URL/series/$pageSegment"
            }
        }

        val html = NetworkClient.getHtml(url, mapOf("Referer" to "$WATCHHENTAI_URL/"))
        if (html.isBlank()) return emptyList()

        var items = parseWatchHentaiCatalogHtml(html)
        if (items.isEmpty() && cleanQuery.isEmpty()) {
            val fallbackVideosUrl = "$WATCHHENTAI_URL/videos/$pageSegment"
            val vHtml = NetworkClient.getHtml(fallbackVideosUrl, mapOf("Referer" to "$WATCHHENTAI_URL/"))
            items = parseWatchHentaiCatalogHtml(vHtml)
        }

        return when (order) {
            KanimeSortOrder.AZ.apiValue -> items.sortedBy { it.title.lowercase() }
            KanimeSortOrder.ZA.apiValue -> items.sortedByDescending { it.title.lowercase() }
            KanimeSortOrder.OLDEST_UPLOADS.apiValue, KanimeSortOrder.OLDEST_RELEASES.apiValue -> items.reversed()
            else -> items
        }
    }

    private fun parseWatchHentaiCatalogHtml(html: String): List<KstreamMediaItem> {
        val items = mutableListOf<KstreamMediaItem>()
        val doc = Jsoup.parse(html, WATCHHENTAI_URL)
        val articles = doc.select("article.item, div.result-item article")
        val seenUrls = mutableSetOf<String>()

        for (art in articles) {
            val badgeText = art.select(".buttonextra, .buttonyear").text()
            if (badgeText.contains("PREVIEW", ignoreCase = true) || badgeText.contains("Upcoming", ignoreCase = true)) {
                continue
            }

            val linkEl = art.selectFirst("a[href*='/series/'], a[href*='/videos/']") ?: continue
            val href = linkEl.attr("href").trim()
            if (href.isEmpty() || href.contains("/feed")) continue
            val fullUrl = if (href.startsWith("http")) href else "$WATCHHENTAI_URL${if (href.startsWith("/")) "" else "/"}$href"
            if (!seenUrls.add(fullUrl)) continue

            val img = art.selectFirst("img")
            var posterUrl = img?.attr("data-src")?.trim().orEmpty()
            if (posterUrl.isEmpty() || posterUrl.startsWith("data:")) {
                posterUrl = img?.attr("src")?.trim().orEmpty()
            }
            if (posterUrl.isNotEmpty() && !posterUrl.startsWith("http")) {
                posterUrl = "$WATCHHENTAI_URL${if (posterUrl.startsWith("/")) "" else "/"}$posterUrl"
            }

            val serieSpan = art.selectFirst("span.serie")?.text()?.trim().orEmpty()
            val h3Text = art.selectFirst("h3, .title a")?.text()?.trim().orEmpty()
            var title = when {
                serieSpan.isNotEmpty() && h3Text.isNotEmpty() && !serieSpan.equals(h3Text, ignoreCase = true) -> "$serieSpan - $h3Text"
                h3Text.isNotEmpty() -> h3Text
                serieSpan.isNotEmpty() -> serieSpan
                else -> img?.attr("alt")?.trim().orEmpty()
            }
            if (title.isEmpty()) {
                title = fullUrl.trimEnd('/').substringAfterLast("/").replace("-id-01", "").replace("-", " ")
            }

            val slug = fullUrl.trimEnd('/').substringAfterLast("/")
            items.add(
                KstreamMediaItem(
                    id = "wh_$slug",
                    title = title,
                    posterUrl = posterUrl,
                    episodeUrl = fullUrl
                )
            )
        }
        return items
    }

    // =========================================================================
    // TERTIARY PROVIDER: HENTAIMAMA.IO
    // =========================================================================

    private suspend fun fetchHentaiMamaFeed(
        page: Int,
        cleanQuery: String,
        order: String
    ): List<KstreamMediaItem> {
        val pageSegment = if (page > 1) "page/$page/" else ""
        val url = when {
            cleanQuery.isNotEmpty() -> {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                "$HENTAIMAMA_URL/${pageSegment}?s=$encoded"
            }
            order == KanimeSortOrder.RECENTLY_UPLOADED.apiValue -> {
                "$HENTAIMAMA_URL/tvshows/$pageSegment"
            }
            else -> {
                "$HENTAIMAMA_URL/tvshows/$pageSegment"
            }
        }

        val html = NetworkClient.getHtml(url, mapOf("Referer" to "$HENTAIMAMA_URL/"))
        if (html.isBlank()) return emptyList()
        val items = parseHentaiMamaCatalogHtml(html)

        return when (order) {
            KanimeSortOrder.AZ.apiValue -> items.sortedBy { it.title.lowercase() }
            KanimeSortOrder.ZA.apiValue -> items.sortedByDescending { it.title.lowercase() }
            KanimeSortOrder.OLDEST_UPLOADS.apiValue, KanimeSortOrder.OLDEST_RELEASES.apiValue -> items.reversed()
            else -> items
        }
    }

    private fun parseHentaiMamaCatalogHtml(html: String): List<KstreamMediaItem> {
        val items = mutableListOf<KstreamMediaItem>()
        val doc = Jsoup.parse(html, HENTAIMAMA_URL)
        val articles = doc.select("article.item, article.series-card")
        val seenUrls = mutableSetOf<String>()

        for (art in articles) {
            val linkEl = art.selectFirst("a[href*='/tvshows/'], a[href*='/episodes/']") ?: continue
            val href = linkEl.attr("href").trim()
            if (href.isEmpty() || href.contains("/feed")) continue
            val fullUrl = if (href.startsWith("http")) href else "$HENTAIMAMA_URL${if (href.startsWith("/")) "" else "/"}$href"
            if (!seenUrls.add(fullUrl)) continue

            val img = art.selectFirst("img")
            var posterUrl = img?.attr("data-src")?.trim().orEmpty()
            if (posterUrl.isEmpty() || posterUrl.startsWith("data:")) {
                posterUrl = img?.attr("src")?.trim().orEmpty()
            }
            if (posterUrl.isNotEmpty() && !posterUrl.startsWith("http")) {
                posterUrl = "$HENTAIMAMA_URL${if (posterUrl.startsWith("/")) "" else "/"}$posterUrl"
            }

            var title = art.selectFirst("h3, .title, .sc-title")?.text()?.trim().orEmpty()
            if (title.isEmpty()) {
                title = img?.attr("alt")?.trim().orEmpty()
            }
            if (title.isEmpty()) {
                title = fullUrl.trimEnd('/').substringAfterLast("/").replace("-", " ")
            }

            val slug = fullUrl.trimEnd('/').substringAfterLast("/")
            items.add(
                KstreamMediaItem(
                    id = "hm_$slug",
                    title = title,
                    posterUrl = posterUrl,
                    episodeUrl = fullUrl
                )
            )
        }
        return items
    }

    // =========================================================================
    // STREAM RESOLUTION (MULTI-SOURCE & MULTI-CDN)
    // =========================================================================

    suspend fun resolveStream(episodeUrl: String): KstreamResolvedStream? = withContext(Dispatchers.IO) {
        val cleanUrl = episodeUrl.trim()
        streamCache[cleanUrl]?.let { cached ->
            if (cached.isFresh(STREAM_CACHE_TTL_MS) && cached.value.qualities.isNotEmpty()) {
                return@withContext cached.value
            }
        }

        val resolved = when {
            cleanUrl.contains("watchhentai.net", ignoreCase = true) -> {
                resolveWatchHentaiStream(cleanUrl)
                    ?: resolveHentaiMamaBySlugFallback(cleanUrl)
            }
            cleanUrl.contains("hentaimama.io", ignoreCase = true) -> {
                resolveHentaiMamaStream(cleanUrl)
                    ?: resolveWatchHentaiBySlugFallback(cleanUrl)
            }
            else -> {
                val hstreamResult = if (!isHstreamInCooldown()) resolveHstreamStream(cleanUrl) else null
                hstreamResult
                    ?: resolveWatchHentaiBySlugFallback(cleanUrl)
                    ?: resolveHentaiMamaBySlugFallback(cleanUrl)
            }
        }

        if (resolved != null && resolved.qualities.isNotEmpty()) {
            streamCache[cleanUrl] = CacheEntry(resolved)
            return@withContext resolved
        }

        streamCache[cleanUrl]?.value
    }

    private suspend fun resolveHstreamStream(episodeUrl: String): KstreamResolvedStream? {
        try {
            val html = NetworkClient.getHtml(episodeUrl, mapOf("Referer" to "$BASE_URL/"))
            if (isMaintenanceOrBlockedHtml(html)) {
                markHstreamDown("Maintenance on episode page $episodeUrl")
                return null
            }
            val doc = Jsoup.parse(html, episodeUrl)

            val eId = doc.selectFirst("input#e_id, input[name='e_id'], [data-episode-id]")?.let { el ->
                el.attr("value").trim().ifEmpty { el.attr("data-episode-id").trim() }
            }
                ?: Regex("""id=["']e_id["'][^>]*value=["'](\d+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""value=["'](\d+)["'][^>]*id=["']e_id["']""").find(html)?.groupValues?.get(1)
                ?: Regex(""""e_id"\s*:\s*"?(\d+)"?""").find(html)?.groupValues?.get(1)
                ?: Regex(""""episode_id"\s*:\s*"?(\d+)"?""").find(html)?.groupValues?.get(1)

            if (eId.isNullOrEmpty()) {
                Log.w(TAG, "Could not find e_id in episode page: $episodeUrl")
                return null
            }

            val baseHttpUrl = "https://hstream.moe/".toHttpUrlOrNull()
            val cookies = if (baseHttpUrl != null) {
                NetworkClient.okHttpClient.cookieJar.loadForRequest(baseHttpUrl)
            } else {
                emptyList()
            }
            val xsrfCookie = cookies.firstOrNull { it.name.equals("XSRF-TOKEN", ignoreCase = true) }?.value
            val decodedXsrf = xsrfCookie?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            } ?: ""
            val metaCsrf = doc.selectFirst("meta[name='csrf-token']")?.attr("content")?.trim().orEmpty()

            val jsonBody = JSONObject().apply {
                put("episode_id", eId)
            }.toString()

            val reqBuilder = Request.Builder()
                .url("$BASE_URL/player/api")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", episodeUrl)
                .header("Origin", BASE_URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

            if (decodedXsrf.isNotEmpty()) {
                reqBuilder.header("X-XSRF-TOKEN", decodedXsrf)
            }
            if (metaCsrf.isNotEmpty()) {
                reqBuilder.header("X-CSRF-TOKEN", metaCsrf)
            }

            val response = NetworkClient.okHttpClient.newCall(reqBuilder.build()).await()
            val respBody = response.body?.string().orEmpty()
            response.close()

            if (respBody.isBlank()) return null
            val json = JSONObject(respBody)

            val title = json.optString("title", "Episode Stream")
            val streamUrl = json.optString("stream_url", "").trim('/')
            if (streamUrl.isBlank()) {
                Log.w(TAG, "Empty stream_url in API response for $episodeUrl")
                return null
            }

            val streamDomains = mutableListOf<String>()
            val domainsArr = json.optJSONArray("stream_domains")
            if (domainsArr != null) {
                for (i in 0 until domainsArr.length()) {
                    val d = domainsArr.optString(i).trimEnd('/')
                    if (d.isNotEmpty() && d !in streamDomains) streamDomains.add(d)
                }
            }
            val asiaDomainsArr = json.optJSONArray("asia_stream_domains")
            if (asiaDomainsArr != null) {
                for (i in 0 until asiaDomainsArr.length()) {
                    val d = asiaDomainsArr.optString(i).trimEnd('/')
                    if (d.isNotEmpty() && d !in streamDomains) streamDomains.add(d)
                }
            }
            if (streamDomains.isEmpty()) {
                streamDomains.add("https://imoto-str.ane-h.xyz")
            }

            val interpolated = json.optInt("interpolated", 0)
            val legacy = json.optInt("legacy", 0)
            val qualities = mutableListOf<StreamQuality>()
            val hstreamHeaders = mapOf("Referer" to "$BASE_URL/")

            // Multi-CDN domain iteration: primary domain gets default flag, backup CDN domains provide failover
            for ((domainIndex, domain) in streamDomains.withIndex()) {
                val isPrimaryDomain = domainIndex == 0
                qualities.add(
                    StreamQuality(
                        quality = "2160p (4K DASH)",
                        url = "$domain/$streamUrl/2160/manifest.mpd",
                        format = "DASH",
                        headers = hstreamHeaders
                    )
                )

                if (interpolated == 1) {
                    qualities.add(
                        StreamQuality(
                            quality = "1080p 48fps (DASH)",
                            url = "$domain/$streamUrl/1080i/manifest.mpd",
                            format = "DASH",
                            headers = hstreamHeaders
                        )
                    )
                }

                qualities.add(
                    StreamQuality(
                        quality = "1080p (DASH)",
                        url = "$domain/$streamUrl/1080/manifest.mpd",
                        format = "DASH",
                        headers = hstreamHeaders
                    )
                )

                qualities.add(
                    StreamQuality(
                        quality = "720p (DASH)",
                        url = "$domain/$streamUrl/720/manifest.mpd",
                        format = "DASH",
                        headers = hstreamHeaders,
                        isDefault = isPrimaryDomain
                    )
                )

                if (legacy == 1) {
                    qualities.add(
                        StreamQuality(
                            quality = "1080p (MP4 Direct)",
                            url = "$domain/$streamUrl/x264.1080p.mp4",
                            format = "MP4",
                            headers = hstreamHeaders
                        )
                    )
                }

                qualities.add(
                    StreamQuality(
                        quality = "720p (MP4 Direct)",
                        url = "$domain/$streamUrl/x264.720p.mp4",
                        format = "MP4",
                        headers = hstreamHeaders
                    )
                )
            }

            val primaryDomain = streamDomains.first()
            val subtitles = mutableListOf<SubtitleTrack>()

            val subsArr = json.optJSONArray("subtitles") ?: json.optJSONArray("captions") ?: json.optJSONArray("tracks")
            if (subsArr != null) {
                for (i in 0 until subsArr.length()) {
                    val subObj = subsArr.optJSONObject(i)
                    if (subObj != null) {
                        val subUrl = subObj.optString("src").ifEmpty { subObj.optString("url") }
                        val label = subObj.optString("label").ifEmpty { subObj.optString("title", "English") }
                        val lang = subObj.optString("lang").ifEmpty { subObj.optString("language", "en") }
                        if (subUrl.isNotEmpty()) {
                            val fullSubUrl = if (subUrl.startsWith("http")) subUrl else "$primaryDomain/$streamUrl/$subUrl"
                            subtitles.add(SubtitleTrack(language = lang, label = label, url = fullSubUrl))
                        }
                    }
                }
            }

            val trackElements = doc.select("track[src]")
            for (track in trackElements) {
                val src = track.attr("src").trim()
                if (src.isNotEmpty()) {
                    val label = track.attr("label").trim().ifEmpty { "English" }
                    val lang = track.attr("srclang").trim().ifEmpty { "en" }
                    val fullSrc = if (src.startsWith("http")) src else if (src.startsWith("/")) "$BASE_URL$src" else "$primaryDomain/$streamUrl/$src"
                    if (subtitles.none { it.url == fullSrc }) {
                        subtitles.add(SubtitleTrack(language = lang, label = label, url = fullSrc))
                    }
                }
            }

            val defaultAssUrl = "$primaryDomain/$streamUrl/eng.ass"
            if (subtitles.none { it.url == defaultAssUrl || it.language == "en" || it.label.equals("English", ignoreCase = true) }) {
                subtitles.add(
                    SubtitleTrack(
                        language = "en",
                        label = "English",
                        url = defaultAssUrl
                    )
                )
            }

            return KstreamResolvedStream(
                title = title,
                qualities = qualities,
                subtitles = subtitles
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error resolving hstream for $episodeUrl: ${e.message}")
            return null
        }
    }

    private fun decodeWatchHentaiMediaUrl(encoded: String): String? {
        return try {
            var s = encoded.trim().replace('-', '+').replace('_', '/')
            while (s.length % 4 != 0) s += "="
            val x = Base64.decode(s, Base64.DEFAULT)
            val k = 13
            val chars = CharArray(x.size)
            for (i in x.indices) {
                val b = x[i].toInt() and 0xFF
                chars[i] = (b xor ((k + (i % 17)) and 0xFF)).toChar()
            }
            var reversed = String(chars).reversed()
            while (reversed.length % 4 != 0) reversed += "="
            String(Base64.decode(reversed, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode WatchHentai media URL: ${e.message}")
            null
        }
    }

    private suspend fun resolveWatchHentaiStream(urlOrSeriesUrl: String): KstreamResolvedStream? {
        try {
            var targetEpisodeUrl = urlOrSeriesUrl
            var html = NetworkClient.getHtml(targetEpisodeUrl, mapOf("Referer" to "$WATCHHENTAI_URL/"))
            if (html.isBlank()) return null

            var doc = Jsoup.parse(html, targetEpisodeUrl)
            // If passed a /series/ URL, resolve the first playable /videos/ episode link
            if (targetEpisodeUrl.contains("/series/")) {
                val firstEpLink = doc.selectFirst("a.tv-ep-card-link[href*='/videos/'], a.tv-single-hero__watch[href*='/videos/'], a[href*='/videos/']")
                    ?.attr("href")?.trim()
                if (!firstEpLink.isNullOrEmpty()) {
                    targetEpisodeUrl = if (firstEpLink.startsWith("http")) firstEpLink else "$WATCHHENTAI_URL${if (firstEpLink.startsWith("/")) "" else "/"}$firstEpLink"
                    html = NetworkClient.getHtml(targetEpisodeUrl, mapOf("Referer" to urlOrSeriesUrl))
                    if (html.isBlank()) return null
                    doc = Jsoup.parse(html, targetEpisodeUrl)
                }
            }

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?.ifEmpty { doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" - Watch")?.trim() }
                ?: "Episode Stream"

            val playerUrls = mutableListOf<String>()
            val iframeSrcs = doc.select("iframe[src*='/player'], iframe[data-src*='/player'], iframe[data-litespeed-src*='/player']")
            for (iframe in iframeSrcs) {
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }.ifEmpty { iframe.attr("data-litespeed-src") }.trim()
                if (src.isNotEmpty() && !src.startsWith("about:")) {
                    val full = if (src.startsWith("http")) src else "$WATCHHENTAI_URL${if (src.startsWith("/")) "" else "/"}$src"
                    if (full !in playerUrls) playerUrls.add(full)
                }
            }

            val regexPlayerMatches = Regex("""https?://watchhentai\.net/player(?:-alt)?/[^\s"'<>]+""").findAll(html)
            for (m in regexPlayerMatches) {
                val pUrl = m.value.trim()
                if (pUrl !in playerUrls) playerUrls.add(pUrl)
            }

            val postId = doc.selectFirst("li.dooplay_player_option[data-post]")?.attr("data-post")?.trim()
            if (!postId.isNullOrEmpty()) {
                val constructed = "$WATCHHENTAI_URL/player/$postId/1/mp4/"
                if (constructed !in playerUrls) playerUrls.add(constructed)
            }

            val qualities = mutableListOf<StreamQuality>()
            val whHeaders = mapOf("Referer" to "$WATCHHENTAI_URL/")
            val seenStreamUrls = mutableSetOf<String>()

            for (pUrl in playerUrls) {
                try {
                    val pHtml = NetworkClient.getHtml(pUrl, mapOf("Referer" to targetEpisodeUrl))
                    if (pHtml.isBlank()) continue

                    // 1. Parse whJwSources JSON array and decode via whDecodeMediaUrl
                    val sourcesMatch = Regex("""whJwSources\s*=\s*(\[\s*\{.*?\}\s*\])\s*;""", RegexOption.DOT_MATCHES_ALL).find(pHtml)
                    if (sourcesMatch != null) {
                        val arr = JSONArray(sourcesMatch.groupValues[1])
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val rawFile = obj.optString("file").trim()
                            val label = obj.optString("label").trim()
                            val decodedUrl = if (rawFile.startsWith("http")) rawFile else decodeWatchHentaiMediaUrl(rawFile)
                            if (!decodedUrl.isNullOrEmpty() && decodedUrl.startsWith("http") && seenStreamUrls.add(decodedUrl)) {
                                val qualityLabel = when {
                                    label.contains("2160") || decodedUrl.contains("2160") -> "2160p"
                                    label.contains("1440") || decodedUrl.contains("1440") -> "1440p"
                                    label.contains("1080") || decodedUrl.contains("1080") -> "1080p"
                                    label.contains("720") || decodedUrl.contains("720") -> "720p"
                                    else -> "1080p"
                                }
                                qualities.add(
                                    StreamQuality(
                                        quality = qualityLabel,
                                        url = decodedUrl,
                                        format = if (decodedUrl.contains(".m3u8")) "HLS" else "MP4",
                                        headers = whHeaders,
                                        isDefault = qualities.isEmpty()
                                    )
                                )
                            }
                        }
                    }

                    // 2. Fallback: Check itemprop="contentUrl" or direct hstorage.xyz MP4 URLs
                    val directMatches = Regex("""https?://[^\s"'<>]+?\.(?:mp4|m3u8)(?:\?[^\s"'<>]*)?""").findAll(pHtml)
                    for (dm in directMatches) {
                        val sUrl = dm.value.replace("\\/", "/").trim()
                        if (seenStreamUrls.add(sUrl)) {
                            val qLabel = when {
                                sUrl.contains("2160") -> "2160p"
                                sUrl.contains("1440") -> "1440p"
                                sUrl.contains("720") -> "720p"
                                else -> "1080p"
                            }
                            qualities.add(
                                StreamQuality(
                                    quality = qLabel,
                                    url = sUrl,
                                    format = if (sUrl.contains(".m3u8")) "HLS" else "MP4",
                                    headers = whHeaders,
                                    isDefault = qualities.isEmpty()
                                )
                            )
                        }
                    }

                    if (qualities.isNotEmpty()) break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "WatchHentai player URL failed ($pUrl): ${e.message}")
                }
            }

            if (qualities.isEmpty()) return null

            // Also provide 720p fallback entry if only a single raw MP4 stream was returned
            if (qualities.size == 1 && qualities[0].quality == "1080p") {
                qualities.add(
                    qualities[0].copy(
                        quality = "720p",
                        isDefault = false
                    )
                )
            }

            return KstreamResolvedStream(
                title = title,
                qualities = qualities,
                subtitles = emptyList()
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error resolving WatchHentai stream for $urlOrSeriesUrl: ${e.message}")
            return null
        }
    }

    private suspend fun resolveHentaiMamaStream(urlOrSeriesUrl: String): KstreamResolvedStream? {
        try {
            var targetEpisodeUrl = urlOrSeriesUrl
            var html = NetworkClient.getHtml(targetEpisodeUrl, mapOf("Referer" to "$HENTAIMAMA_URL/"))
            if (html.isBlank()) return null

            var doc = Jsoup.parse(html, targetEpisodeUrl)
            if (targetEpisodeUrl.contains("/tvshows/")) {
                val firstEpLink = doc.selectFirst("a.dsc-play[href*='/episodes/'], a[href*='/episodes/']")
                    ?.attr("href")?.trim()
                if (!firstEpLink.isNullOrEmpty()) {
                    targetEpisodeUrl = if (firstEpLink.startsWith("http")) firstEpLink else "$HENTAIMAMA_URL${if (firstEpLink.startsWith("/")) "" else "/"}$firstEpLink"
                    html = NetworkClient.getHtml(targetEpisodeUrl, mapOf("Referer" to urlOrSeriesUrl))
                    if (html.isBlank()) return null
                    doc = Jsoup.parse(html, targetEpisodeUrl)
                }
            }

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?.ifEmpty { doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim() }
                ?: "Episode Stream"

            val postId = Regex("""action:\s*['"]get_player_contents['"]\s*,\s*a:\s*['"]?(\d+)['"]?""").find(html)?.groupValues?.get(1)
                ?: Regex("""postid-(\d+)""").find(html)?.groupValues?.get(1)
                ?: doc.selectFirst("[data-post-id], [data-post]")?.let {
                    it.attr("data-post-id").ifEmpty { it.attr("data-post") }
                }

            if (postId.isNullOrEmpty()) return null

            val formBody = FormBody.Builder()
                .add("action", "get_player_contents")
                .add("a", postId)
                .build()

            val ajaxReq = Request.Builder()
                .url("$HENTAIMAMA_URL/wp-admin/admin-ajax.php")
                .post(formBody)
                .header("Referer", targetEpisodeUrl)
                .header("Origin", HENTAIMAMA_URL)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()

            val response = NetworkClient.okHttpClient.newCall(ajaxReq).await()
            val respBody = response.body?.string().orEmpty()
            response.close()
            if (respBody.isBlank()) return null

            val iframeList = mutableListOf<String>()
            val jsonArr = JSONArray(respBody)
            for (i in 0 until jsonArr.length()) {
                val itemHtml = jsonArr.optString(i)
                val srcMatch = Regex("""src=["']([^"']+)["']""").find(itemHtml)
                if (srcMatch != null) {
                    val rawSrc = srcMatch.groupValues[1]
                        .replace("&#038;", "&")
                        .replace("&amp;", "&")
                        .replace("\\/", "/")
                        .trim()
                    if (rawSrc.startsWith("http") && rawSrc !in iframeList) {
                        iframeList.add(rawSrc)
                    }
                }
            }

            // Prioritize r2-mp4 and hls-mp4 first because they expose explicit 1080p & 720p direct MP4 streams
            val prioritizedIframes = iframeList.sortedBy { url ->
                when {
                    url.contains("dt_embed=r2-mp4") -> 0
                    url.contains("dt_embed=hls-mp4") -> 1
                    url.contains("dt_embed=hls") -> 2
                    url.contains("newjav.php") -> 3
                    else -> 4
                }
            }

            val qualities = mutableListOf<StreamQuality>()
            val hmHeaders = mapOf("Referer" to "$HENTAIMAMA_URL/")
            val seenUrls = mutableSetOf<String>()

            for (iframeUrl in prioritizedIframes) {
                try {
                    val embedHtml = NetworkClient.getHtml(iframeUrl, mapOf("Referer" to targetEpisodeUrl))
                    if (embedHtml.isBlank()) continue

                    val fileLabelRegex = Regex(""""file"\s*:\s*"([^"]+)"(?:\s*,\s*"label"\s*:\s*"([^"]+)")?""")
                    for (m in fileLabelRegex.findAll(embedHtml)) {
                        val fileUrl = m.groupValues[1].replace("\\/", "/").trim()
                        val label = m.groupValues[2].trim()
                        if (!fileUrl.startsWith("http") || fileUrl.endsWith(".vtt") || fileUrl.endsWith(".srt")) continue
                        if (!seenUrls.add(fileUrl)) continue

                        val isHls = fileUrl.contains(".m3u8")
                        val qLabel = when {
                            label.contains("2160") || fileUrl.contains("2160p") -> "2160p"
                            label.contains("1440") || fileUrl.contains("1440p") -> "1440p"
                            label.contains("1080") || fileUrl.contains("1080p") -> "1080p"
                            label.contains("720") || fileUrl.contains("720p") -> "720p"
                            isHls -> "1080p"
                            else -> "720p"
                        }
                        qualities.add(
                            StreamQuality(
                                quality = qLabel,
                                url = fileUrl,
                                format = if (isHls) "HLS" else "MP4",
                                headers = hmHeaders,
                                isDefault = qualities.isEmpty()
                            )
                        )
                    }

                    val looseFileRegex = Regex("""file\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""")
                    for (m in looseFileRegex.findAll(embedHtml)) {
                        val fileUrl = m.groupValues[1].replace("\\/", "/").trim()
                        if (seenUrls.add(fileUrl)) {
                            val isHls = fileUrl.contains(".m3u8")
                            qualities.add(
                                StreamQuality(
                                    quality = if (fileUrl.contains("720p")) "720p" else "1080p",
                                    url = fileUrl,
                                    format = if (isHls) "HLS" else "MP4",
                                    headers = hmHeaders,
                                    isDefault = qualities.isEmpty()
                                )
                            )
                        }
                    }

                    if (qualities.size >= 2) break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            if (qualities.isEmpty()) return null
            return KstreamResolvedStream(
                title = title,
                qualities = qualities,
                subtitles = emptyList()
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error resolving HentaiMama stream for $urlOrSeriesUrl: ${e.message}")
            return null
        }
    }

    private suspend fun resolveWatchHentaiBySlugFallback(originalUrl: String): KstreamResolvedStream? {
        return try {
            val rawSlug = originalUrl.trimEnd('/').substringAfterLast("/")
                .replace("-id-01", "")
                .replace(Regex("""-episode-\d+.*$"""), "")
                .replace(Regex("""-\d+$"""), "")
            val epNum = extractEpNumber(originalUrl)
            val candidateEpUrl = "$WATCHHENTAI_URL/videos/$rawSlug-episode-$epNum-id-01/"
            resolveWatchHentaiStream(candidateEpUrl)
                ?: resolveWatchHentaiStream("$WATCHHENTAI_URL/series/$rawSlug-id-01/")
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveHentaiMamaBySlugFallback(originalUrl: String): KstreamResolvedStream? {
        return try {
            val rawSlug = originalUrl.trimEnd('/').substringAfterLast("/")
                .replace("-id-01", "")
                .replace(Regex("""-episode-\d+.*$"""), "")
                .replace(Regex("""-\d+$"""), "")
            val epNum = extractEpNumber(originalUrl)
            val candidateEpUrl = "$HENTAIMAMA_URL/episodes/$rawSlug-episode-$epNum/"
            resolveHentaiMamaStream(candidateEpUrl)
                ?: resolveHentaiMamaStream("$HENTAIMAMA_URL/tvshows/$rawSlug/")
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // ANIME & EPISODE DETAILS (MULTI-SOURCE)
    // =========================================================================

    private fun extractDescriptionFromDoc(doc: Document): String {
        var desc = doc.select("h2:contains(Description) ~ p, div.tv-single-synopsis__body p, div.episode-desc, div.wp-content p")
            .firstOrNull()?.text()?.replace("Read more", "")?.replace("Show less", "")?.trim().orEmpty()
        if (desc.isEmpty()) {
            desc = doc.select("div:has(h2:contains(Description)) p").firstOrNull()?.text()?.trim().orEmpty()
        }
        if (desc.isEmpty()) {
            desc = doc.select("meta[property='og:description'], meta[name='description']").attr("content").trim()
        }
        return desc
    }

    private fun extractSeriesEpisodesFromDoc(
        doc: Document,
        seriesSlug: String,
        seenUrls: MutableSet<String>,
        episodes: MutableList<KanimeEpisodeItem>
    ) {
        val links = doc.select("a[href*='/hentai/']")
        val slugEpisodeRegex = Regex("""^${Regex.escape(seriesSlug)}-(\d+)$""")
        for (a in links) {
            val href = a.attr("href").trim()
            val fullUrl = if (href.startsWith("http")) href else "$BASE_URL${if (href.startsWith("/")) "" else "/"}$href"
            val epSlug = fullUrl.substringAfterLast("/hentai/").substringBefore("?")
            val match = slugEpisodeRegex.find(epSlug)
            if (match != null && seenUrls.add(fullUrl)) {
                val epNum = match.groupValues[1].toIntOrNull() ?: 1
                episodes.add(
                    KanimeEpisodeItem(
                        episodeNumber = epNum,
                        title = "Episode $epNum",
                        episodeUrl = fullUrl
                    )
                )
            }
        }
    }

    suspend fun getAnimeDetails(item: KstreamMediaItem): KanimeAnimeDetails = withContext(Dispatchers.IO) {
        val cacheKey = item.episodeUrl.trim()
        animeDetailsCache[cacheKey]?.let { cached ->
            if (cached.isFresh(DETAILS_CACHE_TTL_MS)) return@withContext cached.value
        }

        val details = when {
            cacheKey.contains("watchhentai.net", ignoreCase = true) -> getWatchHentaiAnimeDetails(item)
            cacheKey.contains("hentaimama.io", ignoreCase = true) -> getHentaiMamaAnimeDetails(item)
            else -> getHstreamAnimeDetails(item)
        }

        animeDetailsCache[cacheKey] = CacheEntry(details)
        details
    }

    private suspend fun getHstreamAnimeDetails(item: KstreamMediaItem): KanimeAnimeDetails {
        val slug = item.episodeUrl.substringAfterLast("/hentai/").substringBefore("?").trim('/')
        val seriesSlug = slug.replace(Regex("""-\d+$"""), "")
        val seriesUrl = "$BASE_URL/hentai/$seriesSlug"

        var posterUrl = item.posterUrl
        var description = ""
        val episodes = mutableListOf<KanimeEpisodeItem>()
        val seenUrls = mutableSetOf<String>()

        if (!isHstreamInCooldown()) {
            try {
                val html = NetworkClient.getHtml(seriesUrl, mapOf("Referer" to "$BASE_URL/"))
                if (isMaintenanceOrBlockedHtml(html)) {
                    markHstreamDown("Maintenance on series page $seriesUrl")
                } else {
                    val doc = Jsoup.parse(html, seriesUrl)
                    val coverImg = doc.selectFirst("img[src*='cover-ep-']")?.attr("src")?.trim()
                        ?: doc.selectFirst("img[src*='/images/hentai/']")?.attr("src")?.trim()
                    if (!coverImg.isNullOrEmpty()) {
                        posterUrl = if (coverImg.startsWith("http")) coverImg else "$BASE_URL${if (coverImg.startsWith("/")) "" else "/"}$coverImg"
                        if (posterUrl.contains("gallery-ep-") && posterUrl.contains("-thumbnail.webp")) {
                            posterUrl = posterUrl.replace(Regex("""gallery-ep-(\d+)-\d+-thumbnail\.webp"""), "cover-ep-$1.webp")
                        }
                    }
                    description = extractDescriptionFromDoc(doc)
                    extractSeriesEpisodesFromDoc(doc, seriesSlug, seenUrls, episodes)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Error fetching series page for $seriesUrl: ${e.message}")
            }
        }

        if ((episodes.isEmpty() || description.isEmpty()) && !isHstreamInCooldown()) {
            try {
                val epHtml = NetworkClient.getHtml(item.episodeUrl, mapOf("Referer" to "$BASE_URL/"))
                if (!isMaintenanceOrBlockedHtml(epHtml)) {
                    val epDoc = Jsoup.parse(epHtml, item.episodeUrl)
                    if (description.isEmpty()) {
                        description = extractDescriptionFromDoc(epDoc)
                    }
                    extractSeriesEpisodesFromDoc(epDoc, seriesSlug, seenUrls, episodes)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Error fetching episode page fallback for ${item.episodeUrl}: ${e.message}")
            }
        }

        if (episodes.isEmpty()) {
            val epNum = extractEpNumber(item.episodeUrl)
            episodes.add(
                KanimeEpisodeItem(
                    episodeNumber = epNum,
                    title = "Episode $epNum",
                    episodeUrl = item.episodeUrl
                )
            )
        }

        episodes.sortBy { it.episodeNumber }

        val initialEpDetails = try {
            getEpisodeDetails(episodes.first().episodeUrl, fallbackTitle = item.title, fallbackPoster = posterUrl)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

        return KanimeAnimeDetails(
            posterUrl = initialEpDetails?.posterUrl ?: posterUrl,
            description = initialEpDetails?.description?.takeIf { it.isNotBlank() } ?: description.ifEmpty { "No description available." },
            episodes = episodes,
            defaultEpisodeDetails = initialEpDetails
        )
    }

    private suspend fun getWatchHentaiAnimeDetails(item: KstreamMediaItem): KanimeAnimeDetails {
        var posterUrl = item.posterUrl
        var description = ""
        val episodes = mutableListOf<KanimeEpisodeItem>()
        val seenEpNumbers = mutableSetOf<Int>()

        try {
            val html = NetworkClient.getHtml(item.episodeUrl, mapOf("Referer" to "$WATCHHENTAI_URL/"))
            if (html.isNotBlank()) {
                var doc = Jsoup.parse(html, item.episodeUrl)
                // If user opened a /videos/ episode URL, check if there's a parent /series/ link to get all episodes
                if (item.episodeUrl.contains("/videos/")) {
                    val seriesLink = doc.selectFirst("a[href*='/series/']")?.attr("href")?.trim()
                    if (!seriesLink.isNullOrEmpty()) {
                        val fullSeriesUrl = if (seriesLink.startsWith("http")) seriesLink else "$WATCHHENTAI_URL${if (seriesLink.startsWith("/")) "" else "/"}$seriesLink"
                        val sHtml = NetworkClient.getHtml(fullSeriesUrl, mapOf("Referer" to item.episodeUrl))
                        if (sHtml.isNotBlank()) {
                            doc = Jsoup.parse(sHtml, fullSeriesUrl)
                        }
                    }
                }

                val posterImg = doc.selectFirst("div.poster img, div.sheader img")
                val rawPoster = posterImg?.attr("data-src")?.ifEmpty { posterImg.attr("src") }?.trim().orEmpty()
                if (rawPoster.isNotEmpty() && !rawPoster.startsWith("data:")) {
                    posterUrl = if (rawPoster.startsWith("http")) rawPoster else "$WATCHHENTAI_URL${if (rawPoster.startsWith("/")) "" else "/"}$rawPoster"
                }

                description = extractDescriptionFromDoc(doc)

                // Extract episodes from series page (prioritize subbed/primary episodes first)
                val epLinks = doc.select("li[data-audio='subbed'] a.tv-ep-card-link[href*='/videos/'], a.tv-ep-card-link[href*='/videos/'], ul.episodios a[href*='/videos/']")
                for (a in epLinks) {
                    val href = a.attr("href").trim()
                    if (href.isEmpty() || href.contains("/feed")) continue
                    val fullEpUrl = if (href.startsWith("http")) href else "$WATCHHENTAI_URL${if (href.startsWith("/")) "" else "/"}$href"
                    val isDubbed = fullEpUrl.contains("-dubbed", ignoreCase = true) || a.text().contains("Dubbed", ignoreCase = true)
                    val epNum = extractEpNumber(fullEpUrl)
                    if (!isDubbed && seenEpNumbers.add(epNum)) {
                        episodes.add(
                            KanimeEpisodeItem(
                                episodeNumber = epNum,
                                title = "Episode $epNum",
                                episodeUrl = fullEpUrl
                            )
                        )
                    }
                }

                // If only dubbed or generic video links exist
                if (episodes.isEmpty()) {
                    for (a in epLinks) {
                        val href = a.attr("href").trim()
                        if (href.isEmpty() || href.contains("/feed")) continue
                        val fullEpUrl = if (href.startsWith("http")) href else "$WATCHHENTAI_URL${if (href.startsWith("/")) "" else "/"}$href"
                        val epNum = extractEpNumber(fullEpUrl)
                        if (seenEpNumbers.add(epNum)) {
                            episodes.add(
                                KanimeEpisodeItem(
                                    episodeNumber = epNum,
                                    title = "Episode $epNum",
                                    episodeUrl = fullEpUrl
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Error fetching WatchHentai anime details for ${item.episodeUrl}: ${e.message}")
        }

        if (episodes.isEmpty()) {
            val epNum = extractEpNumber(item.episodeUrl)
            episodes.add(
                KanimeEpisodeItem(
                    episodeNumber = epNum,
                    title = "Episode $epNum",
                    episodeUrl = item.episodeUrl
                )
            )
        }

        episodes.sortBy { it.episodeNumber }
        val firstEpisodeUrl = episodes.first().episodeUrl
        val initialEpDetails = try {
            getEpisodeDetails(firstEpisodeUrl, fallbackTitle = item.title, fallbackPoster = posterUrl).copy(
                posterUrl = posterUrl.ifEmpty { item.posterUrl },
                description = description.ifEmpty { "No description available." }
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

        return KanimeAnimeDetails(
            posterUrl = posterUrl.ifEmpty { item.posterUrl },
            description = description.ifEmpty { initialEpDetails?.description ?: "No description available." },
            episodes = episodes,
            defaultEpisodeDetails = initialEpDetails
        )
    }

    private suspend fun getHentaiMamaAnimeDetails(item: KstreamMediaItem): KanimeAnimeDetails {
        var posterUrl = item.posterUrl
        var description = ""
        val episodes = mutableListOf<KanimeEpisodeItem>()
        val seenEpNumbers = mutableSetOf<Int>()

        try {
            val html = NetworkClient.getHtml(item.episodeUrl, mapOf("Referer" to "$HENTAIMAMA_URL/"))
            if (html.isNotBlank()) {
                var doc = Jsoup.parse(html, item.episodeUrl)
                if (item.episodeUrl.contains("/episodes/")) {
                    val seriesLink = doc.selectFirst("a[href*='/tvshows/']")?.attr("href")?.trim()
                    if (!seriesLink.isNullOrEmpty()) {
                        val fullSeriesUrl = if (seriesLink.startsWith("http")) seriesLink else "$HENTAIMAMA_URL${if (seriesLink.startsWith("/")) "" else "/"}$seriesLink"
                        val sHtml = NetworkClient.getHtml(fullSeriesUrl, mapOf("Referer" to item.episodeUrl))
                        if (sHtml.isNotBlank()) {
                            doc = Jsoup.parse(sHtml, fullSeriesUrl)
                        }
                    }
                }

                val posterImg = doc.selectFirst("div.poster img, a.sc-poster img")
                val rawPoster = posterImg?.attr("data-src")?.ifEmpty { posterImg.attr("src") }?.trim().orEmpty()
                if (rawPoster.isNotEmpty() && !rawPoster.startsWith("data:")) {
                    posterUrl = if (rawPoster.startsWith("http")) rawPoster else "$HENTAIMAMA_URL${if (rawPoster.startsWith("/")) "" else "/"}$rawPoster"
                }

                description = extractDescriptionFromDoc(doc)

                val epLinks = doc.select("a[href*='/episodes/']")
                for (a in epLinks) {
                    val href = a.attr("href").trim()
                    if (href.isEmpty() || href.contains("/feed")) continue
                    val fullEpUrl = if (href.startsWith("http")) href else "$HENTAIMAMA_URL${if (href.startsWith("/")) "" else "/"}$href"
                    val epNum = extractEpNumber(fullEpUrl)
                    if (seenEpNumbers.add(epNum)) {
                        episodes.add(
                            KanimeEpisodeItem(
                                episodeNumber = epNum,
                                title = "Episode $epNum",
                                episodeUrl = fullEpUrl
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Error fetching HentaiMama anime details for ${item.episodeUrl}: ${e.message}")
        }

        if (episodes.isEmpty()) {
            val epNum = extractEpNumber(item.episodeUrl)
            episodes.add(
                KanimeEpisodeItem(
                    episodeNumber = epNum,
                    title = "Episode $epNum",
                    episodeUrl = item.episodeUrl
                )
            )
        }

        episodes.sortBy { it.episodeNumber }
        val firstEpisodeUrl = episodes.first().episodeUrl
        val initialEpDetails = try {
            getEpisodeDetails(firstEpisodeUrl, fallbackTitle = item.title, fallbackPoster = posterUrl)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

        return KanimeAnimeDetails(
            posterUrl = posterUrl.ifEmpty { item.posterUrl },
            description = initialEpDetails?.description?.takeIf { it.isNotBlank() && it != "No description available." }
                ?: description.ifEmpty { "No description available." },
            episodes = episodes,
            defaultEpisodeDetails = initialEpDetails
        )
    }

    fun extractEpNumber(url: String): Int {
        val clean = url.substringBefore("?").trimEnd('/')
        Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return it
        }
        val cleanWithoutId = clean.replace(Regex("""-id-\d+$"""), "")
        val match = Regex("""-(\d+)$""").find(cleanWithoutId)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    suspend fun getEpisodeDetails(
        episodeUrl: String,
        fallbackTitle: String = "",
        fallbackPoster: String = ""
    ): KanimeEpisodeDetails = withContext(Dispatchers.IO) {
        val cleanUrl = episodeUrl.trim()
        episodeDetailsCache[cleanUrl]?.let { cached ->
            if (cached.isFresh(DETAILS_CACHE_TTL_MS)) return@withContext cached.value
        }

        val epNum = extractEpNumber(cleanUrl)
        try {
            val referer = when {
                cleanUrl.contains("watchhentai.net", ignoreCase = true) -> "$WATCHHENTAI_URL/"
                cleanUrl.contains("hentaimama.io", ignoreCase = true) -> "$HENTAIMAMA_URL/"
                else -> "$BASE_URL/"
            }
            val html = NetworkClient.getHtml(cleanUrl, mapOf("Referer" to referer))
            if (html.isBlank() || (cleanUrl.contains("hstream.moe") && isMaintenanceOrBlockedHtml(html))) {
                return@withContext KanimeEpisodeDetails(
                    episodeNumber = epNum,
                    title = fallbackTitle.ifEmpty { "Episode $epNum" },
                    episodeUrl = cleanUrl,
                    posterUrl = fallbackPoster,
                    date = "",
                    isUncensored = fallbackTitle.contains("uncensored", ignoreCase = true),
                    description = "No description available.",
                    gallery = emptyList()
                )
            }

            val doc = Jsoup.parse(html, cleanUrl)

            // 1. Title
            val h1 = doc.selectFirst("h1")?.text()?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
            val cleanTitle = if (h1.isNotEmpty()) {
                h1.substringBefore(" Watch Hentai").trim()
            } else {
                val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty()
                if (ogTitle.isNotEmpty()) {
                    ogTitle.substringBefore(" - English").substringBefore(" - Watch").substringBefore(" | ").trim()
                } else {
                    fallbackTitle.ifEmpty { "Episode $epNum" }
                }
            }

            // 2. Poster
            var poster = fallbackPoster
            if (poster.isEmpty()) {
                poster = doc.selectFirst("img[src*='cover-ep-'], div.poster img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }?.trim().orEmpty()
            }
            if (poster.isEmpty()) {
                poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim().orEmpty()
            }
            if (poster.isNotEmpty() && !poster.startsWith("http")) {
                val baseHost = referer.trimEnd('/')
                poster = "$baseHost${if (poster.startsWith("/")) "" else "/"}$poster"
            }

            // 3. Date
            var date = doc.selectFirst("span.date, .ep-meta-field:has(.ep-meta-label:contains(Aired)) .ep-meta-value")?.text()?.trim().orEmpty()
            if (date.isEmpty()) {
                val calMatch = Regex("""fa-(?:calendar|upload)[^>]*></i>\s*([0-9]{4}-[0-9]{2}-[0-9]{2})""").find(html)
                if (calMatch != null) {
                    date = calMatch.groupValues[1]
                } else {
                    val jsonDate = Regex(""""(?:uploadDate|datePublished)"\s*:\s*"([0-9]{4}-[0-9]{2}-[0-9]{2})""").find(html)
                    if (jsonDate != null) {
                        date = jsonDate.groupValues[1]
                    } else {
                        val genericDate = Regex("""\b(20[12][0-9]-[01][0-9]-[0-3][0-9])\b""").find(html)
                        if (genericDate != null) date = genericDate.groupValues[1]
                    }
                }
            }

            // 4. Censored / Uncensored
            val isUncensored = html.contains("tags%5B0%5D=uncensored", ignoreCase = true) ||
                html.contains("tags[0]=uncensored", ignoreCase = true) ||
                cleanTitle.contains("uncensored", ignoreCase = true) ||
                cleanUrl.contains("uncensored", ignoreCase = true) ||
                doc.select("a[href*='uncensored'], .buttonuncensured").isNotEmpty() ||
                doc.select("meta[name='description'], meta[property='og:description']").attr("content").contains("uncensored", ignoreCase = true)

            // 5. Description
            val description = extractDescriptionFromDoc(doc).ifEmpty { "No description available." }

            // 6. Gallery
            val gallery = mutableListOf<String>()
            val galleryImgs = doc.select(
                "a[data-gallery='episode-gallery'] img, img[src*='gallery-ep-'], " +
                    "div.galeria a[href], div.g-item img, meta[property='og:image']"
            )
            for (el in galleryImgs) {
                var src = when (el.tagName()) {
                    "meta" -> el.attr("content").trim()
                    "a" -> el.attr("href").trim()
                    else -> el.attr("data-src").ifEmpty { el.attr("src") }.trim()
                }
                if (src.isEmpty() || src.startsWith("data:")) continue
                if (!src.startsWith("http")) {
                    val baseHost = referer.trimEnd('/')
                    src = "$baseHost${if (src.startsWith("/")) "" else "/"}$src"
                }
                if (src !in gallery) {
                    gallery.add(src)
                }
            }
            if (gallery.isEmpty()) {
                val regexMatches = Regex("""https?://[^\s"'<>]+?(?:gallery-ep-[0-9]+-[0-9]+|backdrop|/uploads/[^\s"'<>]+?/\d+)[^\s"'<>]*?\.(?:webp|jpg|png)""").findAll(html)
                for (m in regexMatches) {
                    val src = m.value.replace("\\/", "/").trim()
                    if (src !in gallery) gallery.add(src)
                }
            }

            val result = KanimeEpisodeDetails(
                episodeNumber = epNum,
                title = cleanTitle,
                episodeUrl = cleanUrl,
                posterUrl = poster,
                date = date,
                isUncensored = isUncensored,
                description = description,
                gallery = gallery
            )
            episodeDetailsCache[cleanUrl] = CacheEntry(result)
            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error fetching episode details for $cleanUrl: ${e.message}", e)
            KanimeEpisodeDetails(
                episodeNumber = epNum,
                title = fallbackTitle.ifEmpty { "Episode $epNum" },
                episodeUrl = cleanUrl,
                posterUrl = fallbackPoster,
                date = "",
                isUncensored = fallbackTitle.contains("uncensored", ignoreCase = true),
                description = "No description available.",
                gallery = emptyList()
            )
        }
    }
}
