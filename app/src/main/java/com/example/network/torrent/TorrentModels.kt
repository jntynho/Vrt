package com.example.network.torrent

data class TorrentCandidate(
    val title: String,
    val detailUrl: String,
    val embeddedMagnet: String? = null,
    val score: Int = 0,
    val is4k: Boolean = false,
    val is1080p: Boolean = false,
    val isVr: Boolean = false
)

data class TorrentSearchResult(
    val magnet1080p: String? = null,
    val url1080p: String? = null,
    val magnet2160p: String? = null,
    val url2160p: String? = null,
    val magnetVR: String? = null,
    val urlVR: String? = null,
    val sourceSite: String = "",
    val matchedQuery: String? = null
) {
    val hasResult: Boolean
        get() = !magnet1080p.isNullOrBlank() || !magnet2160p.isNullOrBlank() || !magnetVR.isNullOrBlank()
}
