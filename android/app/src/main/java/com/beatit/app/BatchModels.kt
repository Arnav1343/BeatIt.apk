package com.beatit.app

import androidx.room.*
import java.util.*

enum class SourcePlatform { YOUTUBE, SPOTIFY, APPLE_MUSIC }

enum class TrackStatus {
    EXTRACTED, MATCHING, MATCHED, MATCHED_LOW_CONFIDENCE,
    MATCHING_MANUAL, DISPATCHING, QUEUED, DOWNLOADING, COMPLETED, FAILED
}

enum class BatchState {
    EXTRACTING, MATCHING, QUEUED, DOWNLOADING, AWAITING_USER, COMPLETED, FAILED
}

@Entity(tableName = "batch_tasks")
data class BatchTask(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    var totalTracks: Int = 0,
    var completedCount: Int = 0,
    var failedCount: Int = 0,
    var totalBytesDownloaded: Long = 0,
    var averageDownloadSpeed: Double = 0.0,
    var totalRetries: Int = 0,
    var state: BatchState = BatchState.EXTRACTING,
    var errorCode: String? = null,
    var updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = BatchTask::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["batchId"]), Index(value = ["fingerprint"])]
)
data class Track(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val batchId: String,
    val fingerprint: String,        // SHA256(normTitle|normArtist|bucketedDuration)
    val title: String,
    val artist: String,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
    val sourcePlatform: SourcePlatform,
    var youtubeVideoId: String? = null,
    var matchConfidence: Double? = null,
    var status: TrackStatus = TrackStatus.EXTRACTED,
    var errorCode: String? = null,
    var outputFilePath: String? = null,
    var retryCount: Int = 0,
    var lastAttemptAt: Long? = null,
    var bytesDownloaded: Long? = null,
    var totalBytes: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "match_cache")
data class MatchCacheEntry(
    @PrimaryKey val fingerprint: String, // fingerprint:matcherVersion
    val youtubeVideoId: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface BatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: BatchTask)

    @Update
    suspend fun updateBatch(batch: BatchTask)

    @Query("SELECT * FROM batch_tasks WHERE id = :id")
    suspend fun getBatch(id: String): BatchTask?

    @Query("SELECT * FROM batch_tasks ORDER BY createdAt DESC")
    suspend fun getAllBatches(): List<BatchTask>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<Track>)

    @Update
    suspend fun updateTrack(track: Track)

    @Query("SELECT * FROM tracks WHERE batchId = :batchId")
    suspend fun getTracksForBatch(batchId: String): List<Track>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrack(id: String): Track?

    @Query("SELECT * FROM tracks WHERE status = 'QUEUED' ORDER BY createdAt ASC")
    suspend fun getQueuedTracks(): List<Track>

    @Query("SELECT * FROM tracks WHERE status = 'DOWNLOADING' OR status = 'DISPATCHING'")
    suspend fun getStalledTracks(): List<Track>

    @Transaction
    @Query("SELECT * FROM batch_tasks WHERE id = :batchId")
    suspend fun getBatchWithTracks(batchId: String): BatchWithTracks?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatchCache(entry: MatchCacheEntry)

    @Query("SELECT * FROM match_cache WHERE fingerprint = :fingerprint")
    suspend fun getMatchCache(fingerprint: String): MatchCacheEntry?

    @Query("DELETE FROM match_cache WHERE youtubeVideoId = 'NO_MATCH' AND timestamp < :expireTime")
    suspend fun purgeExpiredNegativeCache(expireTime: Long)
}

data class BatchWithTracks(
    @Embedded val batch: BatchTask,
    @Relation(
        parentColumn = "id",
        entityColumn = "batchId"
    )
    val tracks: List<Track>
)
