package com.beatit.app

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class YoutubeHelper {

    init {
        if (!isInitialized) {
            NewPipe.init(OkHttpDownloader.getInstance())
            isInitialized = true
        }
    }

    // ── StreamInfo cache with 1-hour TTL ───────────────────────────
    private data class CachedStream(
        val streamUrl: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired() = System.currentTimeMillis() - timestamp > CACHE_TTL_MS
    }

    // ── Extract best stream URL from StreamInfo ────────────────────
    private fun extractBestUrl(info: StreamInfo): String? {
        return info.audioStreams
            ?.filter { it.content.isNotEmpty() }
            ?.maxByOrNull { it.averageBitrate }
            ?.content
            ?: info.videoStreams
                ?.filter { it.content.isNotEmpty() }
                ?.firstOrNull()
                ?.content
    }

    /**
     * Pre-fetch stream info in background. Stores a Future so getAudioStreamUrl()
     * can await it instead of starting a duplicate extraction.
     */
    fun prefetchStreamInfo(videoUrl: String) {
        // Already cached and valid
        val cached = streamCache[videoUrl]
        if (cached != null && !cached.isExpired()) return

        // Already fetching — don't duplicate
        if (pendingFetches.containsKey(videoUrl)) return

        val future = prefetchExecutor.submit<String?> {
            try {
                val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
                val url = extractBestUrl(info)
                if (url != null) {
                    streamCache[videoUrl] = CachedStream(url)
                }
                url
            } catch (_: Exception) {
                null
            } finally {
                pendingFetches.remove(videoUrl)
            }
        }
        pendingFetches[videoUrl] = future
    }

    /**
     * Check if a stream URL is already cached and ready.
     */
    fun isPrefetched(videoUrl: String): Boolean {
        val cached = streamCache[videoUrl] ?: return false
        return !cached.isExpired()
    }

    /**
     * Get the best audio stream URL. Three-tier approach:
     * 1. Cache hit → instant
     * 2. Pending prefetch → await (no duplicate work)
     * 3. Fresh extraction (last resort)
     */
    fun getAudioStreamUrl(videoUrl: String): Pair<String?, String?> {
        // 1. Cache hit — instant return
        val cached = streamCache[videoUrl]
        if (cached != null && !cached.isExpired()) {
            return Pair(cached.streamUrl, null)
        }

        // 2. Await pending prefetch — avoid duplicate StreamInfo.getInfo()
        val pending = pendingFetches[videoUrl]
        if (pending != null) {
            try {
                val url = pending.get(30, TimeUnit.SECONDS)
                if (url != null) return Pair(url, null)
            } catch (_: Exception) {
                // Prefetch failed — fall through to fresh extraction
            }
        }

        // 3. Fresh extraction
        return try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
            val url = extractBestUrl(info)
            if (url != null) {
                streamCache[videoUrl] = CachedStream(url)
                Pair(url, null)
            } else {
                Pair(null, "No audio or video streams found for this video")
            }
        } catch (e: Exception) {
            Pair(null, e.message ?: "Unknown error extracting stream")
        }
    }

    // ── Reject pattern for non-music content ─────────────────────
    private val REJECT_PATTERN = Regex(
        """(#shorts|shorts|cricket|wicket|ipl|match|highlights|reaction|gameplay|tutorial|podcast|vlog|unboxing|review|trailer|teaser|behind.the.scenes|interview|news|cooking|recipe|workout|fitness|compilation|prank|challenge|stream|full\s*album|full\s*movie)""",
        RegexOption.IGNORE_CASE
    )

    // ── Search ─────────────────────────────────────────────────────
    fun search(query: String, limit: Int = 5): List<StreamInfoItem> {
        return try {
            // Try YouTube Music songs filter first (returns only actual songs)
            val musicResults = try {
                val searchInfo = SearchInfo.getInfo(
                    ServiceList.YouTube,
                    ServiceList.YouTube.searchQHFactory.fromQuery(
                        query,
                        listOf("music_songs"),
                        ""
                    )
                )
                searchInfo.relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .take(limit)
            } catch (_: Exception) {
                emptyList()
            }

            if (musicResults.isNotEmpty()) {
                musicResults
            } else {
                // Fallback: regular search with " song" appended + filtering
                val musicQuery = "$query song"
                val searchInfo = SearchInfo.getInfo(
                    ServiceList.YouTube,
                    ServiceList.YouTube.searchQHFactory.fromQuery(musicQuery)
                )
                searchInfo.relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .filter { item ->
                        val dur = item.duration
                        val title = item.name ?: ""
                        val url = item.url ?: ""
                        if (url.contains("/shorts/")) return@filter false
                        val goodDuration = dur in 60..600
                        val notRejected = !REJECT_PATTERN.containsMatchIn(title)
                        goodDuration && notRejected
                    }
                    .take(limit)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun suggestions(query: String): List<Map<String, Any?>> {
        return search(query, 8).map { item ->
            mapOf(
                "title" to item.name,
                "uploader" to (item.uploaderName ?: ""),
                "duration" to item.duration,
                "url" to item.url
            )
        }
    }

    companion object {
        private var isInitialized = false
        private const val CACHE_TTL_MS = 3600_000L // 1 hour
        private val streamCache = ConcurrentHashMap<String, CachedStream>()
        private val pendingFetches = ConcurrentHashMap<String, Future<String?>>()
        private val prefetchExecutor = Executors.newSingleThreadExecutor()
    }
}

/**
 * OkHttp-based downloader for NewPipe Extractor.
 */
class OkHttpDownloader private constructor() : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val requestBuilder = okhttp3.Request.Builder().url(request.url())
        request.headers().forEach { (key, values) ->
            values.forEach { value -> requestBuilder.addHeader(key, value) }
        }

        request.dataToSend()?.let { body ->
            requestBuilder.post(body.toRequestBody("application/json".toMediaType()))
        } ?: requestBuilder.get()

        val response = client.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string() ?: ""
        val headers = mutableMapOf<String, List<String>>()
        response.headers.names().forEach { name ->
            headers[name] = response.headers.values(name)
        }
        return Response(response.code, response.message, headers, responseBody, request.url())
    }

    companion object {
        @Volatile private var instance: OkHttpDownloader? = null
        fun getInstance(): OkHttpDownloader =
            instance ?: synchronized(this) {
                instance ?: OkHttpDownloader().also { instance = it }
            }
    }
}
