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
     *
     * A *supplier*, not a single stream: DocImport opens a Uri more than once for a file it has to
     * identify by signature (and twice more for a zipped document, whose entry list decides which
     * parts to read). Real providers hand out a new stream per `openInputStream`;
     * `registerInputStream` hands out the same exhausted one, which turns any second read into a
     * document that "looks empty".
     */
    private fun contentUri(name: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/$name")
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { bytes.inputStream() }
        return uri
    }

    private fun textUri(name: String, text: String): Uri = contentUri(name, text.toByteArray())

    private val lesson = """
        The Water Cycle

        Water evaporates from the ocean and forms clouds in the sky. The clouds cool as they rise
        higher. Water falls back to the ground as rain. Rivers carry the rain water back to the
        ocean. This whole journey is called the water cycle.

        Plants also release water into the air through their leaves. That process is called
        transpiration, and it adds moisture to the clouds above a forest.
    """.trimIndent()

    /**
     * Twelve paragraphs of ordinary prose, ~100 words each — comfortably more than the four chunks
     * one section holds, so the ingest has to run several passes to cover it. Built rather than
     * pasted because only its length and its per-paragraph vocabulary matter; a real 30-page PDF
     * measured 7,527 words, six times this.
     */
    private val longLesson = (1..12).joinToString("\n\n") { n ->
        """
        Layer $n of the atmosphere behaves differently from the layers around it, and students
        often confuse them. Air in layer $n carries water vapour that condenses when the
        temperature falls below its dew point. Instruments carried on balloon $n measure that
        temperature every few seconds as they rise. Readings from balloon $n are radioed back to
        a ground station before the balloon bursts. Forecasters combine reading $n with satellite
        images to predict where rain will fall. Without measurement $n the forecast for the
        following day would be far less reliable than it is.
        """.trimIndent().replace("\n", " ")
    }

    private val longLessonWords = longLesson.split(Regex("\\s+")).size

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

        // Reading() with no page numbers: those are for a scanned PDF being OCR'd, and this is a
        // text file, which is read in one go.
        assertEquals(IngestProgress.Reading(), states.first())
        assertTrue(states.any { it is IngestProgress.Teaching })
        assertTrue(states.contains(IngestProgress.Translating))
        assertTrue(states.last() is IngestProgress.Done)
    }

    /**
     * **The whole document is taught, not just its opening.**
     *
     * This is the regression test for the defect a real 30-page PDF exposed: the pipeline took
     * `chunks.take(Prompts.MAX_CHUNKS)`, so a student's unit notes produced a lesson about the first
     * three pages and a quiz that could not ask about anything after them.
     *
     * The evidence is the key-point count. ExtractiveTeacher emits at most
     * [ExtractiveTeacher.KEY_POINTS] per pass, so more than that many persisted points can only mean
     * a later section was taught too — which the old code could not do at any document length.
     */
    @Test
    fun `every section of a long document is taught, not only the first`() = runTest {
        val states = pipeline().ingest(textUri("long.txt", longLesson)).toList()

        val docId = (states.last() as IngestProgress.Done).docId
        val parts = states.filterIsInstance<IngestProgress.Teaching>()
        val total = parts.first().total

        assertTrue("a $longLessonWords-word document is more than one section", total > 1)
        // Distinct, because the stage is announced once before the tutor is acquired — so the model
        // load is not a blank wait — and again as each section starts. Part 1 therefore repeats.
        assertEquals(
            "every section should be announced",
            (1..total).toList(),
            parts.map { it.part }.distinct(),
        )

        val points = store.artifacts(docId, LessonPipeline.KIND_EXPLANATION, LessonPipeline.LANG_EN)
        assertTrue(
            "only ${points.size} key points: no section past the first was taught",
            points.size > ExtractiveTeacher.KEY_POINTS,
        )
    }

    /**
     * English is the source, so there is nothing to render into and the stage must not be announced.
     * This asserted the opposite while the pipeline emitted Translating unconditionally — the stage
     * appeared, translateInto returned immediately, and the screen named work that never happened.
     */
    @Test
    fun `an English import never reports a translation stage`() = runTest {
        val states = pipeline(SupportedLanguage.ENGLISH).ingest(textUri("water.txt", lesson)).toList()

        assertTrue(states.any { it is IngestProgress.Teaching })
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

    /**
     * **Unsupported now means "these bytes are not text", not "this extension is not on a list".**
     *
     * The import path deliberately attempts anything it cannot identify — that is what makes "import
     * any document" true for a `.tex` file or a note with no extension at all — so this test uses a
     * file that really is binary. A video is the case that has to keep failing: reading one as text
     * would produce a lesson made of mojibake instead of an error the student can act on.
     */
    @Test
    fun `a binary file fails without touching the database`() = runTest {
        val mp4 = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x6D, 0x70, 0x34, 0x32, 0, 1)

        val states = pipeline().ingest(contentUri("clip.mp4", mp4)).toList()

        assertEquals(
            IngestProgress.Failed(com.learnbridge.app.doc.ImportResult.Reason.UNSUPPORTED),
            states.last(),
        )
        assertTrue(store.listDocuments().isEmpty())
    }

    /**
     * The other half of that behaviour: a readable file whose name says nothing is read, not refused.
     *
     * This is the case the old extension whitelist got wrong. A student's notes shared through a chat
     * app arrive named `document` with MIME `application/octet-stream`, and the import used to stop
     * there.
     */
    @Test
    fun `a file with no extension or MIME type is still read as text`() = runTest {
        val states = pipeline().ingest(contentUri("notes-with-no-extension", lesson.toByteArray())).toList()

        assertTrue("expected Done, got ${states.last()}", states.last() is IngestProgress.Done)
        assertEquals(1, store.listDocuments().size)
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
