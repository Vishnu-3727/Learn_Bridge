package com.learnbridge.app.teach

import android.net.Uri
import com.learnbridge.app.LearnBridgeApp
import com.learnbridge.app.doc.Chunk
import com.learnbridge.app.doc.DocStore
import com.learnbridge.app.lang.LessonTranslator
import com.learnbridge.app.lang.SupportedLanguage
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The ingest sequence, end to end, against a real SQLite database and the real extractive tutor.
 *
 * **Why this is an integration test rather than a unit test with mocks.** The invariant worth
 * defending here is not "the pipeline calls the store" — it is "a document is either a finished
 * lesson or it does not exist", and that is a property of the whole sequence plus the database. A
 * mocked store would assert the calls we already know we wrote and would have caught none of the
 * failures this file covers.
 *
 * No model is needed. Robolectric's ActivityManager reports no memory, so ModelHost selects
 * Tier.EXTRACTIVE and LearnBridgeApp hands out ExtractiveTeacher — which is the point of that tier
 * existing. Translation is left at ENGLISH so no ONNX graph is touched.
 *
 * sdk = 34 for the same reason as [com.learnbridge.app.doc.DocStoreTest]: Robolectric 4.11.1 ships
 * no shadows above API 34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LessonPipelineTest {

    private lateinit var app: LearnBridgeApp
    private lateinit var store: DocStore

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as LearnBridgeApp
        store = DocStore(app)
    }

    private fun pipeline(target: SupportedLanguage = SupportedLanguage.ENGLISH) =
        LessonPipeline(app, store, LessonTranslator(app.modelHost), app.modelHost, target)

    /**
     * Registers [text] as the content of a `.txt` Uri. The extension is what DocImport.classify
     * matches on when the resolver reports no MIME type, which is the case in Robolectric.
     */
    private fun textUri(name: String, text: String): Uri {
        val uri = Uri.parse("content://test/$name")
        shadowOf(app.contentResolver).registerInputStream(uri, text.byteInputStream())
        return uri
    }

    private val lesson = """
        The Water Cycle

        Water evaporates from the ocean and forms clouds in the sky. The clouds cool as they rise
        higher. Water falls back to the ground as rain. Rivers carry the rain water back to the
        ocean. This whole journey is called the water cycle.

        Plants also release water into the air through their leaves. That process is called
        transpiration, and it adds moisture to the clouds above a forest.
    """.trimIndent()

    // --- the happy path ---

    @Test
    fun `a text document becomes a finished lesson`() = runTest {
        val states = pipeline().ingest(textUri("water.txt", lesson)).toList()

        val done = states.last()
        assertTrue("expected Done, got $done", done is IngestProgress.Done)
        val docId = (done as IngestProgress.Done).docId

        // Visible in the library, and marked ready rather than left importing.
        val listed = store.listDocuments()
        assertEquals(listOf(docId), listed.map { it.id })
        assertEquals(DocStore.STATUS_READY, listed.single().status)

        // The extractive tutor selects sentences from the document, so every key point must be
        // English text drawn from it — this is the grounding guarantee, checked through the pipeline
        // rather than against the teacher directly.
        val points = store.artifacts(docId, LessonPipeline.KIND_EXPLANATION, LessonPipeline.LANG_EN)
        assertTrue("no key points were persisted", points.isNotEmpty())
        assertTrue(
            "a key point is not from the document: $points",
            points.all { point -> point.split(" ").first().let { lesson.contains(it) } },
        )
    }

    @Test
    fun `progress is reported in order`() = runTest {
        val states = pipeline(SupportedLanguage.HINDI).ingest(textUri("water.txt", lesson)).toList()

        assertEquals(IngestProgress.Reading, states.first())
        assertTrue(states.contains(IngestProgress.Teaching))
        assertTrue(states.contains(IngestProgress.Translating))
        assertTrue(states.last() is IngestProgress.Done)
    }

    /**
     * English is the source, so there is nothing to render into and the stage must not be announced.
     * This asserted the opposite while the pipeline emitted Translating unconditionally — the stage
     * appeared, translateInto returned immediately, and the screen named work that never happened.
     */
    @Test
    fun `an English import never reports a translation stage`() = runTest {
        val states = pipeline(SupportedLanguage.ENGLISH).ingest(textUri("water.txt", lesson)).toList()

        assertTrue(states.contains(IngestProgress.Teaching))
        assertFalse("English needs no translation stage", states.contains(IngestProgress.Translating))
        assertTrue(states.last() is IngestProgress.Done)
    }

    // --- failures are states, and leave nothing behind ---

    @Test
    fun `a blank document fails and leaves no row`() = runTest {
        val states = pipeline().ingest(textUri("empty.txt", "   \n\n  ")).toList()

        assertEquals(IngestProgress.Failed(com.learnbridge.app.doc.ImportResult.Reason.EMPTY), states.last())
        assertTrue(store.listDocuments().isEmpty())
    }

    @Test
    fun `an unsupported file type fails without touching the database`() = runTest {
        val uri = Uri.parse("content://test/archive.zip")
        shadowOf(app.contentResolver).registerInputStream(uri, "binary".byteInputStream())

        val states = pipeline().ingest(uri).toList()

        assertEquals(
            IngestProgress.Failed(com.learnbridge.app.doc.ImportResult.Reason.UNSUPPORTED),
            states.last(),
        )
        assertTrue(store.listDocuments().isEmpty())
    }

    @Test
    fun `an unreadable document fails rather than throwing`() = runTest {
        // No stream registered for this Uri, so openInputStream returns null and the extractor
        // raises IOException. Before DocImport's catch was broadened this class of failure reached
        // the collector as a crash.
        val states = pipeline().ingest(Uri.parse("content://test/missing.txt")).toList()

        assertEquals(
            IngestProgress.Failed(com.learnbridge.app.doc.ImportResult.Reason.UNREADABLE),
            states.last(),
        )
        assertTrue(store.listDocuments().isEmpty())
    }

    /**
     * The invariant behind the whole `.catch` operator: **nothing thrown inside the pipeline reaches
     * the collector, and the half-built row does not survive.**
     *
     * The injected failure stands in for the full disk, the malformed PDF and the native generation
     * error that used to crash the process and leave the ingest overlay up with no way out. It has to
     * be injected rather than provoked: closing a real DocStore does not work, because
     * SQLiteOpenHelper simply reopens the database on the next `writableDatabase` call.
     *
     * It fails *after* insertDocument, which is the case that matters — that is the window in which a
     * document row exists but its lesson does not.
     */
    @Test
    fun `a write failure is reported as a state and cleans up the row`() = runTest {
        val failing = object : DocStore(app) {
            override fun insertChunks(docId: Long, chunks: List<Chunk>) =
                throw android.database.sqlite.SQLiteException("disk I/O error")
        }
        val pipeline = LessonPipeline(
            app,
            failing,
            LessonTranslator(app.modelHost),
            app.modelHost,
            SupportedLanguage.ENGLISH,
        )

        val states = pipeline.ingest(textUri("water.txt", lesson)).toList()

        assertTrue("expected Failed, got ${states.last()}", states.last() is IngestProgress.Failed)
        assertTrue("the failed ingest left a row behind", failing.listDocuments().isEmpty())
    }

    /**
     * Cancellation does not pass through `.catch`, so it is `.onCompletion` that cleans up here. This
     * is the common case in practice: the student leaves the screen while the model is still running,
     * which cancels the collecting scope.
     */
    @Test
    fun `abandoning an ingest leaves no half-built document`() = runTest {
        // take(2) completes the flow after Reading and Teaching, cancelling the producer partway —
        // after insertDocument has run, before the lesson is finished.
        val states = pipeline().ingest(textUri("water.txt", lesson)).take(2).toList()

        assertEquals(2, states.size)
        assertFalse("the ingest should not have reached Done", states.any { it is IngestProgress.Done })
        assertTrue("an abandoned ingest left a row behind", store.listDocuments().isEmpty())
    }
}
