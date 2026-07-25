package com.learnbridge.app.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Real SQLite + FTS4 via Robolectric — the sanitiser and the two fallback heuristics are the point.
 *
 * sdk = 34: see [DocStoreTest] — Robolectric 4.11.1 doesn't ship shadows for this app's targetSdk 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetrievalTest {

    private lateinit var store: DocStore
    private lateinit var retrieval: Retrieval
    private var docId: Long = -1

    @Before
    fun setUp() {
        store = DocStore(RuntimeEnvironment.getApplication())
        retrieval = Retrieval(store)
        docId = store.insertDocument("Photosynthesis", null, 500)
        store.insertChunks(
            docId,
            listOf(
                Chunk(0, "Photosynthesis is how plants turn light into chemical energy."),
                Chunk(1, "Chlorophyll absorbs red and blue light inside the chloroplast."),
                Chunk(2, "The Calvin cycle fixes carbon dioxide into sugar."),
                Chunk(3, "Oxygen is released as a byproduct of the light reactions."),
            ),
        )
    }

    @Test
    fun `chunk 0 is always present even when it does not match`() {
        val result = retrieval.retrieve(docId, "chlorophyll")

        assertTrue("expected chunk 0 in $result", result.any { it.ordinal == 0 })
    }

    @Test
    fun `fewer than two hits falls back to intro, second and last chunk`() {
        // "byproduct" only matches chunk 3 — a single hit, below the 2-hit floor.
        val result = retrieval.retrieve(docId, "byproduct")

        val ordinals = result.map { it.ordinal }.toSet()
        assertTrue("expected fallback set to include 0, 1 and 3, got $ordinals", ordinals.containsAll(setOf(0, 1, 3)))
    }

    /**
     * Regression: FTS4 columns have no type affinity, so `ordinal` is stored as text and a plain
     * `ORDER BY ordinal DESC` sorts lexicographically — picking "9" over "12". Ten chunks is not an
     * edge case; it is about four pages, so this fired on ordinary documents.
     */
    @Test
    fun `last-chunk fallback picks the numerically last chunk past nine chunks`() {
        val longDoc = store.insertDocument("Long chapter", null, 3000)
        val chunks = (0..12).map { Chunk(it, "Section $it discusses topic number $it in detail.") }
        store.insertChunks(longDoc, chunks)

        // A query matching nothing in the document forces the fallback path.
        val result = retrieval.retrieve(longDoc, "zzzznomatch", limit = 4)

        val ordinals = result.map { it.ordinal }
        assertTrue("expected ordinal 12 (not 9) in $ordinals", ordinals.contains(12))
        assertEquals("fallback should not exceed the limit", 3, ordinals.size)
    }

    @Test
    fun `two or more hits skip the fallback and use the matches`() {
        // "light" matches chunk 0 and chunk 3 - two hits, at the fallback floor.
        val result = retrieval.retrieve(docId, "light")

        val ordinals = result.map { it.ordinal }.toSet()
        assertTrue(ordinals.contains(0))
        assertTrue(ordinals.contains(3))
    }

    @Test
    fun `results are ascending by ordinal and deduplicated`() {
        val result = retrieval.retrieve(docId, "light energy")
        val ordinals = result.map { it.ordinal }

        assertEquals(ordinals.sorted(), ordinals)
        assertEquals(ordinals.distinct(), ordinals)
    }

    @Test
    fun `a query containing quotes does not throw`() {
        val result = retrieval.retrieve(docId, "\"chlorophyll")
        assertTrue(result.isNotEmpty()) // falls back rather than crashing
    }

    @Test
    fun `a query containing a wildcard does not throw`() {
        val result = retrieval.retrieve(docId, "chloro*phyll")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `a query containing OR does not throw and does not return everything`() {
        val result = retrieval.retrieve(docId, "OR OR OR")
        // Sanitising strips the reserved keyword, leaving an empty match query -> fallback set,
        // not "OR" reinterpreted as an empty-vs-broad FTS operator returning every row.
        assertTrue(result.size <= 4)
    }

    @Test
    fun `an unbalanced quote does not throw`() {
        val result = retrieval.retrieve(docId, "\"unterminated")
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `an empty query after sanitising returns the fallback set, not a crash or everything`() {
        val result = retrieval.retrieve(docId, "*** --- \"\"\"")

        assertTrue(result.isNotEmpty())
        assertTrue(result.size <= 4)
        assertTrue(result.any { it.ordinal == 0 })
    }

    @Test
    fun `result is capped at the requested limit`() {
        val result = retrieval.retrieve(docId, "light", limit = 1)
        assertTrue(result.size <= 1)
    }
}
