package com.learnbridge.app.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The splitter is the only place a bug here is invisible until it reaches the screen: an over-long
 * fragment does not fail, it comes back silently truncated by the translation engine's decode cap.
 * So the load-bearing assertion in most of these cases is simply "no fragment exceeds the limit".
 */
class LessonTranslatorTest {

    private fun split(text: String, language: SupportedLanguage = SupportedLanguage.HINDI) =
        LessonTranslator.splitForTranslation(text, language)

    private fun assertAllWithinLimit(text: String) {
        val over = split(text).filter { LessonTranslator.wordCount(it.text) > LessonTranslator.MAX_WORDS }
        assertTrue("fragments over the ${LessonTranslator.MAX_WORDS}-word limit: ${over.map { it.text }}", over.isEmpty())
    }

    @Test
    fun `a short sentence stays whole`() {
        val fragments = split("Plants make food from sunlight.")

        assertEquals(1, fragments.size)
        assertEquals("Plants make food from sunlight", fragments[0].text)
    }

    @Test
    fun `full stop becomes a danda`() {
        val fragments = split("Plants make food.")

        assertEquals("।", fragments[0].trailing.trim())
    }

    @Test
    fun `question and exclamation marks are preserved as-is`() {
        assertEquals("?", split("What is photosynthesis?")[0].trailing.trim())
        assertEquals("!", split("Look at that!")[0].trailing.trim())
    }

    @Test
    fun `each sentence becomes its own fragment`() {
        val fragments = split("Plants make food. Chlorophyll absorbs light. Oxygen is released.")

        assertEquals(3, fragments.size)
        assertEquals("Chlorophyll absorbs light", fragments[1].text)
    }

    @Test
    fun `a sentence genuinely over the limit splits at commas`() {
        // 23 words, so it cannot fit in one call even at the raised decode cap.
        val text = "Chlorophyll absorbs red and blue light inside the chloroplast, and the plant then " +
            "converts that captured light energy into glucose which stores it."

        assertAllWithinLimit(text)
        assertTrue("expected more than one fragment", split(text).size > 1)
    }

    @Test
    fun `a long comma-free sentence splits at a conjunction`() {
        val text = "The leaves capture energy from sunlight because chlorophyll inside them absorbs particular wavelengths"

        assertAllWithinLimit(text)
    }

    @Test
    fun `an unpunctuated run is hard-split rather than left over the limit`() {
        val text = (1..40).joinToString(" ") { "word$it" }

        assertAllWithinLimit(text)
        // ceil(40 / MAX_WORDS) fragments, whatever the limit is currently set to.
        assertEquals((40 + LessonTranslator.MAX_WORDS - 1) / LessonTranslator.MAX_WORDS, split(text).size)
    }

    /**
     * Regression: a sentence at or under the limit must arrive at the engine whole. Splitting it at a
     * comma produced grammatically broken Hindi on device — the translation model needs the full
     * clause to resolve grammar, so a single good translation beats two bad fragments.
     */
    @Test
    fun `a comma-containing sentence within the limit is not split`() {
        val text = "A plant makes its own food inside its leaves, using nothing but sunlight and water."

        assertEquals("should translate as one fragment", 1, split(text).size)
    }

    @Test
    fun `rejoining fragments reproduces the sentence structure`() {
        val text = "Plants make food. Oxygen is released."
        val rebuilt = split(text).joinToString("") { it.text + it.trailing }.trim()

        // Punctuation is translated to Hindi convention; word content and order are unchanged.
        assertEquals("Plants make food। Oxygen is released।", rebuilt)
    }

    @Test
    fun `internal splits rejoin with a space and only the last piece carries the punctuation`() {
        val text = "Chlorophyll absorbs red and blue light inside the chloroplast, and the plant then " +
            "converts that captured light energy into glucose which stores it."
        val fragments = split(text)

        assertTrue("this case is only meaningful when the sentence actually splits", fragments.size > 1)
        assertEquals("।", fragments.last().trailing.trim())
        assertTrue(
            "no interior fragment should carry sentence punctuation",
            fragments.dropLast(1).none { it.trailing.contains("।") },
        )
    }

    @Test
    fun `blank and whitespace input yield no fragments`() {
        assertTrue(split("").isEmpty())
        assertTrue(split("    \n  ").isEmpty())
    }

    @Test
    fun `punctuation-only input yields no fragments`() {
        assertTrue(split("... !!").isEmpty())
    }

    @Test
    fun `a sentence without terminating punctuation is still emitted`() {
        val fragments = split("Plants make food from sunlight")

        assertEquals(1, fragments.size)
        assertEquals("", fragments[0].trailing)
    }

    @Test
    fun `a realistic quiz stem stays within the limit`() {
        val text = "Which of the following best describes the process by which plants convert light energy into chemical energy?"

        assertAllWithinLimit(text)
    }

    /**
     * Regression, seen on device: every rendered Hindi line ended "…होती है ।।" because IndicTrans2
     * supplies its own sentence-final danda and the renderer appended a second one.
     */
    @Test
    fun `a translation that already ends in a danda does not get a second one`() {
        assertEquals(
            "पौधे भोजन बनाते हैं।",
            LessonTranslator.joinTranslated("पौधे भोजन बनाते हैं।", "।"),
        )
    }

    @Test
    fun `punctuation is still added when the translation lacks it`() {
        assertEquals(
            "पौधे भोजन बनाते हैं।",
            LessonTranslator.joinTranslated("पौधे भोजन बनाते हैं", "।"),
        )
    }

    @Test
    fun `spacing after a terminator survives deduplication`() {
        assertEquals(
            "पहला वाक्य। ",
            LessonTranslator.joinTranslated("पहला वाक्य।", "। "),
        )
    }

    @Test
    fun `an interior fragment keeps its plain space separator`() {
        assertEquals("पहला भाग ", LessonTranslator.joinTranslated("पहला भाग", " "))
    }

    // --- per-language punctuation ---

    /**
     * The sentence terminator is not universal. Devanagari uses the danda, Urdu uses the Arabic full
     * stop. Emitting a danda in Urdu output reads as the wrong script's punctuation bolted onto the
     * right words — and it was a real defect: "…ہوتا ہے۔।" appeared on device.
     */
    @Test
    fun `each language gets its own sentence terminator`() {
        assertEquals("।", split("Plants make food.", SupportedLanguage.HINDI)[0].trailing.trim())
        assertEquals("।", split("Plants make food.", SupportedLanguage.MARATHI)[0].trailing.trim())
        assertEquals("۔", split("Plants make food.", SupportedLanguage.URDU)[0].trailing.trim())
        assertEquals(".", split("Plants make food.", SupportedLanguage.ENGLISH)[0].trailing.trim())
    }

    @Test
    fun `an Urdu translation ending in its own full stop does not gain a danda`() {
        assertEquals(
            "پانی کبھی استعمال نہیں ہوتا۔",
            LessonTranslator.joinTranslated("پانی کبھی استعمال نہیں ہوتا۔", "۔"),
        )
    }

    @Test
    fun `a danda is not appended to text already ending in an Arabic full stop`() {
        // The dedup must consider every supported language's terminator, not just Devanagari's.
        assertEquals(
            "یہ ایک جملہ ہے۔",
            LessonTranslator.joinTranslated("یہ ایک جملہ ہے۔", "।"),
        )
    }

    @Test
    fun `sentence splitting recognises an Arabic full stop as a boundary`() {
        val fragments = split("پہلا جملہ۔ دوسرا جملہ۔", SupportedLanguage.URDU)

        assertEquals(2, fragments.size)
    }

    @Test
    fun `word count ignores extra whitespace`() {
        assertEquals(3, LessonTranslator.wordCount("  one   two \n three  "))
        assertEquals(0, LessonTranslator.wordCount("   "))
    }
}
