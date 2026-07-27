package com.learnbridge.app.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking rules. Pure, so the whole of "what should I study next" is testable without a
 * database, a model or a device.
 */
class RevisionTest {

    private val now = 1_800_000_000_000L

    private fun doc(id: Long, title: String, ingestedAt: Long = now) =
        DocStore.DocumentRow(id, title, null, 100, ingestedAt, DocStore.STATUS_READY)

    /** A record at [level] mastery whose review fell due [overdueDays] ago. */
    private fun record(
        id: Long,
        level: Double,
        overdueDays: Int = 0,
        confidence: Double = 0.5,
        intervalDays: Int = 7,
    ) = Mastery(
        docId = id,
        mastery = level,
        confidence = confidence,
        exposureCount = 3,
        lastSeen = now,
        intervalDays = intervalDays,
        easeFactor = 2.5,
        dueAt = now - overdueDays * Mastery.DAY_MS,
    )

    @Test
    fun `a document that was never quizzed outranks one partly known`() {
        val docs = listOf(doc(1, "Untouched"), doc(2, "Half known"))
        val mastery = mapOf(2L to record(2, 0.5))

        val top = Revision.next(docs, mastery, now)!!

        assertEquals("Untouched", top.document.title)
    }

    @Test
    fun `the weaker of two documents comes first`() {
        val docs = listOf(doc(1, "Strong"), doc(2, "Weak"))
        val mastery = mapOf(1L to record(1, 0.7), 2L to record(2, 0.2))

        assertEquals("Weak", Revision.next(docs, mastery, now)!!.document.title)
    }

    @Test
    fun `being overdue breaks a tie between equally known documents`() {
        val docs = listOf(doc(1, "On time"), doc(2, "Late"))
        val mastery = mapOf(
            1L to record(1, 0.5, overdueDays = 0),
            2L to record(2, 0.5, overdueDays = 10),
        )

        assertEquals("Late", Revision.next(docs, mastery, now)!!.document.title)
    }

    /** The state the student cannot self-identify, so the ranking has to. */
    @Test
    fun `a confidently wrong document outranks an equally weak but hesitant one`() {
        val docs = listOf(doc(1, "Unsure"), doc(2, "Sure and wrong"))
        val mastery = mapOf(
            1L to record(1, 0.3, confidence = 0.1),
            2L to record(2, 0.3, confidence = 0.9),
        )

        assertEquals("Sure and wrong", Revision.next(docs, mastery, now)!!.document.title)
    }

    @Test
    fun `a mastered document that is not due is not offered`() {
        val docs = listOf(doc(1, "Known cold"))
        val mastery = mapOf(1L to record(1, 0.95, overdueDays = -5))

        assertTrue("nothing to revise", Revision.rank(docs, mastery, now).isEmpty())
        assertNull(Revision.next(docs, mastery, now))
    }

    @Test
    fun `a mastered document is offered again once it falls due`() {
        val docs = listOf(doc(1, "Known cold"))
        val mastery = mapOf(1L to record(1, 0.95, overdueDays = 1))

        assertEquals("Known cold", Revision.next(docs, mastery, now)!!.document.title)
    }

    /**
     * The overdue term is capped, so lateness alone cannot run away with the ranking. Both documents
     * here are exactly one half-life past due — same decay, different absolute lateness — which is
     * what isolates the cap from the forgetting it would otherwise be confounded with.
     */
    @Test
    fun `lateness stops adding urgency past the saturation point`() {
        val docs = listOf(doc(1, "A month late"), doc(2, "A year late"))
        val mastery = mapOf(
            1L to record(1, 0.5, overdueDays = 30, intervalDays = 30),
            2L to record(2, 0.5, overdueDays = 365, intervalDays = 365),
        )

        val ranked = Revision.rank(docs, mastery, now)
        assertEquals("both saturate to the same score", ranked[0].score, ranked[1].score, 0.0001)
    }

    /** Decay keeps running after the cap, so more lateness still means more forgotten. */
    @Test
    fun `past saturation the ranking is still driven by how much was forgotten`() {
        val docs = listOf(doc(1, "A month late"), doc(2, "A year late"))
        val mastery = mapOf(
            1L to record(1, 0.5, overdueDays = 30, intervalDays = 7),
            2L to record(2, 0.5, overdueDays = 365, intervalDays = 7),
        )

        assertEquals("A year late", Revision.next(docs, mastery, now)!!.document.title)
    }

    @Test
    fun `forgetting alone can make a once-strong document the priority`() {
        val docs = listOf(doc(1, "Learned then left"), doc(2, "Steady"))
        val mastery = mapOf(
            // Strong when tested, but two intervals overdue, so decayed to about a quarter.
            1L to record(1, 0.9, overdueDays = 14, intervalDays = 7),
            2L to record(2, 0.6, overdueDays = 0),
        )

        assertEquals("Learned then left", Revision.next(docs, mastery, now)!!.document.title)
    }

    @Test
    fun `an empty library recommends nothing`() {
        assertNull(Revision.next(emptyList(), emptyMap(), now))
    }

    @Test
    fun `ties fall back to the newest document`() {
        val docs = listOf(doc(1, "Older", ingestedAt = now - 1000), doc(2, "Newer", ingestedAt = now))

        // Neither has been quizzed, so both score the full mastery gap.
        assertEquals("Newer", Revision.next(docs, emptyMap(), now)!!.document.title)
    }
}
