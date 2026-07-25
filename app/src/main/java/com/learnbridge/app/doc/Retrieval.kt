package com.learnbridge.app.doc

import android.database.sqlite.SQLiteDatabase

/**
 * Purpose:  Answers "which chunks of this document are relevant to this question", the one seam
 *           between the Ask/quiz/teach flows and [DocStore]'s FTS4 index.
 * Owns:     Nothing — reads through [store]'s database, writes nothing.
 * Lifetime: Cheap to construct; hold one per [DocStore] or make one per call, either is fine.
 * Thread:   Does I/O (SQLite reads). Call off the main thread.
 *
 * Two heuristics matter more than ranking here, because FTS4 has no bm25()/rank() — see [DocStore]'s
 * header for why FTS4 was chosen over FTS5 — so "best match first" isn't available cheaply anyway:
 *  1. Chunk 0 (title + intro) is always included, regardless of whether it matched. This alone
 *     answers "what is this document about" / "summarise this" — the first thing a judge types.
 *  2. Fewer than 2 MATCH hits falls back to chunks [0, 1, last]: intro and conclusion tend to answer
 *     generic questions when the query shares no vocabulary with any specific chunk.
 */
class Retrieval(private val store: DocStore) {

    fun retrieve(docId: Long, query: String, limit: Int = 4): List<Chunk> {
        val db = store.readableDatabase
        val matchQuery = sanitize(query)
        val hits = if (matchQuery.isEmpty()) emptyList() else matchChunks(db, docId, matchQuery, limit)

        // ordinal -> chunk, so re-adding chunk 0 (or any chunk already present) is a no-op dedup.
        val chosen = LinkedHashMap<Int, Chunk>()
        chunkByOrdinal(db, docId, 0)?.let { chosen[0] = it }

        if (hits.size >= 2) {
            for (c in hits) chosen[c.ordinal] = c
        } else {
            chunkByOrdinal(db, docId, 1)?.let { chosen[1] = it }
            lastChunk(db, docId)?.let { chosen[it.ordinal] = it }
            for (c in hits) chosen[c.ordinal] = c // a single weak hit is still worth surfacing
        }

        return chosen.values.sortedBy { it.ordinal }.take(limit)
    }

    /**
     * Raw user text is valid FTS query *syntax*: `"`, `*`, `-`, `OR`, `NEAR`, or an unbalanced quote
     * either throws from `MATCH` or silently changes what it means. Strip FTS operator characters,
     * split into words, drop the reserved keywords and empties, then re-join with our own `OR` so a
     * multi-word question matches any chunk sharing at least one word with it.
     *
     * Every surviving term is scoped to the `text` column (`text:word`) — `chunks_fts` also indexes
     * the `doc_id`/`ordinal` columns, and an unscoped MATCH would let a query like "chapter 2" match
     * on the literal ordinal 2 of an unrelated chunk.
     */
    private fun sanitize(query: String): String {
        val stripped = query.replace(Regex("[\"*\\-:()^]"), " ")
        val words = stripped.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.uppercase() in RESERVED }
        if (words.isEmpty()) return ""
        return words.joinToString(" OR ") { "text:$it" }
    }

    private fun matchChunks(db: SQLiteDatabase, docId: Long, matchQuery: String, limit: Int): List<Chunk> {
        val result = mutableListOf<Chunk>()
        db.rawQuery(
            "SELECT ordinal, text FROM chunks_fts WHERE doc_id = ? AND chunks_fts MATCH ? LIMIT ?",
            arrayOf(docId.toString(), matchQuery, limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) result += Chunk(cursor.getInt(0), cursor.getString(1))
        }
        return result
    }

    private fun chunkByOrdinal(db: SQLiteDatabase, docId: Long, ordinal: Int): Chunk? {
        db.rawQuery(
            "SELECT ordinal, text FROM chunks_fts WHERE doc_id = ? AND ordinal = ?",
            arrayOf(docId.toString(), ordinal.toString()),
        ).use { cursor ->
            return if (cursor.moveToFirst()) Chunk(cursor.getInt(0), cursor.getString(1)) else null
        }
    }

    /**
     * CAST is load-bearing. FTS4 columns carry no declared type, so [DocStore] stores `ordinal` as
     * text to keep comparisons in one storage class — which means a plain `ORDER BY ordinal DESC`
     * sorts lexicographically and picks "9" over "12". Any document past nine chunks (~1,800 words,
     * about four pages) would silently return the wrong chunk here, on exactly the generic-question
     * path this fallback exists to serve.
     */
    private fun lastChunk(db: SQLiteDatabase, docId: Long): Chunk? {
        db.rawQuery(
            "SELECT ordinal, text FROM chunks_fts WHERE doc_id = ? ORDER BY CAST(ordinal AS INTEGER) DESC LIMIT 1",
            arrayOf(docId.toString()),
        ).use { cursor ->
            return if (cursor.moveToFirst()) Chunk(cursor.getInt(0), cursor.getString(1)) else null
        }
    }

    private companion object {
        val RESERVED = setOf("OR", "AND", "NOT", "NEAR")
    }
}
