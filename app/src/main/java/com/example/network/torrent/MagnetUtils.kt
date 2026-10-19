package com.example.network.torrent

object MagnetUtils {
    /**
     * Extracts the 40-hex infohash from a magnet link or raw text,
     * decoding Base32, URL encoding, and HTML entities.
     */
    fun extractMagnetHash(magnet: String?): String? {
        return MagnetParser.parseHash(magnet)
    }

    /**
     * Validates whether a magnet URI contains a valid BTIH hash.
     */
    fun isValidMagnetUri(magnet: String?): Boolean {
        return MagnetParser.parseHash(magnet) != null
    }
}
