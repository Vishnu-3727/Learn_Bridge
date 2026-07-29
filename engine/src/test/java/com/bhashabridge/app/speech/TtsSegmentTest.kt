package com.bhashabridge.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The splitter behind [Tts.speak].
 *
 * `TextToSpeech.speak` rejects input over 4,000 characters outright — it returns ERROR and speaks
 * nothing — so a whole document's lesson, which is every section's key points concatenated, would go
 * silent on the longest documents without this. The engine itself is not involved here; only the
 * pure function is, which is what makes it testable on the JVM.
 */
class TtsSegmentTest {

    @Test
    fun `short text is one segment and is not altered`() {
        val text = "Roots absorb water from the soil."
        assertEquals(listOf(text), Tts.segmentsFor(text))
    }

    @Test
    fun `long text is split into pieces the engine will accept`() {
        val sentence = "Water evaporates from the ocean and forms clouds in the sky. "
        val text = sentence.repeat(200) // ~12,000 characters

        val segments = Tts.segmentsFor(text)

        assertTrue("expected several segments, got ${segments.size}", segments.size > 1)
        assertTrue("a segment exceeds the engine limit", segments.all { it.length <= 3_900 })
        // Nothing is dropped: whitespace differs at the joins, the words do not.
        assertEquals(text.split(" ").filter { it.isNotBlank() }, segments.joinToString(" ").split(" ").filter { it.isNotBlank() })
    }

    @Test
    fun `splits land on sentence ends, not mid-word`() {
        val text = "Sentence number one is here. ".repeat(300)

        val segments = Tts.segmentsFor(text)

        assertTrue(
            "a segment does not end at a sentence: ${segments.map { it.takeLast(20) }}",
            segments.dropLast(1).all { it.endsWith(".") },
        )
    }

    /**
     * Devanagari and Urdu end sentences with their own characters, and a splitter that only knew
     * about '.' would cut a Hindi lesson mid-word at every boundary.
     */
    @Test
    fun `Indic sentence marks are treated as sentence ends`() {
        val text = "पौधे सूरज की रोशनी से अपना भोजन बनाते हैं। ".repeat(200)

        val segments = Tts.segmentsFor(text)

        assertTrue("expected several segments", segments.size > 1)
        assertTrue(
            "a Hindi segment does not end at a danda",
            segments.dropLast(1).all { it.endsWith("।") },
        )
    }

    @Test
    fun `text with no breaks at all is still cut rather than rejected`() {
        val text = "x".repeat(9_000)

        val segments = Tts.segmentsFor(text)

        assertTrue(segments.all { it.length <= 3_900 })
        assertEquals(9_000, segments.sumOf { it.length })
    }
}
