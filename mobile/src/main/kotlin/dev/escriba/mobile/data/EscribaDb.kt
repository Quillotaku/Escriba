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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    /** Cuándo se escribieron sus notas en la bóveda; null si aún no. */
    val processedAtMs: Long? = null,
    /** Perfil con el que se procesó (puede no ser el de grabación si se reprocesó con otro). */
    val processedProfileId: String? = null,
    /** Último error al procesar, para la UI. */
    val processError: String? = null,
    /** Cuándo se borró el audio del móvil por la retención. */
    val audioDeletedAtMs: Long? = null,
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

/** Nota escrita en la bóveda por una conversación de una sesión. */
@Entity(tableName = "notes", primaryKeys = ["sessionId", "conversation"])
data class NoteEntity(
    val sessionId: String,
    val conversation: Int,
    val path: String,
    /** SHA-256 del texto escrito: si el archivo ya no coincide, el usuario lo ha editado. */
    val contentHash: String,
    val audioPath: String?,
    val profileId: String,
    val startMs: Long,
    val writtenAtMs: Long,
    val audioDeletedAtMs: Long? = null,
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

    @Query("SELECT * FROM notes WHERE sessionId = :sessionId ORDER BY conversation")
    suspend fun notes(sessionId: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE audioPath IS NOT NULL AND audioDeletedAtMs IS NULL")
    suspend fun notesWithAudio(): List<NoteEntity>

    @Query("SELECT * FROM notes ORDER BY startMs DESC")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE sessionId = :sessionId AND conversation > :lastConversation")
    suspend fun deleteNotesAfter(sessionId: String, lastConversation: Int)

    @Query("SELECT * FROM sessions WHERE processedAtMs IS NULL AND endMs IS NOT NULL")
    suspend fun unprocessed(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE processedAtMs IS NOT NULL AND audioDeletedAtMs IS NULL")
    suspend fun processedWithAudio(): List<SessionEntity>

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

    @Query("DELETE FROM notes WHERE sessionId = :sessionId")
    suspend fun deleteNotes(sessionId: String)

    @Transaction
    suspend fun delete(sessionId: String) {
        deleteFragments(sessionId)
        deleteNotes(sessionId)
        deleteSession(sessionId)
    }
}

@Database(entities = [SessionEntity::class, FragmentEntity::class, NoteEntity::class], version = 2, exportSchema = false)
abstract class EscribaDb : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        /** Fase 3 → 4: estado de procesado de las sesiones y tabla de notas. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN processedAtMs INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN processedProfileId TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN processError TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN audioDeletedAtMs INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS notes (sessionId TEXT NOT NULL, conversation INTEGER NOT NULL, path TEXT NOT NULL, " +
                        "contentHash TEXT NOT NULL, audioPath TEXT, profileId TEXT NOT NULL, startMs INTEGER NOT NULL, " +
                        "writtenAtMs INTEGER NOT NULL, audioDeletedAtMs INTEGER, PRIMARY KEY(sessionId, conversation))",
                )
            }
        }

        fun open(context: Context): EscribaDb =
            Room.databaseBuilder(context.applicationContext, EscribaDb::class.java, "escriba.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
