package com.example.network

import android.util.Log
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
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

data class PornhubSearchResult(
    val performers: List<StashPerformer>,
    val scenes: List<StashScene>,
    val nextPageUrl: String? = null
)

data class PornhubScenesPage(
    val scenes: List<StashScene>,
    val nextPageUrl: String? = null,
    val performer: StashPerformer? = null
)

data class PornhubChannelSearchResult(
    val studios: List<StashStudio>,
    val scenes: List<StashScene>,
    val nextPageUrl: String? = null
)

data class PornhubChannelScenesPage(
    val scenes: List<StashScene>,
    val nextPageUrl: String? = null,
    val studio: StashStudio? = null
)

object PornhubStarScraper {
    private const val TAG = "PornhubStarScraper"
    private const val BASE_URL = "https://www.pornhub.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private data class CacheEntry<T>(
        val data: T,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isValid(ttlMs: Long = 10 * 60 * 1000L): Boolean =
            System.currentTimeMillis() - timestamp < ttlMs
    }

    private data class AutocompleteBatch(
        val performers: List<StashPerformer>,
        val channels: List<StashStudio>,
        val suggestedQueries: List<String>
    )

    private data class HtmlFetchResult(
        val status: Int,
        val body: String,
        val finalUrl: String
    )

    private val searchCache = ConcurrentHashMap<String, CacheEntry<PornhubSearchResult>>()
    private val scenesPageCache = ConcurrentHashMap<String, CacheEntry<PornhubScenesPage>>()
    private val channelSearchCache = ConcurrentHashMap<String, CacheEntry<PornhubChannelSearchResult>>()
    private val channelScenesPageCache = ConcurrentHashMap<String, CacheEntry<PornhubChannelScenesPage>>()
    private val avatarCache = ConcurrentHashMap<String, String>()
    private val genderCache = ConcurrentHashMap<String, String>()
    private val knownPerformerNamesCache = ConcurrentHashMap<String, StashPerformer>()
    private val channelAvatarCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedPopularChannels: List<StashStudio> = emptyList()

    @Volatile
    private var cachedAutocompleteToken: String? = null

    @Volatile
    private var tokenTimestampMs: Long = 0L

    private val ageCookiesSeeded = AtomicBoolean(false)

    private val CDN_DATE_REGEX = Regex("""/videos/(\d{4})(\d{2})/(\d{2})/""")
    private val VIEWKEY_REGEX = Regex("""[?&]viewkey=([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
    private val TOKEN_REGEX = Regex("""data-token=["']([^"']+)["']""")
    private val RELATIVE_DATE_REGEX = Regex(
        """(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""",
        RegexOption.IGNORE_CASE
    )

    private val BLOCKED_PERFORMER_WORDS = setOf(
        "pornstars", "models", "suggest", "add a pornstar", "verified", "channels", "categories", "pornhub"
    )

    private val COMMON_VIDEO_SUFFIX_WORDS = setOf(
        "anal", "lesbian", "pov", "solo", "creampie", "feet", "bbc", "hd", "cosplay",
        "compilation", "threesome", "gangbang", "foursome", "new", "blacked", "hardcore",
        "squirt", "tits", "ass", "cumshot", "orgasm", "bdsm", "step", "sis", "fan",
        "brazzers", "game", "leaks", "leak", "sex", "video", "videos", "porn", "free", "full"
    )

    private fun ensureAgeCookiesSeeded() {
        if (ageCookiesSeeded.compareAndSet(false, true)) {
            val cookies = mapOf(
                "age_verified" to "1",
                "accessAgeDisclaimerPH" to "1",
                "accessAgeDisclaimerUK" to "1",
                "accessPH" to "1"
            )
            for ((name, value) in cookies) {
                NetworkClient.seedCookie(BASE_URL, name, value, "pornhub.com")
                NetworkClient.seedCookie(BASE_URL, name, value, "www.pornhub.com")
            }
        }
    }

    private fun updateTokenFromHtmlIfPresent(html: String) {
        val token = TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
        if (!token.isNullOrBlank()) {
            cachedAutocompleteToken = token
            tokenTimestampMs = System.currentTimeMillis()
        }
    }

    private fun normalizeUrl(rawUrl: String?): String? {
        val cleaned = rawUrl?.trim()?.replace("&amp;", "&")?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$BASE_URL$cleaned"
            else -> "$BASE_URL/$cleaned"
        }
    }

    private fun normalizePerformerBaseUrl(rawUrl: String): String {
        val full = normalizeUrl(rawUrl) ?: return BASE_URL
        return full
            .substringBefore("?")
            .substringBefore("#")
            .trimEnd('/')
            .removeSuffix("/videos/upload")
            .removeSuffix("/videos")
    }

    private fun extractPerformerSlug(profileUrlOrId: String): String {
        val normalized = normalizePerformerBaseUrl(profileUrlOrId).lowercase()
        return normalized.substringAfterLast('/').trim()
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
        if (body.isNotEmpty()) {
            updateTokenFromHtmlIfPresent(body)
        }
        HtmlFetchResult(code, body, finalUrl)
    }

    private suspend fun fetchHtml(url: String): Pair<Int, String> {
        val res = fetchHtmlWithFinalUrl(url)
        return Pair(res.status, res.body)
    }

    private suspend fun getOrFetchAutocompleteToken(forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        val existing = cachedAutocompleteToken
        if (!forceRefresh && !existing.isNullOrBlank() && (now - tokenTimestampMs) < 15 * 60 * 1000L) {
            return existing
        }
        return try {
            val (status, html) = fetchHtml("$BASE_URL/pornstars")
            if (status in 200..299 && html.isNotBlank()) {
                updateTokenFromHtmlIfPresent(html)
            }
            cachedAutocompleteToken
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            cachedAutocompleteToken
        }
    }

    private suspend fun fetchAutocompleteEndpointJson(
        endpointPath: String,
        query: String,
        token: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        ensureAgeCookiesSeeded()
        val encodedQ = URLEncoder.encode(query, "UTF-8")
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        val url = "$BASE_URL$endpointPath?pornstars=true&token=$encodedToken&orientation=straight&q=$encodedQ&alt=0"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$BASE_URL/pornstars")
            .build()

        val resp = NetworkClient.okHttpClient.newCall(req).await()
        val code = resp.code
        val body = resp.body?.string().orEmpty()
        resp.close()
        if (code in 200..299 && body.isNotBlank()) {
            try {
                JSONObject(body)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    private suspend fun fetchAutocompleteBatch(query: String): AutocompleteBatch = coroutineScope {
        var token = getOrFetchAutocompleteToken(forceRefresh = false)
        if (token.isNullOrBlank()) {
            return@coroutineScope AutocompleteBatch(emptyList(), emptyList(), emptyList())
        }

        var videoJson = fetchAutocompleteEndpointJson("/api/v1/video/search_autocomplete", query, token)
        if (videoJson == null) {
            token = getOrFetchAutocompleteToken(forceRefresh = true)
            if (!token.isNullOrBlank()) {
                videoJson = fetchAutocompleteEndpointJson("/api/v1/video/search_autocomplete", query, token)
            }
        }

        val pornstarJson = if (!token.isNullOrBlank()) {
            fetchAutocompleteEndpointJson("/api/v1/pornstar/search_autocomplete", query, token)
        } else {
            null
        }

        val performers = mutableListOf<StashPerformer>()
        val channels = mutableListOf<StashStudio>()
        val suggestedQueries = mutableListOf<String>()
        val seenUrls = HashSet<String>()
        val seenChannelUrls = HashSet<String>()

        fun parseJsonIntoLists(json: JSONObject?) {
            if (json == null) return

            val pstarsArr = json.optJSONArray("pornstars")
            if (pstarsArr != null) {
                for (i in 0 until pstarsArr.length()) {
                    val obj = pstarsArr.optJSONObject(i) ?: continue
                    val slug = obj.optString("slug").trim()
                    val name = Parser.unescapeEntities(obj.optString("name").trim(), false)
                    if (slug.isNotBlank() && name.length >= 2) {
                        val profileUrl = "$BASE_URL/pornstar/$slug"
                        if (seenUrls.add(profileUrl.lowercase())) {
                            performers.add(
                                StashPerformer(
                                    id = profileUrl,
                                    name = name,
                                    imageUrl = avatarCache[profileUrl.lowercase()],
                                    images = listOfNotNull(avatarCache[profileUrl.lowercase()])
                                )
                            )
                        }
                    }
                }
            }

            val modelsArr = json.optJSONArray("models")
            if (modelsArr != null) {
                for (i in 0 until modelsArr.length()) {
                    val obj = modelsArr.optJSONObject(i) ?: continue
                    val slug = obj.optString("slug").trim()
                    val name = Parser.unescapeEntities(obj.optString("name").trim(), false)
                    if (slug.isNotBlank() && name.length >= 2) {
                        val profileUrl = "$BASE_URL/model/$slug"
                        if (seenUrls.add(profileUrl.lowercase())) {
                            performers.add(
                                StashPerformer(
                                    id = profileUrl,
                                    name = name,
                                    imageUrl = avatarCache[profileUrl.lowercase()],
                                    images = listOfNotNull(avatarCache[profileUrl.lowercase()])
                                )
                            )
                        }
                    }
                }
            }

            val channelsArr = json.optJSONArray("channels")
            if (channelsArr != null) {
                for (i in 0 until channelsArr.length()) {
                    val obj = channelsArr.optJSONObject(i) ?: continue
                    val slug = obj.optString("slug").trim()
                    val name = Parser.unescapeEntities(obj.optString("name").trim(), false)
                    if (slug.isNotBlank() && name.length >= 2) {
                        val channelUrl = "$BASE_URL/channels/$slug"
                        if (seenChannelUrls.add(channelUrl.lowercase())) {
                            channels.add(
                                StashStudio(
                                    id = channelUrl,
                                    name = name,
                                    logoUrl = channelAvatarCache[channelUrl.lowercase()]
                                )
                            )
                        }
                    }
                }
            }

            val albumsArr = json.optJSONArray("albums")
            if (albumsArr != null) {
                for (i in 0 until albumsArr.length()) {
                    val album = albumsArr.optString(i).trim()
                    if (album.isNotBlank()) suggestedQueries.add(album)
                }
            }

            val queriesArr = json.optJSONArray("queries")
            if (queriesArr != null) {
                for (i in 0 until queriesArr.length()) {
                    val qStr = queriesArr.optString(i).trim()
                    if (qStr.isNotBlank()) suggestedQueries.add(qStr)
                }
            }
        }

        // Parse videoJson first (contains pornstars, models, AND channels), then pornstarJson
        parseJsonIntoLists(videoJson)
        parseJsonIntoLists(pornstarJson)

        AutocompleteBatch(performers, channels, suggestedQueries.distinct())
    }

    /**
     * Normalizes a name phonetically to catch common spelling mistakes:
     * - Collapses repeated letters (evva -> eva, lanna -> lana, angella -> angela, wilis -> willis)
     * - Unifies ph/f, kh/k, rh/r, ck/k
     * - Unifies trailing -ie / -y (sweety -> sweetie) and -es / -s (roads -> rhoades)
     */
    private fun phoneticNormalize(input: String): String {
        val cleaned = input.lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
        if (cleaned.isEmpty()) return ""

        return cleaned.split(" ").joinToString(" ") { word ->
            var w = word
                .replace("ph", "f")
                .replace("kh", "k")
                .replace("rh", "r")
                .replace("ck", "k")
            if (w.endsWith("ie") && w.length > 3) {
                w = w.dropLast(2) + "y"
            }
            if (w.endsWith("es") && w.length > 4) {
                w = w.dropLast(2) + "s"
            }
            w.replace(Regex("""([a-z])\1+"""), "$1")
        }
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = min(
                    min(curr[j] + 1, prev[j + 1] + 1),
                    prev[j] + cost
                )
            }
            System.arraycopy(curr, 0, prev, 0, b.length + 1)
        }
        return prev[b.length]
    }

    private fun computeSimilarityRatio(a: String, b: String): Double {
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(a, b)
        return (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun scorePerformerMatch(name: String, query: String, originalIndex: Int): Int {
        val q = query.trim().lowercase(Locale.US).replace(Regex("""\s+"""), " ")
        val n = name.trim().lowercase(Locale.US).replace(Regex("""\s+"""), " ")
        if (q.isEmpty() || n.isEmpty()) return 0

        val qPhonetic = phoneticNormalize(q)
        val nPhonetic = phoneticNormalize(n)

        val qTokens = q.split(" ").filter { it.isNotBlank() }
        val nTokens = n.split(" ").filter { it.isNotBlank() }

        val fullSim = computeSimilarityRatio(n, q)
        val phoneticSim = computeSimilarityRatio(nPhonetic, qPhonetic)
        val bestSim = max(fullSim, phoneticSim)

        // Token-by-token best fuzzy similarity (e.g. "viola myers" vs "violet myers")
        val tokenAvgSim = if (qTokens.isNotEmpty() && nTokens.isNotEmpty()) {
            qTokens.map { qt ->
                nTokens.maxOf { nt ->
                    max(
                        computeSimilarityRatio(qt, nt),
                        computeSimilarityRatio(phoneticNormalize(qt), phoneticNormalize(nt))
                    )
                }
            }.average()
        } else {
            bestSim
        }

        val effectiveSim = max(bestSim, tokenAvgSim)

        val baseScore = when {
            n == q -> 2000
            nTokens.toSet() == qTokens.toSet() && qTokens.size > 1 -> 1900
            nPhonetic.isNotEmpty() && nPhonetic == qPhonetic -> 1820
            n.startsWith("$q ") -> 1720
            n.startsWith(q) -> 1640
            nPhonetic.isNotEmpty() && qPhonetic.isNotEmpty() && nPhonetic.startsWith(qPhonetic) -> 1580
            effectiveSim >= 0.85 -> 1400 + (effectiveSim * 150).toInt()
            nTokens.any { it == q } -> 1350
            n.contains(q) -> 1250
            effectiveSim >= 0.72 -> 1000 + (effectiveSim * 200).toInt()
            effectiveSim >= 0.60 -> 700 + (effectiveSim * 200).toInt()
            else -> (effectiveSim * 400).toInt()
        }
        return baseScore - originalIndex.coerceAtMost(60)
    }

    private fun extractCandidatePerformerNameFromSuggestion(suggestion: String, rawQuery: String): String? {
        val qTokens = rawQuery.lowercase(Locale.US).split(Regex("""\s+""")).filter { it.isNotBlank() }
        val sTokens = suggestion.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (sTokens.isEmpty()) return null

        // Strip trailing common video category words (e.g., "Sweetie Fox Anal" -> "Sweetie Fox")
        val stripped = sTokens.takeWhile { it.lowercase(Locale.US) !in COMMON_VIDEO_SUFFIX_WORDS }
        val candidateTokens = if (stripped.isNotEmpty()) {
            stripped.take(max(qTokens.size, 2).coerceAtMost(3))
        } else {
            sTokens.take(max(qTokens.size, 2).coerceAtMost(3))
        }
        val candidate = candidateTokens.joinToString(" ").trim()
        if (candidate.length < 3) return null

        val sim = max(
            computeSimilarityRatio(candidate.lowercase(Locale.US), rawQuery.lowercase(Locale.US)),
            computeSimilarityRatio(phoneticNormalize(candidate), phoneticNormalize(rawQuery))
        )
        return if (sim >= 0.62) candidate else null
    }

    private suspend fun fetchPornstarsSearchPagePerformers(query: String): List<StashPerformer> {
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = "$BASE_URL/pornstars/search?search=$encoded"
            val (status, html) = fetchHtml(searchUrl)
            if (status in 200..299 && html.isNotBlank()) {
                val doc = Jsoup.parse(html, searchUrl)
                val parsed = parsePerformersFromSearchDoc(doc, query)
                for (p in parsed) {
                    if (!p.imageUrl.isNullOrBlank()) {
                        avatarCache[p.id.lowercase()] = p.imageUrl
                    }
                }
                parsed
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    private fun extractGenderFromProfileDoc(doc: Document): String? {
        val rawGender = doc.selectFirst("span[itemprop=gender], .infoPiece:contains(Gender) span.smallInfo")
            ?.text()?.trim()?.uppercase(Locale.US)
        return when {
            rawGender == null -> null
            rawGender.contains("FEMALE") || rawGender.contains("WOMAN") -> "FEMALE"
            rawGender.contains("MALE") || rawGender.contains("MAN") -> "MALE"
            else -> rawGender
        }
    }

    private suspend fun fetchQuickProfileAvatar(profileUrl: String): String? {
        val key = normalizePerformerBaseUrl(profileUrl).lowercase()
        avatarCache[key]?.let { return it }

        val slug = extractPerformerSlug(profileUrl)
        val isModel = key.contains("/model/")
        val primaryUrl = if (isModel) "$BASE_URL/model/$slug" else "$BASE_URL/pornstar/$slug"
        val fallbackUrl = if (isModel) "$BASE_URL/pornstar/$slug" else "$BASE_URL/model/$slug"

        for (candidate in listOf(primaryUrl, fallbackUrl)) {
            try {
                val res = fetchHtmlWithFinalUrl(candidate)
                if (res.status !in 200..299 || res.body.isBlank()) continue
                if (res.finalUrl.substringBefore("?").trimEnd('/').endsWith("/pornstars", ignoreCase = true)) continue

                val doc = Jsoup.parse(res.body, res.finalUrl)
                val resolvedKey = normalizePerformerBaseUrl(res.finalUrl).lowercase()
                extractGenderFromProfileDoc(doc)?.let { g ->
                    genderCache[key] = g
                    genderCache[resolvedKey] = g
                }
                val avatarImg = doc.selectFirst("img#getAvatar, .thumbImage img, .avatarWrapper img")
                val avatarUrl = extractCleanImageUrl(avatarImg)
                    ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
                if (!avatarUrl.isNullOrBlank()) {
                    avatarCache[key] = avatarUrl
                    avatarCache[resolvedKey] = avatarUrl
                    return avatarUrl
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        return null
    }

    private fun mergeAndRankPerformers(
        lists: List<List<StashPerformer>>,
        query: String
    ): List<StashPerformer> {
        val bySlugOrName = LinkedHashMap<String, StashPerformer>()

        for (list in lists) {
            for (perf in list) {
                val slug = extractPerformerSlug(perf.id)
                val nameKey = perf.name.trim().lowercase(Locale.US).replace(Regex("""\s+"""), "-")
                val dedupKey = slug.ifBlank { nameKey }

                val existing = bySlugOrName[dedupKey]
                if (existing == null) {
                    bySlugOrName[dedupKey] = perf
                } else {
                    // Merge: keep non-null avatar and prefer /model/ if autocomplete explicitly returned /model/ and /pornstar/ had no avatar
                    val mergedAvatar = existing.imageUrl?.takeIf { it.isNotBlank() }
                        ?: perf.imageUrl?.takeIf { it.isNotBlank() }
                    val preferredId = when {
                        !perf.imageUrl.isNullOrBlank() && existing.imageUrl.isNullOrBlank() -> perf.id
                        else -> existing.id
                    }
                    bySlugOrName[dedupKey] = existing.copy(
                        id = preferredId,
                        imageUrl = mergedAvatar,
                        images = listOfNotNull(mergedAvatar)
                    )
                }
            }
        }

        val combined = bySlugOrName.values.toList()
        if (query.isBlank()) return combined

        return combined.mapIndexed { idx, perf -> Pair(idx, perf) }
            .sortedByDescending { (idx, perf) ->
                val matchScore = scorePerformerMatch(perf.name, query, idx)
                val avatarBonus = if (!perf.imageUrl.isNullOrBlank()) 15 else 0
                matchScore + avatarBonus
            }
            .map { it.second }
    }

    suspend fun searchPornhubStars(query: String): PornhubSearchResult = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext PornhubSearchResult(emptyList(), emptyList(), null)

        val cacheKey = cleanQuery.lowercase(Locale.US)
        searchCache[cacheKey]?.takeIf { it.isValid() }?.let {
            return@withContext it.data
        }

        // 1. Direct Pornhub Video URL
        if (VideoResolvers.isPornhubUrl(cleanQuery) && VIEWKEY_REGEX.containsMatchIn(cleanQuery)) {
            val singleScene = scrapeSingleVideoAsStashScene(cleanQuery)
            val performers = singleScene?.femalePerformers ?: emptyList()
            val scenes = if (singleScene != null) listOf(singleScene) else emptyList()
            val result = PornhubSearchResult(performers, scenes, null)
            if (scenes.isNotEmpty()) {
                searchCache[cacheKey] = CacheEntry(result)
            }
            return@withContext result
        }

        // 2. Direct Pornhub Pornstar / Model URL
        if (cleanQuery.contains("pornhub.com/pornstar/", ignoreCase = true) ||
            cleanQuery.contains("pornhub.com/model/", ignoreCase = true)
        ) {
            val pageResult = scrapePornhubPerformerScenes(cleanQuery)
            val perfList = listOfNotNull(pageResult.performer)
            val result = PornhubSearchResult(perfList, pageResult.scenes, pageResult.nextPageUrl)
            if (pageResult.scenes.isNotEmpty()) {
                searchCache[cacheKey] = CacheEntry(result)
            }
            return@withContext result
        }

        // 2b. Direct Pornhub Channel URL pasted in Actor tab
        if (cleanQuery.contains("pornhub.com/channels/", ignoreCase = true)) {
            val chPage = scrapePornhubChannelScenes(cleanQuery)
            val extractedPerfs = chPage.scenes.flatMap { it.femalePerformers }.distinctBy { it.id.lowercase() }
            val result = PornhubSearchResult(extractedPerfs, chPage.scenes, chPage.nextPageUrl)
            if (chPage.scenes.isNotEmpty()) {
                searchCache[cacheKey] = CacheEntry(result)
            }
            return@withContext result
        }

        // 3. Multi-Stage Smart Search (Autocomplete JSON + Pornstars Search HTML + Typo Correction + Direct Slug Fallback)
        try {
            val (autocompleteBatch, initialPagePerformers) = coroutineScope {
                val acDeferred = async { fetchAutocompleteBatch(cleanQuery) }
                val pageDeferred = async { fetchPornstarsSearchPagePerformers(cleanQuery) }
                Pair(acDeferred.await(), pageDeferred.await())
            }

            val allPerformerBatches = mutableListOf<List<StashPerformer>>()
            allPerformerBatches.add(autocompleteBatch.performers)
            allPerformerBatches.add(initialPagePerformers)

            // Check if there is a strong match already or if we should follow up on typo-corrected suggestions
            val prelimMerged = mergeAndRankPerformers(allPerformerBatches, cleanQuery)
            val topPrelimScore = prelimMerged.firstOrNull()?.let { scorePerformerMatch(it.name, cleanQuery, 0) } ?: 0

            val candidateCorrectedNames = autocompleteBatch.suggestedQueries
                .mapNotNull { extractCandidatePerformerNameFromSuggestion(it, cleanQuery) }
                .distinctBy { it.lowercase(Locale.US) }
                .filter { !it.equals(cleanQuery, ignoreCase = true) }
                .take(2)

            if (candidateCorrectedNames.isNotEmpty() && (topPrelimScore < 1650 || initialPagePerformers.isEmpty())) {
                val correctedStr = candidateCorrectedNames.first()
                val (correctedAc, correctedPage) = coroutineScope {
                    val cAc = async { fetchAutocompleteBatch(correctedStr) }
                    val cPage = async {
                        if (initialPagePerformers.isEmpty() || topPrelimScore < 1650) {
                            fetchPornstarsSearchPagePerformers(correctedStr)
                        } else {
                            emptyList()
                        }
                    }
                    Pair(cAc.await(), cPage.await())
                }
                allPerformerBatches.add(0, correctedAc.performers)
                allPerformerBatches.add(correctedPage)
            }

            // Stage 4 fallback: If still empty or low-confidence, check /video/search for "Did you mean" & tagged performers
            var rankedPerformers = mergeAndRankPerformers(allPerformerBatches, cleanQuery)
            if (rankedPerformers.isEmpty()) {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val (_, vHtml) = fetchHtml("$BASE_URL/video/search?search=$encoded")
                if (vHtml.isNotBlank()) {
                    val vDoc = Jsoup.parse(vHtml, "$BASE_URL/video/search?search=$encoded")
                    val didYouMean = vDoc.selectFirst("a.orangeText[href*='/video/search?search='], .spellcheckSuggestion a")
                        ?.text()?.trim()?.takeIf { it.isNotBlank() }
                    if (didYouMean != null && !didYouMean.equals(cleanQuery, ignoreCase = true)) {
                        val (dymAc, dymPage) = coroutineScope {
                            val a = async { fetchAutocompleteBatch(didYouMean) }
                            val b = async { fetchPornstarsSearchPagePerformers(didYouMean) }
                            Pair(a.await(), b.await())
                        }
                        allPerformerBatches.add(dymAc.performers)
                        allPerformerBatches.add(dymPage)
                        rankedPerformers = mergeAndRankPerformers(allPerformerBatches, didYouMean)
                    }
                }
            }

            // Direct slug probe fallback (e.g., if user typed exact handle like "morgpie" or "solazola")
            val directSlug = cleanQuery.lowercase(Locale.US)
                .replace(Regex("""[^a-z0-9]+"""), "-")
                .trim('-')
            if (directSlug.length >= 3 && rankedPerformers.none { extractPerformerSlug(it.id) == directSlug }) {
                val directPage = scrapePornhubPerformerScenes("$BASE_URL/model/$directSlug")
                    .takeIf { it.scenes.isNotEmpty() }
                    ?: scrapePornhubPerformerScenes("$BASE_URL/pornstar/$directSlug")
                        .takeIf { it.scenes.isNotEmpty() }
                if (directPage?.performer != null) {
                    allPerformerBatches.add(0, listOf(directPage.performer))
                    rankedPerformers = mergeAndRankPerformers(allPerformerBatches, cleanQuery)
                }
            }

            if (rankedPerformers.isNotEmpty()) {
                // Find the first performer that actually yields scenes (handling empty duplicate accounts)
                var selectedIndex = 0
                var chosenPage: PornhubScenesPage? = null
                val maxCandidatesToTry = min(3, rankedPerformers.size)

                for (idx in 0 until maxCandidatesToTry) {
                    val candidate = rankedPerformers[idx]
                    val page = scrapePornhubPerformerScenes(
                        pageOrProfileUrl = candidate.id,
                        knownPerformer = candidate
                    )
                    if (page.scenes.isNotEmpty()) {
                        selectedIndex = idx
                        chosenPage = page
                        break
                    }
                }

                val finalPage = chosenPage ?: PornhubScenesPage(emptyList(), null, rankedPerformers.first())
                val mutablePerformers = rankedPerformers.toMutableList()

                // Update selected performer with resolved canonical URL and avatar from their profile page
                finalPage.performer?.let { resolved ->
                    mutablePerformers[selectedIndex] = resolved
                    if (!resolved.imageUrl.isNullOrBlank()) {
                        avatarCache[resolved.id.lowercase()] = resolved.imageUrl
                    }
                }

                // If selectedIndex > 0, move the working performer to index 0 so UI auto-selects it
                if (selectedIndex > 0) {
                    val working = mutablePerformers.removeAt(selectedIndex)
                    mutablePerformers.add(0, working)
                }

                // Concurrently enrich missing avatars for top 6 performers in the horizontal bar
                coroutineScope {
                    mutablePerformers.take(6).mapIndexed { idx, perf ->
                        async {
                            if (perf.imageUrl.isNullOrBlank()) {
                                val avatar = withTimeoutOrNull(3_500L) {
                                    fetchQuickProfileAvatar(perf.id)
                                }
                                if (!avatar.isNullOrBlank()) {
                                    Pair(idx, perf.copy(imageUrl = avatar, images = listOf(avatar)))
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull().forEach { (idx, updatedPerf) ->
                        mutablePerformers[idx] = updatedPerf
                    }
                }

                val result = PornhubSearchResult(
                    performers = mutablePerformers,
                    scenes = finalPage.scenes,
                    nextPageUrl = finalPage.nextPageUrl
                )
                searchCache[cacheKey] = CacheEntry(result)
                return@withContext result
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error searching Pornhub pornstars for '$cleanQuery'", e)
        }

        PornhubSearchResult(emptyList(), emptyList(), null)
    }

    suspend fun exploreTrendingPornhubStars(): PornhubSearchResult = withContext(Dispatchers.IO) {
        val cacheKey = "__explore_trending_pornhub__"
        searchCache[cacheKey]?.takeIf { it.isValid() }?.let {
            return@withContext it.data
        }

        try {
            val url = "$BASE_URL/pornstars"
            val (status, html) = fetchHtml(url)
            if (status in 200..299 && html.isNotBlank()) {
                val doc = Jsoup.parse(html, url)
                val performers = parsePerformersFromSearchDoc(doc, "")
                if (performers.isNotEmpty()) {
                    val firstPerformer = performers.first()
                    val firstPage = scrapePornhubPerformerScenes(
                        pageOrProfileUrl = firstPerformer.id,
                        knownPerformer = firstPerformer
                    )
                    val result = PornhubSearchResult(
                        performers = performers,
                        scenes = firstPage.scenes,
                        nextPageUrl = firstPage.nextPageUrl
                    )
                    searchCache[cacheKey] = CacheEntry(result)
                    return@withContext result
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error exploring trending Pornhub stars", e)
        }

        PornhubSearchResult(emptyList(), emptyList(), null)
    }

    private fun parsePerformersFromSearchDoc(doc: Document, query: String): List<StashPerformer> {
        val rawItems = doc.select(
            "ul#pornstarsSearchResult > li, ul#popularPornstars > li, ul.pornstarIndex > li"
        ).ifEmpty {
            doc.select("li.performerCard")
        }

        val performers = mutableListOf<StashPerformer>()
        val seenIds = HashSet<String>()

        for (li in rawItems) {
            if (li.hasClass("sniperModeEngaged")) continue

            val linkEl = li.selectFirst("a.title[href*='/pornstar/'], a.title[href*='/model/']")
                ?: li.selectFirst("a.performerName[href*='/pornstar/'], a.performerName[href*='/model/']")
                ?: li.selectFirst("a[href*='/pornstar/'], a[href*='/model/']")
                ?: continue

            val rawHref = linkEl.attr("href").trim()
            if (rawHref.isBlank()) continue
            val profileUrl = normalizePerformerBaseUrl(rawHref)
            val key = profileUrl.lowercase()
            if (key in seenIds) continue

            val imgEl = li.selectFirst("img")
            val nameCandidate = linkEl.text().trim()
                .ifBlank { linkEl.attr("title").trim() }
                .ifBlank { imgEl?.attr("alt")?.trim().orEmpty() }
            if (nameCandidate.length < 2 || nameCandidate.lowercase() in BLOCKED_PERFORMER_WORDS) continue

            val avatarUrl = extractCleanImageUrl(imgEl)
            seenIds.add(key)

            val cleanName = Parser.unescapeEntities(nameCandidate, false).trim()
            val perfObj = StashPerformer(
                id = profileUrl,
                name = cleanName,
                gender = genderCache[key],
                imageUrl = avatarUrl,
                images = listOfNotNull(avatarUrl)
            )
            if (!avatarUrl.isNullOrBlank()) {
                avatarCache[key] = avatarUrl
            }
            if (cleanName.contains(" ") && cleanName.length >= 5) {
                knownPerformerNamesCache[cleanName.lowercase(Locale.US)] = perfObj
            }
            performers.add(perfObj)
        }

        return if (query.isBlank()) {
            performers
        } else {
            performers.mapIndexed { idx, perf -> Pair(idx, perf) }
                .sortedByDescending { (idx, perf) -> scorePerformerMatch(perf.name, query, idx) }
                .map { it.second }
        }
    }

    suspend fun scrapePornhubPerformerScenes(
        pageOrProfileUrl: String,
        knownPerformer: StashPerformer? = null
    ): PornhubScenesPage = withContext(Dispatchers.IO) {
        val normalizedInput = normalizeUrl(pageOrProfileUrl)
            ?: return@withContext PornhubScenesPage(emptyList(), null, knownPerformer)

        scenesPageCache[normalizedInput]?.takeIf { it.isValid() }?.let {
            return@withContext it.data
        }

        val isExplicitPaginatedOrSubPage =
            normalizedInput.contains("?page=", ignoreCase = true) ||
                normalizedInput.contains("&page=", ignoreCase = true) ||
                normalizedInput.substringBefore("?").trimEnd('/').endsWith("/videos", ignoreCase = true)

        val baseProfile = normalizePerformerBaseUrl(normalizedInput)
        val slug = extractPerformerSlug(baseProfile)
        val alternateBaseProfile = when {
            baseProfile.contains("/pornstar/", ignoreCase = true) && slug.isNotBlank() -> "$BASE_URL/model/$slug"
            baseProfile.contains("/model/", ignoreCase = true) && slug.isNotBlank() -> "$BASE_URL/pornstar/$slug"
            else -> null
        }

        val candidateUrls = if (isExplicitPaginatedOrSubPage) {
            listOfNotNull(
                normalizedInput,
                baseProfile,
                alternateBaseProfile?.let { "$it/videos" },
                alternateBaseProfile
            ).distinct()
        } else {
            listOfNotNull(
                "$baseProfile/videos",
                baseProfile,
                alternateBaseProfile?.let { "$it/videos" },
                alternateBaseProfile
            ).distinct()
        }

        for (targetUrl in candidateUrls) {
            try {
                val fetchRes = fetchHtmlWithFinalUrl(targetUrl)
                if (fetchRes.status !in 200..299 || fetchRes.body.isBlank()) continue
                // Skip if PornHub redirected a non-existent /pornstar/<slug> to the /pornstars directory
                val finalCleanPath = fetchRes.finalUrl.substringBefore("?").trimEnd('/')
                if (finalCleanPath.endsWith("/pornstars", ignoreCase = true)) continue

                val doc = Jsoup.parse(fetchRes.body, fetchRes.finalUrl)
                val extractedPerf = extractPerformerFromProfileDoc(doc, fetchRes.finalUrl)
                val resolvedPerformer = when {
                    knownPerformer == null -> extractedPerf
                    extractedPerf != null -> knownPerformer.copy(
                        id = extractedPerf.id.ifBlank { knownPerformer.id },
                        imageUrl = knownPerformer.imageUrl?.takeIf { it.isNotBlank() } ?: extractedPerf.imageUrl,
                        images = if (knownPerformer.images.isNotEmpty()) knownPerformer.images else extractedPerf.images
                    )
                    else -> knownPerformer
                }
                val scenes = parseScenesFromPerformerDoc(doc, resolvedPerformer)

                if (scenes.isNotEmpty()) {
                    val nextPageHref = doc.selectFirst("li.page_next a[href]")?.attr("href")?.trim()
                    val nextPageUrl = normalizeUrl(nextPageHref)
                    val pageResult = PornhubScenesPage(
                        scenes = scenes,
                        nextPageUrl = nextPageUrl,
                        performer = resolvedPerformer
                    )
                    scenesPageCache[normalizedInput] = CacheEntry(pageResult)
                    return@withContext pageResult
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Attempt failed for Pornhub performer URL $targetUrl: ${e.message}")
            }
        }

        PornhubScenesPage(emptyList(), null, knownPerformer)
    }

    private fun extractPerformerFromProfileDoc(doc: Document, pageUrl: String): StashPerformer? {
        val name = doc.selectFirst(".nameSubscribe h1, h1[itemprop=name], .top ProfileHeader h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("Porn Videos")
                ?.substringBefore("-")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val normalizedId = normalizePerformerBaseUrl(pageUrl)
        val key = normalizedId.lowercase()
        val gender = extractGenderFromProfileDoc(doc)?.also { genderCache[key] = it } ?: genderCache[key]

        val avatarImg = doc.selectFirst("img#getAvatar, .thumbImage img, .avatarWrapper img")
        val avatarUrl = extractCleanImageUrl(avatarImg)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        if (!avatarUrl.isNullOrBlank()) {
            avatarCache[key] = avatarUrl
        }

        val cleanName = Parser.unescapeEntities(name, false).trim()
        val perf = StashPerformer(
            id = normalizedId,
            name = cleanName,
            gender = gender,
            imageUrl = avatarUrl,
            images = listOfNotNull(avatarUrl)
        )
        if (cleanName.contains(" ") && cleanName.length >= 5) {
            knownPerformerNamesCache[cleanName.lowercase(Locale.US)] = perf
        }
        return perf
    }

    private fun normalizeChannelBaseUrl(rawUrl: String): String {
        val full = normalizeUrl(rawUrl) ?: return BASE_URL
        return full
            .substringBefore("?")
            .substringBefore("#")
            .trimEnd('/')
            .removeSuffix("/videos")
    }

    private fun extractChannelSlug(channelUrlOrId: String): String {
        val normalized = normalizeChannelBaseUrl(channelUrlOrId).lowercase()
        return normalized.substringAfterLast('/').trim()
    }

    private fun parseChannelsFromDoc(doc: Document, query: String): List<StashStudio> {
        val channelCards = doc.select("div.channelsWrapper, li.mainChannels > div, div.description")
        val studios = mutableListOf<StashStudio>()
        val seenIds = HashSet<String>()

        for (card in channelCards) {
            val linkEl = card.selectFirst("div.descriptionContainer a.usernameLink[href*='/channels/']")
                ?: card.selectFirst("a.usernameLink[href*='/channels/']")
                ?: card.selectFirst("div.avatar a[href*='/channels/']")
                ?: card.selectFirst("a[href*='/channels/']")
                ?: continue

            val rawHref = linkEl.attr("href").trim()
            if (rawHref.isBlank() || rawHref.contains("/channels/search", ignoreCase = true)) continue
            val channelUrl = normalizeChannelBaseUrl(rawHref)
            val slug = extractChannelSlug(channelUrl)
            if (slug.isBlank() || slug.equals("channels", ignoreCase = true) || slug.equals("search", ignoreCase = true)) continue

            val key = channelUrl.lowercase()
            if (key in seenIds) continue

            val imgEl = card.selectFirst("div.avatar img, img")
            val rawName = card.selectFirst("div.descriptionContainer a.usernameLink")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: linkEl.text().trim().takeIf { it.isNotBlank() && !it.startsWith("Rank", ignoreCase = true) }
                ?: imgEl?.attr("alt")?.replace(Regex("(?i)\\s*avatar\\s*$"), "")?.trim().orEmpty()

            val cleanName = Parser.unescapeEntities(rawName, false).trim()
            if (cleanName.length < 2 || cleanName.lowercase() in BLOCKED_PERFORMER_WORDS) continue

            val logoUrl = extractCleanImageUrl(imgEl)
            if (!logoUrl.isNullOrBlank()) {
                channelAvatarCache[key] = logoUrl
            }
            seenIds.add(key)

            studios.add(
                StashStudio(
                    id = channelUrl,
                    name = cleanName,
                    logoUrl = logoUrl
                )
            )
        }

        return if (query.isBlank()) {
            studios
        } else {
            studios.mapIndexed { idx, studio -> Pair(idx, studio) }
                .sortedByDescending { (idx, studio) -> scorePerformerMatch(studio.name, query, idx) }
                .map { it.second }
        }
    }

    private suspend fun fetchChannelsSearchPageStudios(query: String): List<StashStudio> {
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = "$BASE_URL/channels/search?channelSearch=$encoded"
            val (status, html) = fetchHtml(searchUrl)
            if (status in 200..299 && html.isNotBlank()) {
                val doc = Jsoup.parse(html, searchUrl)
                parseChannelsFromDoc(doc, query)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    private suspend fun fetchQuickChannelAvatar(channelUrl: String): String? {
        val key = normalizeChannelBaseUrl(channelUrl).lowercase()
        channelAvatarCache[key]?.let { return it }

        val slug = extractChannelSlug(channelUrl)
        if (slug.isBlank()) return null
        val targetUrl = "$BASE_URL/channels/$slug"

        return try {
            val res = fetchHtmlWithFinalUrl(targetUrl)
            if (res.status !in 200..299 || res.body.isBlank()) return null
            val finalClean = res.finalUrl.substringBefore("?").trimEnd('/')
            if (finalClean.endsWith("/channels", ignoreCase = true)) return null

            val doc = Jsoup.parse(res.body, res.finalUrl)
            val avatarImg = doc.selectFirst("img#getAvatar, .avatar img, .thumbImage img")
            val avatarUrl = extractCleanImageUrl(avatarImg)
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            if (!avatarUrl.isNullOrBlank()) {
                channelAvatarCache[key] = avatarUrl
                return avatarUrl
            }
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    private fun mergeAndRankChannels(
        lists: List<List<StashStudio>>,
        query: String
    ): List<StashStudio> {
        val bySlugOrName = LinkedHashMap<String, StashStudio>()

        for (list in lists) {
            for (studio in list) {
                val slug = extractChannelSlug(studio.id)
                val nameKey = studio.name.trim().lowercase(Locale.US).replace(Regex("""\s+"""), "-")
                val dedupKey = slug.ifBlank { nameKey }

                val existing = bySlugOrName[dedupKey]
                if (existing == null) {
                    bySlugOrName[dedupKey] = studio
                } else {
                    val mergedLogo = existing.logoUrl?.takeIf { it.isNotBlank() }
                        ?: studio.logoUrl?.takeIf { it.isNotBlank() }
                    bySlugOrName[dedupKey] = existing.copy(
                        logoUrl = mergedLogo
                    )
                }
            }
        }

        val combined = bySlugOrName.values.toList()
        if (query.isBlank()) return combined

        return combined.mapIndexed { idx, studio -> Pair(idx, studio) }
            .sortedByDescending { (idx, studio) ->
                val matchScore = scorePerformerMatch(studio.name, query, idx)
                val logoBonus = if (!studio.logoUrl.isNullOrBlank()) 15 else 0
                matchScore + logoBonus
            }
            .map { it.second }
    }

    suspend fun searchPornhubChannels(query: String): PornhubChannelSearchResult = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext PornhubChannelSearchResult(emptyList(), emptyList(), null)

        val cacheKey = cleanQuery.lowercase(Locale.US)
        channelSearchCache[cacheKey]?.takeIf { it.isValid() }?.let {
            return@withContext it.data
        }

        // 1. Direct PornHub Channel URL
        if (cleanQuery.contains("pornhub.com/channels/", ignoreCase = true)) {
            val pageResult = scrapePornhubChannelScenes(cleanQuery)
            val studioList = listOfNotNull(pageResult.studio)
            val result = PornhubChannelSearchResult(studioList, pageResult.scenes, pageResult.nextPageUrl)
            if (pageResult.scenes.isNotEmpty()) {
                channelSearchCache[cacheKey] = CacheEntry(result)
            }
            return@withContext result
        }

        // 1b. Direct PornHub Video URL pasted in Studio tab
        if (VideoResolvers.isPornhubUrl(cleanQuery) && VIEWKEY_REGEX.containsMatchIn(cleanQuery)) {
            val singleScene = scrapeSingleVideoAsStashScene(cleanQuery)
            val studioFromScene = singleScene?.let { sc ->
                val sName = sc.studioName?.takeIf { it.isNotBlank() } ?: "PornHub"
                val sId = sc.studioId?.takeIf { it.isNotBlank() } ?: "pornhub"
                StashStudio(id = sId, name = sName, logoUrl = sc.studioLogo)
            }
            val studios = listOfNotNull(studioFromScene)
            val scenes = listOfNotNull(singleScene)
            val result = PornhubChannelSearchResult(studios, scenes, null)
            if (scenes.isNotEmpty()) {
                channelSearchCache[cacheKey] = CacheEntry(result)
            }
            return@withContext result
        }

        // 1c. Direct PornHub Pornstar / Model URL pasted in Studio tab
        if (cleanQuery.contains("pornhub.com/pornstar/", ignoreCase = true) ||
            cleanQuery.contains("pornhub.com/model/", ignoreCase = true)
        ) {
            val perfPage = scrapePornhubPerformerScenes(cleanQuery)
            val studiosFromScenes = perfPage.scenes.mapNotNull { sc ->
                val sId = sc.studioId?.takeIf { it.isNotBlank() && !it.equals("pornhub", ignoreCase = true) } ?: return@mapNotNull null
                val sName = sc.studioName?.takeIf { it.isNotBlank() && !it.equals("PornHub", ignoreCase = true) } ?: return@mapNotNull null
                StashStudio(id = sId, name = sName, logoUrl = sc.studioLogo)
            }.distinctBy { it.id.lowercase() }
            val result = PornhubChannelSearchResult(studiosFromScenes, perfPage.scenes, perfPage.nextPageUrl)
            if (perfPage.scenes.isNotEmpty()) {
                channelSearchCache[cacheKey] = CacheEntry(result)
            }
            return@withContext result
        }

        try {
            val (autocompleteBatch, initialPageStudios) = coroutineScope {
                val acDeferred = async { fetchAutocompleteBatch(cleanQuery) }
                val pageDeferred = async { fetchChannelsSearchPageStudios(cleanQuery) }
                val popularDeferred = async {
                    if (cachedPopularChannels.isEmpty()) {
                        try {
                            val (st, html) = fetchHtml("$BASE_URL/channels")
                            if (st in 200..299 && html.isNotBlank()) {
                                cachedPopularChannels = parseChannelsFromDoc(Jsoup.parse(html, "$BASE_URL/channels"), "")
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                popularDeferred.await()
                Pair(acDeferred.await(), pageDeferred.await())
            }

            val allStudioBatches = mutableListOf<List<StashStudio>>()
            allStudioBatches.add(autocompleteBatch.channels)
            allStudioBatches.add(initialPageStudios)

            // Fuzzy match against cached popular channels for typo tolerance (e.g., "brazers", "bratty siss", "famlixxx")
            if (cachedPopularChannels.isNotEmpty()) {
                val fuzzyPopular = cachedPopularChannels.filter { ch ->
                    scorePerformerMatch(ch.name, cleanQuery, 0) >= 1050
                }
                if (fuzzyPopular.isNotEmpty()) {
                    allStudioBatches.add(fuzzyPopular)
                }
            }

            val prelimMerged = mergeAndRankChannels(allStudioBatches, cleanQuery)
            val topPrelimScore = prelimMerged.firstOrNull()?.let { scorePerformerMatch(it.name, cleanQuery, 0) } ?: 0

            val candidateCorrectedNames = autocompleteBatch.suggestedQueries
                .mapNotNull { extractCandidatePerformerNameFromSuggestion(it, cleanQuery) }
                .distinctBy { it.lowercase(Locale.US) }
                .filter { !it.equals(cleanQuery, ignoreCase = true) }
                .take(2)

            if (candidateCorrectedNames.isNotEmpty() && (topPrelimScore < 1650 || initialPageStudios.isEmpty())) {
                val correctedStr = candidateCorrectedNames.first()
                val (correctedAc, correctedPage) = coroutineScope {
                    val cAc = async { fetchAutocompleteBatch(correctedStr) }
                    val cPage = async { fetchChannelsSearchPageStudios(correctedStr) }
                    Pair(cAc.await(), cPage.await())
                }
                allStudioBatches.add(0, correctedAc.channels)
                allStudioBatches.add(correctedPage)
            }

            var rankedStudios = mergeAndRankChannels(allStudioBatches, cleanQuery)

            // Direct slug probe fallback (e.g. if user typed "brazzers" or "pure-taboo" or "pure taboo")
            val directSlug = cleanQuery.lowercase(Locale.US)
                .replace(Regex("""[^a-z0-9]+"""), "-")
                .trim('-')
            val compactSlug = cleanQuery.lowercase(Locale.US)
                .replace(Regex("""[^a-z0-9]+"""), "")
                .trim()

            for (slugCandidate in listOf(directSlug, compactSlug).distinct()) {
                if (slugCandidate.length >= 3 && rankedStudios.none { extractChannelSlug(it.id) == slugCandidate }) {
                    val directPage = scrapePornhubChannelScenes("$BASE_URL/channels/$slugCandidate")
                        .takeIf { it.scenes.isNotEmpty() }
                    if (directPage?.studio != null) {
                        allStudioBatches.add(0, listOf(directPage.studio))
                        rankedStudios = mergeAndRankChannels(allStudioBatches, cleanQuery)
                        break
                    }
                }
            }

            if (rankedStudios.isNotEmpty()) {
                var selectedIndex = 0
                var chosenPage: PornhubChannelScenesPage? = null
                val maxCandidatesToTry = min(3, rankedStudios.size)

                for (idx in 0 until maxCandidatesToTry) {
                    val candidate = rankedStudios[idx]
                    val page = scrapePornhubChannelScenes(
                        pageOrChannelUrl = candidate.id,
                        knownStudio = candidate
                    )
                    if (page.scenes.isNotEmpty()) {
                        selectedIndex = idx
                        chosenPage = page
                        break
                    }
                }

                val finalPage = chosenPage ?: PornhubChannelScenesPage(emptyList(), null, rankedStudios.first())
                val mutableStudios = rankedStudios.toMutableList()

                finalPage.studio?.let { resolved ->
                    mutableStudios[selectedIndex] = resolved
                    if (!resolved.logoUrl.isNullOrBlank()) {
                        channelAvatarCache[resolved.id.lowercase()] = resolved.logoUrl
                    }
                }

                if (selectedIndex > 0) {
                    val working = mutableStudios.removeAt(selectedIndex)
                    mutableStudios.add(0, working)
                }

                // Concurrently enrich missing logos for top 6 channels in the horizontal bar
                coroutineScope {
                    mutableStudios.take(6).mapIndexed { idx, studio ->
                        async {
                            if (studio.logoUrl.isNullOrBlank()) {
                                val logo = withTimeoutOrNull(3_500L) {
                                    fetchQuickChannelAvatar(studio.id)
                                }
                                if (!logo.isNullOrBlank()) {
                                    Pair(idx, studio.copy(logoUrl = logo))
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull().forEach { (idx, updatedStudio) ->
                        mutableStudios[idx] = updatedStudio
                    }
                }

                val result = PornhubChannelSearchResult(
                    studios = mutableStudios,
                    scenes = finalPage.scenes,
                    nextPageUrl = finalPage.nextPageUrl
                )
                channelSearchCache[cacheKey] = CacheEntry(result)
                return@withContext result
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error searching Pornhub channels for '$cleanQuery'", e)
        }

        PornhubChannelSearchResult(emptyList(), emptyList(), null)
    }

    suspend fun exploreTrendingPornhubChannels(): PornhubChannelSearchResult = withContext(Dispatchers.IO) {
        val cacheKey = "__explore_trending_pornhub_channels__"
        channelSearchCache[cacheKey]?.takeIf { it.isValid() }?.let {
            return@withContext it.data
        }

        try {
            val url = "$BASE_URL/channels"
            val (status, html) = fetchHtml(url)
            if (status in 200..299 && html.isNotBlank()) {
                val doc = Jsoup.parse(html, url)
                val studios = parseChannelsFromDoc(doc, "")
                if (studios.isNotEmpty()) {
                    cachedPopularChannels = studios
                    val firstStudio = studios.first()
                    val firstPage = scrapePornhubChannelScenes(
                        pageOrChannelUrl = firstStudio.id,
                        knownStudio = firstStudio
                    )
                    val result = PornhubChannelSearchResult(
                        studios = studios,
                        scenes = firstPage.scenes,
                        nextPageUrl = firstPage.nextPageUrl
                    )
                    channelSearchCache[cacheKey] = CacheEntry(result)
                    return@withContext result
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error exploring trending Pornhub channels", e)
        }

        PornhubChannelSearchResult(emptyList(), emptyList(), null)
    }

    suspend fun scrapePornhubChannelScenes(
        pageOrChannelUrl: String,
        knownStudio: StashStudio? = null
    ): PornhubChannelScenesPage = withContext(Dispatchers.IO) {
        val normalizedInput = normalizeUrl(pageOrChannelUrl)
            ?: return@withContext PornhubChannelScenesPage(emptyList(), null, knownStudio)

        channelScenesPageCache[normalizedInput]?.takeIf { it.isValid() }?.let {
            return@withContext it.data
        }

        val isExplicitPaginatedOrSubPage =
            normalizedInput.contains("?page=", ignoreCase = true) ||
                normalizedInput.contains("&page=", ignoreCase = true) ||
                normalizedInput.substringBefore("?").trimEnd('/').endsWith("/videos", ignoreCase = true)

        val baseChannel = normalizeChannelBaseUrl(normalizedInput)
        val candidateUrls = if (isExplicitPaginatedOrSubPage) {
            listOf(normalizedInput, baseChannel).distinct()
        } else {
            listOf(baseChannel, "$baseChannel/videos").distinct()
        }

        for (targetUrl in candidateUrls) {
            try {
                val fetchRes = fetchHtmlWithFinalUrl(targetUrl)
                if (fetchRes.status !in 200..299 || fetchRes.body.isBlank()) continue
                val finalCleanPath = fetchRes.finalUrl.substringBefore("?").trimEnd('/')
                if (finalCleanPath.endsWith("/channels", ignoreCase = true)) continue

                val doc = Jsoup.parse(fetchRes.body, fetchRes.finalUrl)
                val extractedStudio = extractChannelFromDoc(doc, fetchRes.finalUrl)
                val resolvedStudio = when {
                    knownStudio == null -> extractedStudio
                    extractedStudio != null -> knownStudio.copy(
                        id = extractedStudio.id.ifBlank { knownStudio.id },
                        logoUrl = knownStudio.logoUrl?.takeIf { it.isNotBlank() } ?: extractedStudio.logoUrl
                    )
                    else -> knownStudio
                }
                val scenes = parseScenesFromPerformerDoc(doc, performer = null, studio = resolvedStudio)

                if (scenes.isNotEmpty()) {
                    val nextPageHref = doc.selectFirst("li.page_next a[href]")?.attr("href")?.trim()
                    val nextPageUrl = normalizeUrl(nextPageHref)
                    val pageResult = PornhubChannelScenesPage(
                        scenes = scenes,
                        nextPageUrl = nextPageUrl,
                        studio = resolvedStudio
                    )
                    channelScenesPageCache[normalizedInput] = CacheEntry(pageResult)
                    return@withContext pageResult
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Attempt failed for Pornhub channel URL $targetUrl: ${e.message}")
            }
        }

        PornhubChannelScenesPage(emptyList(), null, knownStudio)
    }

    private fun extractChannelFromDoc(doc: Document, pageUrl: String): StashStudio? {
        val rawName = doc.selectFirst(".titleWrapper h1, .header h1, h1")?.ownText()?.trim()
            ?.ifBlank { doc.selectFirst(".titleWrapper h1, .header h1, h1")?.text()?.trim() }
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("Porn Videos")
                ?.substringBefore("-")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val avatarImg = doc.selectFirst("img#getAvatar, .avatar img, .thumbImage img")
        val logoUrl = extractCleanImageUrl(avatarImg)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }

        return StashStudio(
            id = normalizeChannelBaseUrl(pageUrl),
            name = Parser.unescapeEntities(rawName, false).trim(),
            logoUrl = logoUrl
        )
    }

    private fun parseScenesFromPerformerDoc(
        doc: Document,
        performer: StashPerformer?,
        studio: StashStudio? = null
    ): List<StashScene> {
        val primaryContainers = listOf(
            "ul#mostRecentVideosSection > li.pcVideoListItem",
            "ul#uploadedVideosSection > li.pcVideoListItem",
            "ul#pornstarsVideoSection > li.pcVideoListItem",
            "ul#claimedRecentVideoSection > li.pcVideoListItem",
            "ul#claimedUploadedVideoSection > li.pcVideoListItem",
            "ul#showAllChanelVideos > li.pcVideoListItem",
            "ul#moreData > li.pcVideoListItem"
        )

        val videoElements = mutableListOf<Element>()
        for (selector in primaryContainers) {
            val found = doc.select(selector)
            if (found.isNotEmpty()) {
                videoElements.addAll(found)
            }
        }

        if (videoElements.isEmpty()) {
            val fallback = doc.select("ul.videos li.pcVideoListItem").filterNot { li ->
                li.parents().any { p ->
                    val id = p.id()
                    id == "headerMainMenu" || id == "hottestMenuSection" || id == "playListHeaderSection"
                }
            }
            videoElements.addAll(fallback)
        }

        val scenes = mutableListOf<StashScene>()
        val seenVkeys = HashSet<String>()

        for (li in videoElements) {
            val vkey = li.attr("data-video-vkey").trim().ifBlank {
                val href = li.selectFirst("a[href*='viewkey=']")?.attr("href").orEmpty()
                VIEWKEY_REGEX.find(href)?.groupValues?.getOrNull(1).orEmpty()
            }
            if (vkey.isBlank() || !seenVkeys.add(vkey.lowercase())) continue

            val videoUrl = "$BASE_URL/view_video.php?viewkey=$vkey"
            val titleEl = li.selectFirst("a.thumbnailTitle, span.title a, a.js-linkVideoThumb")
            val rawTitle = titleEl?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                ?: titleEl?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: li.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            val cleanTitle = Parser.unescapeEntities(rawTitle, false).trim()
            if (cleanTitle.isBlank()) continue

            val imgEl = li.selectFirst("img")
            val coverUrl = extractCleanImageUrl(imgEl)
            val mediabookUrl = imgEl?.attr("data-mediabook")?.trim()

            // Extract date from CDN path (/videos/YYYYMM/DD/) or relative "added" text
            val dateStr = extractDateFromCdnUrl(coverUrl)
                ?: extractDateFromCdnUrl(mediabookUrl)
                ?: parseRelativeDateToYyyyMmDd(li.selectFirst("var.added")?.text())

            // Resolve performers for card display
            val performersList = if (performer != null) {
                listOf(performer)
            } else {
                val cardPerfs = mutableListOf<StashPerformer>()
                val seenCardPerfNames = HashSet<String>()
                val perfLinks = li.select(".usernameWrap a[href*='/pornstar/'], .usernameWrap a[href*='/model/']")
                for (pLink in perfLinks) {
                    val pHref = pLink.attr("href").trim()
                    val pName = Parser.unescapeEntities(pLink.text().trim(), false)
                    if (pHref.isNotBlank() && pName.length >= 2 && seenCardPerfNames.add(pName.lowercase(Locale.US))) {
                        val pId = normalizePerformerBaseUrl(pHref)
                        val cachedAvatar = avatarCache[pId.lowercase()]
                        cardPerfs.add(
                            StashPerformer(
                                id = pId,
                                name = pName,
                                imageUrl = cachedAvatar,
                                images = listOfNotNull(cachedAvatar)
                            )
                        )
                    }
                }
                if (cardPerfs.isEmpty() && knownPerformerNamesCache.isNotEmpty()) {
                    val lowerTitle = cleanTitle.lowercase(Locale.US)
                    for ((knownLowerName, knownPerf) in knownPerformerNamesCache) {
                        if (lowerTitle.contains(knownLowerName) && seenCardPerfNames.add(knownLowerName)) {
                            cardPerfs.add(knownPerf)
                            if (cardPerfs.size >= 3) break
                        }
                    }
                }
                cardPerfs
            }

            // Resolve studio/channel for card display (extract real channel in Actor mode when present on card)
            var cardChannelId: String? = studio?.id?.takeIf { it.isNotBlank() }
            var cardChannelName: String? = studio?.name?.takeIf { it.isNotBlank() }
            var cardChannelLogo: String? = studio?.logoUrl?.takeIf { it.isNotBlank() }

            if (cardChannelId == null || cardChannelName == null) {
                val chLink = li.selectFirst(".usernameWrap a[href*='/channels/'], a[href*='/channels/']")
                if (chLink != null) {
                    val rawChHref = chLink.attr("href").trim()
                    val rawChName = Parser.unescapeEntities(chLink.text().trim(), false)
                    if (rawChHref.isNotBlank() && rawChName.length >= 2 && !rawChHref.contains("/channels/search", ignoreCase = true)) {
                        val normChUrl = normalizeChannelBaseUrl(rawChHref)
                        cardChannelId = normChUrl
                        cardChannelName = rawChName
                        cardChannelLogo = channelAvatarCache[normChUrl.lowercase()]
                    }
                } else {
                    val menuChannelTitle = li.selectFirst("v-more-action-menu[is-channel='1'][title]")
                        ?.attr("title")?.trim()?.takeIf { it.length >= 2 }
                    if (menuChannelTitle != null) {
                        val cleanChName = Parser.unescapeEntities(menuChannelTitle, false).trim()
                        val chSlug = cleanChName.lowercase(Locale.US).replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
                        val normChUrl = if (chSlug.isNotBlank()) "$BASE_URL/channels/$chSlug" else "pornhub"
                        cardChannelId = normChUrl
                        cardChannelName = cleanChName
                        cardChannelLogo = channelAvatarCache[normChUrl.lowercase()]
                    }
                }
            }

            val effectiveStudioId = cardChannelId ?: "pornhub"
            val effectiveStudioName = cardChannelName ?: "PornHub"
            val effectiveStudioLogo = cardChannelLogo

            scenes.add(
                StashScene(
                    id = "pornhub:$vkey",
                    title = cleanTitle,
                    details = "PornHub Video",
                    date = dateStr,
                    studioId = effectiveStudioId,
                    studioName = effectiveStudioName,
                    studioLogo = effectiveStudioLogo,
                    coverUrl = coverUrl,
                    femalePerformers = performersList,
                    directVideoUrl = videoUrl
                )
            )
        }

        return scenes
    }

    private val HIGH_RES_COVER_HTML_REGEX = Regex(
        """https://[a-zA-Z0-9.-]*phncdn\.com/[^\s"'<>\\]+(?:/rs:fit:(?:1280:720|640:360)|\((?:m=[^)]*eaAaGwObaaa[^)]*)\))[^\s"'<>\\]*""",
        RegexOption.IGNORE_CASE
    )

    private fun scoreCoverUrlQuality(rawUrl: String): Int {
        val url = rawUrl.lowercase()
        var score = 100

        // Resolution & aspect ratio scoring
        when {
            url.contains("rs:fit:1920:1080") || url.contains("rs:fit:1280:720") -> score += 500
            url.contains("rs:fit:640:360") || url.contains("eaaagwobaaa") -> score += 400 // Original 16:9 widescreen high-res
            url.contains("/original/") && !url.contains("rs:fit:320:") && !url.contains("rs:fit:258:") &&
                !url.contains("eaftggaaa") && !url.contains("eqglhgaaa") -> score += 300
            url.contains("rs:fit:350:196") -> score += 220
            url.contains("rs:fit:320:180") || url.contains("eaftggaaa") -> score += 150 // 16:9 medium grid thumb
            url.contains("rs:fit:258:145") -> score += 120
            url.contains("eqglhgaaa") || url.contains(":400:300") || url.contains(":320:240") -> score += 80 // 4:3 cropped flashvars thumb
        }

        // CDN reliability scoring (pix-fl and ei.phncdn are CDN-friendlier than pix-cdn77)
        when {
            url.contains("pix-fl.phncdn.com") || url.contains("ei.phncdn.com") -> score += 25
            url.contains("pix-egi.phncdn.com") -> score += 20
            url.contains("pix-cdn77.phncdn.com") -> score += 10
        }

        return score
    }

    fun selectBestPornhubCoverUrl(candidates: List<String?>, rawHtml: String? = null): String? {
        val allCandidates = mutableListOf<String>()

        for (raw in candidates) {
            val cleaned = raw
                ?.replace("\\/", "/")
                ?.let { Parser.unescapeEntities(it, false) }
                ?.let { normalizeUrl(it) }
            if (!cleaned.isNullOrBlank() &&
                !cleaned.startsWith("data:", ignoreCase = true) &&
                !cleaned.endsWith(".avif", ignoreCase = true) &&
                !cleaned.endsWith(".heic", ignoreCase = true) &&
                !cleaned.contains("/timeline/", ignoreCase = true)
            ) {
                allCandidates.add(cleaned)
            }
        }

        if (!rawHtml.isNullOrBlank()) {
            val unescapedHtml = rawHtml.replace("\\/", "/")
            for (match in HIGH_RES_COVER_HTML_REGEX.findAll(unescapedHtml)) {
                val cleaned = Parser.unescapeEntities(match.value, false)
                    .let { normalizeUrl(it) }
                if (!cleaned.isNullOrBlank() && !cleaned.contains("/timeline/", ignoreCase = true)) {
                    allCandidates.add(cleaned)
                }
            }
        }

        return allCandidates
            .distinct()
            .maxByOrNull { scoreCoverUrlQuality(it) }
    }

    private fun extractCleanImageUrl(imgEl: Element?): String? {
        if (imgEl == null) return null
        val candidates = listOf(
            imgEl.attr("data-image"),
            imgEl.attr("data-thumb_url"),
            imgEl.attr("data-mediumthumb"),
            imgEl.attr("data-src"),
            imgEl.attr("src")
        )
        return selectBestPornhubCoverUrl(candidates)
    }

    private fun extractDateFromCdnUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val m = CDN_DATE_REGEX.find(url) ?: return null
        val year = m.groupValues[1].toIntOrNull() ?: return null
        val month = m.groupValues[2].toIntOrNull() ?: return null
        val day = m.groupValues[3].toIntOrNull() ?: return null
        if (year in 2005..2035 && month in 1..12 && day in 1..31) {
            return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
        }
        return null
    }

    private fun parseRelativeDateToYyyyMmDd(rawAdded: String?): String? {
        val text = rawAdded?.trim()?.lowercase() ?: return null
        if (text.isBlank()) return null

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        when {
            text == "today" || text == "just now" -> Unit
            text == "yesterday" -> cal.add(Calendar.DAY_OF_YEAR, -1)
            else -> {
                val m = RELATIVE_DATE_REGEX.find(text)
                if (m != null) {
                    val amount = m.groupValues[1].toIntOrNull() ?: return null
                    val unit = m.groupValues[2].lowercase()
                    // Guard against PornHub's Unix epoch 0 bug ("56 years ago")
                    if (unit == "year" && amount > 25) return null
                    when (unit) {
                        "second", "minute", "hour" -> Unit
                        "day" -> cal.add(Calendar.DAY_OF_YEAR, -amount)
                        "week" -> cal.add(Calendar.DAY_OF_YEAR, -(amount * 7))
                        "month" -> cal.add(Calendar.MONTH, -amount)
                        "year" -> cal.add(Calendar.YEAR, -amount)
                    }
                } else {
                    val millis = VideoResolvers.parsePornhubDateToMillis(rawAdded) ?: return null
                    cal.timeInMillis = millis
                }
            }
        }
        val computedYear = cal.get(Calendar.YEAR)
        if (computedYear !in 2005..2035) return null

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(cal.time)
    }

    suspend fun scrapeSingleVideoAsStashScene(rawVideoUrl: String): StashScene? = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawVideoUrl) ?: return@withContext null
        val vkey = VIEWKEY_REGEX.find(url)?.groupValues?.getOrNull(1) ?: url.hashCode().toString()
        val baseScene = StashScene(
            id = "pornhub:$vkey",
            title = "PornHub Scene",
            date = null,
            studioId = "pornhub",
            studioName = "PornHub",
            coverUrl = null,
            femalePerformers = emptyList(),
            directVideoUrl = url
        )
        enrichSceneForSave(baseScene)
    }

    suspend fun fetchFreshCoverUrl(sceneIdOrVideoUrl: String): String? = withContext(Dispatchers.IO) {
        val trimmed = sceneIdOrVideoUrl.trim()
        val videoUrl = when {
            trimmed.startsWith("pornhub:", ignoreCase = true) -> {
                val vkey = trimmed.removePrefix("pornhub:").trim()
                if (vkey.isNotBlank()) "$BASE_URL/view_video.php?viewkey=$vkey" else return@withContext null
            }
            VideoResolvers.isPornhubUrl(trimmed) -> normalizeUrl(trimmed) ?: return@withContext null
            else -> return@withContext null
        }

        try {
            val (status, html) = fetchHtml(videoUrl)
            if (status !in 200..299 || html.isBlank()) return@withContext null
            val doc = Jsoup.parse(html, videoUrl)
            val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val twitterImage = doc.selectFirst("meta[name=twitter:image]")?.attr("content")
            return@withContext selectBestPornhubCoverUrl(
                candidates = listOf(ogImage, twitterImage),
                rawHtml = html
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /**
     * Enriches a PornHub scene before saving to database so that Title, Cover, Performers (with avatars & gender check),
     * Release Date, Studio/Channel (with logo), and directVideoUrl are all populated accurately.
     */
    suspend fun enrichSceneForSave(
        scene: StashScene,
        blockMalePerformers: Boolean = false
    ): StashScene = withContext(Dispatchers.IO) {
        val videoUrl = scene.directVideoUrl?.takeIf { it.isNotBlank() }
            ?: scene.id.removePrefix("pornhub:").takeIf { it.isNotBlank() }?.let { "$BASE_URL/view_video.php?viewkey=$it" }
            ?: return@withContext scene

        try {
            val (status, html) = fetchHtml(videoUrl)
            if (status !in 200..299 || html.isBlank()) return@withContext scene

            val doc = Jsoup.parse(html, videoUrl)
            var extractedTitle: String? = null
            val coverCandidates = mutableListOf<String?>()
            var extractedDateStr: String? = null

            // 1. JSON-LD
            val ldJsonElements = doc.select("script[type=application/ld+json]")
            for (scriptEl in ldJsonElements) {
                val rawJson = scriptEl.data().trim()
                if (rawJson.isEmpty()) continue
                try {
                    val jsonObj = if (rawJson.startsWith("[")) {
                        val arr = JSONArray(rawJson)
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                            .firstOrNull { it.optString("@type").contains("VideoObject", ignoreCase = true) }
                            ?: arr.optJSONObject(0)
                    } else {
                        JSONObject(rawJson)
                    }
                    if (jsonObj != null) {
                        if (extractedTitle.isNullOrBlank()) {
                            extractedTitle = jsonObj.optString("name").takeIf { it.isNotBlank() }
                        }
                        when (val thumbAny = jsonObj.opt("thumbnailUrl")) {
                            is String -> coverCandidates.add(thumbAny)
                            is JSONArray -> {
                                for (i in 0 until thumbAny.length()) {
                                    coverCandidates.add(thumbAny.optString(i))
                                }
                            }
                        }
                        if (extractedDateStr.isNullOrBlank()) {
                            extractedDateStr = jsonObj.optString("uploadDate").takeIf { it.isNotBlank() }
                                ?: jsonObj.optString("datePublished").takeIf { it.isNotBlank() }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // 2. Meta OG / Twitter high-res covers
            coverCandidates.add(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            coverCandidates.add(doc.selectFirst("meta[name=twitter:image]")?.attr("content"))

            // 3. Flashvars fallback
            val flashvarsMatcher = Pattern.compile("var\\s+flashvars_\\d+\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL).matcher(html)
            if (flashvarsMatcher.find()) {
                val jsonStr = flashvarsMatcher.group(1)
                if (!jsonStr.isNullOrBlank()) {
                    try {
                        val flashvarsJson = JSONObject(jsonStr)
                        if (extractedTitle.isNullOrBlank()) {
                            val vTitle = flashvarsJson.optString("video_title")
                            if (vTitle.isNotBlank()) {
                                extractedTitle = vTitle.replace("+", " ").trim()
                            }
                        }
                        coverCandidates.add(flashvarsJson.optString("image_url"))
                    } catch (_: Exception) {
                    }
                }
            }

            // Include existing card cover as final fallback
            coverCandidates.add(scene.coverUrl)

            // 4. Title & Best High-Res Cover selection
            if (extractedTitle.isNullOrBlank()) {
                extractedTitle = doc.selectFirst("h1.title span.inlineFree, h1.title")?.text()?.trim()
                    ?: doc.selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")?.trim()
            }
            val finalTitle = extractedTitle
                ?.let { Parser.unescapeEntities(it, false) }
                ?.replace(Regex("(?i)\\s*-\\s*Pornhub(\\.com|Premium\\.com)?\\s*$"), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: scene.title

            val finalCover = selectBestPornhubCoverUrl(coverCandidates, html) ?: scene.coverUrl

            // 5. Normalize uploadDate to YYYY-MM-DD
            val formattedDate = extractedDateStr?.take(10)?.takeIf {
                it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) &&
                    (it.substring(0, 4).toIntOrNull() ?: 0) in 2005..2035
            } ?: scene.date ?: extractDateFromCdnUrl(finalCover)

            // 6. Extract Channel / Studio from .userInfoBlock if scene studio is still generic "PornHub"
            var resolvedStudioId = scene.studioId?.takeIf { it.isNotBlank() } ?: "pornhub"
            var resolvedStudioName = scene.studioName?.takeIf { it.isNotBlank() } ?: "PornHub"
            var resolvedStudioLogo = scene.studioLogo?.takeIf { it.isNotBlank() }

            val isGenericPornhubStudio = resolvedStudioId.equals("pornhub", ignoreCase = true) ||
                resolvedStudioName.equals("PornHub", ignoreCase = true)

            if (isGenericPornhubStudio || resolvedStudioLogo.isNullOrBlank()) {
                val channelEl = doc.selectFirst(
                    ".userInfoBlock .usernameWrap[data-type=channel] a[href*='/channels/'], " +
                        ".userInfoBlock .usernameWrap a[href*='/channels/'], " +
                        ".video-detailed-info a[href*='/channels/']"
                )
                if (channelEl != null) {
                    val rawChHref = channelEl.attr("href").trim()
                    val rawChName = Parser.unescapeEntities(channelEl.text().trim(), false)
                    if (rawChHref.isNotBlank() && rawChName.length >= 2) {
                        val normChUrl = normalizeChannelBaseUrl(rawChHref)
                        if (isGenericPornhubStudio) {
                            resolvedStudioId = normChUrl
                            resolvedStudioName = rawChName
                        }
                        val avatarOnVideoPage = extractCleanImageUrl(
                            doc.selectFirst(".userInfoBlock .userAvatar img, .userInfoBlock img")
                        )
                        val chLogo = avatarOnVideoPage
                            ?: channelAvatarCache[normChUrl.lowercase()]
                            ?: withTimeoutOrNull(2_500L) { fetchQuickChannelAvatar(normChUrl) }
                        if (!chLogo.isNullOrBlank()) {
                            channelAvatarCache[normChUrl.lowercase()] = chLogo
                            if (resolvedStudioLogo.isNullOrBlank()) {
                                resolvedStudioLogo = chLogo
                            }
                        }
                    }
                } else if (!isGenericPornhubStudio && resolvedStudioLogo.isNullOrBlank() && resolvedStudioId.contains("/channels/")) {
                    resolvedStudioLogo = channelAvatarCache[resolvedStudioId.lowercase()]
                        ?: withTimeoutOrNull(2_500L) { fetchQuickChannelAvatar(resolvedStudioId) }
                }
            }

            // 7. Extract all tagged performers on the video page (with avatar fallback & male performer filter)
            val pagePerformers = mutableListOf<StashPerformer>()
            val seenNames = HashSet<String>()

            for (existingPerf in scene.femalePerformers) {
                if (existingPerf.name.isNotBlank() && seenNames.add(existingPerf.name.trim().lowercase())) {
                    val key = normalizePerformerBaseUrl(existingPerf.id).lowercase()
                    val enrichedAvatar = existingPerf.imageUrl?.takeIf { it.isNotBlank() }
                        ?: avatarCache[key]
                    pagePerformers.add(
                        if (!enrichedAvatar.isNullOrBlank() && existingPerf.imageUrl.isNullOrBlank()) {
                            existingPerf.copy(imageUrl = enrichedAvatar, images = listOf(enrichedAvatar))
                        } else {
                            existingPerf
                        }
                    )
                }
            }

            val performerEls = doc.select(
                ".pornstarsWrapper a.pstar-list-btn, div.pornstarsWrapper a[href*='/pornstar/'], div.pornstarsWrapper a[href*='/model/']"
            )
            for (el in performerEls) {
                val pName = el.attr("data-mxptext").trim()
                    .ifEmpty { el.ownText().trim() }
                    .ifEmpty { el.text().trim() }
                    .replace(Regex("""\s+\d+(\.\d+)?[KMB]?\s*$""", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (pName.length < 2 || pName.lowercase() in BLOCKED_PERFORMER_WORDS) continue
                if (!seenNames.add(pName.lowercase())) continue

                val pHref = el.attr("href").trim()
                val pId = if (pHref.isNotBlank()) normalizePerformerBaseUrl(pHref) else "pornhub:actor:${pName.lowercase()}"
                val pKey = pId.lowercase()

                var pAvatar = extractCleanImageUrl(el.selectFirst("img"))
                    ?: avatarCache[pKey]
                if (pAvatar.isNullOrBlank() && pHref.isNotBlank()) {
                    pAvatar = withTimeoutOrNull(2_500L) {
                        fetchQuickProfileAvatar(pId)
                    }
                }

                val pGender = genderCache[pKey]
                if (blockMalePerformers && pGender.equals("MALE", ignoreCase = true)) {
                    continue
                }

                val cleanPerfName = Parser.unescapeEntities(pName, false).trim()
                val newPerf = StashPerformer(
                    id = pId,
                    name = cleanPerfName,
                    gender = pGender,
                    imageUrl = pAvatar,
                    images = listOfNotNull(pAvatar)
                )
                if (cleanPerfName.contains(" ") && cleanPerfName.length >= 5) {
                    knownPerformerNamesCache[cleanPerfName.lowercase(Locale.US)] = newPerf
                }
                pagePerformers.add(newPerf)
            }

            scene.copy(
                title = finalTitle,
                coverUrl = finalCover,
                date = formattedDate,
                studioId = resolvedStudioId,
                studioName = resolvedStudioName,
                studioLogo = resolvedStudioLogo,
                femalePerformers = pagePerformers,
                directVideoUrl = videoUrl
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Failed to enrich Pornhub scene $videoUrl, using card metadata", e)
            scene
        }
    }
}
