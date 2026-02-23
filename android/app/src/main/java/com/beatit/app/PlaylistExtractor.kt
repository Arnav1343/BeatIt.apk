package com.beatit.app

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.util.*
import kotlin.math.roundToInt

data class TrackCandidate(
    val title: String,
    val artist: String,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
    val sourcePlatform: SourcePlatform
)

object PlaylistExtractor {

    suspend fun extract(url: String, platform: SourcePlatform): List<TrackCandidate> {
        return when (platform) {
            SourcePlatform.YOUTUBE -> extractYoutube(url)
            SourcePlatform.SPOTIFY -> extractSpotify(url)
            SourcePlatform.APPLE_MUSIC -> extractAppleMusic(url)
        }
    }

    private fun extractYoutube(url: String): List<TrackCandidate> {
        return try {
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            val tracks = mutableListOf<TrackCandidate>()
            
            // Handle pagination (initial page)
            var currentItems = info.relatedItems.filterIsInstance<StreamInfoItem>()
            tracks.addAll(currentItems.map { it.toCandidate() })

            // Basic pagination loop (simplified for now, NewPipe supports more)
            var nextPage = info.nextPage
            var count = tracks.size
            while (nextPage != null && count < 500) {
                val page = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, nextPage)
                val items = page.items.filterIsInstance<StreamInfoItem>()
                tracks.addAll(items.map { it.toCandidate() })
                nextPage = page.nextPage
                count = tracks.size
            }

            tracks.take(500)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun StreamInfoItem.toCandidate() = TrackCandidate(
        title = name ?: "Unknown",
        artist = uploaderName ?: "Unknown",
        durationSeconds = duration.toInt().takeIf { it > 0 },
        thumbnailUrl = thumbnails.firstOrNull()?.url,
        sourcePlatform = SourcePlatform.YOUTUBE
    )

    private fun extractSpotify(url: String): List<TrackCandidate> {
        // Since we don't have a full Spotify API key/extractor here,
        // we use Jsoup to extract metadata from the public preview page.
        // This is a fallback for single tracks or simple previews.
        return try {
            val doc = Jsoup.connect(url).get()
            val title = doc.select("meta[property=og:title]").attr("content")
            val artist = doc.select("meta[property=og:description]").attr("content")
                ?.split("·")?.getOrNull(0)?.trim() ?: ""
            val thumb = doc.select("meta[property=og:image]").attr("content")
            
            if (title.isNotEmpty()) {
                listOf(TrackCandidate(title, artist, null, thumb, SourcePlatform.SPOTIFY))
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractAppleMusic(url: String): List<TrackCandidate> {
        return try {
            val doc = Jsoup.connect(url).get()
            val title = doc.select("meta[property=og:title]").attr("content")
            val artist = doc.select("meta[property=og:description]").attr("content")
                ?.split("·")?.getOrNull(0)?.trim() ?: ""
            val thumb = doc.select("meta[property=og:image]").attr("content")

            if (title.isNotEmpty()) {
                listOf(TrackCandidate(title, artist, null, thumb, SourcePlatform.APPLE_MUSIC))
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Normalization logic for Fingerprinting ──────────────────────

    fun computeFingerprint(title: String, artist: String, duration: Int?): String {
        val normTitle = sanitize(title)
        val normArtist = sanitize(artist)
        val bucketedDuration = duration?.let { (it.toDouble() / 5.0).roundToInt() * 5 }
        
        val input = if (bucketedDuration != null) {
            "$normTitle|$normArtist|$bucketedDuration"
        } else {
            "$normTitle|$normArtist"
        }
        
        return sha256(input)
    }

    fun sanitize(text: String): String {
        return text.lowercase()
            .replace(Regex("""\(.*?\)|\[.*?\]"""), "") // Remove bracketed text
            .replace(Regex("""(?i)\b(feat|ft|official|video|audio|remastered|lyrics|hq|hd|high quality)\b"""), "")
            .replace(Regex("""[^\w\s]"""), "") // Remove non-alphanumeric except space
            .replace(Regex("""\s+"""), " ") // Double spaces
            .trim()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
