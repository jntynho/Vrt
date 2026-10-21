package com.example.network

import org.junit.Assert.assertEquals
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
    fun testPornhubUrlDetection() {
        val testUrl = "https://www.pornhub.com/view_video.php?viewkey=ph5f123456789ab"
        assertTrue(VideoResolvers.isPornhubUrl(testUrl))
        assertTrue(Scraping.isSupportedUrl(testUrl))
    }

    @Test
    fun testScrapingDispatcherSupportedProviders() {
        assertTrue(Scraping.isSupportedUrl("https://gofile.io/d/vZmGcM"))
        assertTrue(Scraping.isSupportedUrl("https://cyberfile.me/ncxW"))
        assertTrue(Scraping.isSupportedUrl("https://www.pornhub.com/view_video.php?viewkey=ph642819"))
    }
}
