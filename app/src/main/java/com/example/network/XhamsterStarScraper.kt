package com.example.network

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

data class XhamsterUnifiedSearchResult(
    val results: List<StashPerformer>,
    val scenes: List<StashScene>,
    val nextPageUrl: String? = null
)

data class XhamsterScenesPage(
    val scenes: List<StashScene>,
    val nextPageUrl: String? = null,
    val entity: StashPerformer? = null
)

object XhamsterStarScraper {
    private const val TAG = "XhamsterStarScraper"
    private const val BASE_URL = "https://xhamster.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val ENTITY_TYPE_ACTOR = "ACTOR"
    const val ENTITY_TYPE_STUDIO = "STUDIO"

    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isValid(ttlMs: Long = 10 * 60 * 1000L): Boolean =
            System.currentTimeMillis() - timestamp < ttlMs
    }

    private data class HtmlFetchResult(
        val status: Int,
        val body: String,
        val finalUrl: String
    )

    private val searchCache = ConcurrentHashMap<String, CacheEntry<XhamsterUnifiedSearchResult>>()
    private val scenesPageCache = ConcurrentHashMap<String, CacheEntry<XhamsterScenesPage>>()
    private val avatarCache = ConcurrentHashMap<String, String>()
    private val knownEntitiesCache = ConcurrentHashMap<String, StashPerformer>()

    @Volatile
    private var cachedDirectoryActors: List<StashPerformer> = emptyList()

    @Volatile
    private var cachedDirectoryStudios: List<StashPerformer> = emptyList()

    private val ageCookiesSeeded = AtomicBoolean(false)

    private val RELATIVE_DATE_REGEX = Regex(
        """(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""",
        RegexOption.IGNORE_CASE
    )

    private val BLOCKED_ENTITY_WORDS = setOf(
        "pornstars", "models", "creators", "channels", "categories", "xhamster",
        "suggest", "verified", "all pornstars", "all channels", "trending", "newest"
    )

    fun isStudioEntity(performer: StashPerformer): Boolean {
        return performer.disambiguation.equals(ENTITY_TYPE_STUDIO, ignoreCase = true) ||
            performer.id.contains("/channels/", ignoreCase = true)
    }

    fun isXhamsterUrl(url: String?): Boolean {
        val u = url?.trim()?.lowercase(Locale.US) ?: return false
        return u.contains("xhamster.") || u.contains("xh.video")
    }

    private fun ensureAgeCookiesSeeded() {
        if (ageCookiesSeeded.compareAndSet(false, true)) {
            val cookies = mapOf(
                "age_verified" to "1",
                "x_age_Disclaimer" to "1",
                "parental-control" to "0"
            )
            for ((name, value) in cookies) {
                NetworkClient.seedCookie(BASE_URL, name, value, "xhamster.com")
                NetworkClient.seedCookie(BASE_URL, name, value, "www.xhamster.com")
            }
        }
    }

    private fun normalizeUrl(rawUrl: String?): String? {
        val cleaned = rawUrl?.trim()
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$BASE_URL$cleaned"
            else -> "$BASE_URL/$cleaned"
        }
    }

    private fun normalizeEntityBaseUrl(rawUrl: String): String {
        val full = normalizeUrl(rawUrl) ?: return BASE_URL
        return full
            .substringBefore("?")
            .substringBefore("#")
            .trimEnd('/')
            .removeSuffix("/newest")
            .removeSuffix("/best")
            .removeSuffix("/hd")
            .removeSuffix("/videos")
    }

    private fun extractEntitySlug(urlOrId: String): String {
        val normalized = normalizeEntityBaseUrl(urlOrId).lowercase()
        return normalized.substringAfterLast('/').trim()
    }

    private fun isValidAvatarOrLogoUrl(url: String?): Boolean {
        val u = url?.trim()?.lowercase() ?: return false
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        if (u.contains("blank.") || u.contains("default_") || u.contains("no-avatar") || u.contains("placeholder")) {
            return false
        }
        return true
    }

    private fun normalizeCoverUrl(rawUrl: String?): String? {
        val normalized = normalizeUrl(rawUrl) ?: return null
        if (normalized.startsWith("data:", ignoreCase = true)) return null
        val lower = normalized.lowercase()
        if (lower.contains("blank.") || lower.contains("placeholder") || lower.contains("/sprites/")) {
            return null
        }
        return normalized
    }

    private fun cacheKnownEntity(entity: StashPerformer) {
        val cleanName = entity.name.trim()
        if (cleanName.length < 2) return
        val key = cleanName.lowercase()
        if (key in BLOCKED_ENTITY_WORDS) return
        val existing = knownEntitiesCache[key]
        if (existing == null || (existing.imageUrl.isNullOrBlank() && !entity.imageUrl.isNullOrBlank())) {
            knownEntitiesCache[key] = entity
        }
        if (!entity.imageUrl.isNullOrBlank()) {
            avatarCache[normalizeEntityBaseUrl(entity.id).lowercase()] = entity.imageUrl
        }
    }

    private suspend fun fetchHtmlWithFinalUrl(url: String): HtmlFetchResult = withContext(Dispatchers.IO) {
        ensureAgeCookiesSeeded()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "$BASE_URL/")
            .build()

        val resp = NetworkClient.okHttpClient.newCall(req).await()
        val code = resp.code
        val finalUrl = resp.request.url.toString()
        val body = resp.body?.string().orEmpty()
        resp.close()
        HtmlFetchResult(code, body, finalUrl)
    }

    private suspend fun fetchHtml(url: String): Pair<Int, String> {
        val res = fetchHtmlWithFinalUrl(url)
        return Pair(res.status, res.body)
    }

    fun extractWindowInitialsJson(html: String): JSONObject? {
        val marker = "window.initials"
        var searchStart = 0
        while (true) {
            val idx = html.indexOf(marker, searchStart)
            if (idx == -1) break
            val eqIdx = html.indexOf('=', idx + marker.length)
            if (eqIdx == -1) break
            val braceStart = html.indexOf('{', eqIdx + 1)
            if (braceStart == -1) break

            var depth = 0
            var inString = false
            var escaped = false
            var endIdx = -1

            for (i in braceStart until html.length) {
                val c = html[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '"') {
                        inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                endIdx = i
                                break
                            }
                        }
                    }
                }
            }

            if (endIdx > braceStart) {
                val jsonCandidate = html.substring(braceStart, endIdx + 1)
                try {
                    return JSONObject(jsonCandidate)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse window.initials candidate: ${e.message}")
                }
            }
            searchStart = braceStart + 1
        }
        return null
    }

    private fun formatEpochSecondsToDate(epochSeconds: Long): String? {
        if (epochSeconds <= 0L) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.format(Date(epochSeconds * 1000L))
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeDateString(rawDate: String?): String? {
        val cleaned = rawDate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (cleaned.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return cleaned
        if (cleaned.length >= 10 && cleaned.substring(0, 10).matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
            return cleaned.substring(0, 10)
        }
        cleaned.toLongOrNull()?.let { epoch ->
            val secs = if (epoch > 10_000_000_000L) epoch / 1000L else epoch
            return formatEpochSecondsToDate(secs)
        }
        val relMatch = RELATIVE_DATE_REGEX.find(cleaned)
        if (relMatch != null) {
            val amount = relMatch.groupValues[1].toIntOrNull() ?: return null
            val unit = relMatch.groupValues[2].lowercase(Locale.US)
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            when {
                unit.startsWith("second") -> cal.add(Calendar.SECOND, -amount)
                unit.startsWith("minute") -> cal.add(Calendar.MINUTE, -amount)
                unit.startsWith("hour") -> cal.add(Calendar.HOUR_OF_DAY, -amount)
                unit.startsWith("day") -> cal.add(Calendar.DAY_OF_YEAR, -amount)
                unit.startsWith("week") -> cal.add(Calendar.WEEK_OF_YEAR, -amount)
                unit.startsWith("month") -> cal.add(Calendar.MONTH, -amount)
                unit.startsWith("year") -> cal.add(Calendar.YEAR, -amount)
            }
            val outFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return outFormat.format(cal.time)
        }
        return null
    }

    private fun humanizeSlug(slug: String): String {
        return slug
            .substringBefore("?")
            .substringBefore("#")
            .trim('/')
            .substringAfterLast('/')
            .replace('-', ' ')
            .replace('_', ' ')
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
            }
    }

    // ==========================================
    // FUZZY & PHONETIC MATCHING ENGINE
    // ==========================================

    private fun collapseAlphanumeric(input: String): String =
        input.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    private fun phoneticSkeleton(input: String): String {
        val lower = input.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
        if (lower.isEmpty()) return ""
        val normalized = lower
            .replace("ph", "f")
            .replace("ck", "k")
            .replace("qu", "kw")
            .replace("x", "ks")
            .replace("z", "s")
            .replace("c", "k")
            .replace("v", "f")
            .replace("y", "i")
        val sb = StringBuilder()
        var prev = ' '
        for (idx in normalized.indices) {
            val ch = normalized[idx]
            val isVowel = ch == 'a' || ch == 'e' || ch == 'i' || ch == 'o' || ch == 'u'
            if (idx == 0) {
                sb.append(ch)
                prev = ch
            } else if (!isVowel && ch != prev) {
                sb.append(ch)
                prev = ch
            }
        }
        return sb.toString()
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            for (j in 0..b.length) {
                prev[j] = curr[j]
            }
        }
        return prev[b.length]
    }

    fun scoreNameSimilarity(candidateName: String, query: String): Int {
        val cleanCandidate = candidateName.trim().lowercase(Locale.US)
        val cleanQuery = query.trim().lowercase(Locale.US)
        if (cleanCandidate.isEmpty() || cleanQuery.isEmpty()) return 0

        if (cleanCandidate == cleanQuery) return 100

        val collapsedCandidate = collapseAlphanumeric(cleanCandidate)
        val collapsedQuery = collapseAlphanumeric(cleanQuery)
        if (collapsedCandidate.isEmpty() || collapsedQuery.isEmpty()) return 0

        if (collapsedCandidate == collapsedQuery) return 98
        if (cleanCandidate.startsWith(cleanQuery) || collapsedCandidate.startsWith(collapsedQuery)) return 92
        if (cleanCandidate.contains(cleanQuery) || collapsedCandidate.contains(collapsedQuery)) return 84
        if (collapsedQuery.contains(collapsedCandidate) && collapsedCandidate.length >= 4) return 78

        val candidateTokens = cleanCandidate.split(Regex("[\\s\\-_]+")).filter { it.isNotBlank() }
        val queryTokens = cleanQuery.split(Regex("[\\s\\-_]+")).filter { it.isNotBlank() }

        if (queryTokens.isNotEmpty() && candidateTokens.isNotEmpty()) {
            var matchedTokens = 0
            for (qt in queryTokens) {
                val bestTokenDist = candidateTokens.minOf { ct ->
                    if (ct == qt) 0
                    else if (ct.startsWith(qt) || qt.startsWith(ct)) 1
                    else levenshteinDistance(ct, qt)
                }
                val allowedDist = when {
                    qt.length <= 3 -> 0
                    qt.length <= 5 -> 1
                    else -> 2
                }
                if (bestTokenDist <= allowedDist) {
                    matchedTokens++
                }
            }
            if (matchedTokens == queryTokens.size && queryTokens.size >= 2) {
                return 88
            } else if (matchedTokens > 0 && queryTokens.size == 1 && candidateTokens.any { it.startsWith(queryTokens[0]) }) {
                return 86
            }
        }

        val maxLen = max(collapsedCandidate.length, collapsedQuery.length)
        val dist = levenshteinDistance(collapsedCandidate, collapsedQuery)
        val similarityRatio = 1.0 - (dist.toDouble() / maxLen.toDouble())

        val skelCandidate = phoneticSkeleton(cleanCandidate)
        val skelQuery = phoneticSkeleton(cleanQuery)
        val phoneticMatch = skelCandidate.isNotEmpty() && skelCandidate.length >= 3 && (
            skelCandidate == skelQuery ||
                skelCandidate.startsWith(skelQuery) ||
                levenshteinDistance(skelCandidate, skelQuery) <= 1
            )

        return when {
            dist <= 1 && maxLen >= 4 -> 89
            dist <= 2 && maxLen >= 6 -> 82
            dist <= 3 && maxLen >= 8 -> 75
            phoneticMatch && similarityRatio >= 0.55 -> 76
            similarityRatio >= 0.72 -> (similarityRatio * 100).toInt()
            similarityRatio >= 0.60 -> (similarityRatio * 90).toInt()
            else -> 0
        }
    }

    // ==========================================
    // ENTITY PARSING (ACTORS & STUDIOS UNIFIED)
    // ==========================================

    private fun parseCreatorsSuggestionsFromInitials(initials: JSONObject?): List<StashPerformer> {
        if (initials == null) return emptyList()
        val results = LinkedHashMap<String, StashPerformer>()

        fun addEntity(rawUrl: String?, rawName: String?, rawLogo: String?, isStudio: Boolean) {
            val baseUrl = normalizeUrl(rawUrl)?.let { normalizeEntityBaseUrl(it) } ?: return
            val lowerUrl = baseUrl.lowercase(Locale.US)
            if (!lowerUrl.contains("/pornstars/") &&
                !lowerUrl.contains("/creators/") &&
                !lowerUrl.contains("/celebrities/") &&
                !lowerUrl.contains("/channels/")
            ) {
                return
            }
            val name = Parser.unescapeEntities(rawName?.trim().orEmpty(), false)
                .ifBlank { humanizeSlug(baseUrl) }
            if (name.length < 2 || name.lowercase(Locale.US) in BLOCKED_ENTITY_WORDS) return

            val entityType = if (isStudio || lowerUrl.contains("/channels/")) {
                ENTITY_TYPE_STUDIO
            } else {
                ENTITY_TYPE_ACTOR
            }
            val validImage = normalizeUrl(rawLogo)?.takeIf { isValidAvatarOrLogoUrl(it) }
                ?: avatarCache[lowerUrl]

            val existing = results[lowerUrl]
            if (existing == null || (existing.imageUrl.isNullOrBlank() && !validImage.isNullOrBlank())) {
                val perf = StashPerformer(
                    id = baseUrl,
                    name = name,
                    disambiguation = entityType,
                    gender = if (entityType == ENTITY_TYPE_ACTOR) "FEMALE" else null,
                    imageUrl = validImage
                )
                results[lowerUrl] = perf
                cacheKnownEntity(perf)
            }
        }

        // 1. searchVideoSuggestions.creators (contains both pornstars/creators AND channels!)
        val creatorsArr = initials.optJSONObject("searchVideoSuggestions")?.optJSONArray("creators")
        if (creatorsArr != null) {
            for (i in 0 until creatorsArr.length()) {
                val obj = creatorsArr.optJSONObject(i) ?: continue
                val type = obj.optString("type").lowercase(Locale.US)
                val link = obj.optString("link").ifBlank { obj.optString("pageURL").ifBlank { obj.optString("url") } }
                val name = obj.optString("name").ifBlank { obj.optString("title") }
                val logo = obj.optString("logo")
                    .ifBlank { obj.optString("thumbUrl") }
                    .ifBlank { obj.optString("logoThumbUrl") }
                    .ifBlank { obj.optString("imageThumbUrl") }
                val isStudio = type == "channel" || type == "studio" || type == "brand" || link.contains("/channels/", ignoreCase = true)
                addEntity(link, name, logo, isStudio)
            }
        }

        // 2. layoutPage.pornstarListProps.pornstars (from /pornstars page)
        val pornstarsArr = initials.optJSONObject("layoutPage")
            ?.optJSONObject("pornstarListProps")
            ?.optJSONArray("pornstars")
            ?: initials.optJSONObject("pornstarListProps")?.optJSONArray("pornstars")
        if (pornstarsArr != null) {
            for (i in 0 until pornstarsArr.length()) {
                val obj = pornstarsArr.optJSONObject(i) ?: continue
                val link = obj.optString("pageURL").ifBlank { obj.optString("url").ifBlank { obj.optString("link") } }
                val name = obj.optString("name").ifBlank { obj.optString("title") }
                val logo = obj.optString("imageThumbUrl")
                    .ifBlank { obj.optString("logoThumbUrl") }
                    .ifBlank { obj.optString("thumbUrl") }
                    .ifBlank { obj.optString("logo") }
                addEntity(link, name, logo, isStudio = false)
            }
        }

        // 3. layoutPage.channels (from /channels page)
        val layoutPage = initials.optJSONObject("layoutPage")
        val channelsArr = layoutPage?.optJSONArray("channels")
            ?: layoutPage?.optJSONObject("channelListProps")?.optJSONArray("channels")
            ?: initials.optJSONArray("channels")
        if (channelsArr != null) {
            for (i in 0 until channelsArr.length()) {
                val obj = channelsArr.optJSONObject(i) ?: continue
                val link = obj.optString("channelURL")
                    .ifBlank { obj.optString("pageURL") }
                    .ifBlank { obj.optString("url") }
                    .ifBlank { obj.optString("link") }
                    .ifBlank {
                        val slug = obj.optString("slug").ifBlank { obj.optString("code") }
                        if (slug.isNotBlank()) "$BASE_URL/channels/$slug" else ""
                    }
                val name = obj.optString("name").ifBlank { obj.optString("title") }
                val logo = obj.optString("logoUrl")
                    .ifBlank { obj.optString("logoThumbUrl") }
                    .ifBlank { obj.optString("thumbUrl") }
                    .ifBlank { obj.optString("logo") }
                    .ifBlank { obj.optString("imageThumbUrl") }
                addEntity(link, name, logo, isStudio = true)
            }
        }

        // 4. Alphabetical lists on /pornstars and /channels
        val pornstarLetters = layoutPage?.optJSONObject("pornstarNamesProps")?.optJSONArray("channelList")
        if (pornstarLetters != null) {
            for (i in 0 until pornstarLetters.length()) {
                val group = pornstarLetters.optJSONObject(i) ?: continue
                val buttons = group.optJSONArray("channelButtonList") ?: continue
                for (j in 0 until buttons.length()) {
                    val btn = buttons.optJSONObject(j) ?: continue
                    val link = btn.optString("pageURL").ifBlank { btn.optString("url").ifBlank { btn.optString("link") } }
                    val name = btn.optString("name").ifBlank { btn.optString("title") }
                    val logo = btn.optString("logoThumbUrl").ifBlank { btn.optString("thumbUrl") }
                    addEntity(link, name, logo, isStudio = false)
                }
            }
        }

        val channelLetters = layoutPage?.optJSONObject("channelsAlphabetProps")?.optJSONArray("letterList")
        if (channelLetters != null) {
            for (i in 0 until channelLetters.length()) {
                val group = channelLetters.optJSONObject(i) ?: continue
                val items = group.optJSONArray("channelList")
                    ?: group.optJSONArray("channelButtonList")
                    ?: group.optJSONArray("items")
                    ?: continue
                for (j in 0 until items.length()) {
                    val btn = items.optJSONObject(j) ?: continue
                    val link = btn.optString("channelURL")
                        .ifBlank { btn.optString("pageURL") }
                        .ifBlank { btn.optString("url") }
                        .ifBlank { btn.optString("link") }
                    val name = btn.optString("name").ifBlank { btn.optString("title") }
                    val logo = btn.optString("logoUrl").ifBlank { btn.optString("thumbUrl") }
                    addEntity(link, name, logo, isStudio = true)
                }
            }
        }

        return results.values.toList()
    }

    private fun parseEntitiesFromDom(doc: Document): List<StashPerformer> {
        val results = LinkedHashMap<String, StashPerformer>()
        val anchors = doc.select(
            "a[href*=/pornstars/], a[href*=/creators/], a[href*=/celebrities/], a[href*=/channels/]"
        )
        for (a in anchors) {
            val rawHref = a.attr("href").trim()
            if (rawHref.isBlank()) continue
            val baseUrl = normalizeUrl(rawHref)?.let { normalizeEntityBaseUrl(it) } ?: continue
            val lowerUrl = baseUrl.lowercase(Locale.US)
            val slug = extractEntitySlug(baseUrl)
            if (slug.isBlank() || slug in BLOCKED_ENTITY_WORDS || slug.length < 2) continue
            if (lowerUrl.endsWith("/pornstars") || lowerUrl.endsWith("/creators") ||
                lowerUrl.endsWith("/celebrities") || lowerUrl.endsWith("/channels")
            ) {
                continue
            }

            val isStudio = lowerUrl.contains("/channels/")
            val cardRoot = a.closest("div.pornstar-thumb-container, div.channel-thumb, div.creator-thumb, li, article") ?: a
            val imgEl = a.selectFirst("img") ?: cardRoot.selectFirst("img")
            val rawImg = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("data-lazy")?.takeIf { it.isNotBlank() }
            val validImg = normalizeUrl(rawImg)?.takeIf { isValidAvatarOrLogoUrl(it) } ?: avatarCache[lowerUrl]

            val rawName = a.selectFirst(".pornstar-thumb-container__info-title, .channel-thumb__title, [class*=title], [class*=name]")?.text()?.trim()
                ?: imgEl?.attr("alt")?.trim()
                ?: a.attr("title").trim()
                .ifBlank { a.text().trim() }
                .ifBlank { humanizeSlug(slug) }

            val cleanName = rawName
                .replace(Regex("""\s*\d+\s*(videos?|scenes?|photos?)\b.*""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { humanizeSlug(slug) }

            if (cleanName.length < 2 || cleanName.lowercase(Locale.US) in BLOCKED_ENTITY_WORDS) continue

            val entityType = if (isStudio) ENTITY_TYPE_STUDIO else ENTITY_TYPE_ACTOR
            val existing = results[lowerUrl]
            if (existing == null || (existing.imageUrl.isNullOrBlank() && !validImg.isNullOrBlank())) {
                val perf = StashPerformer(
                    id = baseUrl,
                    name = cleanName,
                    disambiguation = entityType,
                    gender = if (isStudio) null else "FEMALE",
                    imageUrl = validImg
                )
                results[lowerUrl] = perf
                cacheKnownEntity(perf)
            }
        }
        return results.values.toList()
    }

    private fun extractEntityHeaderFromPage(
        initials: JSONObject?,
        doc: Document,
        fallbackUrl: String,
        knownEntity: StashPerformer?
    ): StashPerformer {
        val baseUrl = normalizeEntityBaseUrl(fallbackUrl)
        val lowerUrl = baseUrl.lowercase(Locale.US)
        val isStudio = (knownEntity != null && isStudioEntity(knownEntity)) || lowerUrl.contains("/channels/")

        var name = knownEntity?.name.orEmpty()
        var imageUrl = knownEntity?.imageUrl

        if (initials != null) {
            val banner = initials.optJSONObject("landingBannerComponent")
                ?: initials.optJSONObject("channelInfo")
                ?: initials.optJSONObject("promoPage")
                ?: initials.optJSONObject("entity")
            if (banner != null) {
                val bName = banner.optString("name").ifBlank { banner.optString("title") }.trim()
                if (bName.isNotBlank() && name.isBlank()) {
                    name = bName
                }
                val bImg = banner.optString("thumbUrl")
                    .ifBlank { banner.optString("logoUrl") }
                    .ifBlank { banner.optString("avatarUrl") }
                    .ifBlank { banner.optString("logo") }
                    .ifBlank { banner.optString("imageThumbUrl") }
                    .ifBlank { banner.optString("imageUrl") }
                val validBImg = normalizeUrl(bImg)?.takeIf { isValidAvatarOrLogoUrl(it) }
                if (!validBImg.isNullOrBlank()) {
                    imageUrl = validBImg
                }
            }
        }

        if (imageUrl.isNullOrBlank()) {
            val domAvatar = doc.selectFirst(
                ".landing-info__logo img, .landing-banner img, .channel-header img, .pornstar-header img"
            )?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
            } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            val validDomImg = normalizeUrl(domAvatar)?.takeIf { isValidAvatarOrLogoUrl(it) }
            if (!validDomImg.isNullOrBlank()) {
                imageUrl = validDomImg
            }
        }

        if (name.isBlank()) {
            val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            name = h1
                .replace(Regex("""\s*(Porn Videos|Videos|Channel|Pornstar).*""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { humanizeSlug(baseUrl) }
        }

        val entity = StashPerformer(
            id = baseUrl,
            name = name,
            disambiguation = if (isStudio) ENTITY_TYPE_STUDIO else ENTITY_TYPE_ACTOR,
            gender = if (isStudio) null else "FEMALE",
            imageUrl = imageUrl ?: avatarCache[lowerUrl]
        )
        cacheKnownEntity(entity)
        return entity
    }

    // ==========================================
    // SCENE EXTRACTION FROM INITIALS & DOM
    // ==========================================

    private fun findVideoThumbArrayInInitials(initials: JSONObject?): JSONArray? {
        if (initials == null) return null

        // Check standard xHamster containers in priority order
        val candidates = listOfNotNull(
            initials.optJSONObject("searchResult")?.optJSONArray("videoThumbProps"),
            initials.optJSONObject("trendingVideoSectionComponent")
                ?.optJSONObject("videoListProps")?.optJSONArray("videoThumbProps"),
            initials.optJSONObject("newestVideoSectionComponent")
                ?.optJSONObject("videoListProps")?.optJSONArray("videoThumbProps"),
            initials.optJSONObject("layoutPage")
                ?.optJSONObject("videoListProps")?.optJSONArray("videoThumbProps"),
            initials.optJSONObject("layoutPage")
                ?.optJSONObject("trendingVideoListProps")?.optJSONArray("videoThumbProps"),
            initials.optJSONObject("videoListProps")?.optJSONArray("videoThumbProps"),
            initials.optJSONArray("videoThumbProps")
        )
        for (arr in candidates) {
            if (arr.length() > 0) return arr
        }

        // Deep scan top-level objects for any nested videoThumbProps array
        val keys = initials.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val childObj = initials.optJSONObject(key) ?: continue
            val directArr = childObj.optJSONArray("videoThumbProps")
            if (directArr != null && directArr.length() > 0) return directArr
            val nestedProps = childObj.optJSONObject("videoListProps")?.optJSONArray("videoThumbProps")
            if (nestedProps != null && nestedProps.length() > 0) return nestedProps
        }
        return null
    }

    private fun extractNextPageUrl(initials: JSONObject?, doc: Document, currentUrl: String): String? {
        // 1. Check DOM pagination link first (most reliable across all xHamster layouts)
        val nextHref = doc.selectFirst(
            "a[data-page=next][href], li.next a[href], a.prev-next-list-link--next[href], link[rel=next][href]"
        )?.attr("href")?.trim()
        if (!nextHref.isNullOrBlank() && !nextHref.startsWith("javascript:", ignoreCase = true) && nextHref != "#") {
            val resolved = normalizeUrl(nextHref)
            if (!resolved.isNullOrBlank() && !resolved.equals(currentUrl, ignoreCase = true)) {
                return resolved
            }
        }

        // 2. Check window.initials pagination objects
        if (initials != null) {
            val pagingCandidates = listOfNotNull(
                initials.optJSONObject("paginationComponent"),
                initials.optJSONObject("paginationProps"),
                initials.optJSONObject("layoutPage")?.optJSONObject("paginationProps"),
                initials.optJSONObject("entity")?.optJSONObject("paging"),
                initials.optJSONObject("paging")
            )
            for (paging in pagingCandidates) {
                val activePage = paging.optInt("active", paging.optInt("currentPage", 1))
                val maxPages = paging.optInt("maxPages", paging.optInt("lastPage", paging.optInt("totalPages", 1)))
                val nextPageVal = paging.optInt("next", if (activePage < maxPages) activePage + 1 else -1)
                if (nextPageVal > activePage) {
                    val template = paging.optString("pageLinkTemplate").ifBlank { paging.optString("urlTemplate") }
                    if (template.isNotBlank() && template.contains("{#}")) {
                        return normalizeUrl(template.replace("{#}", nextPageVal.toString()))
                    }
                    val nextLinkObj = paging.optString("nextLink").ifBlank { paging.optString("nextPageUrl") }
                    if (nextLinkObj.isNotBlank()) {
                        return normalizeUrl(nextLinkObj)
                    }
                }
            }
        }
        return null
    }

    private fun matchPerformersInTitle(title: String): List<StashPerformer> {
        if (title.isBlank() || knownEntitiesCache.isEmpty()) return emptyList()
        val lowerTitle = title.lowercase(Locale.US)
        val matched = mutableListOf<StashPerformer>()
        for ((nameKey, entity) in knownEntitiesCache) {
            if (isStudioEntity(entity)) continue
            if (nameKey.length < 4 || !nameKey.contains(" ")) continue
            val idx = lowerTitle.indexOf(nameKey)
            if (idx >= 0) {
                val beforeOk = idx == 0 || !lowerTitle[idx - 1].isLetterOrDigit()
                val afterIdx = idx + nameKey.length
                val afterOk = afterIdx >= lowerTitle.length || !lowerTitle[afterIdx].isLetterOrDigit()
                if (beforeOk && afterOk) {
                    matched.add(entity)
                }
            }
        }
        return matched.distinctBy { it.id }
    }

    private fun parseScenesFromInitials(
        initials: JSONObject?,
        knownEntity: StashPerformer?,
        discoveredEntities: List<StashPerformer>
    ): List<StashScene> {
        val arr = findVideoThumbArrayInInitials(initials) ?: return emptyList()
        val scenes = mutableListOf<StashScene>()
        val seenIds = HashSet<String>()

        val isKnownStudio = knownEntity != null && isStudioEntity(knownEntity)
        val isKnownActor = knownEntity != null && !isStudioEntity(knownEntity)

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val rawPageUrl = item.optString("pageURL").ifBlank { item.optString("url") }
            val videoUrl = normalizeUrl(rawPageUrl) ?: continue
            if (!videoUrl.contains("/videos/", ignoreCase = true) && !videoUrl.contains("xh.video", ignoreCase = true)) {
                continue
            }

            val rawId = item.optString("id").ifBlank {
                videoUrl.substringAfterLast('/').substringBefore('?').substringBefore('#')
            }
            val sceneId = "xhamster:$rawId"
            if (!seenIds.add(sceneId)) continue

            val title = Parser.unescapeEntities(item.optString("title").trim(), false)
                .ifBlank { humanizeSlug(rawId) }

            val coverUrl = normalizeCoverUrl(item.optString("imageURL"))
                ?: normalizeCoverUrl(item.optString("thumbURL"))
                ?: normalizeCoverUrl(item.optString("previewThumbURL"))

            val createdEpoch = item.optLong("created", 0L)
            val dateStr = if (createdEpoch > 0L) {
                formatEpochSecondsToDate(createdEpoch)
            } else {
                normalizeDateString(item.optString("uploadDate").ifBlank { item.optString("date") })
            }

            val is4k = item.optBoolean("isUHD", false) ||
                item.optString("resolution").contains("2160", ignoreCase = true) ||
                item.optString("resolution").contains("4k", ignoreCase = true) ||
                title.contains(Regex("""\b(4k|2160p|uhd)\b""", RegexOption.IGNORE_CASE))

            // Extract Studio / Channel & Performers from card landing / pornstars
            var studioId: String? = if (isKnownStudio) knownEntity?.id else null
            var studioName: String? = if (isKnownStudio) knownEntity?.name else null
            var studioLogo: String? = if (isKnownStudio) knownEntity?.imageUrl else null

            val performers = LinkedHashMap<String, StashPerformer>()
            if (isKnownActor && knownEntity != null) {
                performers[knownEntity.name.lowercase(Locale.US)] = knownEntity
            }

            val landing = item.optJSONObject("landing")
            if (landing != null) {
                val lType = landing.optString("type").lowercase(Locale.US)
                val lName = Parser.unescapeEntities(landing.optString("name").trim(), false)
                val lLink = normalizeUrl(landing.optString("link"))?.let { normalizeEntityBaseUrl(it) }
                val lLogo = normalizeUrl(landing.optString("logo"))?.takeIf { isValidAvatarOrLogoUrl(it) }

                if (lName.isNotBlank()) {
                    val isChannelLanding = lType == "channel" || lType == "studio" || lType == "brand" ||
                        (lLink != null && lLink.contains("/channels/", ignoreCase = true))
                    val isPerformerLanding = lType == "pornstar" || lType == "creator" || lType == "celebrity" ||
                        (lLink != null && (lLink.contains("/pornstars/", ignoreCase = true) || lLink.contains("/creators/", ignoreCase = true)))

                    if (isChannelLanding) {
                        studioId = lLink ?: "xhamster:channel:${lName.lowercase(Locale.US)}"
                        studioName = lName
                        if (!lLogo.isNullOrBlank()) {
                            studioLogo = lLogo
                        }
                        if (lLink != null) {
                            cacheKnownEntity(
                                StashPerformer(
                                    id = lLink,
                                    name = lName,
                                    disambiguation = ENTITY_TYPE_STUDIO,
                                    imageUrl = lLogo
                                )
                            )
                        }
                    } else if (isPerformerLanding) {
                        val perfId = lLink ?: "xhamster:star:${lName.lowercase(Locale.US)}"
                        val perf = StashPerformer(
                            id = perfId,
                            name = lName,
                            disambiguation = ENTITY_TYPE_ACTOR,
                            gender = "FEMALE",
                            imageUrl = lLogo
                        )
                        performers[lName.lowercase(Locale.US)] = perf
                        if (lLink != null) cacheKnownEntity(perf)
                    }
                }
            }

            // Check card-level pornstars/creators array if present
            val cardStars = item.optJSONArray("pornstars") ?: item.optJSONArray("creators") ?: item.optJSONArray("actors")
            if (cardStars != null) {
                for (j in 0 until cardStars.length()) {
                    val starObj = cardStars.optJSONObject(j)
                    if (starObj != null) {
                        val sName = Parser.unescapeEntities(starObj.optString("name").trim(), false)
                        val sLink = normalizeUrl(starObj.optString("pageURL").ifBlank { starObj.optString("link").ifBlank { starObj.optString("url") } })
                            ?.let { normalizeEntityBaseUrl(it) }
                        val sLogo = normalizeUrl(starObj.optString("thumbUrl").ifBlank { starObj.optString("logo") })
                            ?.takeIf { isValidAvatarOrLogoUrl(it) }
                        if (sName.isNotBlank()) {
                            val perf = StashPerformer(
                                id = sLink ?: "xhamster:star:${sName.lowercase(Locale.US)}",
                                name = sName,
                                disambiguation = ENTITY_TYPE_ACTOR,
                                gender = "FEMALE",
                                imageUrl = sLogo ?: sLink?.let { avatarCache[it.lowercase(Locale.US)] }
                            )
                            performers.putIfAbsent(sName.lowercase(Locale.US), perf)
                        }
                    }
                }
            }

            // Match discovered performers or cached performers mentioned in the scene title
            if (performers.isEmpty() && title.isNotBlank()) {
                val lowerTitle = title.lowercase(Locale.US)
                for (cand in discoveredEntities) {
                    if (isStudioEntity(cand)) continue
                    val cName = cand.name.trim()
                    if (cName.length >= 4 && lowerTitle.contains(cName.lowercase(Locale.US))) {
                        performers.putIfAbsent(cName.lowercase(Locale.US), cand)
                    }
                }
                if (performers.isEmpty()) {
                    for (matched in matchPerformersInTitle(title)) {
                        performers.putIfAbsent(matched.name.lowercase(Locale.US), matched)
                    }
                }
            }

            scenes.add(
                StashScene(
                    id = sceneId,
                    title = title,
                    date = dateStr,
                    studioId = studioId ?: "xhamster",
                    studioName = studioName ?: "xHamster",
                    studioLogo = studioLogo,
                    coverUrl = coverUrl,
                    femalePerformers = performers.values.toList(),
                    directVideoUrl = videoUrl,
                    is4k = is4k
                )
            )
        }

        return scenes
    }

    private fun parseScenesFromDom(
        doc: Document,
        knownEntity: StashPerformer?
    ): List<StashScene> {
        val scenes = mutableListOf<StashScene>()
        val seenUrls = HashSet<String>()
        val isKnownStudio = knownEntity != null && isStudioEntity(knownEntity)
        val isKnownActor = knownEntity != null && !isStudioEntity(knownEntity)

        val cards = doc.select("div.thumb-list__item, article.video-thumb, div.video-thumb")
        for (card in cards) {
            val linkEl = card.selectFirst("a.video-thumb__image-container[href], a.video-thumb-info__name[href], a[href*=/videos/]")
                ?: continue
            val videoUrl = normalizeUrl(linkEl.attr("href"))?.substringBefore("?") ?: continue
            if (!seenUrls.add(videoUrl.lowercase(Locale.US))) continue

            val rawId = card.attr("data-video-id").ifBlank {
                videoUrl.substringAfterLast('/').substringBefore('#')
            }
            val title = card.selectFirst("a.video-thumb-info__name, .video-thumb-info__name")?.text()?.trim()
                ?: linkEl.attr("title").trim()
                .ifBlank { card.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifBlank { humanizeSlug(rawId) }

            val imgEl = card.selectFirst("img")
            val rawCover = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() }
            val coverUrl = normalizeCoverUrl(rawCover)

            val is4k = card.selectFirst("[class*=uhd], [class*=4k], .thumb-image-container__uhd") != null ||
                title.contains(Regex("""\b(4k|2160p|uhd)\b""", RegexOption.IGNORE_CASE))

            val performers = if (isKnownActor && knownEntity != null) {
                listOf(knownEntity)
            } else {
                matchPerformersInTitle(title)
            }

            scenes.add(
                StashScene(
                    id = "xhamster:$rawId",
                    title = title,
                    date = null,
                    studioId = if (isKnownStudio) knownEntity?.id else "xhamster",
                    studioName = if (isKnownStudio) knownEntity?.name else "xHamster",
                    studioLogo = if (isKnownStudio) knownEntity?.imageUrl else null,
                    coverUrl = coverUrl,
                    femalePerformers = performers,
                    directVideoUrl = videoUrl,
                    is4k = is4k
                )
            )
        }
        return scenes
    }

    // ==========================================
    // DIRECTORY INDEXES FOR FUZZY DISCOVERY & EXPLORE
    // ==========================================

    private suspend fun getOrFetchDirectories(): Pair<List<StashPerformer>, List<StashPerformer>> = coroutineScope {
        val currentActors = cachedDirectoryActors
        val currentStudios = cachedDirectoryStudios
        if (currentActors.isNotEmpty() && currentStudios.isNotEmpty()) {
            return@coroutineScope Pair(currentActors, currentStudios)
        }

        val actorsDeferred = async(Dispatchers.IO) {
            if (currentActors.isNotEmpty()) return@async currentActors
            try {
                val (status, html) = fetchHtml("$BASE_URL/pornstars")
                if (status in 200..299 && html.isNotBlank()) {
                    val initials = extractWindowInitialsJson(html)
                    val doc = Jsoup.parse(html, BASE_URL)
                    val fromJson = parseCreatorsSuggestionsFromInitials(initials).filter { !isStudioEntity(it) }
                    val fromDom = parseEntitiesFromDom(doc).filter { !isStudioEntity(it) }
                    val merged = LinkedHashMap<String, StashPerformer>()
                    for (p in fromJson + fromDom) {
                        val key = normalizeEntityBaseUrl(p.id).lowercase(Locale.US)
                        val existing = merged[key]
                        if (existing == null || (existing.imageUrl.isNullOrBlank() && !p.imageUrl.isNullOrBlank())) {
                            merged[key] = p
                        }
                    }
                    val list = merged.values.toList()
                    if (list.isNotEmpty()) cachedDirectoryActors = list
                    list
                } else emptyList()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
        }

        val studiosDeferred = async(Dispatchers.IO) {
            if (currentStudios.isNotEmpty()) return@async currentStudios
            try {
                val (status, html) = fetchHtml("$BASE_URL/channels")
                if (status in 200..299 && html.isNotBlank()) {
                    val initials = extractWindowInitialsJson(html)
                    val doc = Jsoup.parse(html, BASE_URL)
                    val fromJson = parseCreatorsSuggestionsFromInitials(initials).filter { isStudioEntity(it) }
                    val fromDom = parseEntitiesFromDom(doc).filter { isStudioEntity(it) }
                    val merged = LinkedHashMap<String, StashPerformer>()
                    for (s in fromJson + fromDom) {
                        val key = normalizeEntityBaseUrl(s.id).lowercase(Locale.US)
                        val existing = merged[key]
                        if (existing == null || (existing.imageUrl.isNullOrBlank() && !s.imageUrl.isNullOrBlank())) {
                            merged[key] = s
                        }
                    }
                    val list = merged.values.toList()
                    if (list.isNotEmpty()) cachedDirectoryStudios = list
                    list
                } else emptyList()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
        }

        Pair(actorsDeferred.await(), studiosDeferred.await())
    }

    // ==========================================
    // PUBLIC API: EXPLORE, SEARCH, SCRAPE ENTITY, ENRICH SCENE
    // ==========================================

    suspend fun exploreXhamster(): XhamsterUnifiedSearchResult = withContext(Dispatchers.IO) {
        val cacheKey = "__explore_xhamster_unified__"
        searchCache[cacheKey]?.takeIf { it.isValid() }?.let { return@withContext it.data }

        val (actors, studios) = getOrFetchDirectories()
        val unified = mutableListOf<StashPerformer>()
        val seenKeys = HashSet<String>()

        // Interleave top actors and top studios with images so both appear side-by-side in Results
        val actorsWithImg = actors.filter { !it.imageUrl.isNullOrBlank() } + actors.filter { it.imageUrl.isNullOrBlank() }
        val studiosWithImg = studios.filter { !it.imageUrl.isNullOrBlank() } + studios.filter { it.imageUrl.isNullOrBlank() }

        val maxLen = max(actorsWithImg.size, studiosWithImg.size).coerceAtMost(40)
        for (i in 0 until maxLen) {
            if (i < actorsWithImg.size) {
                val a = actorsWithImg[i]
                val k = normalizeEntityBaseUrl(a.id).lowercase(Locale.US)
                if (seenKeys.add(k)) unified.add(a)
            }
            if (i < studiosWithImg.size) {
                val s = studiosWithImg[i]
                val k = normalizeEntityBaseUrl(s.id).lowercase(Locale.US)
                if (seenKeys.add(k)) unified.add(s)
            }
        }

        val firstEntity = unified.firstOrNull()
        val firstPage = if (firstEntity != null) {
            try {
                scrapeXhamsterEntityScenes(firstEntity.id, firstEntity)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                XhamsterScenesPage(emptyList(), null, firstEntity)
            }
        } else {
            XhamsterScenesPage(emptyList(), null, null)
        }

        val updatedUnified = if (firstPage.entity != null && firstEntity != null) {
            unified.mapIndexed { index, item -> if (index == 0) firstPage.entity else item }
        } else {
            unified
        }

        val result = XhamsterUnifiedSearchResult(
            results = updatedUnified,
            scenes = firstPage.scenes,
            nextPageUrl = firstPage.nextPageUrl
        )
        if (updatedUnified.isNotEmpty()) {
            searchCache[cacheKey] = CacheEntry(result)
        }
        result
    }

    suspend fun searchXhamsterUnified(query: String): XhamsterUnifiedSearchResult = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return@withContext XhamsterUnifiedSearchResult(emptyList(), emptyList(), null)
        }

        val cacheKey = cleanQuery.lowercase(Locale.US)
        searchCache[cacheKey]?.takeIf { it.isValid() }?.let { return@withContext it.data }

        // 1. Direct xHamster URL handling (Video URL or Performer/Channel URL)
        if (cleanQuery.contains("xhamster.", ignoreCase = true) || cleanQuery.contains("xh.video", ignoreCase = true)) {
            val normalizedInputUrl = normalizeUrl(cleanQuery) ?: cleanQuery
            if (normalizedInputUrl.contains("/videos/", ignoreCase = true) || normalizedInputUrl.contains("xh.video", ignoreCase = true)) {
                val singleScene = scrapeSingleVideoUrl(normalizedInputUrl)
                if (singleScene != null) {
                    val unifiedFromScene = mutableListOf<StashPerformer>()
                    unifiedFromScene.addAll(singleScene.femalePerformers)
                    if (!singleScene.studioName.isNullOrBlank() &&
                        !singleScene.studioName.equals("xHamster", ignoreCase = true) &&
                        !singleScene.studioId.isNullOrBlank()
                    ) {
                        unifiedFromScene.add(
                            StashPerformer(
                                id = singleScene.studioId,
                                name = singleScene.studioName,
                                disambiguation = ENTITY_TYPE_STUDIO,
                                imageUrl = singleScene.studioLogo
                            )
                        )
                    }
                    val res = XhamsterUnifiedSearchResult(
                        results = unifiedFromScene.distinctBy { it.id },
                        scenes = listOf(singleScene),
                        nextPageUrl = null
                    )
                    searchCache[cacheKey] = CacheEntry(res)
                    return@withContext res
                }
            } else if (normalizedInputUrl.contains("/pornstars/", ignoreCase = true) ||
                normalizedInputUrl.contains("/creators/", ignoreCase = true) ||
                normalizedInputUrl.contains("/celebrities/", ignoreCase = true) ||
                normalizedInputUrl.contains("/channels/", ignoreCase = true)
            ) {
                val page = scrapeXhamsterEntityScenes(normalizedInputUrl, null)
                val entityList = listOfNotNull(page.entity)
                val res = XhamsterUnifiedSearchResult(
                    results = entityList,
                    scenes = page.scenes,
                    nextPageUrl = page.nextPageUrl
                )
                searchCache[cacheKey] = CacheEntry(res)
                return@withContext res
            }
        }

        // 2. Parallel Search + Directory Fuzzy Discovery + Direct Slug Probing
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val candidateSlug = cleanQuery.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        coroutineScope {
            val searchPageDeferred = async {
                try {
                    fetchHtmlWithFinalUrl("$BASE_URL/search/$encodedQuery")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    HtmlFetchResult(0, "", "")
                }
            }

            val directoriesDeferred = async {
                getOrFetchDirectories()
            }

            val searchFetch = searchPageDeferred.await()
            val (dirActors, dirStudios) = directoriesDeferred.await()

            val unifiedMap = LinkedHashMap<String, StashPerformer>()

            fun mergeCandidate(p: StashPerformer) {
                val key = normalizeEntityBaseUrl(p.id).lowercase(Locale.US)
                val existing = unifiedMap[key]
                if (existing == null || (existing.imageUrl.isNullOrBlank() && !p.imageUrl.isNullOrBlank())) {
                    unifiedMap[key] = p
                }
            }

            var searchInitials: JSONObject? = null
            var searchDoc: Document? = null

            if (searchFetch.status in 200..299 && searchFetch.body.isNotBlank()) {
                searchInitials = extractWindowInitialsJson(searchFetch.body)
                searchDoc = Jsoup.parse(searchFetch.body, BASE_URL)

                for (creator in parseCreatorsSuggestionsFromInitials(searchInitials)) {
                    mergeCandidate(creator)
                }
                for (domEntity in parseEntitiesFromDom(searchDoc)) {
                    val sim = scoreNameSimilarity(domEntity.name, cleanQuery)
                    if (sim >= 55) {
                        mergeCandidate(domEntity)
                    }
                }
            }

            // Check if xHamster search returned a typo correction (e.g. "brazers" -> "brazzers" or "lana rhodes" -> "lana rhoades")
            val correctedQuery = searchInitials?.optJSONObject("correction")?.optString("correctedQuery")?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals(cleanQuery, ignoreCase = true) }

            val effectiveQueries = listOfNotNull(cleanQuery, correctedQuery)

            // Fuzzy match against full directory of Pornstars and Studios/Channels
            for (entity in dirActors + dirStudios + knownEntitiesCache.values) {
                val bestSim = effectiveQueries.maxOf { q -> scoreNameSimilarity(entity.name, q) }
                if (bestSim >= 65) {
                    mergeCandidate(entity)
                }
            }

            // If we have a corrected query and few creator suggestions, also fetch corrected search suggestions
            if (correctedQuery != null && unifiedMap.size < 3) {
                try {
                    val encodedCorr = URLEncoder.encode(correctedQuery, "UTF-8")
                    val corrFetch = fetchHtmlWithFinalUrl("$BASE_URL/search/$encodedCorr")
                    if (corrFetch.status in 200..299 && corrFetch.body.isNotBlank()) {
                        val corrInitials = extractWindowInitialsJson(corrFetch.body)
                        for (c in parseCreatorsSuggestionsFromInitials(corrInitials)) {
                            mergeCandidate(c)
                        }
                        if (searchInitials == null || findVideoThumbArrayInInitials(searchInitials)?.length() == 0) {
                            searchInitials = corrInitials
                            searchDoc = Jsoup.parse(corrFetch.body, BASE_URL)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            // Direct slug probe for exact actor or channel if not already found with high confidence
            val hasExactMatch = unifiedMap.values.any { scoreNameSimilarity(it.name, cleanQuery) >= 92 }
            if (!hasExactMatch && candidateSlug.length >= 3) {
                val probeUrls = listOf(
                    "$BASE_URL/pornstars/$candidateSlug" to false,
                    "$BASE_URL/channels/$candidateSlug" to true
                )
                val probeResults = probeUrls.map { (probeUrl, isStudio) ->
                    async {
                        withTimeoutOrNull(3500L) {
                            try {
                                val res = fetchHtmlWithFinalUrl(probeUrl)
                                if (res.status in 200..299 && res.body.isNotBlank() &&
                                    (res.finalUrl.contains("/pornstars/", ignoreCase = true) ||
                                        res.finalUrl.contains("/channels/", ignoreCase = true) ||
                                        res.finalUrl.contains("/creators/", ignoreCase = true))
                                ) {
                                    val pInitials = extractWindowInitialsJson(res.body)
                                    val pDoc = Jsoup.parse(res.body, BASE_URL)
                                    extractEntityHeaderFromPage(
                                        initials = pInitials,
                                        doc = pDoc,
                                        fallbackUrl = res.finalUrl,
                                        knownEntity = StashPerformer(
                                            id = normalizeEntityBaseUrl(res.finalUrl),
                                            name = humanizeSlug(candidateSlug),
                                            disambiguation = if (isStudio) ENTITY_TYPE_STUDIO else ENTITY_TYPE_ACTOR
                                        )
                                    )
                                } else null
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()

                for (probed in probeResults) {
                    mergeCandidate(probed)
                }
            }

            // Score and sort unified results (both Actors and Studios in one list)
            val sortedResults = unifiedMap.values
                .sortedWith(
                    compareByDescending<StashPerformer> { cand ->
                        effectiveQueries.maxOf { q -> scoreNameSimilarity(cand.name, q) }
                    }
                        .thenByDescending { if (!it.imageUrl.isNullOrBlank()) 1 else 0 }
                        .thenBy { it.name.lowercase(Locale.US) }
                )
                .take(35)

            // Backfill missing avatars/logos for top 6 results in parallel so circles look great
            val enrichedTopResults = sortedResults.mapIndexed { idx, item ->
                async {
                    if (idx < 6 && item.imageUrl.isNullOrBlank()) {
                        val fetchedImg = withTimeoutOrNull(2500L) {
                            fetchEntityImageOnly(item.id)
                        }
                        if (!fetchedImg.isNullOrBlank()) {
                            val updated = item.copy(imageUrl = fetchedImg)
                            cacheKnownEntity(updated)
                            updated
                        } else item
                    } else item
                }
            }.awaitAll()

            val topEntity = enrichedTopResults.firstOrNull()
            val topScore = if (topEntity != null) {
                effectiveQueries.maxOf { q -> scoreNameSimilarity(topEntity.name, q) }
            } else 0

            // If we have a strong top entity match, load that entity's scenes directly so the user sees its full catalog
            if (topEntity != null && topScore >= 72) {
                try {
                    val entityPage = scrapeXhamsterEntityScenes(topEntity.id, topEntity)
                    if (entityPage.scenes.isNotEmpty()) {
                        val finalResults = if (entityPage.entity != null) {
                            enrichedTopResults.mapIndexed { index, item ->
                                if (index == 0) entityPage.entity else item
                            }
                        } else enrichedTopResults
                        val res = XhamsterUnifiedSearchResult(
                            results = finalResults,
                            scenes = entityPage.scenes,
                            nextPageUrl = entityPage.nextPageUrl
                        )
                        searchCache[cacheKey] = CacheEntry(res)
                        return@coroutineScope res
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            // Fallback: parse scenes directly from the search page
            val searchScenes = if (searchInitials != null || searchDoc != null) {
                val fromInitials = parseScenesFromInitials(searchInitials, topEntity?.takeIf { topScore >= 85 }, enrichedTopResults)
                if (fromInitials.isNotEmpty()) {
                    fromInitials
                } else if (searchDoc != null) {
                    parseScenesFromDom(searchDoc, topEntity?.takeIf { topScore >= 85 })
                } else emptyList()
            } else emptyList()

            // Harvest any additional channels/performers discovered on the search scene cards
            for (sc in searchScenes) {
                for (perf in sc.femalePerformers) {
                    if (perf.id.startsWith("http", ignoreCase = true)) {
                        mergeCandidate(perf)
                    }
                }
                val sId = sc.studioId
                val sName = sc.studioName
                if (!sId.isNullOrBlank() && sId.startsWith("http", ignoreCase = true) &&
                    !sName.isNullOrBlank() && !sName.equals("xHamster", ignoreCase = true)
                ) {
                    mergeCandidate(
                        StashPerformer(
                            id = sId,
                            name = sName,
                            disambiguation = ENTITY_TYPE_STUDIO,
                            imageUrl = sc.studioLogo
                        )
                    )
                }
            }

            val finalMergedResults = if (enrichedTopResults.isNotEmpty()) {
                enrichedTopResults
            } else {
                unifiedMap.values.toList()
            }

            val nextPage = if (searchDoc != null) {
                extractNextPageUrl(searchInitials, searchDoc, searchFetch.finalUrl.ifBlank { "$BASE_URL/search/$encodedQuery" })
            } else null

            val finalResult = XhamsterUnifiedSearchResult(
                results = finalMergedResults,
                scenes = searchScenes,
                nextPageUrl = nextPage
            )
            if (finalMergedResults.isNotEmpty() || searchScenes.isNotEmpty()) {
                searchCache[cacheKey] = CacheEntry(finalResult)
            }
            finalResult
        }
    }

    private suspend fun fetchEntityImageOnly(entityUrl: String): String? {
        val key = normalizeEntityBaseUrl(entityUrl).lowercase(Locale.US)
        avatarCache[key]?.let { return it }
        return try {
            val (status, html) = fetchHtml(entityUrl)
            if (status !in 200..299 || html.isBlank()) return null
            val initials = extractWindowInitialsJson(html)
            val doc = Jsoup.parse(html, BASE_URL)
            val header = extractEntityHeaderFromPage(initials, doc, entityUrl, null)
            header.imageUrl
        } catch (_: Exception) {
            null
        }
    }

    suspend fun scrapeXhamsterEntityScenes(
        pageOrEntityUrl: String,
        knownEntity: StashPerformer? = null
    ): XhamsterScenesPage = withContext(Dispatchers.IO) {
        val targetUrl = normalizeUrl(pageOrEntityUrl) ?: return@withContext XhamsterScenesPage(emptyList(), null, knownEntity)
        val cacheKey = targetUrl.lowercase(Locale.US)
        scenesPageCache[cacheKey]?.takeIf { it.isValid() }?.let { return@withContext it.data }

        val fetchRes = fetchHtmlWithFinalUrl(targetUrl)
        if (fetchRes.status !in 200..299 || fetchRes.body.isBlank()) {
            return@withContext XhamsterScenesPage(emptyList(), null, knownEntity)
        }

        val initials = extractWindowInitialsJson(fetchRes.body)
        val doc = Jsoup.parse(fetchRes.body, BASE_URL)

        val isSearchUrl = targetUrl.contains("/search/", ignoreCase = true)
        val resolvedEntity = if (!isSearchUrl) {
            extractEntityHeaderFromPage(initials, doc, fetchRes.finalUrl.ifBlank { targetUrl }, knownEntity)
        } else {
            knownEntity
        }

        val discovered = parseCreatorsSuggestionsFromInitials(initials)
        val scenesFromJson = parseScenesFromInitials(initials, resolvedEntity, discovered)
        val scenes = if (scenesFromJson.isNotEmpty()) {
            scenesFromJson
        } else {
            parseScenesFromDom(doc, resolvedEntity)
        }

        val nextPageUrl = extractNextPageUrl(initials, doc, fetchRes.finalUrl.ifBlank { targetUrl })

        val page = XhamsterScenesPage(
            scenes = scenes,
            nextPageUrl = nextPageUrl,
            entity = resolvedEntity
        )
        if (scenes.isNotEmpty()) {
            scenesPageCache[cacheKey] = CacheEntry(page)
        }
        page
    }

    suspend fun scrapeSingleVideoUrl(videoUrl: String): StashScene? = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(videoUrl) ?: return@withContext null
        val stub = StashScene(
            id = "xhamster:${normalized.substringAfterLast('/').substringBefore('?')}",
            title = humanizeSlug(normalized),
            directVideoUrl = normalized
        )
        val enriched = enrichSceneForSave(stub, blockMalePerformers = false)
        if (enriched.title.isBlank() && enriched.coverUrl.isNullOrBlank()) null else enriched
    }

    suspend fun enrichSceneForSave(
        scene: StashScene,
        blockMalePerformers: Boolean = false
    ): StashScene = withContext(Dispatchers.IO) {
        val videoUrl = scene.directVideoUrl?.let { normalizeUrl(it) }
            ?: scene.id.removePrefix("xhamster:").takeIf { it.startsWith("http", ignoreCase = true) }
            ?: return@withContext scene

        try {
            val (status, html) = fetchHtml(videoUrl)
            if (status !in 200..299 || html.isBlank()) return@withContext scene

            val initials = extractWindowInitialsJson(html)
            val doc = Jsoup.parse(html, BASE_URL)

            val videoModel = initials?.optJSONObject("videoModel")
            val xplayerSettings = initials?.optJSONObject("xplayerSettings")

            // 1. Title
            val modelTitle = Parser.unescapeEntities(videoModel?.optString("title")?.trim().orEmpty(), false)
            val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            val finalTitle = modelTitle.ifBlank { ogTitle }.ifBlank { scene.title }

            // 2. High-Res Cover URL
            val modelCover = normalizeCoverUrl(videoModel?.optString("imageURL"))
                ?: normalizeCoverUrl(videoModel?.optString("thumbURL"))
                ?: normalizeCoverUrl(xplayerSettings?.optString("poster"))
                ?: normalizeCoverUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))
                ?: scene.coverUrl

            // 3. Release Date
            val createdEpoch = videoModel?.optLong("created", 0L) ?: 0L
            val finalDate = if (createdEpoch > 0L) {
                formatEpochSecondsToDate(createdEpoch)
            } else {
                scene.date ?: extractUploadDateFromJsonLd(doc)
            }

            // 4. 4K vs HD Quality Detection
            var is4k = scene.is4k ||
                (videoModel?.optBoolean("isUHD", false) == true) ||
                finalTitle.contains(Regex("""\b(4k|2160p|uhd)\b""", RegexOption.IGNORE_CASE))

            if (!is4k && xplayerSettings != null) {
                val sourcesStr = xplayerSettings.optJSONObject("sources")?.toString().orEmpty()
                if (sourcesStr.contains("\"2160p\"", ignoreCase = true) ||
                    sourcesStr.contains("\"4k\"", ignoreCase = true)
                ) {
                    is4k = true
                }
            }

            // 5. Performers & Studio/Channel from videoTagsComponent.tags + videoModel
            val performersMap = LinkedHashMap<String, StashPerformer>()
            for (existingPerf in scene.femalePerformers) {
                performersMap[existingPerf.name.trim().lowercase(Locale.US)] = existingPerf
            }

            var studioId = scene.studioId
            var studioName = scene.studioName
            var studioLogo = scene.studioLogo

            val tagsArr = initials?.optJSONObject("videoTagsComponent")?.optJSONArray("tags")
                ?: initials?.optJSONArray("videoTags")
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    val tag = tagsArr.optJSONObject(i) ?: continue
                    val tagName = Parser.unescapeEntities(
                        tag.optString("nameEn").ifBlank { tag.optString("name") }.trim(),
                        false
                    )
                    if (tagName.isBlank()) continue
                    val tagUrl = normalizeUrl(tag.optString("url").ifBlank { tag.optString("pageURL") })
                        ?.let { normalizeEntityBaseUrl(it) }
                    val tagThumb = normalizeUrl(tag.optString("thumbUrl").ifBlank { tag.optString("logoUrl") })
                        ?.takeIf { isValidAvatarOrLogoUrl(it) }

                    val isChannel = tag.optBoolean("isChannel", false) ||
                        tag.optBoolean("isBrand", false) ||
                        (tagUrl != null && tagUrl.contains("/channels/", ignoreCase = true))

                    val isPerformer = tag.optBoolean("isPornstar", false) ||
                        tag.optBoolean("isCreator", false) ||
                        tag.optBoolean("isCeleb", false) ||
                        (tagUrl != null && (
                            tagUrl.contains("/pornstars/", ignoreCase = true) ||
                                tagUrl.contains("/creators/", ignoreCase = true) ||
                                tagUrl.contains("/celebrities/", ignoreCase = true)
                            ))

                    if (isChannel) {
                        val hasGenericStudio = studioName.isNullOrBlank() || studioName.equals("xHamster", ignoreCase = true)
                        if (hasGenericStudio || !tagThumb.isNullOrBlank()) {
                            studioId = tagUrl ?: "xhamster:channel:${tagName.lowercase(Locale.US)}"
                            studioName = tagName
                            if (!tagThumb.isNullOrBlank()) {
                                studioLogo = tagThumb
                            }
                        }
                        if (tagUrl != null) {
                            cacheKnownEntity(
                                StashPerformer(
                                    id = tagUrl,
                                    name = tagName,
                                    disambiguation = ENTITY_TYPE_STUDIO,
                                    imageUrl = tagThumb
                                )
                            )
                        }
                    } else if (isPerformer) {
                        val genderStr = tag.optString("gender").uppercase(Locale.US)
                        if (blockMalePerformers && (genderStr == "MALE" || genderStr == "M")) {
                            continue
                        }
                        val perfKey = tagName.lowercase(Locale.US)
                        val existing = performersMap[perfKey]
                        val perf = StashPerformer(
                            id = tagUrl ?: existing?.id ?: "xhamster:star:$perfKey",
                            name = tagName,
                            disambiguation = ENTITY_TYPE_ACTOR,
                            gender = if (genderStr.isNotBlank()) genderStr else "FEMALE",
                            imageUrl = tagThumb ?: existing?.imageUrl ?: tagUrl?.let { avatarCache[it.lowercase(Locale.US)] }
                        )
                        performersMap[perfKey] = perf
                        if (tagUrl != null) cacheKnownEntity(perf)
                    }
                }
            }

            // Also check DOM links on the video page for any channel / pornstar badges if tags were sparse
            if (performersMap.isEmpty() || studioName.isNullOrBlank() || studioName.equals("xHamster", ignoreCase = true)) {
                val entityLinks = doc.select(
                    "a[href*=/pornstars/], a[href*=/creators/], a[href*=/channels/]"
                )
                for (a in entityLinks) {
                    val href = normalizeUrl(a.attr("href"))?.let { normalizeEntityBaseUrl(it) } ?: continue
                    val slug = extractEntitySlug(href)
                    if (slug.isBlank() || slug in BLOCKED_ENTITY_WORDS) continue
                    val label = a.text().trim().ifBlank { a.attr("title").trim() }
                    if (label.isBlank() || label.length > 45 || label.lowercase(Locale.US) in BLOCKED_ENTITY_WORDS) continue
                    val img = a.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                        ?.let { normalizeUrl(it) }
                        ?.takeIf { isValidAvatarOrLogoUrl(it) }

                    if (href.contains("/channels/", ignoreCase = true)) {
                        if (studioName.isNullOrBlank() || studioName.equals("xHamster", ignoreCase = true)) {
                            studioId = href
                            studioName = label
                            if (!img.isNullOrBlank()) studioLogo = img
                        }
                    } else if (href.contains("/pornstars/", ignoreCase = true) || href.contains("/creators/", ignoreCase = true)) {
                        val key = label.lowercase(Locale.US)
                        if (!performersMap.containsKey(key) && finalTitle.lowercase(Locale.US).contains(key)) {
                            performersMap[key] = StashPerformer(
                                id = href,
                                name = label,
                                disambiguation = ENTITY_TYPE_ACTOR,
                                gender = "FEMALE",
                                imageUrl = img ?: avatarCache[href.lowercase(Locale.US)]
                            )
                        }
                    }
                }
            }

            // Backfill missing performer avatars if needed
            val finalPerformers = coroutineScope {
                performersMap.values.map { perf ->
                    async {
                        if (perf.imageUrl.isNullOrBlank() && perf.id.startsWith("http", ignoreCase = true)) {
                            val fetched = withTimeoutOrNull(2000L) {
                                fetchEntityImageOnly(perf.id)
                            }
                            if (!fetched.isNullOrBlank()) perf.copy(imageUrl = fetched) else perf
                        } else perf
                    }
                }.awaitAll()
            }

            // Backfill missing studio logo if studioId is a channel URL
            if (studioLogo.isNullOrBlank() && !studioId.isNullOrBlank() && studioId.startsWith("http", ignoreCase = true)) {
                studioLogo = withTimeoutOrNull(2000L) {
                    fetchEntityImageOnly(studioId)
                }
            }

            scene.copy(
                title = finalTitle,
                date = finalDate ?: scene.date,
                studioId = studioId ?: "xhamster",
                studioName = studioName ?: "xHamster",
                studioLogo = studioLogo ?: scene.studioLogo,
                coverUrl = modelCover ?: scene.coverUrl,
                femalePerformers = finalPerformers,
                directVideoUrl = videoUrl,
                is4k = is4k
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "enrichSceneForSave fallback for $videoUrl: ${e.message}")
            scene
        }
    }

    private fun extractUploadDateFromJsonLd(doc: Document): String? {
        val scripts = doc.select("script[type=application/ld+json]")
        for (s in scripts) {
            val raw = s.data().trim()
            if (raw.isEmpty()) continue
            try {
                val obj = JSONObject(raw)
                val uploadDate = obj.optString("uploadDate").ifBlank { obj.optString("datePublished") }
                normalizeDateString(uploadDate)?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    suspend fun fetchFreshCoverUrl(sceneIdOrUrl: String): String? = withContext(Dispatchers.IO) {
        val url = if (sceneIdOrUrl.startsWith("http", ignoreCase = true)) {
            sceneIdOrUrl
        } else {
            return@withContext null
        }
        try {
            val (status, html) = fetchHtml(url)
            if (status !in 200..299 || html.isBlank()) return@withContext null
            val initials = extractWindowInitialsJson(html)
            val videoModel = initials?.optJSONObject("videoModel")
            normalizeCoverUrl(videoModel?.optString("imageURL"))
                ?: normalizeCoverUrl(videoModel?.optString("thumbURL"))
                ?: Jsoup.parse(html, BASE_URL).selectFirst("meta[property=og:image]")?.attr("content")?.let { normalizeCoverUrl(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }
}
