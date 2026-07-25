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
    fun `insertDocument then listDocuments round-trips the row`() {
        val id = store.insertDocument("Photosynthesis", "content://x/1", 342)

        val rows = store.listDocuments()

        assertEquals(1, rows.size)
        assertEquals(id, rows[0].id)
        assertEquals("Photosynthesis", rows[0].title)
        assertEquals("content://x/1", rows[0].sourceUri)
        assertEquals(342, rows[0].wordCount)
        assertEquals("importing", rows[0].status) // insertDocument's default status
    }

    @Test
    fun `setStatus updates only the targeted document`() {
        val a = store.insertDocument("A", null, 1)
        val b = store.insertDocument("B", null, 1)

        store.setStatus(a, "ready")

        val rows = store.listDocuments().associateBy { it.id }
        assertEquals("ready", rows.getValue(a).status)
        assertEquals("importing", rows.getValue(b).status)
    }

    @Test
    fun `deleteDocument leaves no orphan rows in chunks, artifacts or quiz_results`() {
        val docId = store.insertDocument("Doc", null, 10)
        store.insertChunks(docId, listOf(Chunk(0, "intro"), Chunk(1, "body")))
        store.putArtifact(docId, "explanation", "en", 0, "explanation text")
        store.recordQuizResult(docId, 0, correct = true)
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

    @Test
    fun `saveText and savedText round-trip through the filesystem`() {
        val docId = store.insertDocument("Doc", null, 10)
        assertNull(store.savedText(docId)) // nothing saved yet

        store.saveText(docId, "line one\nline two")

        assertEquals("line one\nline two", store.savedText(docId))
    }
}
