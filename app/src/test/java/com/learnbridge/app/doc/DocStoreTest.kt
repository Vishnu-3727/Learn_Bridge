package com.learnbridge.app.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Real SQLite via Robolectric — verifies the schema and the API surface, not a mock.
 *
 * sdk = 34: this app targets 36, but Robolectric 4.11.1 (the declared version) only ships shadows
 * up to API 34. Pinning the test SDK is a test-harness constraint, not a statement about what the
 * app supports on device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocStoreTest {

    private lateinit var store: DocStore

    @Before
    fun setUp() {
        store = DocStore(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `insertDocument then listDocuments round-trips a finished document`() {
        val id = store.insertDocument("Photosynthesis", "content://x/1", 342)
        store.setStatus(id, DocStore.STATUS_READY)

        val rows = store.listDocuments()

        assertEquals(1, rows.size)
        assertEquals(id, rows[0].id)
        assertEquals("Photosynthesis", rows[0].title)
        assertEquals("content://x/1", rows[0].sourceUri)
        assertEquals(342, rows[0].wordCount)
        assertEquals(DocStore.STATUS_READY, rows[0].status)
    }

    /**
     * The load-bearing assertion for the ghost-document bug: an ingest that never finished must not
     * appear in the library at all. It used to, and it opened to an empty lesson with no way to
     * retry or remove it.
     */
    @Test
    fun `listDocuments hides a document that never finished importing`() {
        store.insertDocument("Half-imported", null, 10) // left at STATUS_IMPORTING

        assertTrue(store.listDocuments().isEmpty())
    }

    /**
     * A document left mid-import by a killed process is removed when the database is next opened.
     *
     * Hiding it from the library was only half the job: the row, its chunks, its artifacts and its
     * saved text stayed on the device permanently, because the only cleanup lived in
     * [com.learnbridge.app.teach.LessonPipeline] and a killed process runs no cleanup at all. Found on
     * a real device as a PDF still `importing` four days after the fact.
     *
     * A second [DocStore] over the same database stands in for the next process: SQLiteOpenHelper
     * runs `onOpen` per instance, so this exercises exactly the path a fresh launch takes.
     */
    @Test
    fun `a document left importing by an earlier process is swept on the next open`() {
        val abandoned = store.insertDocument("Half-imported", null, 10) // left at STATUS_IMPORTING
        store.insertChunks(abandoned, listOf(Chunk(0, "orphan chunk")))
        store.putArtifact(abandoned, "explanation", "en", 0, "orphan explanation")
        store.saveText(abandoned, "orphan text")

        val finished = store.insertDocument("Finished", null, 10)
        store.setStatus(finished, DocStore.STATUS_READY)
        store.close()

        val reopened = DocStore(RuntimeEnvironment.getApplication())

        assertEquals(listOf(finished), reopened.listDocuments().map { it.id })
        // Everything the abandoned import had written, gone with it.
        assertEquals(0, Retrieval(reopened).retrieve(abandoned, "orphan").size)
        assertTrue(reopened.artifacts(abandoned, "explanation", "en").isEmpty())
        assertNull(reopened.savedText(abandoned))
    }

    @Test
    fun `setStatus updates only the targeted document`() {
        val a = store.insertDocument("A", null, 1)
        val b = store.insertDocument("B", null, 1)

        store.setStatus(a, DocStore.STATUS_READY)

        val listed = store.listDocuments()
        // Only A is listed, because only A finished — which is the point of filtering on status.
        assertEquals(listOf(a), listed.map { it.id })
        assertEquals(DocStore.STATUS_READY, listed.single().status)

        // B is still in the table, just not shown; setStatus must not have touched it.
        store.setStatus(b, DocStore.STATUS_READY)
        assertEquals(setOf(a, b), store.listDocuments().map { it.id }.toSet())
    }

    @Test
    fun `deleteDocument leaves no orphan rows in chunks or artifacts`() {
        val docId = store.insertDocument("Doc", null, 10)
        store.setStatus(docId, DocStore.STATUS_READY)
        store.insertChunks(docId, listOf(Chunk(0, "intro"), Chunk(1, "body")))
        store.putArtifact(docId, "explanation", "en", 0, "explanation text")
        store.saveText(docId, "the extracted text")

        store.deleteDocument(docId)

        assertEquals(0, Retrieval(store).retrieve(docId, "intro").size)
        assertTrue(store.artifacts(docId, "explanation", "en").isEmpty())
        assertNull(store.savedText(docId))
        assertTrue(store.listDocuments().none { it.id == docId })
    }

    @Test
    fun `putArtifact is scoped by kind and lang`() {
        val docId = store.insertDocument("Doc", null, 10)
        store.putArtifact(docId, "explanation", "en", 0, "english explanation")
        store.putArtifact(docId, "explanation", "hi", 0, "hindi explanation")
        store.putArtifact(docId, "quiz", "en", 0, "quiz stem")

        assertEquals(listOf("english explanation"), store.artifacts(docId, "explanation", "en"))
        assertEquals(listOf("hindi explanation"), store.artifacts(docId, "explanation", "hi"))
        assertEquals(listOf("quiz stem"), store.artifacts(docId, "quiz", "en"))
    }

    @Test
    fun `putArtifact replaces an existing row at the same ordinal instead of duplicating it`() {
        val docId = store.insertDocument("Doc", null, 10)
        store.putArtifact(docId, "keypoint", "en", 0, "first version")
        store.putArtifact(docId, "keypoint", "en", 0, "regenerated version")

        assertEquals(listOf("regenerated version"), store.artifacts(docId, "keypoint", "en"))
    }

    /**
     * The replacement is one atomic statement against a UNIQUE index, not a delete followed by an
     * insert — so re-rendering a whole lesson can never leave a hole where an artifact used to be.
     * Distinct ordinals must survive it, which is what proves the constraint is scoped to the right
     * four columns and not collapsing every ordinal into one row.
     */
    @Test
    fun `re-rendering every ordinal keeps one row each and loses none`() {
        val docId = store.insertDocument("Doc", null, 10)
        repeat(5) { store.putArtifact(docId, "explanation", "en", it, "first pass $it") }
        repeat(5) { store.putArtifact(docId, "explanation", "en", it, "second pass $it") }

        assertEquals(
            List(5) { "second pass $it" },
            store.artifacts(docId, "explanation", "en"),
        )
    }

    /**
     * The query this replaced took whichever row SQLite returned first, with no ORDER BY. That was
     * survivable only while a document could hold exactly one translation; a lesson rendered into a
     * second language afterwards would have made the toggle's target depend on storage order.
     */
    @Test
    fun `translationLanguages returns every rendered language, ordered, and never English`() {
        val docId = store.insertDocument("Doc", null, 10)
        store.putArtifact(docId, "explanation", "en", 0, "english")
        store.putArtifact(docId, "explanation", "ur", 0, "urdu")
        store.putArtifact(docId, "explanation", "hi", 0, "hindi")
        store.putArtifact(docId, "quiz", "hi", 0, "hindi quiz") // same language, second kind

        assertEquals(listOf("hi", "ur"), store.translationLanguages(docId))
    }

    @Test
    fun `a document with no translation reports none`() {
        val docId = store.insertDocument("Doc", null, 10)
        store.putArtifact(docId, "explanation", "en", 0, "english only")

        assertTrue(store.translationLanguages(docId).isEmpty())
    }

    @Test
    fun `saveText and savedText round-trip through the filesystem`() {
        val docId = store.insertDocument("Doc", null, 10)
        assertNull(store.savedText(docId)) // nothing saved yet

        store.saveText(docId, "line one\nline two")

        assertEquals("line one\nline two", store.savedText(docId))
    }
}
