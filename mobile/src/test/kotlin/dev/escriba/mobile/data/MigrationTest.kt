package dev.escriba.mobile.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Las migraciones de Room, sobre SQLite de verdad (Robolectric). Los esquemas de las versiones 1
 * a 4 se derivaron del 5 quitando lo que añadió cada migración (están en mobile/schemas).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EscribaDb::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `de la v1 a la actual sin perder sesiones ni fragmentos`() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, profileId, profileHash, startMs, lastSpeechEndMs, endMs, expectedFragments, " +
                    "receivedFragments, speechMs, bytes, updatedAtMs) VALUES ('s1', 'trabajo', 'h', 1000, 5000, 6000, 1, 1, 4000, 2048, 6000)",
            )
            db.execSQL(
                "INSERT INTO fragments (sessionId, seq, startMs, endMs, segmentsJson, speechMs, sizeBytes, sha256, path, receivedAtMs) " +
                    "VALUES ('s1', 0, 1000, 6000, '[]', 4000, 2048, 'abc', '/x.ogg', 6000)",
            )
        }

        helper.runMigrationsAndValidate(DB, 6, true, *EscribaDb.MIGRATIONS).use { db ->
            db.query("SELECT profileId, speechMs, processedAtMs FROM sessions WHERE id = 's1'").use { c ->
                c.moveToFirst()
                assertEquals("trabajo", c.getString(0))
                assertEquals(4000, c.getLong(1))
                assertEquals(true, c.isNull(2)) // columna nueva de la v2, vacía
            }
            db.query("SELECT COUNT(*) FROM fragments").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun `de la v4 a la v5 aparecen las tablas de voces`() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO insights (sessionId, conversation, model, profileHash, json, createdAtMs) VALUES ('s1', 1, 'gemma', 'h', '{}', 1)",
            )
        }
        helper.runMigrationsAndValidate(DB, 5, true, EscribaDb.MIGRATION_4_5).use { db ->
            db.query("SELECT COUNT(*) FROM voices").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            db.query("SELECT model FROM insights").use { c ->
                c.moveToFirst()
                assertEquals("gemma", c.getString(0))
            }
            db.query("SELECT * FROM diarizations").use { c -> assertNull(if (c.moveToFirst()) c else null) }
        }
    }

    @Test
    fun `de la v5 a la v6 las transcripciones pasan al registro de uso`() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO transcripts (sessionId, model, language, linesJson, audioMs, transcribeMs, createdAtMs) " +
                    "VALUES ('s1', 'ggml-large-v3-turbo-q8_0.bin', 'es', '[]', 215000, 55000, 1000)",
            )
        }
        helper.runMigrationsAndValidate(DB, 6, true, EscribaDb.MIGRATION_5_6).use { db ->
            db.query("SELECT kind, model, audioMs, computeMs, promptTokens FROM usage_log").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("asr", c.getString(0))
                assertEquals("ggml-large-v3-turbo-q8_0.bin", c.getString(1))
                assertEquals(215000, c.getLong(2))
                assertEquals(55000, c.getLong(3))
                assertEquals(0, c.getInt(4))
            }
        }
    }

    /**
     * Ruta absoluta como nombre: Room comprueba que la ruta pedida acabe en «/nombre», y en Windows
     * (donde corren estos tests) el separador es «\». Con la ruta entera, coincide tal cual.
     */
    private val DB: String = java.io.File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "migracion-test.db",
    ).absolutePath
}
