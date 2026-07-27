package com.learnbridge.app.doc

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric only for org.json, which is an Android stub on the JVM. Nothing here touches a
 * database or a device — see [DocStoreTest] for why the SDK is pinned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LearnerExportTest {

    private val now = 1_800_000_000_000L

    private fun doc(id: Long, title: String) =
        DocStore.DocumentRow(id, title, null, 420, now - 1000, DocStore.STATUS_READY)

    private fun quizzed(id: Long) = Mastery.initial(id, now).afterQuiz(4, 5, 3_000, now)

    @Test
    fun `the text export names every document`() {
        val text = LearnerExport.asText(
            listOf(
                LearnerExport.Row(doc(1, "The Water Cycle"), quizzed(1)),
                LearnerExport.Row(doc(2, "Photosynthesis"), null),
            ),
            now,
        )

        assertTrue(text.contains("The Water Cycle"))
        assertTrue(text.contains("Photosynthesis"))
        assertTrue("an unquizzed document should say so", text.contains("Not quizzed yet"))
    }

    /** The claim the whole design rests on: this is a summary, not a copy of the student's material. */
    @Test
    fun `no export carries document text`() {
        val row = LearnerExport.Row(doc(1, "The Water Cycle"), quizzed(1))

        val text = LearnerExport.asText(listOf(row), now)
        val json = LearnerExport.asJson(listOf(row), now)

        // The title is the only thing from the document that appears; there is no field anywhere
        // that could carry a chunk, an explanation or a quiz question.
        assertFalse(json.contains("chunk"))
        assertFalse(json.contains("explanation"))
        assertTrue(text.contains("does not contain your documents"))
    }

    @Test
    fun `the json export is parseable and carries the mastery fields`() {
        val json = JSONObject(
            LearnerExport.asJson(listOf(LearnerExport.Row(doc(1, "The Water Cycle"), quizzed(1))), now)
        )

        assertEquals(LearnerExport.SCHEMA_VERSION, json.getInt("schemaVersion"))
        val entry = json.getJSONArray("documents").getJSONObject(0)
        assertEquals("The Water Cycle", entry.getString("title"))

        val mastery = entry.getJSONObject("mastery")
        assertEquals(1, mastery.getInt("quizzesTaken"))
        assertTrue(mastery.getDouble("score") > 0.0)
        assertTrue(mastery.has("confidence"))
        assertTrue(mastery.has("dueAt"))
    }

    @Test
    fun `an unquizzed document carries no mastery object`() {
        val json = JSONObject(
            LearnerExport.asJson(listOf(LearnerExport.Row(doc(1, "Untouched"), null)), now)
        )

        val entry = json.getJSONArray("documents").getJSONObject(0)
        assertFalse("nothing was learned, so nothing should be claimed", entry.has("mastery"))
    }

    @Test
    fun `an empty library still exports something a person can read`() {
        val text = LearnerExport.asText(emptyList(), now)
        val json = JSONObject(LearnerExport.asJson(emptyList(), now))

        assertTrue(text.contains("Nothing has been learned yet"))
        assertEquals(0, json.getJSONArray("documents").length())
    }

    @Test
    fun `a confidently wrong document is called out in the readable export`() {
        var m = Mastery.initial(1, now)
        repeat(5) { m = m.afterQuiz(1, 5, medianLatencyMs = 2_000, now = now) }

        val text = LearnerExport.asText(listOf(LearnerExport.Row(doc(1, "Ohm's Law"), m)), now)

        assertTrue(text.contains("likely a misunderstanding"))
    }
}
