package com.learnbridge.app.lang

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bhashabridge.app.Direction
import com.bhashabridge.app.mt.DecodeConfig
import com.bhashabridge.app.mt.GreedyDecoder
import com.bhashabridge.app.mt.MtEngine
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Purpose:  Proves on real hardware that every [SupportedLanguage] renders in its own script from the
 *           one loaded translation engine.
 * Thread:   Instrumentation thread. [MtEngine.translate] is synchronous and this is not the main thread.
 *
 * Why a device test and not a JVM one: [BrahmicTransliteratorTest] already covers the character
 * mapping, but it feeds the mapping hand-written Devanagari. The claim this file exists to defend is
 * stronger and cannot be made off-device — that the *model's actual output*, for a real English
 * sentence, lands in the right Unicode block for all thirteen targets. That needs the ONNX graphs,
 * which only exist inside the APK.
 *
 * The engine is loaded ONCE for the whole class, because a cold load is ~14 s and switching target
 * language is free — which is itself the architectural claim under test.
 */
@RunWith(AndroidJUnit4::class)
class AllLanguagesDeviceTest {

    @Test
    fun everyLanguageRendersInItsOwnScript() {
        val failures = mutableListOf<String>()

        for (language in SupportedLanguage.targets) {
            val targetId = engine.languageId(language.tag)
            if (targetId == null) {
                failures += "${language.code}: tag ${language.tag} absent from the target vocabulary"
                continue
            }

            // The same two post-processing steps LessonTranslator applies, in the same order, so this
            // measures what a student actually sees rather than the engine's raw output.
            val raw = engine.translate(SENTENCE, targetId)
            val out = LessonTranslator.normalizePunctuation(
                BrahmicTransliterator.transliterate(raw, language.scriptOffset),
                language,
            )
            Log.i(TAG, "${language.code} (${language.endonym}): $out")

            if (out.isBlank()) {
                failures += "${language.code}: blank output"
                continue
            }

            // Each language ends its sentences its own way, and the model does not reliably agree —
            // it emitted a Latin full stop for Marathi and an ASCII pipe for Odia. A space in front of
            // the terminator is the detokenizer's, and equally visible on screen.
            if (out.last() != language.terminator) {
                failures += "${language.code}: ends with '${out.last()}', expected '${language.terminator}' — \"$out\""
            }
            if (out.contains(" ${language.terminator}") || out.contains(" ,")) {
                failures += "${language.code}: space before punctuation in \"$out\""
            }

            // Urdu is Perso-Arabic, reached through its own vocabulary coverage rather than an offset,
            // so the Brahmic block arithmetic below does not describe it.
            if (language == SupportedLanguage.URDU) {
                if (out.none { it.code in ARABIC_BLOCK }) failures += "ur: no Perso-Arabic characters in \"$out\""
                continue
            }

            // The script the model's Devanagari should have become. Derived from the enum's own offset
            // rather than a second table, so a wrong offset cannot be masked by a matching expectation.
            val expected = (DEVANAGARI_START + language.scriptOffset)..(DEVANAGARI_END + language.scriptOffset)
            if (out.none { it.code in expected }) {
                failures += "${language.code}: nothing in ${expected.first.toString(16)}-" +
                    "${expected.last.toString(16)} — got \"$out\""
            }

            // Leftover Devanagari in a transliterated language means the mapping missed characters the
            // model emitted. Danda and double danda are shared punctuation and legitimately stay.
            if (language.scriptOffset != 0) {
                val leftover = out.filter { it.code in DEVANAGARI_START..DEVANAGARI_END && it.code !in SHARED }
                if (leftover.isNotEmpty()) {
                    failures += "${language.code}: untransliterated Devanagari \"$leftover\" in \"$out\""
                }
            }
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * The output must not be the English input echoed back. A tokenizer that silently failed to resolve
     * a tag, or a decoder that stopped at step zero, both produce output that passes a "not blank"
     * check while teaching the student nothing.
     */
    @Test
    fun hindiIsNotTheEnglishEchoed() {
        val targetId = engine.languageId(SupportedLanguage.HINDI.tag)
        assertNotNull("hin_Deva missing from the vocabulary", targetId)

        val out = engine.translate(SENTENCE, targetId)
        Log.i(TAG, "hi: $out")
        assertFalse("Hindi came back as the English input", out.trim().equals(SENTENCE, ignoreCase = true))
        assertTrue("no Devanagari in \"$out\"", out.any { it.code in DEVANAGARI_START..DEVANAGARI_END })
    }

    companion object {
        private const val TAG = "AllLanguagesDeviceTest"

        /**
         * Short, textbook-flavoured, and within the 18-word fragment limit LessonTranslator enforces,
         * so this exercises the same path a real lesson takes rather than a longer one that would be
         * split first.
         */
        private const val SENTENCE = "Water evaporates from the ocean and forms clouds in the sky."

        private const val DEVANAGARI_START = 0x0900
        private const val DEVANAGARI_END = 0x097F

        /** Danda and double danda — shared across every Brahmic script, so never transliterated. */
        private val SHARED = setOf(0x0964, 0x0965)

        /** Arabic plus the Arabic Supplement and Extended-A ranges Urdu draws on. */
        private val ARABIC_BLOCK = 0x0600..0x077F

        private lateinit var engine: MtEngine

        @BeforeClass
        @JvmStatic
        fun loadEngine() {
            // Same decoder configuration ModelHost uses in production; a lower step ceiling would
            // truncate output and make the script assertions test the wrong thing.
            engine = MtEngine(
                InstrumentationRegistry.getInstrumentation().targetContext,
                Direction.EN_TO_HI,
                GreedyDecoder(DecodeConfig(maxSteps = 48, minTargetLen = 48)),
            )
        }

        @AfterClass
        @JvmStatic
        fun releaseEngine() {
            engine.release()
        }
    }
}
