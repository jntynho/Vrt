package com.example.network.torrent

import java.text.Normalizer
import java.util.regex.Pattern

object AnchorMatcher {

    val GENERIC_TERMS = setOf(
        "pov", "vr", "scene", "episode", "part", "compilation", "special",
        "massage", "casting", "interview", "amateur", "solo", "hardcore", "anal",
        "blowjob", "creampie", "squirt", "threesome", "interracial", "lesbian",
        "milf", "teen", "stepmom", "stepsister", "stepdaughter", "video", "clip",
        "trailer", "bonus", "xxx", "mp4", "mkv", "uhd", "4k", "1080p", "2160p",
        "friends", "vol", "volume", "edition", "series", "update", "exclusive"
    )

    private val ACCENT_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+")

    fun normalizeText(input: String): String {
        val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
        val withoutAccents = ACCENT_PATTERN.matcher(nfd).replaceAll("").lowercase()
        return NON_ALPHANUMERIC.matcher(withoutAccents).replaceAll(" ").replace(Regex("\\s+"), " ").trim()
    }

    fun toCompact(input: String): String {
        val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
        val withoutAccents = ACCENT_PATTERN.matcher(nfd).replaceAll("").lowercase()
        return NON_ALPHANUMERIC.matcher(withoutAccents).replaceAll("")
    }

    private fun isWholeWordMatch(text: String, word: String): Boolean {
        if (word.isBlank()) return false
        val regex = Pattern.compile("(?:^|\\s)" + Pattern.quote(word) + "(?:$|\\s)")
        return regex.matcher(text).find()
    }

    data class AnchorResult(
        val isMatch: Boolean,
        val studioMatched: Boolean,
        val actorMatched: Boolean
    )

    /**
     * Checks anchor matching per Section 5.7.
     */
    fun checkAnchors(
        title: String,
        studios: List<String>,
        actors: List<String>,
        query: String = ""
    ): AnchorResult {
        val normalizedTitle = normalizeText(title)
        val compactTitle = toCompact(title)

        var studioMatched = false
        var actorMatched = false

        // 1. Studio checks
        for (studio in studios) {
            val tokens = StudioNameNormalizer.getAnchorTokens(studio)
            for (token in tokens) {
                val normToken = normalizeText(token)
                val compToken = toCompact(token)

                val compMatch = compToken.length >= 3 && compactTitle.contains(compToken)
                val wordMatch = normToken.isNotBlank() && isWholeWordMatch(normalizedTitle, normToken)

                if (compMatch || wordMatch) {
                    studioMatched = true
                    break
                }
            }
            if (studioMatched) break
        }

        // 2. Actor checks
        for (actor in actors) {
            val compActor = toCompact(actor)
            if (compActor.length >= 5 && compactTitle.contains(compActor)) {
                actorMatched = true
                break
            }

            val parts = normalizeText(actor).split(" ").filter {
                it.length >= 3 && !GENERIC_TERMS.contains(it)
            }

            val matchedParts = parts.filter { isWholeWordMatch(normalizedTitle, it) }
            if (matchedParts.size >= 2 || (matchedParts.size == 1 && matchedParts[0].length >= 4)) {
                actorMatched = true
                break
            }
        }

        val hasStudiosOrActors = studios.isNotEmpty() || actors.isNotEmpty()

        return if (hasStudiosOrActors) {
            val isMatch = studioMatched || actorMatched
            AnchorResult(isMatch, studioMatched, actorMatched)
        } else {
            // When neither studio nor actor is provided: query must contain at least one meaningful term
            val queryTerms = normalizeText(query).split(" ").filter { term ->
                term.length >= 3 && !GENERIC_TERMS.contains(term) && !term.matches(Regex("\\d+"))
            }
            val queryMatch = queryTerms.any { isWholeWordMatch(normalizedTitle, it) || compactTitle.contains(toCompact(it)) }
            AnchorResult(queryMatch, studioMatched = false, actorMatched = false)
        }
    }

    /**
     * Scores a candidate title:
     * Score = number of query words contained in the title + 15 if studio matched + 15 if actor matched.
     */
    fun calculateScore(
        title: String,
        query: String,
        studioMatched: Boolean,
        actorMatched: Boolean
    ): Int {
        val normTitle = normalizeText(title)
        val queryWords = normalizeText(query).split(" ").filter { it.isNotBlank() }

        var wordCount = 0
        for (w in queryWords) {
            if (isWholeWordMatch(normTitle, w)) {
                wordCount++
            }
        }

        var score = wordCount
        if (studioMatched) score += 15
        if (actorMatched) score += 15
        return score
    }
}
