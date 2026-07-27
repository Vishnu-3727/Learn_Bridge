package com.learnbridge.app

import com.learnbridge.app.doc.Mastery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * That "delete everything" deletes everything.
 *
 * Written because it did not. Deletion was three calls at the call site — empty the database, prune
 * the captures — and the export directory was not among them, so a student who exported their
 * Learning Twin and then asked for all their data to be removed was left with a file on disk
 * spelling out what the app had concluded about them. A control that makes a promise in its label
 * needs a test that checks the promise, not the implementation.
 *
 * See [com.learnbridge.app.doc.DocStoreTest] for why the SDK is pinned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EraseLearnerDataTest {

    private lateinit var app: LearnBridgeApp

    @Before
    fun setUp() {
        // Robolectric discards android.util.Log unless told otherwise. Everything in this area
        // reports failure by logging and returning a safe empty value, so without this a broken
        // export looks identical to an empty one and the test says nothing about why.
        org.robolectric.shadows.ShadowLog.stream = System.out
        app = RuntimeEnvironment.getApplication() as LearnBridgeApp
    }

    /**
     * Puts one of everything the app persists about a student on disk and in the database.
     *
     * The export file is written directly rather than through [LearnBridgeApp.writeExports], because
     * that method ends in `FileProvider.getUriForFile`, which cannot resolve its configured roots
     * under Robolectric — it fails with "Failed to find configured root" against a real, correctly
     * declared `file_paths.xml`. The export path itself is verified on a device instead. What this
     * file is for is the *erasure*, and a previous export is a file in that directory however it got
     * there.
     */
    private fun seed(): Long {
        val docId = app.docStore.insertDocument("The Water Cycle", "content://x/1", 433)
        app.docStore.setStatus(docId, com.learnbridge.app.doc.DocStore.STATUS_READY)
        app.docStore.saveText(docId, "The water on Earth is never used up.")
        app.docStore.putArtifact(docId, "explanation", "en", 0, "Water moves in a loop.")
        app.docStore.putMastery(Mastery.initial(docId).afterQuiz(3, 5, 4_000))
        exportDir.mkdirs()
        File(exportDir, "learnbridge-test.txt").writeText("what this app has learned about you")
        return docId
    }

    private val exportDir get() = File(app.filesDir, "export")
    private val docsDir get() = File(app.filesDir, "docs")

    @Test
    fun `everything is really there before it is erased`() {
        val docId = seed()

        assertEquals(1, app.docStore.listDocuments().size)
        assertEquals(1, app.docStore.artifacts(docId, "explanation", "en").size)
        assertTrue(app.docStore.savedText(docId) != null)
        assertTrue(app.docStore.mastery(docId) != null)
        assertTrue("the export must exist for the erase test to mean anything", hasFiles(exportDir))
    }

    @Test
    fun `erasing removes the documents and everything generated from them`() {
        val docId = seed()

        app.eraseAllLearnerData()

        assertTrue(app.docStore.listDocuments().isEmpty())
        assertTrue(app.docStore.artifacts(docId, "explanation", "en").isEmpty())
        assertNull("the extracted text must go too", app.docStore.savedText(docId))
        assertFalse(hasFiles(docsDir))
    }

    @Test
    fun `erasing removes the Learning Twin`() {
        val docId = seed()

        app.eraseAllLearnerData()

        assertNull(app.docStore.mastery(docId))
        assertTrue(app.docStore.allMastery().isEmpty())
    }

    /** The one that was missed. */
    @Test
    fun `erasing removes a previously exported copy of the Twin`() {
        seed()
        assertTrue(hasFiles(exportDir))

        app.eraseAllLearnerData()

        assertFalse("an export left on disk is the data the student asked to delete", hasFiles(exportDir))
    }

    @Test
    fun `the database still works after erasing`() {
        seed()
        app.eraseAllLearnerData()

        // Tables are emptied rather than dropped, so the next import must need no migration and no
        // repair — it should simply write.
        val docId = app.docStore.insertDocument("A fresh start", null, 10)
        app.docStore.setStatus(docId, com.learnbridge.app.doc.DocStore.STATUS_READY)
        app.docStore.putMastery(Mastery.initial(docId))

        assertEquals(1, app.docStore.listDocuments().size)
        assertTrue(app.docStore.mastery(docId) != null)
    }

    @Test
    fun `erasing an app with nothing in it is harmless`() {
        app.eraseAllLearnerData()
        app.eraseAllLearnerData()

        assertTrue(app.docStore.listDocuments().isEmpty())
    }

    private fun hasFiles(dir: File): Boolean = dir.listFiles()?.isNotEmpty() == true
}
