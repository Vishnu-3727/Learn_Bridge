package com.learnbridge.app.teach

import com.learnbridge.app.doc.Chunk
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

    // --- duplicate options ---

    /**
     * The pair that motivated the filter, from `Unit - 3 SV CONSTRAINTS AND ARRAYS` on the SM-M315F.
     * Both options state the same packed/unpacked rule; the model swapped "is a type of array where
     * dimensions are declared" for "is used to refer to dimensions declared" and "group of bits" for
     * "set of bits". The student picked one, was told "Not quite", and had no way to have known.
     */
    @Test
    fun `a distractor that only rewords the answer is dropped, and with it the item`() {
        val raw = """
            Q: What is the difference between packed and unpacked arrays in SystemVerilog?
            A: A packed array is a type of array where dimensions are declared before the variable name, and is represented as a contiguous group of bits.
            X: A packed array is used to refer to dimensions declared before the variable name, and is represented as a contiguous set of bits.
        """.trimIndent()

        // The only distractor restates the answer, so nothing usable is left and the item goes.
        assertTrue(LessonParser.parseQuiz(raw).isEmpty())
    }

    @Test
    fun `a true-but-not-the-answer distractor survives`() {
        val raw = """
            Q: What's the primary purpose of photosynthesis?
            A: Photosynthesis converts light energy into chemical energy.
            X: Photosynthesis requires chlorophyll and light energy.
        """.trimIndent()

        val items = LessonParser.parseQuiz(raw)

        // Shares three words with the answer and is a legitimate distractor. Filtering this would
        // discard the good questions along with the broken ones.
        assertEquals(1, items.size)
        assertEquals(listOf("Photosynthesis requires chlorophyll and light energy."), items[0].distractors)
    }

    /** The reworded option scores 0.71 — a near miss rather than a reordered copy, which would be 1.0. */
    @Test
    fun `the item survives when a reworded distractor sits beside a real one`() {
        val raw = """
            Q: What captures light energy?
            A: Chlorophyll absorbs the light inside the leaf.
            X: Inside the leaf, chlorophyll absorbs the sunlight.
            X: Glucose stores the energy the plant has made.
        """.trimIndent()

        val items = LessonParser.parseQuiz(raw)

        assertEquals(1, items.size)
        assertEquals(listOf("Glucose stores the energy the plant has made."), items[0].distractors)
    }

    @Test
    fun `two distractors that reword each other collapse to the first`() {
        val raw = """
            Q: What gas is released?
            A: Oxygen
            X: Nitrogen gas leaves the leaf through the stomata.
            X: Through the stomata, nitrogen gas leaves the leaf.
            X: Hydrogen
        """.trimIndent()

        val items = LessonParser.parseQuiz(raw)

        assertEquals(1, items.size)
        assertEquals(
            listOf("Nitrogen gas leaves the leaf through the stomata.", "Hydrogen"),
            items[0].distractors,
        )
    }

    @Test
    fun `case and punctuation do not make an option distinct`() {
        val raw = """
            Q: What captures light energy?
            A: Chlorophyll
            X: chlorophyll.
        """.trimIndent()

        assertTrue(LessonParser.parseQuiz(raw).isEmpty())
    }

    /**
     * Verbatim from the SM-M315F on 2026-07-30, `unit_3_SV.pdf`. The prompt says "Never copy a
     * bracket" and the model did not copy one — it copied the words inside it. Only one real option
     * was left, so the question could not be answered.
     */
    @Test
    fun `a copied template slot is not an option`() {
        val raw = """
            Q: What type of data type can be used as an argument for an inside operator?
            A: A variable.
            X: A different wrong option
        """.trimIndent()

        assertTrue(LessonParser.parseQuiz(raw).isEmpty())
    }

    @Test
    fun `a copied slot costs only its own option when a real one remains`() {
        val raw = """
            Q: What captures light energy?
            A: Chlorophyll
            X: A wrong option
            X: Glucose
        """.trimIndent()

        val items = LessonParser.parseQuiz(raw)

        assertEquals(1, items.size)
        assertEquals(listOf("Glucose"), items[0].distractors)
    }

    @Test
    fun `a copied slot in the question drops the whole item`() {
        val raw = """
            Q: The question
            A: Chlorophyll
            X: Glucose
            Q: What gas is released?
            A: Oxygen
            X: Nitrogen
        """.trimIndent()

        val items = LessonParser.parseQuiz(raw)

        assertEquals(1, items.size)
        assertEquals("What gas is released?", items[0].question)
    }

    @Test
    fun `wording that merely shares words with a slot survives`() {
        val raw = """
            Q: What does a constraint narrow down?
            A: The values a variable can be randomized to.
            X: An option that is wrong for every question asked.
        """.trimIndent()

        // Shares "option", "wrong" and "question" with the slots without being one of them.
        assertEquals(1, LessonParser.parseQuiz(raw).size)
    }

    /**
     * The slots are interpolated into the prompt from the same constants the filter reads, so this
     * pins the rendered wording. The counts recorded on [Prompts.quiz] were measured against this
     * exact text; changing it silently would invalidate them and nothing else would notice.
     */
    @Test
    fun `the quiz prompt still renders the slots the filter recognises`() {
        val prompt = Prompts.quiz(listOf(Chunk(0, "Any text.")))

        assertTrue(prompt.contains("Q: (the question)"))
        assertTrue(prompt.contains("A: (the correct answer)"))
        assertTrue(prompt.contains("X: (a wrong option)"))
        assertTrue(prompt.contains("X: (a different wrong option)"))
    }

    @Test
    fun `similarity scores the measured pairs either side of the threshold`() {
        val duplicate = LessonParser.similarity(
            "A packed array is a type of array where dimensions are declared before the variable name, and is represented as a contiguous group of bits.",
            "A packed array is used to refer to dimensions declared before the variable name, and is represented as a contiguous set of bits.",
        )
        val distinct = LessonParser.similarity(
            "Photosynthesis converts light energy into chemical energy.",
            "Photosynthesis requires chlorophyll and light energy.",
        )

        assertTrue("duplicate scored $duplicate", duplicate >= LessonParser.SAME_OPTION)
        assertTrue("distinct scored $distinct", distinct < LessonParser.SAME_OPTION)
        assertEquals(1.0, LessonParser.similarity("Oxygen", "oxygen!"), 0.0001)
        assertEquals(0.0, LessonParser.similarity("Oxygen", "Nitrogen"), 0.0001)
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
