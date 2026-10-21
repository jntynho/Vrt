package com.example.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewScrapersUnitTest {

    @Test
    fun testGofileUrlDetectionAndContentIdParsing() {
        val testUrl = "https://gofile.io/d/vZmGcM"
        assertTrue(GofileScraper.isGofileUrl(testUrl))
        assertEquals("vZmGcM", GofileScraper.parseContentId(testUrl))
        assertTrue(Scraping.isSupportedUrl(testUrl))
    }

    @Test
    fun testCyberFileUrlDetectionAndCodeParsing() {
        val testUrl = "https://cyberfile.me/ncxW"
        assertTrue(CyberFileScraper.isCyberfileUrl(testUrl))
        assertEquals("ncxW", CyberFileScraper.parseCode(testUrl))
        assertTrue(Scraping.isSupportedUrl(testUrl))
    }

    @Test
    fun testEpornerUrlDetectionAndHashEncoding() {
        val testUrl = "https://www.eporner.com/video-Tot6CWRUesR/-/"
        assertTrue(EpornerScraper.isEpornerUrl(testUrl))
        assertEquals("Tot6CWRUesR", EpornerScraper.parseVideoId(testUrl))
        assertTrue(Scraping.isSupportedUrl(testUrl))

        // Test hash encoding logic
        val rawHash = "4c37896c69d03c2b4ee28c29e5ac47c1"
        val encoded = EpornerScraper.encodeEpornerHash(rawHash)
        assertNotNull(encoded)
        assertTrue("Encoded hash should not be empty", encoded.isNotBlank())
        assertEquals("l5b5gstcxv6zlvyjvt1rq50ht", encoded)
    }

    @Test
    fun testPorntrexUrlDetectionAndIdParsing() {
        val testUrl = "https://www.porntrex.com/video/2450911/skinny-german-hairy-milf-small-tits"
        assertTrue(PorntrexScraper.isPorntrexUrl(testUrl))
        assertEquals("2450911", PorntrexScraper.parseVideoId(testUrl))
        assertTrue(Scraping.isSupportedUrl(testUrl))
    }

    @Test
    fun testXhamsterUrlDetectionAndPrngDecoding() {
        val testUrl = "https://xhamster.com/videos/laura-angel-forever-original-version-in-full-hd-xhaqBT3"
        assertTrue(VideoResolvers.isXhamsterUrl(testUrl))
        assertTrue(Scraping.isSupportedUrl(testUrl))

        // Test decoding with Algorithm 6 hex string
        val hex720 = "066c0dd1ec91111e6e722a30ef33eea5e75b6a5d1b11cd136a60e9b1dddd5e55601ea6f1075cf9ac3450274a1f72b4732a95354f57987d9532bd7e5b16c24448752916af45453b87ac0ad3a892bd2652586320ac171c33dd7145214c5f249b214bb1563379078339cf3edc51677899ea62072cb61ae7af4aa72971be3d2ff751514a3308028b311477d32937b2c9194800"
        val decoded = VideoResolvers.decodeXhamsterUrl(hex720)
        assertNotNull("Decoded URL must not be null", decoded)
        assertTrue("Decoded URL must start with http", decoded!!.startsWith("http"))
        assertTrue("Decoded URL must contain 720p", decoded.contains("720p"))
    }

    @Test
    fun testPornhubUrlDetection() {
        val testUrl = "https://www.pornhub.com/view_video.php?viewkey=ph5f123456789ab"
        assertTrue(VideoResolvers.isPornhubUrl(testUrl))
        assertTrue(Scraping.isSupportedUrl(testUrl))
    }

    @Test
    fun testScrapingDispatcherAllProviders() {
        assertTrue(Scraping.isSupportedUrl("https://gofile.io/d/vZmGcM"))
        assertTrue(Scraping.isSupportedUrl("https://cyberfile.me/ncxW"))
        assertTrue(Scraping.isSupportedUrl("https://www.eporner.com/video-Tot6CWRUesR/"))
        assertTrue(Scraping.isSupportedUrl("https://www.porntrex.com/video/2450911/"))
        assertTrue(Scraping.isSupportedUrl("https://xhamster.com/videos/test-xh12345"))
        assertTrue(Scraping.isSupportedUrl("https://www.pornhub.com/view_video.php?viewkey=ph642819"))
    }
}
