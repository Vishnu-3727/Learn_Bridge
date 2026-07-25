package com.learnbridge.app.ui

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
        assertFalse(AskUi(output = AskOutput.Final("answer")).busy)
        assertFalse(AskUi(output = AskOutput.Failed("error")).busy)
    }
}
