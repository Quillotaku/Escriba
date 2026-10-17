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

/** A recording session rebuilt on the phone from its fragments. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val profileHash: String,
    val startMs: Long,
    /** Real time of the end of the last speech segment received. */
    val lastSpeechEndMs: Long,
    /** End of the session according to the watch; null while it's still open. */
    val endMs: Long? = null,
    /** Total number of fragments, which the watch reports when the session ends. */
    val expectedFragments: Int? = null,
    val receivedFragments: Int = 0,
    val speechMs: Long = 0,
    val bytes: Long = 0,
    val updatedAtMs: Long,
    /** When its notes were written to the vault; null if not yet. */
    val processedAtMs: Long? = null,
    /** Profile it was processed with (may differ from the recording one if reprocessed with another). */
    val processedProfileId: String? = null,
    /** Last processing error, for the UI. */
    val processError: String? = null,
    /** When the audio was deleted from the phone by retention. */
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
    /** Segment table in JSON, as it came from the watch. */
    val segmentsJson: String,
    val speechMs: Long,
    val sizeBytes: Long,
    val sha256: String,
    val path: String,
    val receivedAtMs: Long,
)

/** A note written to the vault for one conversation of a session. */
@Entity(tableName = "notes", primaryKeys = ["sessionId", "conversation"])
data class NoteEntity(
    val sessionId: String,
    val conversation: Int,
    val path: String,
    /** SHA-256 of the written text: if the file no longer matches, the user edited it. */
    val contentHash: String,
    val audioPath: String?,
    val profileId: String,
    val startMs: Long,
    val writtenAtMs: Long,
    val audioDeletedAtMs: Long? = null,
)

/** Transcript of a whole session: the lines with their real time, from all its conversations. */
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

/** LLM result for one conversation (title, summary, tasks…) in JSON. */
@Entity(tableName = "insights", primaryKeys = ["sessionId", "conversation"])
data class InsightsEntity(
    val sessionId: String,
    val conversation: Int,
    val model: String,
    /** Profile fingerprint: if the prompt changes, the result is no longer valid and is recomputed. */
    val profileHash: String,
    val json: String,
    val createdAtMs: Long,
)

/** Diarization of a session: each speaker's turns and their average voiceprint. */
@Entity(tableName = "diarizations")
data class DiarizationEntity(
    @PrimaryKey val sessionId: String,
    val turnsJson: String,
    /** Speaker → voiceprint (base64 of float32), in JSON. */
    val embeddingsJson: String,
    /** Speaker → ms of speech, in JSON. */
    val speechMsJson: String,
    val createdAtMs: Long,
)

/** A voice enrolled by the user. Biometric data: only in this private database. */
@Entity(tableName = "voices")
data class VoiceEntity(
    @PrimaryKey val name: String,
    val embedding: String,
    val samples: Int,
    val updatedAtMs: Long,
)

/**
 * Model usage: one row per transcription or summary made (not deleted when reprocessing).
 * It feeds the dashboard: minutes of audio transcribed, compute time and LLM tokens.
 */
@Entity(tableName = "usage_log")
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "asr" (transcription) or "llm" (summary). */
    val kind: String,
    val model: String,
    val sessionId: String,
    /** Audio processed (transcription only). */
    val audioMs: Long,
    /** Compute time. */
    val computeMs: Long,
    val promptTokens: Int,
    val generatedTokens: Int,
    val atMs: Long,
) {
    companion object {
        const val ASR = "asr"
        const val LLM = "llm"
    }
}

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

    /** Adds a fragment and updates its session's totals atomically. */
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

    /** Fragments since a date: the dashboard computes the week's conversations from them. */
    @Query("SELECT * FROM fragments WHERE startMs >= :fromMs")
    fun observeFragmentsSince(fromMs: Long): Flow<List<FragmentEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM fragments WHERE sessionId = :sessionId ORDER BY seq")
    fun observeFragments(sessionId: String): Flow<List<FragmentEntity>>

    @Query("SELECT * FROM notes WHERE sessionId = :sessionId ORDER BY conversation")
    fun observeNotes(sessionId: String): Flow<List<NoteEntity>>

    @Insert
    suspend fun insertUsage(u: UsageEntity)

    @Query("SELECT * FROM usage_log WHERE atMs >= :fromMs ORDER BY atMs")
    fun observeUsageSince(fromMs: Long): Flow<List<UsageEntity>>

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

@Database(entities = [SessionEntity::class, FragmentEntity::class, NoteEntity::class, TranscriptEntity::class, InsightsEntity::class, DiarizationEntity::class, VoiceEntity::class, UsageEntity::class], version = 6, exportSchema = true)
abstract class EscribaDb : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        /** v1 → v2: session processing state and notes table. */
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

        /** v2 → v3: transcripts. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS transcripts (sessionId TEXT NOT NULL PRIMARY KEY, model TEXT NOT NULL, " +
                        "language TEXT NOT NULL, linesJson TEXT NOT NULL, audioMs INTEGER NOT NULL, transcribeMs INTEGER NOT NULL, " +
                        "createdAtMs INTEGER NOT NULL)",
                )
            }
        }

        /** v3 → v4: LLM results. */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS insights (sessionId TEXT NOT NULL, conversation INTEGER NOT NULL, model TEXT NOT NULL, " +
                        "profileHash TEXT NOT NULL, json TEXT NOT NULL, createdAtMs INTEGER NOT NULL, PRIMARY KEY(sessionId, conversation))",
                )
            }
        }

        /** v4 → v5: diarization and enrolled voices. */
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

        /**
         * v5 → v6: model usage log. Existing transcripts are imported as history (summary tokens
         * weren't stored before: they're counted from here on).
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `usage_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `audioMs` INTEGER NOT NULL, `computeMs` INTEGER NOT NULL, " +
                        "`promptTokens` INTEGER NOT NULL, `generatedTokens` INTEGER NOT NULL, `atMs` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO usage_log (kind, model, sessionId, audioMs, computeMs, promptTokens, generatedTokens, atMs) " +
                        "SELECT 'asr', model, sessionId, audioMs, transcribeMs, 0, 0, createdAtMs FROM transcripts",
                )
            }
        }

        /** All migrations, in order. The tests check they lead from v1 to the current version. */
        internal val MIGRATIONS get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        fun open(context: Context): EscribaDb =
            Room.databaseBuilder(context.applicationContext, EscribaDb::class.java, "escriba.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
