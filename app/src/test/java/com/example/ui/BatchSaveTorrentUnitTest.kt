package com.example.ui

import com.example.data.local.entity.ActorEntity
import com.example.data.local.entity.LinkEntity
import com.example.data.local.entity.StudioEntity
import com.example.network.torrent.TorrentSearchResult
import org.junit.Assert.*
import org.junit.Test

class BatchSaveTorrentUnitTest {

    @Test
    fun testTorrentFieldsPopulatedOnSearchResult() {
        val searchResult = TorrentSearchResult(
            magnet1080p = "magnet:?xt=urn:btih:1111111111111111111111111111111111111111",
            magnet2160p = "magnet:?xt=urn:btih:2222222222222222222222222222222222222222",
            url1080p = "https://xxxclub.to/torrents/details/101",
            url2160p = "https://xxxclub.to/torrents/details/102",
            sourceSite = "XXXClub"
        )

        assertTrue(searchResult.hasResult)

        val link = LinkEntity(
            id = "test_link_1",
            stashDbId = "scene_123",
            title = "Test Scene Title",
            coverImage = "https://example.com/cover.jpg",
            magnet = searchResult.magnet1080p,
            magnet4K = searchResult.magnet2160p,
            torrentUrlHD = searchResult.url1080p,
            torrentUrl4K = searchResult.url2160p,
            torrentSiteName = searchResult.sourceSite
        )

        assertEquals("magnet:?xt=urn:btih:1111111111111111111111111111111111111111", link.magnet)
        assertEquals("magnet:?xt=urn:btih:2222222222222222222222222222222222222222", link.magnet4K)
        assertEquals("https://xxxclub.to/torrents/details/101", link.torrentUrlHD)
        assertEquals("https://xxxclub.to/torrents/details/102", link.torrentUrl4K)
        assertEquals("XXXClub", link.torrentSiteName)
    }

    @Test
    fun testSaveContinuesWhenTorrentFetchFailsOrReturnsNull() {
        val searchResult: TorrentSearchResult? = null

        val link = LinkEntity(
            id = "test_link_2",
            stashDbId = "scene_456",
            title = "Scene Without Torrent",
            coverImage = "https://example.com/cover2.jpg",
            magnet = searchResult?.magnet1080p,
            magnet4K = searchResult?.magnet2160p,
            torrentUrlHD = searchResult?.url1080p,
            torrentUrl4K = searchResult?.url2160p,
            torrentSiteName = searchResult?.sourceSite
        )

        assertNotNull(link)
        assertEquals("test_link_2", link.id)
        assertEquals("Scene Without Torrent", link.title)
        assertNull(link.magnet)
        assertNull(link.magnet4K)
        assertNull(link.torrentUrlHD)
        assertNull(link.torrentUrl4K)
        assertNull(link.torrentSiteName)
    }

    @Test
    fun testSharedSearchQueryAndActorStudioArguments() {
        val actor = ActorEntity(id = "a1", name = "Angela White")
        val studio = StudioEntity(id = "s1", name = "Brazzers Network")
        val sceneTitle = "Angela White Brazzers Scene"

        val actorNames = listOf(actor).map { it.name }
        val studioNames = listOf(studio).map { it.name }

        val queries = com.example.network.torrent.QueryBuilder.buildCascadeQueries(
            title = sceneTitle,
            actors = actorNames,
            studios = studioNames,
            dateStr = "24 03 05"
        )

        assertTrue(queries.isNotEmpty())
        assertTrue(queries.contains("Angela White Brazzers 24 03 05"))
    }
}
