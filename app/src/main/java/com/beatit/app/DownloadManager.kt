package com.beatit.app

import android.content.Context
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TaskStatus(
    val status: String,          // extracting | downloading | done | error
    val percent: Int = 0,
    val result: Map<String, Any>? = null,
    val error: String? = null
)

class DownloadManager(
    private val context: Context,
    private val musicDir: File
) {
    private val tasks = ConcurrentHashMap<String, TaskStatus>()
    private val executor = Executors.newCachedThreadPool()
    private val youtubeHelper = YoutubeHelper()

    // Shared OkHttpClient with connection pooling
    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun startDownload(videoUrl: String, title: String, quality: Int, codec: String): String {
        val taskId = UUID.randomUUID().toString()
        tasks[taskId] = TaskStatus("extracting", 0)

        executor.submit {
            runDownload(taskId, videoUrl, title, quality, codec)
        }
        return taskId
    }

    fun getProgress(taskId: String): Map<String, Any?>? {
        val t = tasks[taskId] ?: return null
        return buildMap {
            put("status", t.status)
            put("percent", t.percent)
            t.result?.let { put("result", it) }
            t.error?.let { put("error", it) }
        }
    }

    private fun runDownload(taskId: String, videoUrl: String, title: String, quality: Int, codec: String) {
        try {
            // Step 1: Get audio stream URL (may be instant if pre-fetched)
            tasks[taskId] = TaskStatus("extracting", 0)
            val (streamUrl, streamError) = youtubeHelper.getAudioStreamUrl(videoUrl)
            if (streamUrl == null) {
                throw IOException(streamError ?: "Could not get audio stream URL")
            }

            // Step 2: Download audio with 64KB buffer
            tasks[taskId] = TaskStatus("downloading", 5)
            val extension = if (codec == "opus") "opus" else "mp3"
            val outputFile = File(musicDir, "${sanitize(title)}.$extension")
            downloadWithProgress(streamUrl, outputFile, taskId)

            // Step 3: Done
            val sizeHuman = humanSize(outputFile.length())
            tasks[taskId] = TaskStatus(
                status = "done",
                percent = 100,
                result = mapOf(
                    "filename" to outputFile.name,
                    "title" to title,
                    "size_human" to sizeHuman
                )
            )
        } catch (e: Exception) {
            tasks[taskId] = TaskStatus("error", error = e.message ?: "Unknown error")
        }
    }

    private fun downloadWithProgress(url: String, dest: File, taskId: String) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        val body = response.body ?: throw IOException("Empty response body")
        val contentLength = body.contentLength()

        dest.outputStream().use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(65536) // 64KB buffer (was 8KB)
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    downloaded += read
                    if (contentLength > 0) {
                        val pct = ((downloaded * 95) / contentLength).toInt().coerceIn(5, 99)
                        tasks[taskId] = TaskStatus("downloading", pct)
                    }
                }
            }
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().take(80)

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}
