package com.beatit.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow

object BatchManager {
    private const val TAG = "BatchManager"
    private const val MAX_CONCURRENT_DOWNLOADS = 3
    private const val MAX_RETRIES = 3
    private const val HEALTH_CHECK_INTERVAL_MS = 60_000L
    private const val DEBOUNCE_INTERVAL_MS = 500L
    private const val WATCHDOG_TIMEOUT_MS = 30_000L

    private val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS)
    private val activeWorkerCount = AtomicInteger(0)
    private val lock = Any()

    private var isRecovering = false
    private var globalRateLimitUntil = 0L
    private var globalConsecutive429s = 0

    private val trackWatchdogs = ConcurrentHashMap<String, Long>()
    private val lastProgressBytes = ConcurrentHashMap<String, Long>()
    private val emaSpeeds = ConcurrentHashMap<String, Double>()

    private lateinit var dao: BatchDao
    private lateinit var youtubeHelper: YoutubeHelper
    private lateinit var musicDir: File
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun init(context: Context, musicDir: File) {
        val database = AppDatabase.getDatabase(context)
        this.dao = database.batchDao()
        this.youtubeHelper = YoutubeHelper()
        this.musicDir = musicDir

        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                runHealthMonitor()
            }
        }

        runRecovery()
        startDispatchLoop()
    }

    private fun startDispatchLoop() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                if (System.currentTimeMillis() < globalRateLimitUntil) {
                    delay(5000)
                    continue
                }

                if (activeWorkerCount.get() >= MAX_CONCURRENT_DOWNLOADS || isRecovering) {
                    delay(1000)
                    continue
                }

                dispatchNextIfPossible()
                delay(500)
            }
        }
    }

    private suspend fun dispatchNextIfPossible() {
        synchronized(lock) {
            CoroutineScope(Dispatchers.IO).launch {
                val nextTrack = dao.getQueuedTracks().firstOrNull() ?: return@launch
                
                // DISPATCHING Guard
                if (nextTrack.status != TrackStatus.QUEUED) return@launch
                
                transitionInternal(nextTrack, TrackStatus.DISPATCHING)
                dao.updateTrack(nextTrack)
                
                activeWorkerCount.incrementAndGet()
                executor.submit {
                    runWorker(nextTrack.id)
                }
            }
        }
    }

    private fun runWorker(trackId: String) {
        val track = runBlocking { dao.getTrack(trackId) } ?: run {
            activeWorkerCount.decrementAndGet()
            return
        }

        try {
            runBlocking { transition(track.id, TrackStatus.DOWNLOADING) }
            
            val videoUrl = track.youtubeVideoId ?: throw IOException("No Video ID")
            val (streamUrl, streamError) = youtubeHelper.getAudioStreamUrl(videoUrl)
            
            if (streamUrl == null) throw IOException(streamError ?: "Stream extraction failed")

            val extension = "mp3" // Default for now
            val finalFile = File(musicDir, "${sanitize(track.title)}.$extension")
            val tempFile = File(musicDir, "${sanitize(track.title)}.$extension.tmp")
            track.outputFilePath = finalFile.absolutePath
            
            downloadWithProgress(streamUrl, tempFile, track)
            
            if (tempFile.renameTo(finalFile)) {
                runBlocking { transition(track.id, TrackStatus.COMPLETED) }
                globalConsecutive429s = 0 // Reset on success
            } else {
                throw IOException("Failed to rename temp file")
            }
        } catch (e: Exception) {
            handleWorkerFailure(track, e)
        } finally {
            activeWorkerCount.decrementAndGet()
            trackWatchdogs.remove(track.id)
            lastProgressBytes.remove(track.id)
            emaSpeeds.remove(track.id)
        }
    }

    private fun handleWorkerFailure(track: Track, e: Exception) {
        val msg = e.message ?: "Unknown error"
        Log.e(TAG, "Worker failed for ${track.id}: $msg")
        
        if (msg.contains("429") || msg.contains("403")) {
            globalConsecutive429s++
            val cooldown = if (globalConsecutive429s == 1) 60_000L else 120_000L
            globalRateLimitUntil = System.currentTimeMillis() + cooldown
            Log.w(TAG, "Rate limit hit. Cooling down for ${cooldown/1000}s")
        }

        track.retryCount++
        track.errorCode = msg
        runBlocking {
            if (track.retryCount < MAX_RETRIES) {
                transition(track.id, TrackStatus.QUEUED)
            } else {
                transition(track.id, TrackStatus.FAILED)
            }
        }
    }

    private fun downloadWithProgress(url: String, dest: File, track: Track) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            if (response.code == 429 || response.code == 403) throw IOException("HTTP ${response.code}")
            throw IOException("Download failed: ${response.code}")
        }
        
        val body = response.body ?: throw IOException("Empty body")
        track.totalBytes = body.contentLength()
        
        var lastWriteTime = 0L

        dest.outputStream().use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(65536)
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    downloaded += read
                    
                    track.bytesDownloaded = downloaded
                    trackWatchdogs[track.id] = System.currentTimeMillis()
                    
                    // Periodic DB update (debounced)
                    val now = System.currentTimeMillis()
                    if (now - lastWriteTime > DEBOUNCE_INTERVAL_MS) {
                        calculateEmaSpeed(track, downloaded, now - lastWriteTime)
                        runBlocking { dao.updateTrack(track) }
                        lastWriteTime = now
                    }
                }
            }
        }
        // Final flush
        runBlocking { dao.updateTrack(track) }
    }

    private fun calculateEmaSpeed(track: Track, currentBytes: Long, deltaMs: Long) {
        val lastBytes = lastProgressBytes[track.id] ?: 0L
        val instantSpeed = (currentBytes - lastBytes).toDouble() / (deltaMs / 1000.0)
        val prevEma = emaSpeeds[track.id] ?: instantSpeed
        val newEma = (instantSpeed * 0.3) + (prevEma * 0.7)
        emaSpeeds[track.id] = newEma
        lastProgressBytes[track.id] = currentBytes
        
        // Update batch aggregate (simplified here)
    }

    // ── Internal Helpers ──────────────────────────────────────────

    private fun runRecovery() {
        isRecovering = true
        CoroutineScope(Dispatchers.IO).launch {
            synchronized(lock) {
                try {
                    val stalled = dao.getStalledTracks()
                    stalled.forEach { track ->
                        track.outputFilePath?.let { File(it).delete() }
                        transitionInternal(track, TrackStatus.QUEUED)
                        dao.updateTrack(track)
                    }
                } catch (e: Exception) {
                    Log.error(TAG, "Recovery failed", e)
                } finally {
                    isRecovering = false
                    activeWorkerCount.set(0)
                }
            }
        }
    }

    private fun runHealthMonitor() {
        val now = System.currentTimeMillis()
        trackWatchdogs.forEach { (id, lastTime) ->
            if (now - lastTime > WATCHDOG_TIMEOUT_MS) {
                Log.w(TAG, "Watchdog timeout for track $id. Requeuing.")
                runBlocking { transition(id, TrackStatus.QUEUED) }
            }
        }
        
        // Zombie detection
        if (activeWorkerCount.get() > 0 && trackWatchdogs.isEmpty()) {
            Log.e(TAG, "CRITICAL_INVARIANT: activeWorkerCount > 0 but no active watchdogs. Resetting.")
            activeWorkerCount.set(0)
        }
    }

    suspend fun transition(trackId: String, nextStatus: TrackStatus) {
        synchronized(lock) {
            val track = dao.getTrack(trackId) ?: return
            if (isValidTransition(track.status, nextStatus)) {
                transitionInternal(track, nextStatus)
                dao.updateTrack(track)
                updateBatchState(track.batchId)
            }
        }
    }

    private fun transitionInternal(track: Track, nextStatus: TrackStatus) {
        track.status = nextStatus
        track.updatedAt = System.currentTimeMillis()
    }

    private fun isValidTransition(from: TrackStatus, to: TrackStatus): Boolean {
        return when (from) {
            TrackStatus.EXTRACTED -> to == TrackStatus.MATCHING
            TrackStatus.MATCHING -> to in listOf(TrackStatus.MATCHED, TrackStatus.MATCHED_LOW_CONFIDENCE, TrackStatus.FAILED)
            TrackStatus.MATCHED -> to == TrackStatus.QUEUED
            TrackStatus.MATCHED_LOW_CONFIDENCE -> to in listOf(TrackStatus.MATCHED, TrackStatus.MATCHING, TrackStatus.MATCHING_MANUAL)
            TrackStatus.MATCHING_MANUAL -> to in listOf(TrackStatus.MATCHED, TrackStatus.MATCHED_LOW_CONFIDENCE, TrackStatus.FAILED)
            TrackStatus.QUEUED -> to == TrackStatus.DISPATCHING
            TrackStatus.DISPATCHING -> to in listOf(TrackStatus.DOWNLOADING, TrackStatus.QUEUED)
            TrackStatus.DOWNLOADING -> to in listOf(TrackStatus.COMPLETED, TrackStatus.FAILED, TrackStatus.QUEUED)
            TrackStatus.FAILED -> to == TrackStatus.QUEUED
            TrackStatus.COMPLETED -> false
        }
    }

    private suspend fun updateBatchState(batchId: String) {
        val batchWithTracks = dao.getBatchWithTracks(batchId) ?: return
        val batch = batchWithTracks.batch
        val tracks = batchWithTracks.tracks

        val completed = tracks.count { it.status == TrackStatus.COMPLETED }
        val failed = tracks.count { it.status == TrackStatus.FAILED }
        val lowConf = tracks.count { it.status == TrackStatus.MATCHED_LOW_CONFIDENCE }
        val active = tracks.count { it.status in listOf(TrackStatus.MATCHING, TrackStatus.QUEUED, TrackStatus.DISPATCHING, TrackStatus.DOWNLOADING) }

        batch.completedCount = completed
        batch.failedCount = failed
        batch.updatedAt = System.currentTimeMillis()

        when {
            completed + failed == batch.totalTracks && lowConf == 0 -> batch.state = BatchState.COMPLETED
            failed == batch.totalTracks -> batch.state = BatchState.FAILED
            lowConf > 0 && active == 0 -> batch.state = BatchState.AWAITING_USER
            active > 0 -> batch.state = BatchState.DOWNLOADING
            else -> batch.state = BatchState.QUEUED
        }
        dao.updateBatch(batch)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().take(80)

    fun submitBatch(url: String, platform: SourcePlatform) {
        if (isRecovering) return
        CoroutineScope(Dispatchers.IO).launch {
            val batch = BatchTask(state = BatchState.EXTRACTING)
            dao.insertBatch(batch)
            try {
                val candidates = PlaylistExtractor.extract(url, platform)
                if (candidates.size > 500) throw IOException("Batch exceeds 500 tracks")
                val tracks = candidates.map { c ->
                    Track(
                        batchId = batch.id,
                        fingerprint = PlaylistExtractor.computeFingerprint(c.title, c.artist, c.durationSeconds),
                        title = c.title,
                        artist = c.artist,
                        durationSeconds = c.durationSeconds,
                        thumbnailUrl = c.thumbnailUrl,
                        sourcePlatform = c.sourcePlatform
                    )
                }
                dao.insertTracks(tracks)
                batch.totalTracks = tracks.size
                batch.state = BatchState.MATCHING
                dao.updateBatch(batch)
                processMatching(batch.id)
            } catch (e: Exception) {
                batch.state = BatchState.FAILED
                batch.errorCode = e.message
                dao.updateBatch(batch)
            }
        }
    }

    private suspend fun processMatching(batchId: String) {
        val tracks = dao.getTracksForBatch(batchId)
        tracks.forEach { track ->
            if (track.status == TrackStatus.EXTRACTED) {
                transition(track.id, TrackStatus.MATCHING)
                val (videoId, confidence) = TrackMapper.mapTrack(track, dao, youtubeHelper)
                track.youtubeVideoId = videoId
                track.matchConfidence = confidence
                if (videoId == null) {
                    transition(track.id, TrackStatus.FAILED)
                    track.errorCode = "No match found"
                } else if (confidence < 0.75) {
                    transition(track.id, TrackStatus.MATCHED_LOW_CONFIDENCE)
                } else {
                    transition(track.id, TrackStatus.MATCHED)
                    transition(track.id, TrackStatus.QUEUED)
                }
                dao.updateTrack(track)
            }
        }
        updateBatchState(batchId)
    }
}
