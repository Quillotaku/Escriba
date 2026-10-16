package dev.escriba.mobile.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Sesión de grabación reconstruida en el móvil a partir de sus fragmentos. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val profileHash: String,
    val startMs: Long,
    /** Hora real del final del último tramo de voz recibido. */
    val lastSpeechEndMs: Long,
    /** Final de la sesión según el reloj; null mientras siga abierta. */
    val endMs: Long? = null,
    /** Número total de fragmentos, que el reloj comunica al terminar la sesión. */
    val expectedFragments: Int? = null,
    val receivedFragments: Int = 0,
    val speechMs: Long = 0,
    val bytes: Long = 0,
    val updatedAtMs: Long,
) {
    val isComplete: Boolean get() = expectedFragments != null && receivedFragments >= expectedFragments
}

@Entity(tableName = "fragments", primaryKeys = ["sessionId", "seq"])
data class FragmentEntity(
    val sessionId: String,
    val seq: Int,
    val startMs: Long,
    val endMs: Long,
    /** Tabla de segmentos en JSON, tal como llegó del reloj. */
    val segmentsJson: String,
    val speechMs: Long,
    val sizeBytes: Long,
    val sha256: String,
    val path: String,
    val receivedAtMs: Long,
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startMs DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Query("SELECT * FROM fragments WHERE sessionId = :sessionId ORDER BY seq")
    suspend fun fragments(sessionId: String): List<FragmentEntity>

    @Query("SELECT * FROM fragments WHERE sessionId = :sessionId AND seq = :seq")
    suspend fun fragment(sessionId: String, seq: Int): FragmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFragment(fragment: FragmentEntity)

    @Query("DELETE FROM fragments WHERE sessionId = :sessionId")
    suspend fun deleteFragments(sessionId: String)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    /** Añade un fragmento y actualiza los totales de su sesión de forma atómica. */
    @Transaction
    suspend fun addFragment(fragment: FragmentEntity, sessionTemplate: SessionEntity) {
        insertFragment(fragment)
        val current = session(fragment.sessionId) ?: sessionTemplate
        upsertSession(
            current.copy(
                receivedFragments = current.receivedFragments + 1,
                speechMs = current.speechMs + fragment.speechMs,
                bytes = current.bytes + fragment.sizeBytes,
                lastSpeechEndMs = maxOf(current.lastSpeechEndMs, fragment.endMs),
                updatedAtMs = fragment.receivedAtMs,
            ),
        )
    }

    @Transaction
    suspend fun delete(sessionId: String) {
        deleteFragments(sessionId)
        deleteSession(sessionId)
    }
}

@Database(entities = [SessionEntity::class, FragmentEntity::class], version = 1, exportSchema = false)
abstract class EscribaDb : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        fun open(context: Context): EscribaDb =
            Room.databaseBuilder(context.applicationContext, EscribaDb::class.java, "escriba.db").build()
    }
}
