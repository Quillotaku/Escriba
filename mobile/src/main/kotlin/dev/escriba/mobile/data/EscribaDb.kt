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

/** Transcripción de una sesión completa: las frases con su hora real, de todas sus conversaciones. */
@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey val sessionId: String,
    val model: String,
    val language: String,
    val linesJson: String,
    val audioMs: Long,
    val transcribeMs: Long,
    val createdAtMs: Long,
)

/** Resultado del LLM para una conversación (título, resumen, tareas…) en JSON. */
@Entity(tableName = "insights", primaryKeys = ["sessionId", "conversation"])
data class InsightsEntity(
    val sessionId: String,
    val conversation: Int,
    val model: String,
    /** Huella del perfil: si cambia el prompt, el resultado ya no vale y se recalcula. */
    val profileHash: String,
    val json: String,
    val createdAtMs: Long,
)

/** Diarización de una sesión: tramos de cada hablante y la huella media de cada uno. */
@Entity(tableName = "diarizations")
data class DiarizationEntity(
    @PrimaryKey val sessionId: String,
    val turnsJson: String,
    /** Hablante → huella (base64 de float32), en JSON. */
    val embeddingsJson: String,
    /** Hablante → ms de voz, en JSON. */
    val speechMsJson: String,
    val createdAtMs: Long,
)

/** Voz registrada por el usuario. Dato biométrico: solo en esta base de datos privada. */
@Entity(tableName = "voices")
data class VoiceEntity(
    @PrimaryKey val name: String,
    val embedding: String,
    val samples: Int,
    val updatedAtMs: Long,
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

    @Query("SELECT * FROM transcripts WHERE sessionId = :sessionId")
    suspend fun transcript(sessionId: String): TranscriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranscript(transcript: TranscriptEntity)

    @Query("DELETE FROM transcripts WHERE sessionId = :sessionId")
    suspend fun deleteTranscript(sessionId: String)

    @Query("SELECT * FROM insights WHERE sessionId = :sessionId")
    suspend fun insights(sessionId: String): List<InsightsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInsights(insights: InsightsEntity)

    @Query("DELETE FROM insights WHERE sessionId = :sessionId")
    suspend fun deleteInsights(sessionId: String)

    @Query("SELECT * FROM diarizations WHERE sessionId = :sessionId")
    suspend fun diarization(sessionId: String): DiarizationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiarization(d: DiarizationEntity)

    @Query("DELETE FROM diarizations WHERE sessionId = :sessionId")
    suspend fun deleteDiarization(sessionId: String)

    @Query("SELECT * FROM diarizations")
    fun observeDiarizations(): Flow<List<DiarizationEntity>>

    @Query("SELECT * FROM insights")
    fun observeInsights(): Flow<List<InsightsEntity>>

    /** Fragmentos desde una fecha: el panel calcula con ellos las conversaciones de la semana. */
    @Query("SELECT * FROM fragments WHERE startMs >= :fromMs")
    fun observeFragmentsSince(fromMs: Long): Flow<List<FragmentEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM fragments WHERE sessionId = :sessionId ORDER BY seq")
    fun observeFragments(sessionId: String): Flow<List<FragmentEntity>>

    @Query("SELECT * FROM notes WHERE sessionId = :sessionId ORDER BY conversation")
    fun observeNotes(sessionId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM voices ORDER BY name")
    suspend fun voices(): List<VoiceEntity>

    @Query("SELECT * FROM voices ORDER BY name")
    fun observeVoices(): Flow<List<VoiceEntity>>

    @Query("SELECT * FROM voices WHERE name = :name")
    suspend fun voice(name: String): VoiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVoice(v: VoiceEntity)

    @Query("DELETE FROM voices WHERE name = :name")
    suspend fun deleteVoice(name: String)

    @Transaction
    suspend fun delete(sessionId: String) {
        deleteFragments(sessionId)
        deleteNotes(sessionId)
        deleteTranscript(sessionId)
        deleteInsights(sessionId)
        deleteDiarization(sessionId)
        deleteSession(sessionId)
    }
}

@Database(entities = [SessionEntity::class, FragmentEntity::class, NoteEntity::class, TranscriptEntity::class, InsightsEntity::class, DiarizationEntity::class, VoiceEntity::class], version = 5, exportSchema = true)
abstract class EscribaDb : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        /** Fase 3 → 4: estado de procesado de las sesiones y tabla de notas. */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
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

        /** Fase 4 → 6: transcripciones. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS transcripts (sessionId TEXT NOT NULL PRIMARY KEY, model TEXT NOT NULL, " +
                        "language TEXT NOT NULL, linesJson TEXT NOT NULL, audioMs INTEGER NOT NULL, transcribeMs INTEGER NOT NULL, " +
                        "createdAtMs INTEGER NOT NULL)",
                )
            }
        }

        /** Fase 6 → 7: resultados del LLM. */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS insights (sessionId TEXT NOT NULL, conversation INTEGER NOT NULL, model TEXT NOT NULL, " +
                        "profileHash TEXT NOT NULL, json TEXT NOT NULL, createdAtMs INTEGER NOT NULL, PRIMARY KEY(sessionId, conversation))",
                )
            }
        }

        /** Fase 7 → 8: diarización y voces registradas. */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS diarizations (sessionId TEXT NOT NULL PRIMARY KEY, turnsJson TEXT NOT NULL, " +
                        "embeddingsJson TEXT NOT NULL, speechMsJson TEXT NOT NULL, createdAtMs INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS voices (name TEXT NOT NULL PRIMARY KEY, embedding TEXT NOT NULL, " +
                        "samples INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL)",
                )
            }
        }

        /** Todas las migraciones, en orden. Los tests comprueban que llevan de la v1 a la actual. */
        internal val MIGRATIONS get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        fun open(context: Context): EscribaDb =
            Room.databaseBuilder(context.applicationContext, EscribaDb::class.java, "escriba.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
