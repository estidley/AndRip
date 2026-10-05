package com.andrip.browser.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

object DownloadStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val CANCELED = "CANCELED"
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    /** A [com.andrip.browser.detect.MediaKind] name. */
    val kind: String,
    val title: String,
    val pageUrl: String?,
    val referer: String?,
    val userAgent: String?,
    val status: String = DownloadStatus.QUEUED,
    /** 0..100, or -1 when the size is unknown. */
    val progress: Int = -1,
    /** Human-readable progress, e.g. "12.3 MB of 80.0 MB" or "40 of 212 segments". */
    val detail: String = "",
    val error: String? = null,
    val outputUri: String? = null,
    val fileName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Insert
    suspend fun insert(item: DownloadEntity): Long

    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?)

    @Query("UPDATE downloads SET progress = :progress, detail = :detail WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Int, detail: String)

    @Query("UPDATE downloads SET status = 'DONE', progress = 100, error = NULL, outputUri = :uri, fileName = :fileName WHERE id = :id")
    suspend fun setDone(id: Long, uri: String, fileName: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "andrip.db")
                .build()
                .also { instance = it }
        }
    }
}
