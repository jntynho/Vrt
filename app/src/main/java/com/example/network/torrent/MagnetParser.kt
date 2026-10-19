package com.example.network.torrent

import com.example.BuildConfig
import java.net.URLDecoder
import java.util.regex.Pattern

object MagnetParser {
    private val HEX_40_REGEX = Regex("^[a-fA-F0-9]{40}$")
    private val BASE32_32_REGEX = Regex("^[a-zA-Z2-7]{32}$")
    private val BTIH_PATTERN = Pattern.compile("btih:([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE)

    /**
     * Parses a magnet link, URL-encoded string, HTML-escaped string, or bare hash into a normalized 40-character lowercase hex string.
     */
    fun parseHash(input: String?): String? {
        if (input.isNullOrBlank()) return null

        var s = input.trim()

        // 1. Unescape HTML entities if present (&amp; -> &)
        s = s.replace("&amp;", "&", ignoreCase = true)

        // 2. Decode URL encoding if %3A or similar exists
        if (s.contains("%3", ignoreCase = true) || s.contains("%2", ignoreCase = true)) {
            try {
                s = URLDecoder.decode(s, "UTF-8")
            } catch (_: Exception) {}
        }

        s = s.trim()

        // Direct 40-hex string
        if (HEX_40_REGEX.matches(s)) {
            return s.lowercase()
        }

        // Direct 32-base32 string
        if (BASE32_32_REGEX.matches(s)) {
            return base32ToHex(s)
        }

        // Magnet link BTIH extraction
        val matcher = BTIH_PATTERN.matcher(s)
        if (matcher.find()) {
            val matched = matcher.group(1) ?: return null
            if (HEX_40_REGEX.matches(matched)) {
                return matched.lowercase()
            }
            if (BASE32_32_REGEX.matches(matched)) {
                return base32ToHex(matched)
            }
        }

        return null
    }

    /**
     * Converts a 32-character Base32 encoded string to a 40-character lowercase hex string.
     */
    fun base32ToHex(base32: String): String? {
        val clean = base32.trim().uppercase()
        if (clean.length != 32) return null

        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bytes = ByteArray(20)
        var buffer = 0
        var bitsLeft = 0
        var count = 0

        for (c in clean) {
            val valIndex = base32Chars.indexOf(c)
            if (valIndex < 0) return null
            buffer = (buffer shl 5) or valIndex
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bytes[count++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                bitsLeft -= 8
            }
        }

        if (count != 20) return null

        val hexChars = CharArray(40)
        val hexArray = "0123456789abcdef".toCharArray()
        for (i in 0 until 20) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.coppersurfer.tk:6969/announce"
    )

    fun extractInfoHash(input: String?): String? = parseHash(input)

    fun isInfoHash(input: String?): Boolean = parseHash(input) != null

    fun isValidMagnetUri(input: String?): Boolean = parseHash(input) != null

    fun toCanonicalMagnet(hash: String, displayName: String? = null, trackers: List<String> = emptyList()): String {
        val cleanHash = parseHash(hash) ?: hash.trim().lowercase()
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(cleanHash)
        if (!displayName.isNullOrBlank()) {
            try {
                sb.append("&dn=").append(java.net.URLEncoder.encode(displayName, "UTF-8"))
            } catch (_: Exception) {
                sb.append("&dn=").append(displayName)
            }
        }
        val effectiveTrackers = trackers.ifEmpty { DEFAULT_TRACKERS }
        for (tr in effectiveTrackers) {
            if (tr.isNotBlank()) {
                try {
                    sb.append("&tr=").append(java.net.URLEncoder.encode(tr.trim(), "UTF-8"))
                } catch (_: Exception) {
                    sb.append("&tr=").append(tr.trim())
                }
            }
        }
        return sb.toString()
    }

    /**
     * Safely redacts sensitive keys, tokens, stream URLs, and magnet links for logging.
     */
    fun redact(text: String?): String {
        if (!BuildConfig.DEBUG) return "[REDACTED]"
        if (text.isNullOrBlank()) return ""
        if (text.length <= 8) return "***"
        return text.take(4) + "..." + text.takeLast(4)
    }
}
