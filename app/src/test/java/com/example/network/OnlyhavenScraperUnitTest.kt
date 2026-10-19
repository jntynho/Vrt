package com.example.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlyhavenScraperUnitTest {

    @Test
    fun testGetInitialCreators() = runBlocking {
        val creators = OnlyhavenScraper.getCreators(page = 1)
        println("Fetched initial creators count: ${creators.size}")
        assertTrue("Initial creators should not be empty", creators.isNotEmpty())
        val first = creators.first()
        println("First creator: ${first.displayName} (@${first.name}) - ${first.profileUrl}")
        assertTrue(first.id.isNotEmpty())
        assertTrue(first.profileUrl.startsWith("https://cum.st/creators/"))
        assertTrue(first.avatarUrl.contains("avatar.webp"))
    }

    @Test
    fun testSearchDemonMika() = runBlocking {
        val results = OnlyhavenScraper.getCreators(page = 1, query = "demon_mika")
        println("Search results count: ${results.size}")
        assertTrue("Should find demon_mika", results.isNotEmpty())
        val match = results.firstOrNull { it.name.equals("demon_mika", ignoreCase = true) }
        assertNotNull("demon_mika should be present", match)
        assertEquals("39740564", match?.id)
        assertEquals("onlyfans", match?.service)
        assertEquals("https://cum.st/creators/onlyfans/39740564?display=collages", match?.profileUrl)
        println("Matched demon_mika: id=${match?.id}, displayName=${match?.displayName}, profileUrl=${match?.profileUrl}")
    }

    @Test
    fun testGetCreatorMediaForDemonMika() = runBlocking {
        val mediaList = OnlyhavenScraper.getCreatorMedia(
            service = "onlyfans",
            creatorId = "39740564",
            creatorName = "demon_mika",
            page = 1
        )
        println("Fetched media items count: ${mediaList.size}")
        assertTrue("Media items should not be empty", mediaList.isNotEmpty())
        val firstItem = mediaList.first()
        println("First media item: kind=${firstItem.kind}, sha256=${firstItem.sha256}, thumb=${firstItem.thumbnailUrl}, orig=${firstItem.originalUrl}")
        assertTrue("Thumbnail must be preview.webp", firstItem.thumbnailUrl.contains("preview.webp"))
        assertTrue("Original must be on media server", firstItem.originalUrl.startsWith("https://e1.cum.st/media/"))
        assertEquals("b1dd26edfd71cfe90344b8e20ed650452f77f3238351fae953d5cdf3afc1eb4d", firstItem.sha256)
        assertTrue("First item should be multi-media album/carousel", firstItem.isMultiMedia)
        assertEquals(2, firstItem.postAttachments.size)

        val images = mediaList.filter { it.kind == "image" }
        val videos = mediaList.filter { it.kind == "video" }
        println("Images count: ${images.size}, Videos count: ${videos.size}")
        assertTrue("Should contain images", images.isNotEmpty())
    }
}
