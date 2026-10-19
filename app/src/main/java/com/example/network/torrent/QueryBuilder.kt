package com.example.network.torrent

import java.util.regex.Pattern

object QueryBuilder {

    private val JAV_PATTERN = Pattern.compile("([a-zA-Z]{2,5}-\\d{3,4})")
    private val HTTP_URL_PATTERN = Pattern.compile("(?i)^https?://.*")
    private val PUNCTUATION_PATTERN = Pattern.compile("[,.'’\\-]+")
    private val CAMEL_SPLIT_PATTERN = Pattern.compile("(?<=[a-z])(?=[A-Z])")

    val GENERIC_TITLES = setOf(
        "pov", "vr", "scene", "episode", "part", "part1", "part2", "part3",
        "compilation", "special", "massage", "casting", "interview", "amateur",
        "solo", "hardcore", "anal", "blowjob", "creampie", "squirt", "threesome",
        "interracial", "lesbian", "milf", "teen", "stepmom", "stepsister", "stepdaughter"
    )

    data class ActorVariants(
        val clean: String,
        val splitCamel: String
    ) {
        fun toList(): List<String> {
            val list = mutableListOf<String>()
            if (clean.isNotBlank()) list.add(clean)
            if (splitCamel.isNotBlank() && !list.contains(splitCamel)) list.add(splitCamel)
            return list
        }
    }

    /**
     * Cleans title string if it is not an http(s) URL.
     * Replaces , . ' ’ - with spaces, collapses whitespace, trims.
     */
    fun cleanTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        val trimmed = title.trim()
        if (HTTP_URL_PATTERN.matcher(trimmed).matches()) {
            return ""
        }
        val noPunct = PUNCTUATION_PATTERN.matcher(trimmed).replaceAll(" ")
        return noPunct.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Checks if title contains a JAV code matching ([a-zA-Z]{2,5}-\d{3,4}).
     */
    fun extractJavMatch(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val matcher = JAV_PATTERN.matcher(title)
        return if (matcher.find()) {
            matcher.group(1)?.uppercase()
        } else {
            null
        }
    }

    /**
     * Checks if a title is considered generic:
     * normalized value (lowercase, letters/digits only) is in GENERIC_TITLES or length <= 3.
     */
    fun isGenericTitle(titleClean: String): Boolean {
        val normalized = titleClean.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (normalized.length <= 3) return true
        return GENERIC_TITLES.contains(normalized)
    }

    /**
     * Generates clean and splitCamel variants for an actor name.
     */
    fun getActorVariants(actorName: String): ActorVariants {
        val trimmed = actorName.trim()
        val clean = PUNCTUATION_PATTERN.matcher(trimmed).replaceAll(" ")
            .replace(Regex("\\s+"), " ").trim()
        val splitCamel = CAMEL_SPLIT_PATTERN.split(clean).joinToString(" ")
            .replace(Regex("\\s+"), " ").trim()
        return ActorVariants(clean, splitCamel)
    }

    /**
     * Helper to join non-empty components with spaces, sanitize studio names, and collapse spaces.
     */
    private fun buildQueryString(vararg parts: String): String {
        val joined = parts.filter { it.isNotBlank() }.joinToString(" ")
        val sanitized = StudioNameNormalizer.sanitizeQuery(joined)
        return sanitized.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Builds the ordered cascade of queries according to Section 5.4.
     */
    fun buildCascadeQueries(
        title: String?,
        actors: List<String>,
        studios: List<String>,
        dateStr: String,
        extraSearchText: String? = null
    ): List<String> {
        val titleClean = cleanTitle(title)
        val queries = mutableListOf<String>()

        // Studio handling
        val effectiveStudios = studios.toMutableList()
        if (effectiveStudios.isEmpty()) {
            val combinedText = "${title.orEmpty()} ${extraSearchText.orEmpty()}".lowercase()
            if (combinedText.contains("scoreland") || combinedText.contains("score land")) {
                effectiveStudios.add("PornMegaLoad")
            }
        }

        val allStudioVariants = effectiveStudios.flatMap { StudioNameNormalizer.getStudioVariants(it) }.distinct()
        val allActorVariantsList = actors.map { getActorVariants(it) }

        val primaryActorVariants = if (allActorVariantsList.isNotEmpty()) {
            allActorVariantsList[0].toList()
        } else {
            emptyList()
        }

        val otherActorVariantsList = if (allActorVariantsList.size > 1) {
            allActorVariantsList.drop(1)
        } else {
            emptyList()
        }

        fun addQuery(q: String) {
            val trimmed = q.trim()
            if (trimmed.isNotBlank() && !queries.contains(trimmed)) {
                queries.add(trimmed)
            }
        }

        // 1. each primary-actor variant × each studio variant × dateStr
        for (actorVar in primaryActorVariants) {
            for (studioVar in allStudioVariants) {
                addQuery(buildQueryString(actorVar, studioVar, dateStr))
            }
        }

        // 2. if titleClean: primary-actor variant + titleClean + dateStr; then (only when the title is NOT generic) titleClean + dateStr
        if (titleClean.isNotBlank()) {
            for (actorVar in primaryActorVariants) {
                addQuery(buildQueryString(actorVar, titleClean, dateStr))
            }
            if (!isGenericTitle(titleClean)) {
                addQuery(buildQueryString(titleClean, dateStr))
            }
        }

        // 3. each other actor variant × each studio variant × dateStr
        for (otherActor in otherActorVariantsList) {
            for (actorVar in otherActor.toList()) {
                for (studioVar in allStudioVariants) {
                    addQuery(buildQueryString(actorVar, studioVar, dateStr))
                }
            }
        }

        // 4. if exactly 2 actors and at least 1 studio: both actor names joined + first studio variant + dateStr
        if (allActorVariantsList.size == 2 && allStudioVariants.isNotEmpty()) {
            val firstActor = allActorVariantsList[0].clean
            val secondActor = allActorVariantsList[1].clean
            val firstStudio = allStudioVariants[0]
            addQuery(buildQueryString(firstActor, secondActor, firstStudio, dateStr))
        }

        // 5. primary-actor variants + dateStr
        for (actorVar in primaryActorVariants) {
            addQuery(buildQueryString(actorVar, dateStr))
        }

        // 6. other actors + dateStr
        for (otherActor in otherActorVariantsList) {
            for (actorVar in otherActor.toList()) {
                addQuery(buildQueryString(actorVar, dateStr))
            }
        }

        // 7. each studio variant + dateStr
        for (studioVar in allStudioVariants) {
            addQuery(buildQueryString(studioVar, dateStr))
        }

        return queries
    }
}
