package com.learnbridge.app.doc

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The v1 → v2 upgrade, against a real v1 database.
 *
 * This exists because the previous `onUpgrade` dropped every table and called `onCreate`. That was
 * deliberate and documented as safe while there was only a v1 — and the first real v2 would have
 * silently erased every document a student had imported, on the launch after the update. There is
 * no way to check that from inside the app, so it is checked here: a database built with the v1
 * schema is opened by the current helper and must come out with its rows intact.
 *
 * See [DocStoreTest] for why the SDK is pinned to 34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocStoreMigrationTest {

    /** Builds the schema exactly as version 1 shipped it, including the table v2 drops. */
    private class V1Helper(context: android.content.Context) :
        SQLiteOpenHelper(context, "learnbridge_docs.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE documents(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    sourceUri TEXT,
                    wordCount INTEGER NOT NULL,
                    ingestedAt INTEGER NOT NULL,
                    status TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE VIRTUAL TABLE chunks_fts USING fts4(doc_id, ordinal, text)")
            db.execSQL(
                """
                CREATE TABLE artifacts(
                    docId INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    lang TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    text TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX idx_artifacts_lookup ON artifacts(docId, kind, lang, ordinal)")
            // Present on any device that installed a build from before the F8 removal.
            db.execSQL("CREATE TABLE quiz_results(docId INTEGER, ordinal INTEGER, correct INTEGER, answeredAt INTEGER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun seedV1() {
        val helper = V1Helper(RuntimeEnvironment.getApplication())
        val db = helper.writableDatabase
        db.insert(
            "documents",
            null,
            ContentValues().apply {
                put("title", "The Water Cycle")
                put("sourceUri", "content://x/1")
                put("wordCount", 433)
                put("ingestedAt", 1_700_000_000_000L)
                put("status", DocStore.STATUS_READY)
            },
        )
        db.insert(
            "artifacts",
            null,
            ContentValues().apply {
                put("docId", 1L)
                put("kind", LessonKinds.EXPLANATION)
                put("lang", "hi")
                put("ordinal", 0)
                put("text", "पानी कभी समाप्त नहीं होता।")
            },
        )
        helper.close()
    }

    @Test
    fun `upgrading from v1 keeps the documents a student already imported`() {
        seedV1()

        val rows = DocStore(RuntimeEnvironment.getApplication()).listDocuments()

        assertEquals("the v1 document must survive the upgrade", 1, rows.size)
        assertEquals("The Water Cycle", rows[0].title)
        assertEquals(433, rows[0].wordCount)
    }

    @Test
    fun `upgrading from v1 keeps generated artifacts`() {
        seedV1()

        val store = DocStore(RuntimeEnvironment.getApplication())
        val docId = store.listDocuments().single().id

        assertEquals(listOf("hi"), store.translationLanguages(docId))
        assertEquals(1, store.artifacts(docId, LessonKinds.EXPLANATION, "hi").size)
    }

    @Test
    fun `upgrading from v1 adds a usable mastery table`() {
        seedV1()

        val store = DocStore(RuntimeEnvironment.getApplication())
        val docId = store.listDocuments().single().id
        store.putMastery(Mastery.initial(docId).afterQuiz(4, 5, 3_000))

        val record = store.mastery(docId)
        assertNotNull("the v2 table must exist after an upgrade, not only on a fresh install", record)
        assertTrue(record!!.mastery > 0.0)
        assertEquals(1, record.exposureCount)
    }

    @Test
    fun `upgrading from v1 drops the unread quiz_results table`() {
        seedV1()

        val db = DocStore(RuntimeEnvironment.getApplication()).readableDatabase
        val present = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'quiz_results'",
            null,
        ).use { it.count > 0 }

        assertEquals("quiz_results should be gone", false, present)
    }

    @Test
    fun `a fresh install lands on the same schema as an upgraded one`() {
        val store = DocStore(RuntimeEnvironment.getApplication())
        val docId = store.insertDocument("New", null, 10)

        // onCreate runs the migrations rather than duplicating their DDL, so this is the check that
        // the two paths cannot drift.
        store.putMastery(Mastery.initial(docId))

        assertNotNull(store.mastery(docId))
    }

    /** Kept local so the test does not depend on a pipeline constant that could be renamed. */
    private object LessonKinds {
        const val EXPLANATION = "explanation"
    }
}
