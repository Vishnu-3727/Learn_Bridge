package com.learnbridge.app.doc

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * Purpose:  Every piece of ingested-document state the app persists: the document list, its chunk
 *           text (for FTS retrieval), generated artifacts (explanations and quiz items, per
 *           language). The only type in the app that touches this database.
 * Owns:     The single writable [SQLiteDatabase] backing the doc.* tables, plus the plain-text mirror
 *           at `filesDir/docs/<docId>.txt`.
 * Lifetime: One instance per process is enough (SQLiteOpenHelper is safe to share); construct once
 *           and hold it, same pattern as [com.learnbridge.app.ModelHost] for its models.
 * Thread:   android.database.sqlite serialises writers internally, so concurrent callers are safe.
 *           Callers should still not call this from the main thread — every method here does I/O.
 *
 * Plain SQLiteOpenHelper, not Room: Room buys type safety at the cost of KSP + annotation processing
 * on every build, for a schema that will not evolve in the five days this app has left. FTS4, not
 * FTS5: FTS4 works across the whole minSdk 24 range; FTS5 and bm25() are only guaranteed from
 * roughly API 30, and a hackathon demo device is not guaranteed to be that new.
 *
 * The extracted plain text is also written to disk (see [saveText]/[savedText]) so that a schema
 * change on day 4 never means re-OCRing or re-parsing a source document — [chunks_fts] can be
 * rebuilt from the saved text alone.
 */
// `open`, with `open` writes, for exactly one reason: LessonPipelineTest subclasses this to make a
// write fail on demand. The invariant it checks — nothing thrown inside the pipeline reaches the
// collector — cannot be exercised otherwise, because SQLiteOpenHelper reopens a closed database on
// the next writableDatabase call, so there is no way to break a real store from outside.
open class DocStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext

    /** One row of the `documents` table, as returned by [listDocuments]. */
    data class DocumentRow(
        val id: Long,
        val title: String,
        val sourceUri: String?,
        val wordCount: Int,
        val ingestedAt: Long,
        val status: String,
    )

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
        // Column named doc_id, not docId: FTS3/4 tables have an implicit, case-insensitive `docid`
        // alias for their rowid. A column spelled "docId" collides with it and SQLite refuses the
        // CREATE VIRTUAL TABLE with "vtable constructor failed" — reproduced with a bare
        // SQLiteDatabase, so this is a real on-device failure, not a Robolectric artifact.
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
        // UNIQUE, not a plain index, and that is load-bearing rather than defensive: it is what lets
        // putArtifact replace a row in a single atomic statement instead of delete-then-insert.
        //
        // (docId, kind, lang) is still the lookup the UI runs constantly — "show me the English
        // explanation" / "is there a Tamil one yet" — and remains a usable prefix of this index, so
        // that query costs nothing extra and now gets its ORDER BY ordinal for free.
        db.execSQL("CREATE UNIQUE INDEX idx_artifacts_lookup ON artifacts(docId, kind, lang, ordinal)")

        // A fresh install runs every migration's DDL rather than a hand-copied duplicate of it, so
        // the schema a new device gets and the schema an upgraded device reaches cannot drift apart —
        // which is the usual way migration bugs are introduced.
        migrate(db, from = 1, to = DB_VERSION)
    }

    /**
     * Steps the schema forward one version at a time.
     *
     * The previous implementation dropped every table and recreated them. That was declared correct
     * for schema v1 — there was nothing to preserve and nothing had ever shipped a v2 — and it stops
     * being correct the moment a v2 exists, which is now: it would erase every imported document and
     * everything learned about the student on the first launch after the update.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        migrate(db, from = oldVersion, to = newVersion)
    }

    private fun migrate(db: SQLiteDatabase, from: Int, to: Int) {
        for (version in from + 1..to) {
            when (version) {
                2 -> {
                    db.execSQL(CREATE_MASTERY)
                    // Written by builds before the F8 removal, read by nothing then or now. This is
                    // the one place that will ever clear it off a device that still carries it.
                    db.execSQL("DROP TABLE IF EXISTS quiz_results")
                }
            }
        }
    }

    fun insertDocument(title: String, sourceUri: String?, wordCount: Int): Long {
        val values = ContentValues().apply {
            put("title", title)
            put("sourceUri", sourceUri)
            put("wordCount", wordCount)
            put("ingestedAt", System.currentTimeMillis())
            put("status", STATUS_IMPORTING)
        }
        return writableDatabase.insert("documents", null, values)
    }

    fun setStatus(docId: Long, status: String) {
        val values = ContentValues().apply { put("status", status) }
        writableDatabase.update("documents", values, "id = ?", arrayOf(docId.toString()))
    }

    open fun insertChunks(docId: Long, chunks: List<Chunk>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (c in chunks) {
                // Stored as TEXT, not INTEGER: FTS4 columns carry no declared type/affinity, so an
                // INTEGER value here and a TEXT '1' bind parameter in a later `doc_id = ?` WHERE
                // clause compare as different storage classes and never match — verified against a
                // bare SQLiteDatabase, not just this test harness. Storing both sides as text keeps
                // every comparison in the same storage class.
                val values = ContentValues().apply {
                    put("doc_id", docId.toString())
                    put("ordinal", c.ordinal.toString())
                    put("text", c.text)
                }
                db.insert("chunks_fts", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Every document that finished importing, newest first.
     *
     * Filtered on `status`, which is why that column exists. [LessonPipeline] deletes a document
     * that fails partway through, but a process kill can land between the insert and that cleanup —
     * and an unfinished row rendered in the library is indistinguishable from a real lesson until
     * the student opens it and finds it empty, with no way to retry or remove it.
     */
    fun listDocuments(): List<DocumentRow> {
        val rows = mutableListOf<DocumentRow>()
        readableDatabase.rawQuery(
            "SELECT id, title, sourceUri, wordCount, ingestedAt, status FROM documents " +
                "WHERE status = ? ORDER BY ingestedAt DESC",
            arrayOf(STATUS_READY),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += DocumentRow(
                    id = cursor.getLong(0),
                    title = cursor.getString(1),
                    sourceUri = cursor.getString(2),
                    wordCount = cursor.getInt(3),
                    ingestedAt = cursor.getLong(4),
                    status = cursor.getString(5),
                )
            }
        }
        return rows
    }

    /** Removes the document and every row that references it — chunks and artifacts — plus its saved text. */
    fun deleteDocument(docId: Long) {
        val idArg = arrayOf(docId.toString())
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("documents", "id = ?", idArg)
            db.delete("chunks_fts", "doc_id = ?", idArg)
            db.delete("artifacts", "docId = ?", idArg)
            db.delete("mastery", "docId = ?", idArg)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        File(docsDir(appContext), "$docId.txt").delete()
    }

    /**
     * Erases every document, artifact, chunk and mastery record, and the extracted text on disk.
     *
     * Irreversible, and meant to be: this backs the one control that lets a student take back
     * everything the app has worked out about them. The tables are emptied rather than dropped, so
     * the schema — and therefore the schema version — is untouched and the next import needs no
     * migration.
     *
     * Deliberately does not touch preferences. The teaching language and the remembered device tier
     * are settings, not things learned about the student, and silently resetting the app's language
     * as a side effect of deleting data would be its own surprise.
     */
    fun deleteEverything() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("documents", null, null)
            db.delete("chunks_fts", null, null)
            db.delete("artifacts", null, null)
            db.delete("mastery", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        docsDir(appContext).listFiles()?.forEach { it.delete() }
    }

    /**
     * Replaces any existing row for this (docId, kind, lang, ordinal) — regeneration is idempotent.
     *
     * One statement, not a delete followed by an insert. The two-statement version had a window in
     * which the row simply did not exist: a crash or a process kill landing there lost the artifact
     * and left the caller believing it had been written. `CONFLICT_REPLACE` against the unique index
     * makes the swap atomic, and is less work than the pair it replaces.
     */
    fun putArtifact(docId: Long, kind: String, lang: String, ordinal: Int, text: String) {
        val values = ContentValues().apply {
            put("docId", docId)
            put("kind", kind)
            put("lang", lang)
            put("ordinal", ordinal)
            put("text", text)
        }
        writableDatabase.insertWithOnConflict(
            "artifacts",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** All artifact rows for (docId, kind, lang) in ordinal order. Empty if that language hasn't been generated yet. */
    fun artifacts(docId: Long, kind: String, lang: String): List<String> = queryStrings(
        "SELECT text FROM artifacts WHERE docId = ? AND kind = ? AND lang = ? ORDER BY ordinal",
        docId.toString(), kind, lang,
    )

    /**
     * Every non-English language this document has been rendered into, empty if it has none.
     *
     * Discovered from the stored rows rather than read from a setting, because a document keeps the
     * languages it was rendered into. Changing the app's preference must not make an existing lesson
     * claim a translation it does not have.
     *
     * `ORDER BY lang`, and a list rather than one row: the earlier version took whichever row SQLite
     * happened to return first, which was harmless only while a document could hold exactly one
     * translation. A document can now be rendered into a second language after import.
     */
    fun translationLanguages(docId: Long): List<String> = queryStrings(
        "SELECT DISTINCT lang FROM artifacts WHERE docId = ? AND lang <> 'en' ORDER BY lang",
        docId.toString(),
    )

    /** Every row of a one-column query, in the order the query asked for. */
    private fun queryStrings(sql: String, vararg args: String): List<String> {
        val result = mutableListOf<String>()
        readableDatabase.rawQuery(sql, arrayOf(*args)).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0)
        }
        return result
    }

    // --- Learning Twin ---------------------------------------------------------------------------

    /**
     * Reads this document's mastery record, or null before the student has ever been quizzed on it.
     *
     * Null rather than a zero-filled default so the caller can tell "never attempted" from "attempted
     * and scored zero" — two states that mean opposite things to a revision schedule.
     */
    fun mastery(docId: Long): Mastery? =
        readableDatabase.rawQuery("$SELECT_MASTERY WHERE docId = ?", arrayOf(docId.toString()))
            .use { if (it.moveToFirst()) it.toMastery() else null }

    /** Every mastery record, soonest due first. The Twin, in full — this is what an export would write. */
    fun allMastery(): List<Mastery> {
        val rows = mutableListOf<Mastery>()
        readableDatabase.rawQuery("$SELECT_MASTERY ORDER BY dueAt", null)
            .use { while (it.moveToNext()) rows += it.toMastery() }
        return rows
    }

    fun putMastery(record: Mastery) {
        val values = ContentValues().apply {
            put("docId", record.docId)
            put("mastery", record.mastery)
            put("confidence", record.confidence)
            put("exposureCount", record.exposureCount)
            put("lastSeen", record.lastSeen)
            put("intervalDays", record.intervalDays)
            put("easeFactor", record.easeFactor)
            put("dueAt", record.dueAt)
        }
        writableDatabase.insertWithOnConflict("mastery", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun Cursor.toMastery() = Mastery(
        docId = getLong(0),
        mastery = getDouble(1),
        confidence = getDouble(2),
        exposureCount = getInt(3),
        lastSeen = getLong(4),
        intervalDays = getInt(5),
        easeFactor = getDouble(6),
        dueAt = getLong(7),
    )

    /** Writes [text] to `filesDir/docs/<docId>.txt`. Called once, right after extraction. */
    fun saveText(docId: Long, text: String) {
        val dir = docsDir(appContext)
        dir.mkdirs()
        File(dir, "$docId.txt").writeText(text)
    }

    /** The saved plain text for [docId], or null if extraction never completed (or was deleted). */
    fun savedText(docId: Long): String? {
        val file = File(docsDir(appContext), "$docId.txt")
        return if (file.exists()) file.readText() else null
    }

    companion object {
        private const val DB_NAME = "learnbridge_docs.db"

        /** 2 added the `mastery` table. Bumping this requires a matching branch in [migrate]. */
        private const val DB_VERSION = 2

        /**
         * Both mastery reads select the same eight columns in the same order, and [toMastery] indexes
         * them positionally — so the column list and that function have to move together or the
         * record comes back with confidence in the mastery field. One string makes that impossible.
         */
        private const val SELECT_MASTERY =
            "SELECT docId, mastery, confidence, exposureCount, lastSeen, intervalDays, easeFactor, " +
                "dueAt FROM mastery"

        /**
         * One row per document: what the student knows about it, how sure they are, and when it
         * should next be revised. Keyed on docId alone, so a second quiz updates the row rather than
         * appending — the record is a compact summary of every attempt, never a log of them.
         */
        private val CREATE_MASTERY = """
            CREATE TABLE mastery(
                docId INTEGER PRIMARY KEY,
                mastery REAL NOT NULL,
                confidence REAL NOT NULL,
                exposureCount INTEGER NOT NULL,
                lastSeen INTEGER NOT NULL,
                intervalDays INTEGER NOT NULL,
                easeFactor REAL NOT NULL,
                dueAt INTEGER NOT NULL
            )
        """.trimIndent()

        /**
         * The status a document reaches only once its lesson is fully generated and persisted.
         *
         * Declared here rather than in [LessonPipeline] because this class filters on it — the writer
         * and the reader must not be able to drift apart on the spelling of a magic string.
         */
        const val STATUS_READY = "ready"

        /** A document whose ingest has started but not finished. Never shown in the library. */
        const val STATUS_IMPORTING = "importing"

        private fun docsDir(context: Context) = File(context.filesDir, "docs")
    }
}
