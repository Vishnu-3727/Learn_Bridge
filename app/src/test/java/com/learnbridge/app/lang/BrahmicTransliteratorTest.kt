package com.learnbridge.app.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expected strings here are not invented. The Devanagari inputs are **verbatim model output**
 * captured on device when the target language tag was set to Tamil, and the expected results are the
 * same sentences in their real script. That makes this a test against ground truth rather than
 * against my understanding of Unicode.
 */
class BrahmicTransliteratorTest {

    private val tamil = SupportedLanguage.TAMIL.scriptOffset

    @Test
    fun `real model output converts to real Tamil`() {
        // Captured from the device: "water on earth is never used up".
        val fromModel = "पूमियिल् उळ्ळ नीर् ऒरुपोतुम् पयऩ्पटुत्तप्पटुवतिल्लै"
        val expected = "பூமியில் உள்ள நீர் ஒருபோதும் பயன்படுத்தப்படுவதில்லை"

        assertEquals(expected, BrahmicTransliterator.transliterate(fromModel, tamil))
    }

    @Test
    fun `the Tamil-specific extended letters map correctly`() {
        // These four are exactly why IndicTrans2's unified representation round-trips: each has a
        // counterpart at the same offset in the southern blocks.
        assertEquals("ழ", BrahmicTransliterator.transliterate("ऴ", tamil))
        assertEquals("ற", BrahmicTransliterator.transliterate("ऱ", tamil))
        assertEquals("ன", BrahmicTransliterator.transliterate("ऩ", tamil))
        assertEquals("ள", BrahmicTransliterator.transliterate("ळ", tamil))
    }

    @Test
    fun `each script offset lands in its own Unicode block`() {
        // "ka" in Devanagari, converted into each southern and eastern script.
        val ka = "क"
        assertEquals("க", BrahmicTransliterator.transliterate(ka, SupportedLanguage.TAMIL.scriptOffset))
        assertEquals("క", BrahmicTransliterator.transliterate(ka, SupportedLanguage.TELUGU.scriptOffset))
        assertEquals("ಕ", BrahmicTransliterator.transliterate(ka, SupportedLanguage.KANNADA.scriptOffset))
        assertEquals("ക", BrahmicTransliterator.transliterate(ka, SupportedLanguage.MALAYALAM.scriptOffset))
        assertEquals("ক", BrahmicTransliterator.transliterate(ka, SupportedLanguage.BENGALI.scriptOffset))
        assertEquals("ક", BrahmicTransliterator.transliterate(ka, SupportedLanguage.GUJARATI.scriptOffset))
        assertEquals("ਕ", BrahmicTransliterator.transliterate(ka, SupportedLanguage.PUNJABI.scriptOffset))
        assertEquals("କ", BrahmicTransliterator.transliterate(ka, SupportedLanguage.ODIA.scriptOffset))
    }

    @Test
    fun `Devanagari-script languages are left untouched`() {
        val hindi = "पौधे अपना भोजन बनाते हैं।"

        assertEquals(hindi, BrahmicTransliterator.transliterate(hindi, SupportedLanguage.HINDI.scriptOffset))
        assertEquals(hindi, BrahmicTransliterator.transliterate(hindi, SupportedLanguage.MARATHI.scriptOffset))
    }

    @Test
    fun `Urdu is not a Brahmic script and is never offset`() {
        val urdu = "پانی کبھی استعمال نہیں ہوتا۔"

        assertEquals(urdu, BrahmicTransliterator.transliterate(urdu, SupportedLanguage.URDU.scriptOffset))
    }

    @Test
    fun `the danda is shared punctuation, not a letter to be offset`() {
        // 0x0964 sits inside the Devanagari block but every Brahmic script uses it as-is. Offsetting
        // it would land on an unrelated letter.
        assertEquals("க।", BrahmicTransliterator.transliterate("क।", tamil))
    }

    @Test
    fun `Latin, spaces and digits pass through unchanged`() {
        assertEquals(
            "H2O கு 42 %",
            BrahmicTransliterator.transliterate("H2O कु 42 %", tamil),
        )
    }

    /**
     * Tamil has no aspirated or voiced stops, so those Devanagari letters map onto unassigned Tamil
     * code points. A model translating into Tamil should not emit them, but if it does the result must
     * not be a row of replacement boxes.
     */
    @Test
    fun `code points with no counterpart are dropped rather than shown as tofu`() {
        val result = BrahmicTransliterator.transliterate("ख", tamil)

        assertTrue("expected the unmapped letter to be dropped, got '$result'", result.isEmpty())
        assertFalse(result.contains('�'))
    }

    @Test
    fun `empty and non-Indic input is returned unchanged`() {
        assertEquals("", BrahmicTransliterator.transliterate("", tamil))
        assertEquals("hello world", BrahmicTransliterator.transliterate("hello world", tamil))
    }
}
