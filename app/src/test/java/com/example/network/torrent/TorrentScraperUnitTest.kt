package com.example.network.torrent

import org.junit.Assert.*
import org.junit.Test
import java.util.*

class TorrentScraperUnitTest {

    // 1. Query cascade order test (Section 5.4)
    @Test
    fun testQueryCascadeOrder() {
        val actors = listOf("Angela White", "Mick Blue")
        val studios = listOf("Brazzers Network")
        val dateStr = "24 03 05"
        val title = "The Boss Returns"

        val queries = QueryBuilder.buildCascadeQueries(
            title = title,
            actors = actors,
            studios = studios,
            dateStr = dateStr
        )

        assertTrue(queries.isNotEmpty())
        // Rule 1: primary-actor variant × studio variant × dateStr
        assertEquals("Angela White Brazzers 24 03 05", queries[0])

        // Rule 2: primary-actor + titleClean + dateStr, then titleClean + dateStr (if not generic)
        assertTrue(queries.contains("Angela White The Boss Returns 24 03 05"))
        assertTrue(queries.contains("The Boss Returns 24 03 05"))

        // Rule 3: other actor variant × studio variant × dateStr
        assertTrue(queries.contains("Mick Blue Brazzers 24 03 05"))

        // Rule 4: exactly 2 actors + studio variant + dateStr
        assertTrue(queries.contains("Angela White Mick Blue Brazzers 24 03 05"))

        // Rule 5: primary-actor variants + dateStr
        assertTrue(queries.contains("Angela White 24 03 05"))

        // Rule 6: other actors + dateStr
        assertTrue(queries.contains("Mick Blue 24 03 05"))

        // Rule 7: studio variant + dateStr
        assertTrue(queries.contains("Brazzers 24 03 05"))

        // Verify generic title exclusion: "Scene" alone should not produce "Scene 24 03 05"
        val genericQueries = QueryBuilder.buildCascadeQueries(
            title = "Scene",
            actors = emptyList(),
            studios = listOf("Hussie Pass"),
            dateStr = dateStr
        )
        assertFalse(genericQueries.contains("Scene 24 03 05"))
    }

    // 2. Date patterns (UTC vs local) test (Section 5.6)
    @Test
    fun testDatePatternsUtcVsLocal() {
        // Create a timestamp where UTC and a negative-offset timezone (e.g. EST/PST) have different calendar days
        // 2024-03-05 01:30:00 UTC
        val calUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2024, Calendar.MARCH, 5, 1, 30, 0)
        }
        val targetMs = calUtc.timeInMillis

        val patterns = DatePatterns.generatePatterns(targetDateMs = targetMs)

        // Must contain UTC date variants
        assertTrue(patterns.contains("24 03 05"))
        assertTrue(patterns.contains("24.03.05"))
        assertTrue(patterns.contains("2024-03-05"))
        assertTrue(patterns.contains("mar 05 2024") || patterns.contains("mar 5 2024"))

        // Check date matching
        assertTrue(DatePatterns.matchesAnyDatePattern("Studio 24.03.05 Angela 1080p", patterns))
        assertTrue(DatePatterns.matchesAnyDatePattern("Studio 2024-03-05 Angela 4K", patterns))
        assertFalse(DatePatterns.matchesAnyDatePattern("Studio 2021-01-01 Angela 1080p", patterns))
    }

    // 3. Anchor matching test (Section 5.7)
    @Test
    fun testAnchorMatching() {
        val studios = listOf("Brazzers Network")
        val actors = listOf("Angela White")

        // Studio match
        val res1 = AnchorMatcher.checkAnchors("Brazzers - Hot Scene 1080p", studios, emptyList())
        assertTrue(res1.isMatch)
        assertTrue(res1.studioMatched)

        // Actor match
        val res2 = AnchorMatcher.checkAnchors("Hot Scene Featuring Angela White 1080p", emptyList(), actors)
        assertTrue(res2.isMatch)
        assertTrue(res2.actorMatched)

        // No match
        val res3 = AnchorMatcher.checkAnchors("Completely Unrelated Video 1080p", studios, actors)
        assertFalse(res3.isMatch)

        // Score calculation: query word count + 15 if studio + 15 if actor
        val score = AnchorMatcher.calculateScore(
            title = "Brazzers Angela White Special Scene",
            query = "Angela White Brazzers",
            studioMatched = true,
            actorMatched = true
        )
        // 3 words matched ("angela", "white", "brazzers") = 3 + 15 + 15 = 33
        assertEquals(33, score)
    }

    // 4. Quality filters test (Section 5.7)
    @Test
    fun testQualityFilters() {
        val htmlFixture = """
            <ul class="tsearch">
                <li>
                    <a href="/torrents/details/101">Angela White Brazzers 24 03 05 VP9 1080p</a>
                    <a href="magnet:?xt=urn:btih:1111111111111111111111111111111111111111">Magnet</a>
                </li>
                <li>
                    <a href="/torrents/details/102">Angela White Brazzers 24 03 05 480p</a>
                    <a href="magnet:?xt=urn:btih:2222222222222222222222222222222222222222">Magnet</a>
                </li>
                <li>
                    <a href="/torrents/details/103">Angela White Brazzers 24 03 05 1080p</a>
                    <a href="magnet:?xt=urn:btih:3333333333333333333333333333333333333333">Magnet</a>
                </li>
                <li>
                    <a href="/torrents/details/104">Angela White Brazzers 24 03 05 2160p UHD 4K</a>
                    <a href="magnet:?xt=urn:btih:4444444444444444444444444444444444444444">Magnet</a>
                </li>
            </ul>
        """.trimIndent()

        val datePatterns = setOf("24 03 05")
        val studios = listOf("Brazzers")
        val actors = listOf("Angela White")

        val candidates = TorrentScraper.parseXxxclubList(
            html = htmlFixture,
            query = "Angela White Brazzers",
            datePatterns = datePatterns,
            studios = studios,
            actors = actors
        )

        // 101 (VP9) and 102 (480p) should be rejected. 103 (1080p) and 104 (2160p) accepted.
        assertEquals(2, candidates.size)
        val titles = candidates.map { it.title }
        assertFalse(titles.any { it.contains("VP9") })
        assertFalse(titles.any { it.contains("480p") })
        assertTrue(candidates.any { it.is1080p && !it.is4k })
        assertTrue(candidates.any { it.is4k })
    }

    // 5. Honeypot-safe next page test (Section 5.8)
    @Test
    fun testHoneypotNextPage() {
        val htmlWithTraps = """
            <div>
                <a href="/torrents/trap1" style="display:none">Next Page</a>
                <a href="/torrents/trap2" style="position:absolute;left:-9999px">Next</a>
                <a href="/torrents/trap3" style="visibility:hidden" title="Next Page">Page</a>
                <a href="/torrents/search/2/query?page=2" title="Next Page">Next Page</a>
            </div>
        """.trimIndent()

        val nextPageUrl = TorrentScraper.findNextPageUrl(htmlWithTraps)
        assertNotNull(nextPageUrl)
        assertEquals("https://xxxclub.to/torrents/search/2/query?page=2", nextPageUrl)
    }

    // 6. Magnet validation test (Section 8)
    @Test
    fun testMagnetValidation() {
        // Valid 40-char hex
        val hexMagnet = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=test&tr=udp%3A%2F%2Ftracker"
        assertTrue(MagnetUtils.isValidMagnetUri(hexMagnet))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", MagnetUtils.extractMagnetHash(hexMagnet))

        // Valid 32-char base32
        val b32Hash = "4W3NXG35V524QOMX5377X5Q57S234567"
        val b32Magnet = "magnet:?xt=urn:btih:$b32Hash&dn=test"
        assertTrue(MagnetUtils.isValidMagnetUri(b32Magnet))
        assertEquals(MagnetParser.base32ToHex(b32Hash), MagnetUtils.extractMagnetHash(b32Magnet))

        // Invalid magnets
        assertFalse(MagnetUtils.isValidMagnetUri("https://example.com/video.mp4"))
        assertFalse(MagnetUtils.isValidMagnetUri("magnet:?xt=urn:sha1:da39a3ee5e6b4b0d3255bfef95601890afd80709"))
        assertFalse(MagnetUtils.isValidMagnetUri("magnet:?xt=urn:btih:short123"))
        assertNull(MagnetUtils.extractMagnetHash(null))
        assertNull(MagnetUtils.extractMagnetHash(""))
    }

    // 7. Sukebei parsing test with improvements (Section 4)
    @Test
    fun testSukebeiParsing() {
        val htmlFixture = """
            <table>
                <tr>
                    <td colspan="2"><a href="/view/1001">ABP-123 VP9 Bad Quality</a></td>
                    <td><a href="magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa">M</a></td>
                </tr>
                <tr>
                    <td colspan="2"><a href="/view/1002">SSNI-999 Wrong JAV Scene 1080p</a></td>
                    <td><a href="magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb">M</a></td>
                </tr>
                <tr>
                    <td colspan="2"><a href="/view/1003">ABP-123 HD Bare Quality</a></td>
                    <td><a href="magnet:?xt=urn:btih:cccccccccccccccccccccccccccccccccccccccc">M</a></td>
                </tr>
                <tr>
                    <td colspan="2"><a href="/view/1004">ABP-123 FHD 1080P Best Quality</a></td>
                    <td><a href="magnet:?xt=urn:btih:dddddddddddddddddddddddddddddddddddddddd">M</a></td>
                </tr>
                <tr>
                    <td colspan="2"><a href="/view/1005">ABP-123 4K 2160P Ultra</a></td>
                    <td><a href="magnet:?xt=urn:btih:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee">M</a></td>
                </tr>
            </table>
        """.trimIndent()

        val result = TorrentScraper.parseSukebei(htmlFixture, "ABP-123")

        assertTrue(result.hasResult)
        // 1080p must pick FHD 1080P (Row 1004) over bare HD (Row 1003)
        assertEquals("magnet:?xt=urn:btih:dddddddddddddddddddddddddddddddddddddddd", result.magnet1080p)
        assertEquals("https://sukebei.nyaa.si/view/1004", result.url1080p)

        // 4K must pick 4K row (Row 1005)
        assertEquals("magnet:?xt=urn:btih:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", result.magnet2160p)
        assertEquals("https://sukebei.nyaa.si/view/1005", result.url2160p)
    }

    // 8. Studio normalization test (Section 5.2 & 5.10)
    @Test
    fun testStudioNormalization() {
        // A. Forced mapping
        assertEquals("PornMegaLoad", StudioNameNormalizer.applyForcedMapping("Scoreland"))
        assertEquals("PornMegaLoad", StudioNameNormalizer.applyForcedMapping("score land"))
        assertEquals("Angela PornMegaLoad 24 03 05", StudioNameNormalizer.sanitizeQuery("Angela scoreland 24 03 05"))

        // B. Alias expansion
        val dadsVariants = StudioNameNormalizer.getStudioVariants("DadsLovePorn")
        assertTrue(dadsVariants.contains("Bangbros"))
        assertTrue(dadsVariants.contains("Bang Bros"))

        val hussieVariants = StudioNameNormalizer.getStudioVariants("Hot and Tatted")
        assertTrue(hussieVariants.contains("Hussie Pass"))

        // C. Suffix stripping + compaction
        val brazzersVariants = StudioNameNormalizer.getStudioVariants("Brazzers Network")
        assertTrue(brazzersVariants.contains("Brazzers"))

        val stripped = StudioNameNormalizer.getStudioVariants("Brazzers Exxtra Studios")
        assertTrue(stripped.contains("Brazzers Exxtra"))
        assertTrue(stripped.contains("BrazzersExxtra")) // noSpace
    }
}
