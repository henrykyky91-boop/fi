package de.zapko.spur.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

object Sync {
    /** Nur auf dem Handy, wird nie hochgeladen. */
    const val LOCAL_ONLY = 0
    /** Wartet auf Upload. */
    const val PENDING = 1
    /** Liegt auf dem Server. */
    const val SYNCED = 2
    /** Upload fehlgeschlagen, wird erneut versucht. */
    const val ERROR = 3
}

@Entity(tableName = "media", indices = [Index("takenAt"), Index("sync")])
data class MediaEntity(
    @PrimaryKey val id: String,
    val type: String = "photo",            // photo | video
    val source: String = "spur",           // spur | snapchat
    val takenAt: Long,
    val tzOffset: Int = 0,                 // Minuten
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Float? = null,
    val altitude: Double? = null,
    val heading: Float? = null,
    val speed: Float? = null,
    val address: String? = null,
    val city: String? = null,
    val country: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val size: Long = 0,
    val mime: String = "image/jpeg",
    val device: String? = null,
    val camera: String? = null,
    val note: String = "",
    val tags: String = "",
    val favorite: Boolean = false,
    val localPath: String? = null,
    val thumbPath: String? = null,
    val sync: Int = Sync.PENDING,
    val onServer: Boolean = false,
    val serverThumb: Boolean = false,
    val dirty: Boolean = false,
    val pendingDelete: Boolean = false,
    val locationLate: Boolean = false,
    val sha256: String? = null,
    val error: String? = null,
    val updatedAt: Long = 0,
)

// Abgeleitete Werte bewusst ausserhalb der Entity (Room soll sie nicht als Spalten sehen)
val MediaEntity.isVideo: Boolean get() = type == "video"
val MediaEntity.hasLocation: Boolean get() = lat != null && lon != null
val MediaEntity.hasLocalFile: Boolean get() = localPath != null
val MediaEntity.cityName: String? get() = city?.takeIf { it.isNotBlank() && it != "-" }

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE pendingDelete = 0 ORDER BY takenAt DESC")
    fun observeAll(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun get(id: String): MediaEntity?

    @Query("SELECT * FROM media WHERE id = :id")
    fun observe(id: String): Flow<MediaEntity?>

    @Upsert
    suspend fun upsert(item: MediaEntity)

    @Upsert
    suspend fun upsertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM media WHERE (sync = 1 OR sync = 3) AND pendingDelete = 0 ORDER BY takenAt ASC")
    suspend fun pendingUploads(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE dirty = 1 AND onServer = 1 AND pendingDelete = 0")
    suspend fun dirty(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE pendingDelete = 1")
    suspend fun pendingDeletes(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE lat IS NULL AND source = 'spur' AND takenAt >= :since")
    suspend fun missingLocationSince(since: Long): List<MediaEntity>

    @Query("SELECT * FROM media WHERE lat IS NOT NULL AND (city IS NULL OR city = '') AND source = 'spur' AND pendingDelete = 0 LIMIT 40")
    suspend fun missingAddress(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE sync = 2 AND localPath IS NOT NULL")
    suspend fun syncedWithLocalFile(): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM media WHERE (sync = 1 OR sync = 3) AND pendingDelete = 0")
    fun observePendingCount(): Flow<Int>
}

@Database(entities = [MediaEntity::class], version = 1, exportSchema = false)
abstract class SpurDb : RoomDatabase() {
    abstract fun media(): MediaDao

    companion object {
        @Volatile private var inst: SpurDb? = null
        fun get(ctx: Context): SpurDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, SpurDb::class.java, "spur.db")
                .build().also { inst = it }
        }
    }
}
