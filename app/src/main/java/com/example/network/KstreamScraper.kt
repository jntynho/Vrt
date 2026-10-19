package com.example.network

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder

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

    suspend fun getFeed(
        page: Int = 1,
        query: String = "",
        order: String = "recently-uploaded"
    ): List<KstreamMediaItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val url = if (cleanQuery.isNotEmpty()) {
            "$BASE_URL/search?view=poster&search=${URLEncoder.encode(cleanQuery, "UTF-8")}&order=$order&page=$page"
        } else {
            "$BASE_URL/search?view=poster&order=$order&page=$page"
        }

        try {
            val html = NetworkClient.getHtml(url, mapOf("Referer" to "$BASE_URL/"))
            if (html.isBlank()) return@withContext emptyList()
            parseCatalogHtml(html)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error fetching feed page $page with order $order: ${e.message}", e)
            throw e
        }
    }

    private fun parseCatalogHtml(html: String): List<KstreamMediaItem> {
        val items = mutableListOf<KstreamMediaItem>()
        val doc = Jsoup.parse(html, BASE_URL)
        val cardElements = doc.select("a[href*='/hentai/']")

        val seenUrls = mutableSetOf<String>()
        for (a in cardElements) {
            val href = a.attr("href").trim()
            if (href.isEmpty() || !href.contains("/hentai/")) continue
            val fullUrl = if (href.startsWith("http")) href else "$BASE_URL${if (href.startsWith("/")) "" else "/"}$href"
            if (!seenUrls.add(fullUrl)) continue

            val img = a.selectFirst("img")
            var title = a.selectFirst("h3")?.text()?.trim().orEmpty()
            if (title.isEmpty()) {
                title = img?.attr("alt")?.trim().orEmpty()
            }
            if (title.isEmpty()) {
                title = fullUrl.substringAfterLast("/").replace("-", " ")
            }

            var imgSrc = img?.attr("src")?.trim().orEmpty()
            if (imgSrc.isEmpty() || imgSrc.startsWith("data:")) {
                imgSrc = img?.attr("data-src")?.trim().orEmpty()
            }
            var posterUrl = if (imgSrc.startsWith("http")) imgSrc else "$BASE_URL${if (imgSrc.startsWith("/")) "" else "/"}$imgSrc"

            // Ensure we strictly capture the full poster image and not gallery thumbnails
            if (posterUrl.contains("gallery-ep-") && posterUrl.contains("-thumbnail.webp")) {
                posterUrl = posterUrl.replace(Regex("""gallery-ep-(\d+)-\d+-thumbnail\.webp"""), "cover-ep-$1.webp")
            }

            val id = fullUrl.substringAfterLast("/hentai/").substringBefore("?").ifEmpty { java.util.UUID.randomUUID().toString() }

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

    suspend fun resolveStream(episodeUrl: String): KstreamResolvedStream? = withContext(Dispatchers.IO) {
        try {
            val html = NetworkClient.getHtml(episodeUrl, mapOf("Referer" to "$BASE_URL/"))
            val doc = Jsoup.parse(html, episodeUrl)

            val eId = doc.selectFirst("input#e_id, input[name='e_id']")?.attr("value")?.trim()
                ?: Regex("""id=["']e_id["'][^>]*value=["'](\d+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""value=["'](\d+)["'][^>]*id=["']e_id["']""").find(html)?.groupValues?.get(1)

            if (eId.isNullOrEmpty()) {
                Log.w(TAG, "Could not find e_id in episode page: $episodeUrl")
                return@withContext null
            }

            // Extract CSRF token from cookies
            val baseHttpUrl = "https://hstream.moe/".toHttpUrlOrNull()
            val cookies = if (baseHttpUrl != null) {
                NetworkClient.okHttpClient.cookieJar.loadForRequest(baseHttpUrl)
            } else {
                emptyList()
            }
            val xsrfCookie = cookies.firstOrNull { it.name == "XSRF-TOKEN" }?.value
            val decodedXsrf = xsrfCookie?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            } ?: ""

            val jsonBody = JSONObject().apply {
                put("episode_id", eId)
            }.toString()

            val reqBuilder = Request.Builder()
                .url("$BASE_URL/player/api")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", episodeUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")

            if (decodedXsrf.isNotEmpty()) {
                reqBuilder.header("X-XSRF-TOKEN", decodedXsrf)
            }

            val response = NetworkClient.okHttpClient.newCall(reqBuilder.build()).await()
            val respBody = response.body?.string().orEmpty()
            response.close()

            if (respBody.isBlank()) return@withContext null
            val json = JSONObject(respBody)

            val title = json.optString("title", "Episode Stream")
            val streamUrl = json.optString("stream_url", "").trim('/')
            if (streamUrl.isBlank()) {
                Log.w(TAG, "Empty stream_url in API response for $episodeUrl")
                return@withContext null
            }

            val streamDomains = mutableListOf<String>()
            val domainsArr = json.optJSONArray("stream_domains")
            if (domainsArr != null) {
                for (i in 0 until domainsArr.length()) {
                    streamDomains.add(domainsArr.getString(i))
                }
            }
            val asiaDomainsArr = json.optJSONArray("asia_stream_domains")
            if (asiaDomainsArr != null) {
                for (i in 0 until asiaDomainsArr.length()) {
                    streamDomains.add(asiaDomainsArr.getString(i))
                }
            }

            val domain = (streamDomains.firstOrNull() ?: "https://imoto-str.ane-h.xyz").trimEnd('/')
            val interpolated = json.optInt("interpolated", 0)
            val legacy = json.optInt("legacy", 0)

            val qualities = mutableListOf<StreamQuality>()

            // 4K / 2160p DASH
            qualities.add(
                StreamQuality(
                    quality = "2160p (4K DASH)",
                    url = "$domain/$streamUrl/2160/manifest.mpd",
                    format = "DASH",
                    headers = mapOf("Referer" to "$BASE_URL/")
                )
            )

            if (interpolated == 1) {
                qualities.add(
                    StreamQuality(
                        quality = "1080p 48fps (DASH)",
                        url = "$domain/$streamUrl/1080i/manifest.mpd",
                        format = "DASH",
                        headers = mapOf("Referer" to "$BASE_URL/")
                    )
                )
            }

            // 1080p DASH
            qualities.add(
                StreamQuality(
                    quality = "1080p (DASH)",
                    url = "$domain/$streamUrl/1080/manifest.mpd",
                    format = "DASH",
                    headers = mapOf("Referer" to "$BASE_URL/")
                )
            )

            // 720p DASH
            qualities.add(
                StreamQuality(
                    quality = "720p (DASH)",
                    url = "$domain/$streamUrl/720/manifest.mpd",
                    format = "DASH",
                    headers = mapOf("Referer" to "$BASE_URL/"),
                    isDefault = true
                )
            )

            if (legacy == 1) {
                qualities.add(
                    StreamQuality(
                        quality = "1080p (MP4 Direct)",
                        url = "$domain/$streamUrl/x264.1080p.mp4",
                        format = "MP4",
                        headers = mapOf("Referer" to "$BASE_URL/")
                    )
                )
            }

            // 720p MP4 Direct Fallback
            qualities.add(
                StreamQuality(
                    quality = "720p (MP4 Direct)",
                    url = "$domain/$streamUrl/x264.720p.mp4",
                    format = "MP4",
                    headers = mapOf("Referer" to "$BASE_URL/")
                )
            )

            val subtitles = mutableListOf<SubtitleTrack>()

            // 1. Check for subtitle tracks directly in the API JSON response if available
            val subsArr = json.optJSONArray("subtitles") ?: json.optJSONArray("captions") ?: json.optJSONArray("tracks")
            if (subsArr != null) {
                for (i in 0 until subsArr.length()) {
                    val subObj = subsArr.optJSONObject(i)
                    if (subObj != null) {
                        val subUrl = subObj.optString("src").ifEmpty { subObj.optString("url") }
                        val label = subObj.optString("label").ifEmpty { subObj.optString("title", "English") }
                        val lang = subObj.optString("lang").ifEmpty { subObj.optString("language", "en") }
                        if (subUrl.isNotEmpty()) {
                            val fullSubUrl = if (subUrl.startsWith("http")) subUrl else "$domain/$streamUrl/$subUrl"
                            subtitles.add(SubtitleTrack(language = lang, label = label, url = fullSubUrl))
                        }
                    }
                }
            }

            // 2. Parse <track> tags in the episode HTML if present
            val trackElements = doc.select("track[src]")
            for (track in trackElements) {
                val src = track.attr("src").trim()
                if (src.isNotEmpty()) {
                    val label = track.attr("label").trim().ifEmpty { "English" }
                    val lang = track.attr("srclang").trim().ifEmpty { "en" }
                    val fullSrc = if (src.startsWith("http")) src else if (src.startsWith("/")) "$BASE_URL$src" else "$domain/$streamUrl/$src"
                    if (subtitles.none { it.url == fullSrc }) {
                        subtitles.add(SubtitleTrack(language = lang, label = label, url = fullSrc))
                    }
                }
            }

            // 3. Hstream standard default subtitle: eng.ass located inside the stream directory
            val defaultAssUrl = "$domain/$streamUrl/eng.ass"
            if (subtitles.none { it.url == defaultAssUrl || it.language == "en" || it.label.equals("English", ignoreCase = true) }) {
                subtitles.add(
                    SubtitleTrack(
                        language = "en",
                        label = "English",
                        url = defaultAssUrl
                    )
                )
            }

            KstreamResolvedStream(
                title = title,
                qualities = qualities,
                subtitles = subtitles
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error resolving stream for $episodeUrl: ${e.message}", e)
            null
        }
    }

    private fun extractDescriptionFromDoc(doc: Document): String {
        var desc = doc.select("h2:contains(Description) ~ p").firstOrNull()?.text()?.trim().orEmpty()
        if (desc.isEmpty()) {
            desc = doc.select("div:has(h2:contains(Description)) p").firstOrNull()?.text()?.trim().orEmpty()
        }
        if (desc.isEmpty()) {
            desc = doc.select("meta[name='description']").attr("content").trim()
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
        val slug = item.episodeUrl.substringAfterLast("/hentai/").substringBefore("?").trim()
        val seriesSlug = slug.replace(Regex("""-\d+$"""), "")
        val seriesUrl = "$BASE_URL/hentai/$seriesSlug"

        var posterUrl = item.posterUrl
        var description = ""
        val episodes = mutableListOf<KanimeEpisodeItem>()
        val seenUrls = mutableSetOf<String>()

        try {
            // 1. Try loading series page
            val html = NetworkClient.getHtml(seriesUrl, mapOf("Referer" to "$BASE_URL/"))
            if (html.isNotBlank()) {
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

        // 2. If episodes list or description is missing, fallback to parsing episodeUrl page
        if (episodes.isEmpty() || description.isEmpty()) {
            try {
                val epHtml = NetworkClient.getHtml(item.episodeUrl, mapOf("Referer" to "$BASE_URL/"))
                if (epHtml.isNotBlank()) {
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

        // 3. If still empty, ensure at least this episode is listed
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
            getEpisodeDetails(item.episodeUrl, fallbackTitle = item.title, fallbackPoster = posterUrl)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Error fetching initial episode details: ${e.message}")
            null
        }

        KanimeAnimeDetails(
            posterUrl = initialEpDetails?.posterUrl ?: posterUrl,
            description = initialEpDetails?.description?.takeIf { it.isNotBlank() } ?: description.ifEmpty { "No description available." },
            episodes = episodes,
            defaultEpisodeDetails = initialEpDetails
        )
    }

    fun extractEpNumber(url: String): Int {
        val clean = url.substringBefore("?").trimEnd('/')
        val match = Regex("""-(\d+)$""").find(clean)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    suspend fun getEpisodeDetails(
        episodeUrl: String,
        fallbackTitle: String = "",
        fallbackPoster: String = ""
    ): KanimeEpisodeDetails = withContext(Dispatchers.IO) {
        val epNum = extractEpNumber(episodeUrl)
        try {
            val html = NetworkClient.getHtml(episodeUrl, mapOf("Referer" to "$BASE_URL/"))
            if (html.isBlank()) {
                return@withContext KanimeEpisodeDetails(
                    episodeNumber = epNum,
                    title = fallbackTitle.ifEmpty { "Episode $epNum" },
                    episodeUrl = episodeUrl,
                    posterUrl = fallbackPoster,
                    date = "",
                    isUncensored = fallbackTitle.contains("uncensored", ignoreCase = true),
                    description = "No description available.",
                    gallery = emptyList()
                )
            }

            val doc = Jsoup.parse(html, episodeUrl)

            // 1. Title with episode numbering as on site (e.g. from H1 or og:title)
            val h1 = doc.selectFirst("h1")?.text()?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
            val cleanTitle = if (h1.isNotEmpty()) {
                h1
            } else {
                val ogTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty()
                if (ogTitle.isNotEmpty()) {
                    ogTitle.substringBefore(" - English").substringBefore(" | ").trim()
                } else {
                    fallbackTitle.ifEmpty { "Episode $epNum" }
                }
            }

            // 2. Poster for this episode
            var poster = doc.selectFirst("img[src*='cover-ep-']")?.attr("src")?.trim().orEmpty()
            if (poster.isEmpty()) {
                poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.trim().orEmpty()
            }
            if (poster.isEmpty()) {
                poster = fallbackPoster
            }
            if (poster.isNotEmpty() && !poster.startsWith("http")) {
                poster = "$BASE_URL${if (poster.startsWith("/")) "" else "/"}$poster"
            }

            // 3. Date
            var date = ""
            val calMatch = Regex("""fa-(?:calendar|upload)[^>]*></i>\s*([0-9]{4}-[0-9]{2}-[0-9]{2})""").find(html)
            if (calMatch != null) {
                date = calMatch.groupValues[1]
            } else {
                val jsonDate = Regex(""""uploadDate":"([0-9]{4}-[0-9]{2}-[0-9]{2})""").find(html)
                if (jsonDate != null) {
                    date = jsonDate.groupValues[1]
                } else {
                    val genericDate = Regex("""\b(20[12][0-9]-[01][0-9]-[0-3][0-9])\b""").find(html)
                    if (genericDate != null) date = genericDate.groupValues[1]
                }
            }

            // 4. Censored / Uncensored
            val isUncensored = html.contains("tags%5B0%5D=uncensored", ignoreCase = true) ||
                html.contains("tags[0]=uncensored", ignoreCase = true) ||
                cleanTitle.contains("uncensored", ignoreCase = true) ||
                doc.select("a[href*='uncensored']").isNotEmpty() ||
                doc.select("meta[name='description']").attr("content").contains("uncensored", ignoreCase = true)

            // 5. Description
            val description = extractDescriptionFromDoc(doc).ifEmpty { "No description available." }

            // 6. Gallery
            val gallery = mutableListOf<String>()
            val galleryImgs = doc.select("a[data-gallery='episode-gallery'] img, img[src*='gallery-ep-']")
            for (img in galleryImgs) {
                var src = img.attr("src").trim()
                if (src.isEmpty()) continue
                if (!src.startsWith("http")) {
                    src = "$BASE_URL${if (src.startsWith("/")) "" else "/"}$src"
                }
                if (src !in gallery) {
                    gallery.add(src)
                }
            }
            if (gallery.isEmpty()) {
                val regexMatches = Regex("""(?:href|src)=["']([^"']*(?:gallery-ep-[0-9]+-[0-9]+)[^"']*)["']""").findAll(html)
                for (m in regexMatches) {
                    var src = m.groupValues[1]
                    if (src.contains("thumbnail") || src.endsWith(".webp") || src.endsWith(".jpg") || src.endsWith(".png")) {
                        if (!src.startsWith("http")) src = "$BASE_URL${if (src.startsWith("/")) "" else "/"}$src"
                        if (src !in gallery) gallery.add(src)
                    }
                }
            }

            KanimeEpisodeDetails(
                episodeNumber = epNum,
                title = cleanTitle,
                episodeUrl = episodeUrl,
                posterUrl = poster,
                date = date,
                isUncensored = isUncensored,
                description = description,
                gallery = gallery
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error fetching episode details for $episodeUrl: ${e.message}", e)
            KanimeEpisodeDetails(
                episodeNumber = epNum,
                title = fallbackTitle.ifEmpty { "Episode $epNum" },
                episodeUrl = episodeUrl,
                posterUrl = fallbackPoster,
                date = "",
                isUncensored = fallbackTitle.contains("uncensored", ignoreCase = true),
                description = "No description available.",
                gallery = emptyList()
            )
        }
    }
}
