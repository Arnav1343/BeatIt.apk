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
        fun isExpired(): Boolean =
            System.currentTimeMillis() - timestamp > CACHE_TTL_MS
    }

    /**
     * Search YouTube and return the best matching result.
     */
    fun search(query: String): Map<String, Any?>? {
        return try {
            val service = ServiceList.YouTube
            val queryHandler = service.searchQHFactory.fromQuery(query)
            val searchInfo = SearchInfo.getInfo(service, queryHandler)
            val item = searchInfo.relatedItems
                ?.filterIsInstance<StreamInfoItem>()
                ?.firstOrNull() ?: return null

            mapOf(
                "url" to item.url,
                "title" to item.name,
                "uploader" to (item.uploaderName ?: ""),
                "duration" to item.duration
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Return quick title suggestions for the search bar.
     */
    fun suggestions(query: String): List<Map<String, Any?>> {
        return try {
            val service = ServiceList.YouTube
            val queryHandler = service.searchQHFactory.fromQuery(query)
            val searchInfo = SearchInfo.getInfo(service, queryHandler)
            searchInfo.relatedItems
                ?.filterIsInstance<StreamInfoItem>()
                ?.take(6)
                ?.map { item ->
                    mapOf(
                        "url" to item.url,
                        "title" to item.name,
                        "uploader" to (item.uploaderName ?: ""),
                        "duration" to item.duration
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Pre-fetch and cache the stream info for a video URL in the background.
     * Call this when the user selects a suggestion — by the time they hit
     * Download, the stream URL is already cached.
     */
    fun prefetchStreamInfo(videoUrl: String) {
        prefetchExecutor.submit {
            try {
                if (streamCache.containsKey(videoUrl) && !streamCache[videoUrl]!!.isExpired()) {
                    return@submit // already cached
                }
                val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
                val url = info.audioStreams
                    ?.filter { it.content.isNotEmpty() }
                    ?.maxByOrNull { it.averageBitrate }
                    ?.content
                    ?: info.videoStreams
                        ?.filter { it.content.isNotEmpty() }
                        ?.firstOrNull()
                        ?.content

                if (url != null) {
                    streamCache[videoUrl] = CachedStream(url)
                }
            } catch (_: Exception) {
                // prefetch failure is silent — download will retry
            }
        }
    }

    /**
     * Check if a stream URL is already cached and ready.
     */
    fun isPrefetched(videoUrl: String): Boolean {
        val cached = streamCache[videoUrl] ?: return false
        return !cached.isExpired()
    }

    /**
     * Get the best audio stream URL for a given YouTube video URL.
     * Uses cache if available, otherwise fetches fresh.
     */
    fun getAudioStreamUrl(videoUrl: String): Pair<String?, String?> {
        // Check cache first
        val cached = streamCache[videoUrl]
        if (cached != null && !cached.isExpired()) {
            return Pair(cached.streamUrl, null)
        }

        return try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)

            val audioUrl = info.audioStreams
                ?.filter { it.content.isNotEmpty() }
                ?.maxByOrNull { it.averageBitrate }
                ?.content

            if (audioUrl != null) {
                streamCache[videoUrl] = CachedStream(audioUrl)
                return Pair(audioUrl, null)
            }

            val videoUrl2 = info.videoStreams
                ?.filter { it.content.isNotEmpty() }
                ?.firstOrNull()
                ?.content

            if (videoUrl2 != null) {
                streamCache[videoUrl] = CachedStream(videoUrl2)
                return Pair(videoUrl2, null)
            }

            Pair(null, "No audio or video streams found for this video")
        } catch (e: Exception) {
            Pair(null, e.message ?: "Unknown error extracting stream")
        }
    }

    companion object {
        private var isInitialized = false
        private const val CACHE_TTL_MS = 3600_000L // 1 hour
        private val streamCache = ConcurrentHashMap<String, CachedStream>()
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
