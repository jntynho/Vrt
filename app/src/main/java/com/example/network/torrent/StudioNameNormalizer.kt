package com.example.network.torrent

import java.text.Normalizer
import java.util.regex.Pattern

object StudioNameNormalizer {

    private val SCORELAND_REGEX = Pattern.compile("(?i)\\bscore\\s*land\\b|\\bscoreland\\b")
    private val SUFFIX_PATTERN = Pattern.compile("(?i)(\\s|-)*(VIP|Network|Premium|Plus|Media|Entertainment|Films|Studios|Studio|Site)$")
    private val PUNCTUATION_PATTERN = Pattern.compile("[^a-zA-Z0-9]+")
    private val ACCENT_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")

    private val ALIAS_MAP = listOf(
        listOf("dadsloveporn", "dads love porn") to listOf("DadsLovePorn", "Bangbros", "Bang Bros"),
        listOf("hot and tatted", "hot & tatted") to listOf("Hot and Tatted", "Hussie Pass"),
        listOf("hussie auditions") to listOf("Hussie Auditions", "Hussie Pass"),
        listOf("interracial povs") to listOf("Interracial POVs", "Hussie Pass"),
        listOf("pov pornstars") to listOf("POV Pornstars", "Hussie Pass"),
        listOf("hussie pass") to listOf("Hussie Pass", "Hot and Tatted")
    )

    /**
     * Applies forced mapping (e.g. scoreland -> PornMegaLoad).
     */
    fun applyForcedMapping(name: String): String {
        val lower = name.trim().lowercase()
        return if (lower.contains("scoreland") || lower.contains("score land")) {
            "PornMegaLoad"
        } else {
            name.trim()
        }
    }

    /**
     * Replaces scoreland variations in a query string.
     */
    fun sanitizeQuery(query: String): String {
        return SCORELAND_REGEX.matcher(query).replaceAll("PornMegaLoad").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Generates studio variants per section 5.2 and 5.10.
     * (1) alias names
     * (2) stripped = name without trailing VIP|Network|... with & -> and
     * (3) noSpace = stripped without spaces/punctuation
     * (4) withSpace = stripped with punctuation -> space, collapsed
     * (5) first word if length > 2
     */
    fun getStudioVariants(studioName: String): List<String> {
        val mapped = applyForcedMapping(studioName)
        if (mapped.isBlank()) return emptyList()

        val results = mutableListOf<String>()
        val lower = mapped.lowercase()

        // 1. Alias expansion
        for ((keys, aliases) in ALIAS_MAP) {
            if (keys.any { lower.contains(it) }) {
                aliases.forEach { alias ->
                    if (!results.contains(alias)) results.add(alias)
                }
            }
        }

        // Base name with & -> and
        val andReplaced = mapped.replace("&", "and")

        // 2. Stripped of suffix
        val stripped = SUFFIX_PATTERN.matcher(andReplaced).replaceFirst("").trim()
        val baseCandidate = if (stripped.isNotBlank()) stripped else andReplaced
        if (!results.contains(baseCandidate)) {
            results.add(baseCandidate)
        }

        // 3. noSpace: stripped without spaces/punctuation (e.g. BrazzersExxtra)
        val noSpace = PUNCTUATION_PATTERN.matcher(baseCandidate).replaceAll("")
        if (noSpace.isNotBlank() && !results.contains(noSpace)) {
            results.add(noSpace)
        }

        // 4. withSpace: stripped with punctuation -> space, collapsed
        val withSpace = PUNCTUATION_PATTERN.matcher(baseCandidate).replaceAll(" ").replace(Regex("\\s+"), " ").trim()
        if (withSpace.isNotBlank() && !results.contains(withSpace)) {
            results.add(withSpace)
        }

        // 5. First word if length > 2
        val words = withSpace.split(" ").filter { it.isNotBlank() }
        if (words.isNotEmpty()) {
            val first = words[0]
            if (first.length > 2 && !results.contains(first)) {
                results.add(first)
            }
        }

        return results
    }

    /**
     * Strip accents from a string and lowercase.
     */
    fun stripAccents(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return ACCENT_PATTERN.matcher(normalized).replaceAll("").lowercase().trim()
    }

    /**
     * Compact letters/digits only.
     */
    fun toCompact(input: String): String {
        return PUNCTUATION_PATTERN.matcher(stripAccents(input)).replaceAll("")
    }

    /**
     * Anchor tokens for a studio:
     * - clean name
     * - compact form (letters/digits only, length >= 3)
     * - stripped-suffix form
     * - stripped compact form
     */
    fun getAnchorTokens(studioName: String): Set<String> {
        val mapped = applyForcedMapping(studioName)
        if (mapped.isBlank()) return emptySet()

        val tokens = mutableSetOf<String>()
        val clean = stripAccents(mapped.replace("&", "and"))
        tokens.add(clean)

        val compact = toCompact(clean)
        if (compact.length >= 3) tokens.add(compact)

        val stripped = stripAccents(SUFFIX_PATTERN.matcher(clean).replaceFirst("").trim())
        if (stripped.isNotBlank()) {
            tokens.add(stripped)
            val compactStripped = toCompact(stripped)
            if (compactStripped.length >= 3) tokens.add(compactStripped)
        }

        return tokens
    }
}
