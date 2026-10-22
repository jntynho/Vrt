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
    val scenes: List<StashScene>,
    val nextPageUrl: String? = null
)

data class SexMexScenesPage(
    val scenes: List<StashScene>,
    val nextPageUrl: String? = null
)

object SexMexScraper {
    private const val TAG = "SexMexScraper"
    private const val BASE_URL = "https://sexmex.xxx"

    // In-memory High-Performance Cache (10 min TTL for searches & scenes, 30 min for avatars)
    private data class CacheEntry<T>(val data: T, val timestamp: Long = System.currentTimeMillis()) {
        fun isValid(ttlMs: Long = 10 * 60 * 1000L): Boolean = System.currentTimeMillis() - timestamp < ttlMs
    }

    private val searchCache = ConcurrentHashMap<String, CacheEntry<SexMexSearchResult>>()
    private val scenesPageCache = ConcurrentHashMap<String, CacheEntry<SexMexScenesPage>>()
    private val modelAvatarCache = ConcurrentHashMap<String, String>()
    private var latestScenesPageCache: CacheEntry<SexMexScenesPage>? = null

    // Pre-compiled fast Regexes to avoid full DOM overhead on single-value extractions
    private val FAST_AVATAR_REGEX = Regex("""class=[\"'][^\"']*model_bio_thumb[^\"']*[\"'][^>]*src=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    private val FAST_AVATAR_FALLBACK_REGEX = Regex("""src=[\"']([^\"']+)[\"'][^>]*class=[\"'][^\"']*model_bio_thumb""", RegexOption.IGNORE_CASE)
    private val FAST_CONTENTTHUMBS_REGEX = Regex("""src=[\"']([^\"']*contentthumbs[^\"']*)[\"']""", RegexOption.IGNORE_CASE)
    private val FAST_PICTURE_SRCSET_REGEX = Regex("""<picture[^>]*>[\s\S]*?<source[^>]+srcset=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    private val NUMERIC_DATE_REGEX = Regex("""\b(\d{1,4})[/\.-](\d{1,2})[/\.-](\d{2,4})\b""")
    private val TEXT_MONTH_DATE_REGEX = Regex(
        """\b(?:(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\s+(\d{1,2}),?\s+(\d{4})|(\d{1,2})\s+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?),?\s+(\d{4}))\b""",
        RegexOption.IGNORE_CASE
    )

    private fun extractCleanUrlFromSrcset(rawSrcset: String?): String? {
        if (rawSrcset.isNullOrBlank()) return null
        val candidates = rawSrcset.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return null
        val chosen = candidates.first()
            .substringBefore(" ")
            .substringBefore("\t")
            .trim()
        return chosen.ifEmpty { null }
    }

    private fun normalizeUrl(rawUrl: String?): String? {
        val cleaned = rawUrl?.trim()?.replace("&amp;", "&")?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$BASE_URL$cleaned"
            else -> "$BASE_URL/$cleaned"
        }
    }

    private fun monthNameToNumber(monthName: String): String? {
        return when (monthName.take(3).lowercase()) {
            "jan" -> "01"
            "feb" -> "02"
            "mar" -> "03"
            "apr" -> "04"
            "may" -> "05"
            "jun" -> "06"
            "jul" -> "07"
            "aug" -> "08"
            "sep" -> "09"
            "oct" -> "10"
            "nov" -> "11"
            "dec" -> "12"
            else -> null
        }
    }

    private fun formatToYyyyMmDd(raw: String): String {
        val numericMatch = NUMERIC_DATE_REGEX.find(raw)
        if (numericMatch != null) {
            val p1 = numericMatch.groupValues[1]
            val p2 = numericMatch.groupValues[2]
            val p3 = numericMatch.groupValues[3]

            return if (p1.length == 4) {
                val n2 = p2.toIntOrNull() ?: 1
                val n3 = p3.toIntOrNull() ?: 1
                val (month, day) = if (n2 > 12 && n3 <= 12) Pair(p3, p2) else Pair(p2, p3)
                "$p1-${month.padStart(2, '0')}-${day.padStart(2, '0')}"
            } else if (p3.length == 4 || p3.length == 2) {
                val year = if (p3.length == 2) "20$p3" else p3
                val n1 = p1.toIntOrNull() ?: 1
                val n2 = p2.toIntOrNull() ?: 1
                val (month, day) = if (n1 > 12 && n2 <= 12) Pair(p2, p1) else Pair(p1, p2)
                "$year-${month.padStart(2, '0')}-${day.padStart(2, '0')}"
            } else {
                raw
            }
        }

        val textMatch = TEXT_MONTH_DATE_REGEX.find(raw)
        if (textMatch != null) {
            val m1 = textMatch.groupValues[1]
            if (m1.isNotEmpty()) {
                val month = monthNameToNumber(m1) ?: return raw
                val day = textMatch.groupValues[2].padStart(2, '0')
                val year = textMatch.groupValues[3]
                return "$year-$month-$day"
            } else {
                val day = textMatch.groupValues[4].padStart(2, '0')
                val month = monthNameToNumber(textMatch.groupValues[5]) ?: return raw
                val year = textMatch.groupValues[6]
                return "$year-$month-$day"
            }
        }

        return raw
    }

    private fun containsDatePattern(text: String): Boolean {
        return NUMERIC_DATE_REGEX.containsMatchIn(text) || TEXT_MONTH_DATE_REGEX.containsMatchIn(text)
    }

    private fun extractNextPageUrl(doc: org.jsoup.nodes.Document, currentUrl: String): String? {
        val selectors = listOf(
            "a[rel=next]",
            "ul.pagination li.active + li a",
            ".pagination li.active + li a",
            "a.next",
            "a.page-link[aria-label*=Next]",
            ".pagination a:containsOwn(Next)",
            ".pagination a:containsOwn(»)",
            ".pagination a:containsOwn(>)"
        )
        val normalizedCurrent = currentUrl.substringBefore("#").trimEnd('/')
        for (selector in selectors) {
            val el = doc.selectFirst(selector) ?: continue
            val rawHref = el.attr("abs:href").ifBlank { el.attr("href") }.trim()
            if (rawHref.isBlank() || rawHref == "#" || rawHref.startsWith("javascript:", ignoreCase = true)) continue
            val fullUrl = normalizeUrl(rawHref) ?: continue
            if (fullUrl.substringBefore("#").trimEnd('/') != normalizedCurrent) {
                return fullUrl
            }
        }
        return null
    }

    suspend fun searchSexMex(query: String): SexMexSearchResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return SexMexSearchResult(emptyList(), emptyList(), null)

        val cacheKey = cleanQuery.lowercase()
        searchCache[cacheKey]?.takeIf { it.isValid() }?.let {
            Log.d(TAG, "Serving SexMex search from in-memory cache: $cleanQuery")
            return it.data
        }

        // Direct SexMex URL handling
        if (cleanQuery.startsWith("http://", ignoreCase = true) || cleanQuery.startsWith("https://", ignoreCase = true)) {
            val pageResult = scrapeSexMexPageWithPagination(cleanQuery)
            val result = SexMexSearchResult(emptyList(), pageResult.scenes, pageResult.nextPageUrl)
            searchCache[cacheKey] = CacheEntry(result)
            return result
        }

        val directScenes = mutableListOf<StashScene>()
        var directNextPageUrl: String? = null

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
                    if (href.contains("models.html", ignoreCase = true)) continue
                    val name = link.text().trim()
                    if (name.length > 1) {
                        val normName = name.lowercase().replace(" ", "")
                        if (normName.contains(normQuery) || normQuery.contains(normName)) {
                            val fullUrl = normalizeUrl(href) ?: continue
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
                            scrapeSexMexPageWithPagination(firstModelUrl)
                        }

                        val resolvedModels = modelsDeferred.awaitAll()
                        val resolvedPage = firstModelScenesDeferred.await()
                        SexMexSearchResult(resolvedModels, resolvedPage.scenes, resolvedPage.nextPageUrl)
                    }

                    searchCache[cacheKey] = CacheEntry(result)
                    return result
                } else {
                    // When no performer name matched, parse scenes directly from search page
                    directScenes.addAll(parseScenesFromHtml(doc, searchUrl))
                    directNextPageUrl = extractNextPageUrl(doc, searchUrl)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in SexMex search for '$cleanQuery'", e)
        }

        val finalResult = SexMexSearchResult(emptyList(), directScenes.distinctBy { it.id }, directNextPageUrl)
        searchCache[cacheKey] = CacheEntry(finalResult)
        return finalResult
    }

    suspend fun scrapeSexMexPageWithPagination(pageUrl: String): SexMexScenesPage {
        scenesPageCache[pageUrl]?.takeIf { it.isValid() }?.let {
            return it.data
        }

        val scenes = mutableListOf<StashScene>()
        var nextPageUrl: String? = null
        try {
            val html = NetworkClient.getHtml(pageUrl)
            if (html.isNotEmpty()) {
                val doc = Jsoup.parse(html, pageUrl)
                scenes.addAll(parseScenesFromHtml(doc, pageUrl))
                nextPageUrl = extractNextPageUrl(doc, pageUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping SexMex page: $pageUrl", e)
        }

        val distinctScenes = scenes.distinctBy { it.id }
        val pageResult = SexMexScenesPage(distinctScenes, nextPageUrl)
        if (distinctScenes.isNotEmpty()) {
            scenesPageCache[pageUrl] = CacheEntry(pageResult)
        }
        return pageResult
    }

    suspend fun scrapeSexMexPage(pageUrl: String): List<StashScene> {
        return scrapeSexMexPageWithPagination(pageUrl).scenes
    }

    suspend fun getLatestSexMexScenesWithPagination(): SexMexScenesPage {
        latestScenesPageCache?.takeIf { it.isValid(5 * 60 * 1000L) }?.let {
            return it.data
        }

        val scenes = mutableListOf<StashScene>()
        var nextPageUrl: String? = null
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
                        nextPageUrl = extractNextPageUrl(doc, url)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching latest SexMex scenes from $url", e)
            }
        }

        val distinct = scenes.distinctBy { it.id }
        val pageResult = SexMexScenesPage(distinct, nextPageUrl)
        if (distinct.isNotEmpty()) {
            latestScenesPageCache = CacheEntry(pageResult)
        }
        return pageResult
    }

    suspend fun getLatestSexMexScenes(): List<StashScene> {
        return getLatestSexMexScenesWithPagination().scenes
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
                    avatarUrl = extractCleanUrlFromSrcset(m.groupValues[1]) ?: m.groupValues[1]
                }

                // 2. Fallback to Jsoup only if regex didn't find it
                if (avatarUrl.isNullOrBlank()) {
                    val modelDoc = Jsoup.parse(html, modelUrl)
                    val imgEl = modelDoc.selectFirst("img.model_bio_thumb, .profile-pic img, img[alt*='$modelName'], img[src*='contentthumbs']")
                    avatarUrl = imgEl?.attr("src")?.ifEmpty { null }
                        ?: extractCleanUrlFromSrcset(modelDoc.selectFirst("picture source")?.attr("srcset"))
                }

                avatarUrl = normalizeUrl(avatarUrl)
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
            val rawHref = link.attr("abs:href").ifEmpty { link.attr("href") }
            val sceneUrl = normalizeUrl(rawHref) ?: continue
            if (sceneUrl.isBlank() || seenUrls.contains(sceneUrl)) continue

            // Resolve the card container first so split image/title <a href="/updates/..."> links share full card metadata
            val container = link.parents().firstOrNull {
                it.hasClass("update_thumb") ||
                    it.hasClass("thumb") ||
                    it.hasClass("thumbs") ||
                    it.hasClass("col-lg-4") ||
                    it.hasClass("col-md-4") ||
                    it.hasClass("col-sm-6") ||
                    it.hasClass("scene-card") ||
                    it.tagName() == "article" ||
                    it.selectFirst(".scene-date") != null
            } ?: link.parent()

            // Determine title from link or its enclosing card container
            var rawTitle = link.attr("title").trim()
            if (rawTitle.isEmpty()) {
                rawTitle = link.selectFirst("h3, h4, h5, .scene-title, .update-title, img[alt]")?.text()?.trim()
                    ?.ifEmpty { link.selectFirst("img")?.attr("alt")?.trim() }
                    ?: ""
            }
            if (rawTitle.isEmpty() && container != null) {
                rawTitle = container.selectFirst("h3, h4, h5, .scene-title, .update-title, a[href*='/updates/'][title]")?.let { el ->
                    el.attr("title").trim().ifEmpty { el.text().trim() }
                }?.ifEmpty {
                    container.select("a[href*='/updates/'], a[href*='/tour/updates/']")
                        .map { it.text().trim() }
                        .firstOrNull { it.isNotEmpty() }
                        ?: container.selectFirst("img[alt]")?.attr("alt")?.trim()
                } ?: ""
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

            fun processPart(part: String) {
                val trimmed = part.trim()
                if (containsDatePattern(trimmed)) {
                    extractedDateFromTitle = formatToYyyyMmDd(trimmed)
                } else if (trimmed.isNotEmpty()) {
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
                val nonDateParts = titleParts.filter { !containsDatePattern(it) && it.isNotBlank() }
                if (nonDateParts.isNotEmpty()) {
                    cleanTitle = nonDateParts[0].trim()
                }
            } else {
                // Single part, check if it contains a date
                val m = NUMERIC_DATE_REGEX.find(rawTitle) ?: TEXT_MONTH_DATE_REGEX.find(rawTitle)
                if (m != null) {
                    extractedDateFromTitle = formatToYyyyMmDd(m.value)
                    cleanTitle = rawTitle.replace(m.value, "").replace(Regex("""\s+-\s+|\s+\.\s+|\s+&\s+"""), " ").trim()
                }
            }

            // Cover thumbnail (supports srcset with multiple descriptors + container fallback)
            val rawCoverCandidate = extractCleanUrlFromSrcset(link.selectFirst("picture source")?.attr("srcset"))
                ?: link.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }?.takeIf { it.isNotBlank() }
                ?: extractCleanUrlFromSrcset(container?.selectFirst("picture source")?.attr("srcset"))
                ?: container?.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }?.takeIf { it.isNotBlank() }

            val cleanCoverUrl = normalizeUrl(rawCoverCandidate)

            // Date and details from sibling / parent container
            var dateStr = container?.selectFirst(".scene-date, time, .date, .update-date")?.text()?.trim()
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
                    if (cleanName.isNotBlank() && cleanName.length > 2 && !cleanName.equals(cleanTitle, ignoreCase = true)) {
                        performersList.add(cleanName)
                    }
                }
            }

            val performers = performersList.distinct().map {
                val slug = it.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                StashPerformer(
                    id = "sexmex:${slug.ifBlank { UUID.randomUUID().toString() }}",
                    name = it,
                    gender = "FEMALE",
                    imageUrl = null
                )
            }

            if (cleanTitle.isNotBlank() && !cleanCoverUrl.isNullOrBlank()) {
                seenUrls.add(sceneUrl)
                scenes.add(
                    StashScene(
                        id = sceneUrl,
                        title = cleanTitle,
                        details = descr ?: "SexMex Official Scene",
                        date = dateStr?.ifBlank { null },
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
            var coverUrl = extractCleanUrlFromSrcset(FAST_PICTURE_SRCSET_REGEX.find(html)?.groupValues?.getOrNull(1))
                ?: FAST_CONTENTTHUMBS_REGEX.find(html)?.groupValues?.getOrNull(1)

            // 2. Jsoup Fallback
            if (coverUrl.isNullOrEmpty()) {
                val doc = Jsoup.parse(html, sceneUrl)
                coverUrl = extractCleanUrlFromSrcset(doc.selectFirst("picture source")?.attr("srcset"))
                if (coverUrl.isNullOrEmpty()) {
                    coverUrl = doc.selectFirst("img[src*='contentthumbs'], .update_thumb img, img.scene-cover")?.attr("src")
                }
                if (coverUrl.isNullOrEmpty()) {
                    coverUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")
                }
            }

            return normalizeUrl(coverUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching fresh cover for $sceneUrl", e)
            return null
        }
    }
}
