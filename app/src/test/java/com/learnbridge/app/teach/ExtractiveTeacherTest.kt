package com.learnbridge.app.teach

import com.learnbridge.app.doc.Chunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extractive tutor is what ships on a device that cannot hold a language model, so it has to be
 * correct on its own terms rather than treated as a placeholder.
 *
 * The property that matters most is the one a generative teacher cannot offer: **every sentence it
 * produces must come from the source document.** Several assertions below check exactly that, because
 * a paraphrase appearing here would mean a bug that invented content.
 */
class ExtractiveTeacherTest {

    /** Zero pacing delay: the emit rhythm is presentation, not logic under test. */
    private val teacher = ExtractiveTeacher(chunkDelayMs = 0)

    /** Real prose, because centrality scoring on three toy sentences proves nothing. */
    private val document = """
        Photosynthesis is the process by which plants make their own food using sunlight.
        Animals get energy by eating other living things, but plants do not need to eat.
        Photosynthesis happens mainly inside the leaves of the plant.
        Each leaf contains tiny structures called chloroplasts, which hold a green pigment.
        That green pigment is called chlorophyll, and it captures energy from sunlight.
        The plant takes carbon dioxide from the air through small openings called stomata.
        Water is absorbed by the roots and carried upward through the stem.
        Using light energy, the plant combines carbon dioxide and water to form glucose.
        Glucose is a sugar that stores energy for later growth.
        Oxygen is produced as well, and the plant releases it into the air.
        Almost all the oxygen we breathe was released by plants during photosynthesis.
        Photosynthesis is therefore the starting point of nearly every food chain on Earth.
    """.trimIndent()

    private val chunks = listOf(Chunk(0, document))

    private suspend fun collect(request: TeachRequest): String =
        teacher.stream(request).toList().joinToString("")

    private fun sentencesOf(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    // --- Explain ---

    @Test
    fun `explain returns bulleted key points that the parser can read`() = runBlocking {
        val points = LessonParser.parseKeyPoints(collect(TeachRequest.Explain(chunks)))

        assertTrue("expected several key points, got ${points.size}", points.size >= 3)
        assertTrue("key points should not be empty strings", points.all { it.isNotBlank() })
    }

    @Test
    fun `every key point comes from the source document`() = runBlocking {
        val points = LessonParser.parseKeyPoints(collect(TeachRequest.Explain(chunks)))

        for (point in points) {
            // Long sentences are shortened with a trailing ellipsis, so compare on a stable prefix.
            val stem = point.removeSuffix("…").trim().take(30)
            assertTrue("invented content not in the document: \"$point\"", document.contains(stem))
        }
    }

    @Test
    fun `key points stay in document order`() = runBlocking {
        val points = LessonParser.parseKeyPoints(collect(TeachRequest.Explain(chunks)))
        val positions = points.map { document.indexOf(it.removeSuffix("…").trim().take(30)) }

        assertEquals("key points should read in document order", positions.sorted(), positions)
    }

    /**
     * Regression, found on device: a title and a section heading were selected as two of five key
     * points. Headings are short, capitalised and grammatical, so they score well on centrality and
     * read as confident facts — which makes this a quality bug that looks like working output.
     */
    @Test
    fun `titles and section headings are never offered as key points`() = runBlocking {
        val withHeadings = """
            Photosynthesis: How Plants Make Their Own Food

            Every living thing needs energy to stay alive, and animals get it by eating.
            A plant instead makes its own food inside its leaves using sunlight and water.

            What the plant needs

            It needs carbon dioxide, which it takes from the air through openings called stomata.
            Water is absorbed by the roots and carried upward through the stem of the plant.

            Why it matters to everyone

            Photosynthesis is the starting point of nearly every food chain on the Earth.
            Almost all the oxygen that we breathe was released by plants and by ocean algae.
        """.trimIndent()

        val points = LessonParser.parseKeyPoints(
            collect(TeachRequest.Explain(listOf(Chunk(0, withHeadings)))),
        )

        assertTrue("expected some key points", points.isNotEmpty())
        val headings = listOf(
            "Photosynthesis: How Plants Make Their Own Food",
            "What the plant needs",
            "Why it matters to everyone",
        )
        for (point in points) {
            assertFalse(
                "heading offered as a key point: \"$point\"",
                headings.any { point.trim().removeSuffix("…").trim().equals(it, ignoreCase = true) },
            )
            assertTrue(
                "a key point must be a full sentence, got: \"$point\"",
                point.trimEnd().endsWith(".") || point.trimEnd().endsWith("…"),
            )
        }
    }

    /**
     * Regression, found by photographing a page in airplane mode: ML Kit returns one string per
     * *visual* line, so a wrapped sentence arrives as "leaves, using nothing but sunlight, water" /
     * "and air." — neither piece is a sentence. Judging headings line by line discarded nearly all of
     * it and produced a lesson with zero key points from a successful OCR read.
     */
    @Test
    fun `wrapped OCR lines are reflowed instead of discarded`() = runBlocking {
        val ocrStyle = """
            Photosynthesis
            A plant makes its own food inside
            its leaves, using nothing but
            sunlight, water and air.
            It needs carbon dioxide, which it
            takes from the air through small
            openings called stomata.
            Almost all the oxygen we breathe
            was released by plants during
            photosynthesis.
        """.trimIndent()

        val points = LessonParser.parseKeyPoints(
            collect(TeachRequest.Explain(listOf(Chunk(0, ocrStyle)))),
        )

        assertTrue("OCR-style wrapped input produced no key points at all", points.isNotEmpty())
        assertTrue(
            "key points should be reflowed sentences, not wrapped fragments: $points",
            points.all { it.trimEnd().endsWith(".") },
        )
    }

    @Test
    fun `an empty document produces no key points rather than throwing`() = runBlocking {
        assertTrue(LessonParser.parseKeyPoints(collect(TeachRequest.Explain(emptyList()))).isEmpty())
        assertTrue(
            LessonParser.parseKeyPoints(collect(TeachRequest.Explain(listOf(Chunk(0, "   "))))).isEmpty(),
        )
    }

    // --- Ask ---

    @Test
    fun `a question answerable from the text gets the relevant sentence`() = runBlocking {
        val answer = LessonParser.parseAnswer(collect(TeachRequest.Ask("What is chlorophyll?", chunks)))

        assertNotNull("expected an answer", answer)
        assertTrue(
            "answer should mention chlorophyll, got: $answer",
            answer!!.contains("chlorophyll", ignoreCase = true),
        )
    }

    @Test
    fun `a question the document does not cover returns the not-in-text sentinel`() = runBlocking {
        val raw = collect(TeachRequest.Ask("Who won the 1998 football world cup?", chunks))

        assertTrue("expected the sentinel, got: $raw", raw.contains(Prompts.NOT_IN_TEXT))
        assertNull("the UI must see a null answer", LessonParser.parseAnswer(raw))
    }

    @Test
    fun `answers are drawn verbatim from the document`() = runBlocking {
        val answer = LessonParser.parseAnswer(collect(TeachRequest.Ask("How is glucose formed?", chunks)))

        assertNotNull(answer)
        for (line in answer!!.lines().filter { it.isNotBlank() }) {
            val stem = line.removeSuffix("…").trim().take(30)
            assertTrue("answer line not found in document: \"$line\"", document.contains(stem))
        }
    }

    // --- Quiz ---

    @Test
    fun `quiz output parses into usable multiple-choice items`() = runBlocking {
        val items = LessonParser.parseQuiz(collect(TeachRequest.Quiz(chunks)))

        assertTrue("expected at least two quiz items, got ${items.size}", items.size >= 2)
        for (item in items) {
            assertTrue("stem must contain a blank: ${item.question}", item.question.contains("______"))
            assertTrue("needs distractors", item.distractors.isNotEmpty())
            assertFalse("the answer must not also be a distractor", item.distractors.contains(item.correct))
        }
    }

    @Test
    fun `the blanked answer does not also appear in its own stem`() = runBlocking {
        val items = LessonParser.parseQuiz(collect(TeachRequest.Quiz(chunks)))

        for (item in items) {
            assertFalse(
                "the answer \"${item.correct}\" is still visible in its own question",
                item.question.contains(item.correct, ignoreCase = true),
            )
        }
    }

    @Test
    fun `distractors are drawn from the document, not invented`() = runBlocking {
        val items = LessonParser.parseQuiz(collect(TeachRequest.Quiz(chunks)))

        for (item in items) {
            for (distractor in item.distractors) {
                assertTrue(
                    "distractor \"$distractor\" is not a word from the document",
                    document.contains(distractor, ignoreCase = true),
                )
            }
        }
    }

    /**
     * Regression, found on device: the first version produced "Every living ______ needs energy to
     * stay alive." with the answer "thing" and distractors "release" and "tiger" — all three appear
     * exactly once, so all three won on rarity while testing nothing.
     *
     * The invariant is that an answer must be part of the document's subject matter, which is true if
     * the document either **defines** it or **keeps returning to it**. Definition is the stronger of
     * the two signals: "tiny structures called chloroplasts" names a concept worth testing even though
     * the word appears only once, and it beats a term repeated twice in passing.
     */
    @Test
    fun `quiz answers are subject-matter terms, either defined or recurring`() = runBlocking {
        val items = LessonParser.parseQuiz(collect(TeachRequest.Quiz(chunks)))
        assertTrue("expected quiz items", items.isNotEmpty())

        fun occurrences(term: String) =
            Regex("\\b" + Regex.escape(term) + "\\b", RegexOption.IGNORE_CASE).findAll(document).count()

        val defined = ExtractiveTeacher.DEFINITION_CUE.findAll(document)
            .map { it.groupValues[2].lowercase() }
            .toSet()

        for (item in items) {
            val term = item.correct.lowercase()
            assertTrue(
                "answer \"$term\" is neither defined in the document nor recurring " +
                    "(${occurrences(term)}x); defined terms were $defined",
                term in defined || occurrences(term) >= 2,
            )
        }
    }

    /**
     * Regression: function words and common verbs are both frequent and recurring, so neither the
     * length filter nor the recurrence filter excludes them. On device this produced the answer
     * "every", with "together" and "releases" as distractors.
     */
    @Test
    fun `function words and common verbs are never quiz answers or distractors`() = runBlocking {
        val rejected = setOf(
            "thing", "things", "kind", "part", "number", "example",
            "every", "together", "release", "releases", "released", "through", "another", "because",
            "called", "makes", "needs", "using",
        )
        val items = LessonParser.parseQuiz(collect(TeachRequest.Quiz(chunks)))
        assertTrue("expected quiz items", items.isNotEmpty())

        for (item in items) {
            assertFalse(
                "function word used as the answer: ${item.correct}",
                item.correct.lowercase() in rejected,
            )
            for (distractor in item.distractors) {
                assertFalse(
                    "function word used as a distractor: $distractor",
                    distractor.lowercase() in rejected,
                )
            }
        }
    }

    /**
     * Textbook prose announces its own vocabulary — "structures called chloroplasts", "a pigment
     * known as chlorophyll", "openings called stomata". Those terms should be reached for first,
     * because a word the author bothered to name is a word worth testing.
     */
    @Test
    fun `explicitly defined terms are preferred as quiz answers`() = runBlocking {
        val items = LessonParser.parseQuiz(TeachRequest.Quiz(chunks).let { collect(it) })
        val answers = items.map { it.correct.lowercase() }

        val defined = setOf("chloroplasts", "chlorophyll", "stomata")
        assertTrue(
            "expected at least one explicitly defined term among the answers $answers",
            answers.any { it in defined },
        )
    }

    @Test
    fun `a document too short for a quiz yields nothing rather than a broken item`() = runBlocking {
        val items = LessonParser.parseQuiz(collect(TeachRequest.Quiz(listOf(Chunk(0, "Short text here.")))))

        assertTrue("expected no items from a one-sentence document", items.isEmpty())
    }

}
