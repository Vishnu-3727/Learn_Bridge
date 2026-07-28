package com.learnbridge.app.teach

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.learnbridge.app.doc.Chunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Purpose:  Settles F16 — whether [Prompts.applyTurnMarkers] should be true.
 * Thread:   Instrumentation thread. Generation is slow and synchronous here by design.
 *
 * **The question.** Gemma's instruction tuning expects `<start_of_turn>user … <end_of_turn>
 * <start_of_turn>model`. MediaPipe may already apply that template itself, from the chat template
 * baked into the `.task` bundle. If it does, adding the markers wraps the prompt twice and the model
 * sees a malformed conversation — which degrades output in ways that look like bad prompt wording,
 * and would send someone rewriting perfectly good prompts to fix the wrong thing.
 *
 * It cannot be settled by reading code. MediaPipe's templating lives inside the native task runner
 * and inside the bundle, so the only honest answer is to generate both ways on the real file and
 * read the output. That is what this does.
 *
 * **Requires the Gemma weights**, which are licence-gated and therefore not in the repository and not
 * in the APK. Stage them and the test runs; without them it skips rather than fails, exactly like
 * TokenizerTest's real-vocabulary case:
 *
 * ```
 * adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.learnbridge.app/files/
 * ./gradlew :app:connectedDebugAndroidTest --tests '*TurnMarkerDeviceTest*'
 * ```
 *
 * **Reading the result.** The assertions only catch the unambiguous failures — empty output, or a
 * response that echoes turn markers back, which is the signature of double wrapping. Quality is a
 * judgement call, so both responses are logged in full under the tag below and the verdict is yours:
 *
 * ```
 * adb logcat -s TurnMarkerAB
 * ```
 *
 * If the two are indistinguishable, leave [Prompts.applyTurnMarkers] true — the markers are then
 * redundant but harmless, and matching the model's documented format is the safer default when the
 * bundle could change.
 */
@RunWith(AndroidJUnit4::class)
class TurnMarkerDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Restored so the setting this test flips cannot leak into another test in the same run. */
    private val original = Prompts.applyTurnMarkers

    @After
    fun restore() {
        Prompts.applyTurnMarkers = original
    }

    @Test
    fun turnMarkersDoNotCorruptGeneration() {
        val model = GemmaTeacher.stagedModel(context)
        assumeTrue(
            "Gemma weights not staged — push ${GemmaTeacher.MODEL_NAME} to getExternalFilesDir, " +
                "or build with it in app/src/main/assets/, to run this",
            model != null,
        )

        // One engine for both arms. A cold load costs seconds and the prompt is the only variable
        // under test, so loading twice would add noise and time without adding evidence.
        val teacher = GemmaTeacher.create(context, model!!)
        try {
            val withMarkers = generate(teacher, markers = true)
            val withoutMarkers = generate(teacher, markers = false)

            Log.i(TAG, "=== applyTurnMarkers = true ===\n$withMarkers")
            Log.i(TAG, "=== applyTurnMarkers = false ===\n$withoutMarkers")
            Log.i(
                TAG,
                "lengths: with=${withMarkers.length} without=${withoutMarkers.length}; " +
                    "bullet lines: with=${bulletLines(withMarkers)} without=${bulletLines(withoutMarkers)}",
            )

            assertTrue("generation with turn markers produced nothing", withMarkers.isNotBlank())
            assertTrue("generation without turn markers produced nothing", withoutMarkers.isNotBlank())

            // The specific symptom of double wrapping: the model, shown a malformed conversation,
            // continues the markup instead of answering.
            assertNoTurnMarkers("with markers", withMarkers)
            assertNoTurnMarkers("without markers", withoutMarkers)
        } finally {
            teacher.release()
        }
    }

    private fun generate(teacher: GemmaTeacher, markers: Boolean): String {
        Prompts.applyTurnMarkers = markers
        return runBlocking {
            teacher.stream(TeachRequest.Explain(listOf(Chunk(0, LESSON)))).toList().joinToString("")
        }
    }

    private fun assertNoTurnMarkers(arm: String, output: String) {
        for (marker in listOf("<start_of_turn>", "<end_of_turn>")) {
            assertTrue(
                "$arm: the response contains $marker, which is what a double-wrapped prompt looks like",
                !output.contains(marker),
            )
        }
    }

    /** How many lines came back in the "- " shape the prompt asked for. The cheapest quality proxy
     *  there is: instruction-following, counted rather than eyeballed. */
    private fun bulletLines(output: String): Int =
        output.lines().count { it.trimStart().startsWith("- ") }

    private companion object {
        const val TAG = "TurnMarkerAB"

        /** Short and factual, so the interesting variable is the wrapping and not the source text. */
        const val LESSON =
            "The water on Earth is never used up. It moves in a loop called the water cycle. " +
                "The sun heats water in oceans and lakes. Some of that water turns into an " +
                "invisible gas called water vapour and rises into the air. As water vapour rises " +
                "and cools, it turns back into tiny droplets of liquid water. Those droplets " +
                "gather into clouds. When the droplets grow heavy they fall as rain or snow."
    }
}
