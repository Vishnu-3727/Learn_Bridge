package com.learnbridge.app.ui

import com.learnbridge.app.lang.SupportedLanguage
import com.learnbridge.app.teach.LessonParser
import com.learnbridge.app.teach.Prompts
import com.learnbridge.app.teach.QuizItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises only the pure logic behind [LessonViewModel] — [QuizUi]'s state transitions and
 * [resolveFallback] — with no Android framework, no database, and no mocking library. Neither needs
 * one: both are plain functions over plain data.
 */
class LessonViewModelTest {

    private val q1 = QuizItem("What captures light energy?", "Chlorophyll", listOf("Glucose", "Oxygen"))
    private val q2 = QuizItem("What gas is released?", "Oxygen", listOf("Nitrogen", "Hydrogen"))

    // --- quiz advance/score ---

    @Test
    fun `answering correctly scores and marks the question answered`() {
        val quiz = QuizUi(items = listOf(q1, q2))
        val (_, correctIndex) = q1.shuffledOptions()

        val answered = quiz.answer(correctIndex)

        assertTrue(answered.answered)
        assertEquals(correctIndex, answered.selectedIndex)
        assertEquals(1, answered.score)
    }

    @Test
    fun `answering incorrectly marks the question answered without scoring`() {
        val quiz = QuizUi(items = listOf(q1, q2))
        val (_, correctIndex) = q1.shuffledOptions()
        val wrongIndex = (0..2).first { it != correctIndex }

        val answered = quiz.answer(wrongIndex)

        assertTrue(answered.answered)
        assertEquals(0, answered.score)
    }

    @Test
    fun `a second tap on an already-answered question does not change the score`() {
        val quiz = QuizUi(items = listOf(q1, q2))
        val (_, correctIndex) = q1.shuffledOptions()
        val wrongIndex = (0..2).first { it != correctIndex }

        val onceCorrect = quiz.answer(correctIndex)
        val tappedAgain = onceCorrect.answer(wrongIndex)

        assertEquals("re-tapping must not touch the score", onceCorrect.score, tappedAgain.score)
        assertEquals("re-tapping must not change which option is recorded", onceCorrect.selectedIndex, tappedAgain.selectedIndex)
    }

    @Test
    fun `next is a no-op until the current question is answered`() {
        val quiz = QuizUi(items = listOf(q1, q2))

        val stillFirst = quiz.next()

        assertEquals(0, stillFirst.currentIndex)
        assertFalse(stillFirst.answered)
    }

    @Test
    fun `next advances the index and resets per-question state`() {
        val quiz = QuizUi(items = listOf(q1, q2))
        val (_, correctIndex) = q1.shuffledOptions()

        val advanced = quiz.answer(correctIndex).next()

        assertEquals(1, advanced.currentIndex)
        assertFalse(advanced.answered)
        assertEquals(null, advanced.selectedIndex)
        assertEquals("score earned on the previous question must carry forward", 1, advanced.score)
    }

    @Test
    fun `the quiz is done only once every question has been advanced past`() {
        val quiz = QuizUi(items = listOf(q1, q2))
        val (_, c1) = q1.shuffledOptions()
        val (_, c2) = q2.shuffledOptions()

        val afterFirst = quiz.answer(c1).next()
        assertFalse("one of two answered is not done", afterFirst.isDone)

        val afterSecond = afterFirst.answer(c2).next()
        assertTrue("both answered and advanced past must be done", afterSecond.isDone)
        assertEquals(2, afterSecond.score)
        assertEquals(2, afterSecond.total)
    }

    @Test
    fun `an empty quiz is never reported done`() {
        // isDone requires items to be non-empty, so the "no quiz rows" screen (quiz_unavailable)
        // and the "finished every question" screen (quiz_score) can never be confused for each other.
        assertFalse(QuizUi(items = emptyList()).isDone)
    }

    // --- language fallback ---

    @Test
    fun `resolveFallback keeps the requested language when it has rows`() {
        val result = resolveFallback("en", "hi", primary = listOf("a", "b"), fallback = listOf("x"))

        assertEquals(listOf("a", "b"), result.rows)
        assertEquals("en", result.lang)
        assertFalse(result.fellBack)
    }

    @Test
    fun `resolveFallback falls back to the other language when Hindi rows are absent`() {
        val result = resolveFallback("hi", "en", primary = emptyList(), fallback = listOf("english point"))

        assertEquals(listOf("english point"), result.rows)
        assertEquals("en", result.lang)
        assertTrue("must report that it fell back, so the UI can show the inline note", result.fellBack)
    }

    @Test
    fun `resolveFallback yields nothing when neither language has rows`() {
        val result = resolveFallback<String>("hi", "en", primary = emptyList(), fallback = emptyList())

        assertTrue(result.rows.isEmpty())
        assertFalse("nothing to show is not the same as having fallen back to a shown language", result.fellBack)
    }

    // --- ask: null parseAnswer -> no-answer state ---

    @Test
    fun `a null parseAnswer maps to the no-answer output state`() {
        val parsed = LessonParser.parseAnswer(Prompts.NOT_IN_TEXT)

        val output = AskOutput.Final(parsed)

        assertEquals(null, parsed)
        assertTrue(output.isNoAnswer())
    }

    @Test
    fun `a real parsed answer is not the no-answer state`() {
        val parsed = LessonParser.parseAnswer("Chlorophyll captures the light.")

        val output = AskOutput.Final(parsed)

        assertFalse(output.isNoAnswer())
    }

    @Test
    fun `askUi is busy only while a question is in flight`() {
        assertFalse(AskUi(output = AskOutput.Empty).busy)
        assertTrue(AskUi(output = AskOutput.InProgress).busy)
        assertTrue(AskUi(output = AskOutput.Streaming).busy)
        assertTrue("rendering into the lesson language is still in flight", AskUi(output = AskOutput.Rendering("ta")).busy)
        assertFalse(AskUi(output = AskOutput.Final("answer")).busy)
        assertFalse(AskUi(output = AskOutput.Failed("error")).busy)
    }

    // --- ask: which language the answer is rendered into ---

    @Test
    fun `an answer is rendered into the language the lesson is being read in`() {
        assertEquals(SupportedLanguage.TAMIL, answerTarget("ta"))
        assertEquals(SupportedLanguage.HINDI, answerTarget("hi"))
    }

    @Test
    fun `an English lesson leaves the generated answer alone`() {
        // The teacher already generates English, so translating would be a model swap for nothing.
        assertEquals(null, answerTarget("en"))
    }

    @Test
    fun `an unknown language code leaves the answer alone rather than guessing`() {
        assertEquals(null, answerTarget("zz"))
    }

    @Test
    fun `an answer defaults to English until something says otherwise`() {
        // What the Listen button reads to choose a voice: a rendering that never ran, or that failed
        // and fell back, must not claim the lesson's language.
        assertEquals("en", AskOutput.Final("answer").lang)
    }

    // --- what survives a language toggle mid-quiz ---

    /**
     * The defect this guards against: the shuffle is seeded by the question text, so the translated
     * question orders its options differently. Carrying `selectedIndex` across the swap drew the tick
     * and cross against the previous permutation — the ✓ moved and the ✗ could land on an option the
     * student never tapped.
     */
    @Test
    fun `a language toggle keeps score and position but clears the stale selection`() {
        val english = QuizUi(items = listOf(q1, q2))
        val (_, correctIndex) = q1.shuffledOptions()
        val answered = english.answer(correctIndex).next() // question 1 correct, now on question 2

        // What loadQuiz(preserveProgress = true) rebuilds when the translated rows arrive.
        val translated = QuizUi(
            items = listOf(hindi(q1), hindi(q2)),
            currentIndex = answered.currentIndex,
            score = answered.score,
        )

        assertEquals(1, translated.score)
        assertEquals(1, translated.currentIndex)
        assertFalse("the previous question's answered flag must not carry over", translated.answered)
        assertEquals(null, translated.selectedIndex)
    }

    @Test
    fun `translating a question really does change its option order`() {
        // Not a tautology: it is the premise the test above depends on. If shuffledOptions ever
        // became language-stable, preserving the selection would be safe and this would fail.
        val englishOrder = q1.shuffledOptions().first
        val hindiOrder = hindi(q1).shuffledOptions().first

        assertEquals(englishOrder.size, hindiOrder.size)
        assertTrue(
            "seeded by question text, so a translated question must reshuffle",
            englishOrder.map { it.length } != hindiOrder.map { it.length } ||
                englishOrder.indexOf(q1.correct) != hindiOrder.indexOf(hindi(q1).correct),
        )
    }

    // --- a quiz with no questions ---

    /**
     * A document the generator produced no questions for still reaches this class: the Quiz tab
     * renders "no questions could be generated" from the same empty [QuizUi]. Nothing in the UI can
     * be tapped in that state today, so this was a hole rather than a live crash — but [QuizUi.isDone]
     * is false for an empty quiz, so the guard in [QuizUi.answer] let an empty list fall through to
     * `items[0]`. One stray call was all it needed.
     */
    @Test
    fun `answering an empty quiz does nothing instead of crashing`() {
        val empty = QuizUi(items = emptyList())

        val answered = empty.answer(0)

        assertEquals(empty, answered)
        assertFalse(answered.answered)
        assertEquals(0, answered.score)
    }

    @Test
    fun `advancing an empty quiz does nothing`() {
        val empty = QuizUi(items = emptyList())

        assertEquals(empty, empty.next())
    }

    @Test
    fun `an empty quiz reports no median latency`() {
        assertEquals(0L, QuizUi(items = emptyList()).medianLatencyMs)
    }

    // --- answer latency, which is what the Twin reads confidence from ---

    @Test
    fun `answering records how long the question was on screen`() {
        val shown = 1_000_000L
        val quiz = QuizUi(items = listOf(q1, q2), shownAt = shown)

        val answered = quiz.answer(0, now = shown + 4_000)

        assertEquals(listOf(4_000L), answered.latenciesMs)
    }

    /** A quiz restored without a display timestamp must not report a latency measured from the epoch. */
    @Test
    fun `an unknown display time records no latency at all`() {
        val quiz = QuizUi(items = listOf(q1, q2), shownAt = 0L)

        val answered = quiz.answer(0, now = 1_800_000_000_000L)

        assertTrue("a 56-year latency would wreck the confidence estimate", answered.latenciesMs.isEmpty())
    }

    @Test
    fun `the median ignores one interrupted question`() {
        val quiz = QuizUi(items = listOf(q1, q2), latenciesMs = listOf(3_000, 4_000, 900_000))

        assertEquals(4_000L, quiz.medianLatencyMs)
    }

    /** Stands in for the same item rendered in another language: different strings, same structure. */
    private fun hindi(item: QuizItem) = QuizItem(
        question = "अनुवादित: ${item.question}",
        correct = "अनुवादित: ${item.correct}",
        distractors = item.distractors.map { "अनुवादित: $it" },
    )
}
