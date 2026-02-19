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
import java.util.concurrent.TimeUnit

class YoutubeHelper {

    init {
        if (!isInitialized) {
            NewPipe.init(OkHttpDownloader.getInstance())
            isInitialized = true
        }
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
     * Get the best audio-only stream URL for a given YouTube video URL.
     */
    fun getAudioStreamUrl(videoUrl: String): String? {
        return try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
            info.audioStreams
                ?.maxByOrNull { it.averageBitrate }
                ?.content
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private var isInitialized = false
    }
}

/**
 * OkHttp-based downloader for NewPipe Extractor.
 * Uses OkHttp 4.x property-access syntax.
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
