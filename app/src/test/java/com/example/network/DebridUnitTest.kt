package com.example.network

import com.example.network.debrid.DebridFileInfo
import com.example.network.debrid.VideoFilePicker
import com.example.network.torrent.MagnetParser
import org.junit.Assert.*
import org.junit.Test

class DebridUnitTest {

    @Test
    fun testMagnetParserHex40() {
        val hex = "1234567890abcdef1234567890abcdef12345678"
        val parsed = MagnetParser.parseHash(hex)
        assertEquals(hex.lowercase(), parsed)
    }

    @Test
    fun testMagnetParserBase32ToHex() {
        // 32 chars Base32 string
        val base32 = "MBSWY3DPEB2GQZLOOR4XAZLSEB2GQZLO"
        val parsed = MagnetParser.parseHash(base32)
        assertNotNull(parsed)
        assertEquals(40, parsed!!.length)
    }

    @Test
    fun testMagnetParserURLEncodedAndHtmlEscaped() {
        val magnet = "magnet:?xt=urn%3Abtih%3A1234567890abcdef1234567890abcdef12345678&amp;dn=Test"
        val parsed = MagnetParser.parseHash(magnet)
        assertEquals("1234567890abcdef1234567890abcdef12345678", parsed)
    }

    @Test
    fun testVideoFilePickerIgnoresSampleAndPicksLargest() {
        val files = listOf(
            DebridFileInfo("1", "sample_video.mp4", 50_000_000L),
            DebridFileInfo("2", "Feature_Movie.mkv", 2_500_000_000L),
            DebridFileInfo("3", "Feature_Movie_1080p.mp4", 1_500_000_000L),
            DebridFileInfo("4", "movie_trailer.mp4", 80_000_000L),
            DebridFileInfo("5", "subtitles.srt", 100_000L)
        )

        val best = VideoFilePicker.pickBestVideoFile(files)
        assertNotNull(best)
        assertEquals("2", best!!.id)
        assertEquals("Feature_Movie.mkv", best.name)
    }

    @Test
    fun testVideoFilePickerEpisodeHint() {
        val files = listOf(
            DebridFileInfo("1", "Show.S01E01.1080p.mkv", 1_000_000_000L),
            DebridFileInfo("2", "Show.S01E02.1080p.mkv", 1_100_000_000L),
            DebridFileInfo("3", "Show.S01E03.1080p.mkv", 1_200_000_000L)
        )

        val matched = VideoFilePicker.pickBestVideoFile(files, episodeHint = "S01E02")
        assertNotNull(matched)
        assertEquals("2", matched!!.id)
    }

    @Test
    fun testJavMultiFileSelectionWithSpamPromo() {
        val files = listOf(
            DebridFileInfo("1", "hhd800.com@SNOS-308.mp4", 6_830_000_000L),
            DebridFileInfo("2", "18+游戏大全(996gg.cc)-七龍珠H版-三國志H版-三國群淫傳等.mp4", 1_910_000L)
        )

        val best = VideoFilePicker.pickBestVideoFile(files, episodeHint = "SNOS-308")
        assertNotNull(best)
        assertEquals("1", best!!.id)
        assertEquals("hhd800.com@SNOS-308.mp4", best.name)
    }

    @Test
    fun testMediaExtensionOf() {
        val url1 = "https://example.com/video/stream.mkv?token=12345&expires=9999"
        assertEquals("mkv", MediaUrlValidator.mediaExtensionOf(url1))

        val url2 = "https://real-debrid.com/d/ABC123XYZ/video.mp4#t=10"
        assertEquals("mp4", MediaUrlValidator.mediaExtensionOf(url2))
    }

    @Test
    fun testCanonicalMagnetIncludesTrackers() {
        val hex = "1234567890abcdef1234567890abcdef12345678"
        val canonical = MagnetParser.toCanonicalMagnet(hex)
        assertTrue(canonical.startsWith("magnet:?xt=urn:btih:1234567890abcdef1234567890abcdef12345678"))
        assertTrue(canonical.contains("&tr="))
    }

    @Test
    fun testDebridMagnetParserDelegation() {
        val hex = "1234567890abcdef1234567890abcdef12345678"
        val parsed = com.example.network.torrent.MagnetParser.parseHash("magnet:?xt=urn:btih:$hex")
        assertEquals(hex, parsed)
    }

    @Test
    fun testMagnetUtilsUnified() {
        val base32 = "MBSWY3DPEB2GQZLOOR4XAZLSEB2GQZLO"
        val hash = com.example.network.torrent.MagnetUtils.extractMagnetHash("magnet:?xt=urn:btih:$base32")
        assertNotNull(hash)
        assertEquals(40, hash!!.length)
    }
}
