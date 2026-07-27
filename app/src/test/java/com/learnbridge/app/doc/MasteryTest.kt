package com.learnbridge.app.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Learning Twin's update rules. Pure arithmetic on an immutable value, so none of this needs a
 * database, a model or a device — which is the reason [Mastery] holds the logic rather than the
 * ViewModel that calls it.
 */
class MasteryTest {

    private val now = 1_800_000_000_000L
    private fun fresh() = Mastery.initial(docId = 1, now = now)

    @Test
    fun `a new document is unknown and due immediately`() {
        val m = fresh()
        assertEquals(0.0, m.mastery, 0.0)
        assertEquals(0, m.exposureCount)
        assertTrue("a document never studied is due now", m.isDue(now))
    }

    @Test
    fun `a perfect quiz raises mastery without claiming it outright`() {
        val m = fresh().afterQuiz(correct = 5, total = 5, medianLatencyMs = 3_000, now = now)

        assertTrue("mastery must rise", m.mastery > 0.0)
        // The point of a moving average: one good run is evidence, not proof.
        assertTrue("one quiz must not claim full mastery, was ${m.mastery}", m.mastery < 1.0)
        assertEquals(1, m.exposureCount)
    }

    @Test
    fun `repeated perfect quizzes converge towards mastery`() {
        var m = fresh()
        repeat(10) { m = m.afterQuiz(5, 5, 3_000, now) }

        assertTrue("ten perfect runs should approach mastery, was ${m.mastery}", m.mastery > 0.99)
    }

    /** The behaviour a streak counter cannot express: evidence accumulates and can also be lost. */
    @Test
    fun `a failed quiz pulls mastery back down`() {
        var m = fresh()
        repeat(5) { m = m.afterQuiz(5, 5, 3_000, now) }
        val peak = m.mastery

        m = m.afterQuiz(correct = 0, total = 5, medianLatencyMs = 3_000, now = now)

        assertTrue("mastery must fall after a failure", m.mastery < peak)
    }

    @Test
    fun `a failed quiz resets the review interval to one day`() {
        var m = fresh()
        repeat(5) { m = m.afterQuiz(5, 5, 3_000, now) }
        assertTrue("intervals should have grown, was ${m.intervalDays}", m.intervalDays > 1)

        m = m.afterQuiz(0, 5, 3_000, now)

        assertEquals("a lost document is due again tomorrow", 1, m.intervalDays)
    }

    @Test
    fun `successful reviews push the next one further out`() {
        var m = fresh().afterQuiz(5, 5, 3_000, now)
        val first = m.intervalDays
        m = m.afterQuiz(5, 5, 3_000, now)

        assertTrue("interval must grow, $first then ${m.intervalDays}", m.intervalDays > first)
        assertEquals(now + m.intervalDays * Mastery.DAY_MS, m.dueAt)
    }

    @Test
    fun `the ease factor never falls below the floor`() {
        var m = fresh()
        repeat(20) { m = m.afterQuiz(0, 5, 3_000, now) }

        assertTrue("ease must stay above 1.3, was ${m.easeFactor}", m.easeFactor >= 1.3)
    }

    // --- confidence is measured separately from mastery, and that separation is the point ---

    @Test
    fun `fast answers read as confident and slow answers do not`() {
        val quick = fresh().afterQuiz(5, 5, medianLatencyMs = 2_000, now = now)
        val slow = fresh().afterQuiz(5, 5, medianLatencyMs = 30_000, now = now)

        assertTrue("fast recall is confident", quick.confidence > slow.confidence)
        assertEquals("a 30s answer is not confident", 0.0, slow.confidence, 0.001)
    }

    @Test
    fun `correct but slow is high mastery and low confidence`() {
        var m = fresh()
        repeat(5) { m = m.afterQuiz(5, 5, medianLatencyMs = 30_000, now = now) }

        assertTrue("answers were right, so mastery is high", m.mastery > 0.9)
        assertTrue("but they were laboured, so confidence is low", m.confidence < 0.1)
    }

    @Test
    fun `fast and wrong is flagged as confidently wrong`() {
        var m = fresh()
        repeat(5) { m = m.afterQuiz(correct = 1, total = 5, medianLatencyMs = 2_000, now = now) }

        assertTrue("scoring 1 of 5 is not mastery", m.mastery < 0.5)
        assertTrue("answering in 2s is confident", m.confidence > 0.6)
        assertTrue("which is the state worth interrupting for", m.isConfidentlyWrong)
    }

    @Test
    fun `a slow wrong answer is not confidently wrong`() {
        var m = fresh()
        repeat(5) { m = m.afterQuiz(1, 5, medianLatencyMs = 30_000, now = now) }

        assertFalse("hesitant and wrong is a different problem", m.isConfidentlyWrong)
    }

    // --- forgetting ---

    @Test
    fun `mastery does not decay before the review falls due`() {
        val m = fresh().afterQuiz(5, 5, 3_000, now)

        assertEquals(m.mastery, m.decayed(m.dueAt - 1), 0.0001)
    }

    @Test
    fun `mastery halves one interval past the due date`() {
        val m = fresh().afterQuiz(5, 5, 3_000, now)
        val oneIntervalLate = m.dueAt + m.intervalDays * Mastery.DAY_MS

        assertEquals(m.mastery / 2, m.decayed(oneIntervalLate), 0.0001)
    }

    @Test
    fun `an empty quiz changes nothing`() {
        val m = fresh()
        assertEquals(m, m.afterQuiz(correct = 0, total = 0, medianLatencyMs = 1_000, now = now))
    }
}
