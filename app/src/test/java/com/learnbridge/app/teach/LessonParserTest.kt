package com.learnbridge.app.teach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The producer is a 1B model that ignores its own output format regularly, so these cases are all
 * shapes of real misbehaviour: markdown emphasis, self-numbering, lettered options, prompt echo,
 * truncation mid-item. Every one must yield usable content or nothing — never an exception.
 */
class LessonParserTest {

    // --- key points ---

    @Test
    fun `parses clean bulleted key points`() {
        val raw = """
            - Plants make food from sunlight.
            - Chlorophyll absorbs the light.
            - Sugar stores the captured energy.
        """.trimIndent()

        val points = LessonParser.parseKeyPoints(raw)

        assertEquals(3, points.size)
        assertEquals("Plants make food from sunlight.", points[0])
    }

    @Test
    fun `strips markdown emphasis and self-numbering`() {
        val raw = """
            **1. Plants make food from sunlight.**
            2. *Chlorophyll absorbs the light.*
        """.trimIndent()

        val points = LessonParser.parseKeyPoints(raw)

        assertEquals(listOf("Plants make food from sunlight.", "Chlorophyll absorbs the light."), points)
    }

    @Test
    fun `falls back to plain lines when the model ignored bullets`() {
        val raw = "Plants make food from sunlight.\nChlorophyll absorbs the light."

        val points = LessonParser.parseKeyPoints(raw)

        assertEquals(2, points.size)
    }

    @Test
    fun `drops prompt echo and preamble`() {
        val raw = """
            Sure, here are the five most important facts:
            - Plants make food from sunlight.
        """.trimIndent()

        val points = LessonParser.parseKeyPoints(raw)

        assertEquals(listOf("Plants make food from sunlight."), points)
    }

    @Test
    fun `empty output yields no key points rather than throwing`() {
        assertTrue(LessonParser.parseKeyPoints("").isEmpty())
        assertTrue(LessonParser.parseKeyPoints("   \n  \n").isEmpty())
    }

    // --- quiz ---

    @Test
    fun `parses well-formed quiz items`() {
        val raw = """
            Q: What captures light energy?
            A: Chlorophyll
            X: Glucose
            X: Oxygen
            Q: What gas is released?
            A: Oxygen
            X: Nitrogen
            X: Hydrogen
        """.trimIndent()

        val items = LessonParser.parseQuiz(raw)

        assertEquals(2, items.size)
        assertEquals("Chlorophyll", items[0].correct)
        assertEquals(2, items[0].distractors.size)
    }

    @Test
    fun `accepts lettered distractors because models reach for them`() {
        val raw = """
            Q: What captures light energy?
            A: Chlorophyll
            B: Glucose
            C: Oxygen
        """.trimIndent()

        val items = LessonParser.parseQuiz(raw)

        assertEquals(1, items.size)
        assertEquals("Chlorophyll", items[0].correct)
        assertTrue(items[0].distractors.containsAll(listOf("Glucose", "Oxygen")))
    }

    @Test
    fun `salvages good items and drops the malformed one`() {
        val raw = """
            Q: What captures light energy?
            A: Chlorophyll
            X: Glucose
            Q: This question has no answer line
            X: Something
            Q: What gas is released?
            A: Oxygen
            X: Nitrogen
        """.trimIndent()

        val items = LessonParser.parseQuiz(raw)

        // Three questions asked, one unusable — two survive rather than the whole thing failing.
        assertEquals(2, items.size)
        assertEquals(listOf("What captures light energy?", "What gas is released?"), items.map { it.question })
    }

    @Test
    fun `a rejected item does not leak its options into the next one`() {
        // The first item's only distractor duplicates its answer, so it is unusable and dropped.
        val raw = """
            Q: Bad item
            A: Same
            X: Same
            Q: Good item
            A: Right
            X: Wrong
        """.trimIndent()

        val items = LessonParser.parseQuiz(raw)

        assertEquals(1, items.size)
        assertEquals("Good item", items[0].question)
        assertEquals(listOf("Wrong"), items[0].distractors)
    }

    @Test
    fun `truncated final item is dropped`() {
        val raw = """
            Q: What captures light energy?
            A: Chlorophyll
            X: Glucose
            Q: What gas is
        """.trimIndent()

        assertEquals(1, LessonParser.parseQuiz(raw).size)
    }

    @Test
    fun `garbage yields an empty quiz rather than throwing`() {
        assertTrue(LessonParser.parseQuiz("I am not able to help with that.").isEmpty())
        assertTrue(LessonParser.parseQuiz("").isEmpty())
    }

    @Test
    fun `options are shuffled off the first position but stay stable across calls`() {
        val item = QuizItem("What captures light energy?", "Chlorophyll", listOf("Glucose", "Oxygen"))

        val (first, correctIndex) = item.shuffledOptions()
        val (second, secondIndex) = item.shuffledOptions()

        assertEquals("same item must shuffle identically every render", first, second)
        assertEquals(correctIndex, secondIndex)
        assertEquals("Chlorophyll", first[correctIndex])
        assertEquals(3, first.size)
    }

    // --- answers ---

    @Test
    fun `not-in-text sentinel becomes a null answer`() {
        assertNull(LessonParser.parseAnswer(Prompts.NOT_IN_TEXT))
        assertNull(LessonParser.parseAnswer("I'm sorry, NOT_IN_TEXT — the passage doesn't say."))
    }

    @Test
    fun `a real answer survives with its line structure`() {
        val answer = LessonParser.parseAnswer("Chlorophyll captures the light.\nIt sits inside the leaf.")

        assertNotNull(answer)
        assertEquals(2, answer!!.lines().size)
    }

    @Test
    fun `blank output is a null answer, not an empty bubble`() {
        assertNull(LessonParser.parseAnswer("   \n \n "))
    }
}
