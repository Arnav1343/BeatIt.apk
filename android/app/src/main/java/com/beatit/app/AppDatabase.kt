package com.beatit.app

import android.content.Context
import androidx.room.*

class AppTypeConverters {
    @TypeConverter
    fun fromSourcePlatform(value: SourcePlatform) = value.name
    @TypeConverter
    fun toSourcePlatform(value: String) = SourcePlatform.valueOf(value)

    @TypeConverter
    fun fromTrackStatus(value: TrackStatus) = value.name
    @TypeConverter
    fun toTrackStatus(value: String) = TrackStatus.valueOf(value)

    @TypeConverter
    fun fromBatchState(value: BatchState) = value.name
    @TypeConverter
    fun toBatchState(value: String) = BatchState.valueOf(value)
}

@Database(
    entities = [BatchTask::class, Track::class, MatchCacheEntry::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(AppTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun batchDao(): BatchDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "beatit_database"
                )
                .fallbackToDestructiveMigration() // For dev phase, can add explicit migrations later
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
