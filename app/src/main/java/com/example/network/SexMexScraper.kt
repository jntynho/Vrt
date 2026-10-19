package com.example.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SexMexSearchResult(
    val models: List<StashPerformer>,
    val scenes: List<StashScene>
)

object SexMexScraper {
    private const val TAG = "SexMexScraper"
    private const val BASE_URL = "https://sexmex.xxx"

    // In-memory High-Performance Cache (10 min TTL for searches & scenes, 30 min for avatars)
    private data class CacheEntry<T>(val data: T, val timestamp: Long = System.currentTimeMillis()) {
        fun isValid(ttlMs: Long = 10 * 60 * 1000L): Boolean = System.currentTimeMillis() - timestamp < ttlMs
    }

    private val searchCache = ConcurrentHashMap<String, CacheEntry<SexMexSearchResult>>()
    private val scenesCache = ConcurrentHashMap<String, CacheEntry<List<StashScene>>>()
    private val modelAvatarCache = ConcurrentHashMap<String, String>()
    private var latestScenesCache: CacheEntry<List<StashScene>>? = null

    // Pre-compiled fast Regexes to avoid full DOM overhead on single-value extractions
    private val FAST_AVATAR_REGEX = Regex("""class=[\"'][^\"']*model_bio_thumb[^\"']*[\"'][^>]*src=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    private val FAST_AVATAR_FALLBACK_REGEX = Regex("""src=[\"']([^\"']+)[\"'][^>]*class=[\"'][^\"']*model_bio_thumb""", RegexOption.IGNORE_CASE)
    private val FAST_CONTENTTHUMBS_REGEX = Regex("""src=[\"']([^\"']*contentthumbs[^\"']*)[\"']""", RegexOption.IGNORE_CASE)
    private val FAST_PICTURE_SRCSET_REGEX = Regex("""<picture[^>]*>[\s\S]*?<source[^>]+srcset=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)

    suspend fun searchSexMex(query: String): SexMexSearchResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return SexMexSearchResult(emptyList(), emptyList())

        val cacheKey = cleanQuery.lowercase()
        searchCache[cacheKey]?.takeIf { it.isValid() }?.let {
            Log.d(TAG, "Serving SexMex search from in-memory cache: $cleanQuery")
            return it.data
        }

        // Direct SexMex URL handling
        if (cleanQuery.startsWith("http://", ignoreCase = true) || cleanQuery.startsWith("https://", ignoreCase = true)) {
            val scenes = scrapeSexMexPage(cleanQuery)
            val result = SexMexSearchResult(emptyList(), scenes)
            searchCache[cacheKey] = CacheEntry(result)
            return result
        }

        val directScenes = mutableListOf<StashScene>()

        try {
            val searchUrl = "$BASE_URL/tour/search.php?query=${URLEncoder.encode(cleanQuery, "UTF-8")}"
            Log.d(TAG, "Fetching SexMex search: $searchUrl")
            val html = NetworkClient.getHtml(searchUrl)
            if (html.isNotEmpty()) {
                val doc = Jsoup.parse(html, searchUrl)

                // 1. Extract models matching search query
                val modelLinks = doc.select("a[href*='/models/']")
                val modelsMap = linkedMapOf<String, String>() // rawName to url
                val normQuery = cleanQuery.lowercase().replace(" ", "")

                for (link in modelLinks) {
                    val href = link.attr("href")
                    if (href.contains("models.html")) continue
                    val name = link.text().trim()
                    if (name.length > 1) {
                        val normName = name.lowercase().replace(" ", "")
                        if (normName.contains(normQuery) || normQuery.contains(normName)) {
                            val fullUrl = if (href.startsWith("http")) href else "$BASE_URL${if (href.startsWith("/")) "" else "/"}$href"
                            if (!modelsMap.containsKey(name.lowercase())) {
                                modelsMap[name.lowercase()] = fullUrl
                            }
                        }
                    }
                }

                val topModels = modelsMap.entries.take(6).toList()

                if (topModels.isNotEmpty()) {
                    val result = coroutineScope {
                        // Launch parallel avatar fetching for all matched models
                        val modelsDeferred = topModels.map { (rawName, modelUrl) ->
                            async(Dispatchers.IO) {
                                val modelName = modelUrl.substringAfterLast("/").substringBefore(".html")
                                    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                                    .replace("-", " ")
                                    .trim()
                                    .split(" ")
                                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

                                val avatarUrl = fetchModelAvatarFast(modelUrl, modelName)
                                StashPerformer(
                                    id = modelUrl,
                                    name = if (modelName.isNotBlank()) modelName else rawName.replaceFirstChar { it.uppercase() },
                                    gender = "FEMALE",
                                    imageUrl = avatarUrl
                                )
                            }
                        }

                        // Concurrently fetch the first model's scenes in the SAME parallel batch
                        val firstModelUrl = topModels.first().value
                        val firstModelScenesDeferred = async(Dispatchers.IO) {
                            scrapeSexMexPage(firstModelUrl)
                        }

                        val resolvedModels = modelsDeferred.awaitAll()
                        val resolvedScenes = firstModelScenesDeferred.await()
                        SexMexSearchResult(resolvedModels, resolvedScenes)
                    }

                    searchCache[cacheKey] = CacheEntry(result)
                    return result
                } else {
                    // When no performer name matched, parse scenes directly from search page
                    directScenes.addAll(parseScenesFromHtml(doc, searchUrl))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in SexMex search for '$cleanQuery'", e)
        }

        val finalResult = SexMexSearchResult(emptyList(), directScenes.distinctBy { it.id })
        searchCache[cacheKey] = CacheEntry(finalResult)
        return finalResult
    }

    suspend fun scrapeSexMexPage(pageUrl: String): List<StashScene> {
        scenesCache[pageUrl]?.takeIf { it.isValid() }?.let {
            return it.data
        }

        val scenes = mutableListOf<StashScene>()
        try {
            val html = NetworkClient.getHtml(pageUrl)
            if (html.isNotEmpty()) {
                val doc = Jsoup.parse(html, pageUrl)
                scenes.addAll(parseScenesFromHtml(doc, pageUrl))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping SexMex page: $pageUrl", e)
        }

        val distinctScenes = scenes.distinctBy { it.id }
        if (distinctScenes.isNotEmpty()) {
            scenesCache[pageUrl] = CacheEntry(distinctScenes)
        }
        return distinctScenes
    }

    suspend fun getLatestSexMexScenes(): List<StashScene> {
        latestScenesCache?.takeIf { it.isValid(5 * 60 * 1000L) }?.let {
            return it.data
        }

        val scenes = mutableListOf<StashScene>()
        val candidateUrls = listOf(
            "$BASE_URL/tour/categories/movies.html",
            "$BASE_URL/tour/"
        )
        for (url in candidateUrls) {
            try {
                Log.d(TAG, "Fetching latest SexMex scenes from: $url")
                val html = NetworkClient.getHtml(url)
                if (html.isNotEmpty()) {
                    val doc = Jsoup.parse(html, url)
                    val parsed = parseScenesFromHtml(doc, url)
                    if (parsed.isNotEmpty()) {
                        scenes.addAll(parsed)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching latest SexMex scenes from $url", e)
            }
        }

        val distinct = scenes.distinctBy { it.id }
        if (distinct.isNotEmpty()) {
            latestScenesCache = CacheEntry(distinct)
        }
        return distinct
    }

    private suspend fun fetchModelAvatarFast(modelUrl: String, modelName: String): String? {
        modelAvatarCache[modelUrl]?.let { return it }

        var avatarUrl: String? = null
        try {
            val html = NetworkClient.getHtml(modelUrl)
            if (html.isNotEmpty()) {
                // 1. Ultra-fast Regex match (0.1ms)
                val m = FAST_AVATAR_REGEX.find(html) ?: FAST_AVATAR_FALLBACK_REGEX.find(html) ?: FAST_CONTENTTHUMBS_REGEX.find(html)
                if (m != null && m.groupValues.size > 1) {
                    avatarUrl = m.groupValues[1].replace("&amp;", "&")
                }

                // 2. Fallback to Jsoup only if regex didn't find it
                if (avatarUrl.isNullOrBlank()) {
                    val modelDoc = Jsoup.parse(html, modelUrl)
                    val imgEl = modelDoc.selectFirst("img.model_bio_thumb, .profile-pic img, img[alt*='$modelName'], img[src*='contentthumbs']")
                    avatarUrl = imgEl?.attr("src")?.ifEmpty { null }?.replace("&amp;", "&")
                }

                if (avatarUrl != null && !avatarUrl.startsWith("http")) {
                    avatarUrl = "$BASE_URL${if (avatarUrl.startsWith("/")) "" else "/"}$avatarUrl"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching model avatar for $modelName", e)
        }

        if (!avatarUrl.isNullOrBlank()) {
            modelAvatarCache[modelUrl] = avatarUrl
        }
        return avatarUrl
    }

    private fun parseScenesFromHtml(doc: org.jsoup.nodes.Document, sourceUrl: String): List<StashScene> {
        val scenes = mutableListOf<StashScene>()
        val updateLinks = doc.select("a[href*='/updates/'], a[href*='/tour/updates/']")
        val seenUrls = mutableSetOf<String>()

        for (link in updateLinks) {
            val sceneUrl = link.attr("abs:href").ifEmpty { link.attr("href") }
            if (sceneUrl.isBlank() || seenUrls.contains(sceneUrl)) continue

            // Determine title
            var rawTitle = link.attr("title").trim()
            if (rawTitle.isEmpty()) {
                rawTitle = link.selectFirst("h3, h5, .scene-title, img[alt]")?.text()
                    ?.ifEmpty { link.selectFirst("img")?.attr("alt") }
                    ?: ""
            }

            // Decode basic HTML entities that might appear in title
            rawTitle = rawTitle
                .replace("&rsquo;", "'")
                .replace("&lsquo;", "'")
                .replace("&rdquo;", "\"")
                .replace("&ldquo;", "\"")
                .replace("&amp;", "&")

            var cleanTitle = rawTitle
            val actorsFromTitleList = mutableListOf<String>()
            var extractedDateFromTitle: String? = null

            val dateRegex = Regex("""\b(\d{1,4})[/\.-](\d{1,2})[/\.-](\d{2,4})\b""")

            // Helper to clean and format a date string to YYYY-MM-DD if possible
            fun formatToYyyyMmDd(raw: String): String {
                val m = dateRegex.find(raw) ?: return raw
                val p1 = m.groupValues[1]
                val p2 = m.groupValues[2]
                val p3 = m.groupValues[3]
                
                return if (p1.length == 4) {
                    // YYYY-MM-DD
                    "$p1-${p2.padStart(2, '0')}-${p3.padStart(2, '0')}"
                } else if (p3.length == 4) {
                    // MM/DD/YYYY or DD/MM/YYYY
                    "$p3-${p1.padStart(2, '0')}-${p2.padStart(2, '0')}"
                } else if (p3.length == 2) {
                    // MM/DD/YY -> 20YY
                    "20$p3-${p1.padStart(2, '0')}-${p2.padStart(2, '0')}"
                } else {
                    raw
                }
            }

            fun processPart(part: String) {
                val trimmed = part.trim()
                if (dateRegex.containsMatchIn(trimmed)) {
                    extractedDateFromTitle = formatToYyyyMmDd(trimmed)
                } else {
                    actorsFromTitleList.add(trimmed)
                }
            }

            // Split rawTitle by the dots
            val titleParts = if (rawTitle.contains(" . ")) {
                rawTitle.split(" . ")
            } else if (rawTitle.contains(" .")) {
                rawTitle.split(" .")
            } else if (rawTitle.contains(".")) {
                val parts = rawTitle.split(".")
                // If parts look like date digits, don't split! (e.g. 10.24.2024)
                if (parts.all { it.trim().all { c -> c.isDigit() } }) {
                    listOf(rawTitle)
                } else {
                    parts
                }
            } else {
                listOf(rawTitle)
            }

            if (titleParts.size > 1) {
                for (part in titleParts) {
                    processPart(part)
                }
                
                // Determine the clean title: first part that is not a date
                val nonDateParts = titleParts.filter { !dateRegex.containsMatchIn(it) }
                if (nonDateParts.isNotEmpty()) {
                    cleanTitle = nonDateParts[0].trim()
                }
            } else {
                // Single part, check if it contains a date
                val m = dateRegex.find(rawTitle)
                if (m != null) {
                    extractedDateFromTitle = formatToYyyyMmDd(m.value)
                    cleanTitle = rawTitle.replace(m.value, "").replace(Regex("""\s+-\s+|\s+\.\s+|\s+&\s+"""), " ").trim()
                }
            }

            // Cover thumbnail
            var coverUrl = link.selectFirst("picture source")?.attr("srcset")
            if (coverUrl.isNullOrEmpty()) {
                coverUrl = link.selectFirst("img")?.attr("src")
            }
            if (coverUrl.isNullOrEmpty()) {
                val parent = link.parent()
                coverUrl = parent?.selectFirst("picture source")?.attr("srcset")
                    ?: parent?.selectFirst("img")?.attr("src")
            }

            if (coverUrl != null && coverUrl.startsWith("//")) {
                coverUrl = "https:$coverUrl"
            } else if (coverUrl != null && !coverUrl.startsWith("http")) {
                coverUrl = "$BASE_URL${if (coverUrl.startsWith("/")) "" else "/"}$coverUrl"
            }
            val cleanCoverUrl = coverUrl?.replace("&amp;", "&")

            // Date and details from sibling / parent
            val container = link.parents().firstOrNull { it.hasClass("update_thumb") || it.hasClass("thumb") || it.hasClass("thumbs") || it.selectFirst(".scene-date") != null } ?: link.parent()
            var dateStr = container?.selectFirst(".scene-date, time, .date")?.text()?.trim()
            val descr = container?.selectFirst(".scene-descr, .description, p")?.text()?.trim()

            // Process and format extracted date
            if (!dateStr.isNullOrBlank()) {
                dateStr = formatToYyyyMmDd(dateStr)
            } else if (!extractedDateFromTitle.isNullOrBlank()) {
                dateStr = extractedDateFromTitle
            }

            // Performers in scene - strict filter to exclude any scene update links (/updates/)
            val performerElements = container?.select("a[href*='/models/'], a.modelnamesut")
                ?.filter { el ->
                    val href = el.attr("href")
                    !href.contains("/updates/", ignoreCase = true) && !href.contains("/tour/updates/", ignoreCase = true)
                } ?: emptyList()

            val performersList = mutableListOf<String>()
            for (linkEl in performerElements) {
                val href = linkEl.attr("href")
                val rawName = if (href.contains("/models/", ignoreCase = true) && !href.contains("models.html", ignoreCase = true)) {
                    val modelId = href.substringAfterLast("/").substringBefore(".html").substringBefore("?")
                    if (modelId.isNotBlank() && !modelId.equals("models", ignoreCase = true)) {
                        modelId.replace("-", " ")
                            .replace("_", " ")
                            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                            .split(" ")
                            .filter { it.isNotBlank() }
                            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                            .trim()
                    } else {
                        null
                    }
                } else {
                    val text = linkEl.text().trim()
                    if (text.isNotEmpty() && !text.contains("Models", ignoreCase = true)) {
                        text
                    } else {
                        null
                    }
                }

                if (!rawName.isNullOrBlank()) {
                    // Split by comma, " & ", " and ", or " And " to handle multiple performers listed together
                    val splitNames = rawName.split(Regex(",|\\s+&\\s+|\\s+and\\s+|\\s+And\\s+"))
                    for (name in splitNames) {
                        val cleanName = name.trim()
                        if (cleanName.isNotBlank() && cleanName.length > 2) {
                            performersList.add(cleanName)
                        }
                    }
                }
            }

            // Fallback performers: if links didn't give us performers, use those parsed from the title separator
            if (performersList.isEmpty()) {
                for (name in actorsFromTitleList) {
                    val cleanName = name.trim()
                    if (cleanName.isNotBlank() && cleanName.length > 2) {
                        performersList.add(cleanName)
                    }
                }
            }

            val performers = performersList.distinct().map {
                StashPerformer(id = UUID.randomUUID().toString(), name = it, gender = "FEMALE", imageUrl = null)
            }

            if (cleanTitle.isNotBlank() && !cleanCoverUrl.isNullOrBlank()) {
                seenUrls.add(sceneUrl)
                scenes.add(
                    StashScene(
                        id = sceneUrl,
                        title = cleanTitle,
                        details = descr ?: "SexMex Official Scene",
                        date = dateStr ?: "Unknown Date",
                        studioId = "sexmex",
                        studioName = "SexMex",
                        studioLogo = null,
                        coverUrl = cleanCoverUrl,
                        femalePerformers = performers
                    )
                )
            }
        }
        return scenes
    }

    suspend fun fetchFreshCoverUrl(sceneUrl: String): String? {
        try {
            val html = NetworkClient.getHtml(sceneUrl)
            if (html.isEmpty()) return null

            // 1. Fast Regex attempt (0.1 ms)
            var coverUrl = FAST_PICTURE_SRCSET_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?: FAST_CONTENTTHUMBS_REGEX.find(html)?.groupValues?.getOrNull(1)

            // 2. Jsoup Fallback
            if (coverUrl.isNullOrEmpty()) {
                val doc = Jsoup.parse(html, sceneUrl)
                coverUrl = doc.selectFirst("picture source")?.attr("srcset")
                if (coverUrl.isNullOrEmpty()) {
                    coverUrl = doc.selectFirst("img[src*='contentthumbs'], .update_thumb img, img.scene-cover")?.attr("src")
                }
                if (coverUrl.isNullOrEmpty()) {
                    coverUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")
                }
            }

            if (coverUrl != null && coverUrl.startsWith("//")) {
                coverUrl = "https:$coverUrl"
            } else if (coverUrl != null && !coverUrl.startsWith("http")) {
                coverUrl = "$BASE_URL${if (coverUrl.startsWith("/")) "" else "/"}$coverUrl"
            }
            return coverUrl?.replace("&amp;", "&")?.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching fresh cover for $sceneUrl", e)
            return null
        }
    }
}
