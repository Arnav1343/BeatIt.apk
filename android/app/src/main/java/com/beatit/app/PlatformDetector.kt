package com.beatit.app

import java.net.URL

object PlatformDetector {
    private val WHITELISTED_DOMAINS = setOf(
        "youtube.com", "www.youtube.com", "youtu.be",
        "spotify.com", "open.spotify.com",
        "music.apple.com"
    )

    fun detectPlatform(url: String): SourcePlatform? {
        return try {
            val parsedUrl = URL(url)
            
            // Security: Enforce HTTPS
            if (parsedUrl.protocol != "https") return null
            
            val host = parsedUrl.host?.lowercase() ?: return null
            
            // Security: Reject IP-based URLs and private ranges
            if (isIpAddress(host)) return null
            
            // Whitelist check
            if (!isWhitelisted(host)) return null

            when {
                host.contains("youtube") || host.contains("youtu.be") -> SourcePlatform.YOUTUBE
                host.contains("spotify") -> SourcePlatform.SPOTIFY
                host.contains("apple") -> SourcePlatform.APPLE_MUSIC
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isWhitelisted(host: String): Boolean {
        return WHITELISTED_DOMAINS.any { domain -> 
            host == domain || host.endsWith(".$domain") 
        }
    }

    private fun isIpAddress(host: String): Boolean {
        val ipRegex = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
        if (ipRegex.matches(host)) return true
        
        // Basic check for localhost or private network ranges
        if (host == "localhost") return true
        if (host.startsWith("127.")) return true
        if (host.startsWith("192.168.")) return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("172.")) {
            val second = host.split(".")[1].toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }
}
