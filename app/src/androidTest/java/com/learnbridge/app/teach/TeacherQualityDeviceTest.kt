package com.learnbridge.app.teach

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.learnbridge.app.doc.Chunk
import com.learnbridge.app.doc.chunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Purpose:  Settles F17's open half — what the generative tutor is actually *worth* on the target
 *           hardware, now that the weights exist. Runs all three prompts against the real model and
 *           reports raw output, what the parsers salvaged, and how long each took.
 * Thread:   Instrumentation thread. Generation is slow and synchronous here by design.
 *
 * **Why this is a device test and not a judgement made from a transcript.** The failure this was
 * written to chase is invisible in the finished lesson: a real import produced five good key points
 * and then **one** quiz item where five were asked for, and `LessonParser` is deliberately forgiving,
 * so a mangled or truncated response and a well-formed short one look identical downstream. The only
 * way to tell a parser problem from a model problem is to read what the model actually emitted — and
 * here it was the model, which stopped after one block. `Prompts.quiz` carries the four-version
 * measurement that came out of running this, and the reason it now asks for three questions.
 *
 * **Requires the Gemma weights**, licence-gated and therefore neither in the repository nor in the
 * APK. Without them this skips rather than fails, exactly like [TurnMarkerDeviceTest]:
 *
 * ```
 * adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.learnbridge.app/files/
 * ./gradlew :app:connectedDebugAndroidTest --tests '*TeacherQualityDeviceTest*'
 * adb logcat -s TeacherQuality
 * ```
 *
 * The assertions cover only what is unambiguously broken — nothing generated, or nothing parsed out
 * of a non-empty response. Whether five key points are *good* key points is a human call, which is
 * why every response is logged in full.
 */
@RunWith(AndroidJUnit4::class)
class TeacherQualityDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyPromptProducesSomethingTheParsersCanUse() {
        val model = GemmaTeacher.modelFile(context)
        assumeTrue(
            "Gemma weights not staged — push ${GemmaTeacher.MODEL_NAME} to getExternalFilesDir to run this",
            model != null,
        )

        // One engine for all three prompts, matching how LessonPipeline uses it: a single
        // withTeacher acquisition generates every artifact for a document.
        val loadStarted = System.currentTimeMillis()
        val teacher = GemmaTeacher.create(context, model!!)
        Log.i(TAG, "engine load: ${System.currentTimeMillis() - loadStarted} ms")

        try {
            val explain = measure(teacher, "explain", TeachRequest.Explain(CHUNKS))
            val quiz = measure(teacher, "quiz", TeachRequest.Quiz(CHUNKS))
            val ask = measure(teacher, "ask", TeachRequest.Ask(QUESTION, CHUNKS))

            val keyPoints = LessonParser.parseKeyPoints(explain)
            val quizItems = LessonParser.parseQuiz(quiz)

            Log.i(
                TAG,
                "parsed: keyPoints=${keyPoints.size}/$KEY_POINTS_ASKED " +
                    "quizItems=${quizItems.size}/$QUIZ_ITEMS_ASKED",
            )
            keyPoints.forEachIndexed { i, point -> Log.i(TAG, "  keyPoint[$i]: $point") }
            quizItems.forEachIndexed { i, item ->
                Log.i(TAG, "  quiz[$i]: ${item.question} | A=${item.correct} | X=${item.distractors}")
            }

            // Empty output is a broken engine, not a quality opinion.
            for ((name, raw) in listOf("explain" to explain, "quiz" to quiz, "ask" to ask)) {
                assertTrue("$name generated nothing", raw.isNotBlank())
            }

            // A non-empty response that yields zero structure means prompt and parser have drifted
            // apart — the one failure mode the forgiving parsers are designed to hide.
            assertTrue("explain produced no parseable key points", keyPoints.isNotEmpty())
            assertTrue("quiz produced no parseable questions", quizItems.isNotEmpty())
        } finally {
            teacher.release()
        }
    }

    /** Generates, logs the response in full, and reports wall time. */
    private fun measure(teacher: GemmaTeacher, name: String, request: TeachRequest): String {
        val started = System.currentTimeMillis()
        val raw = runBlocking { teacher.stream(request).toList().joinToString("") }
        Log.i(TAG, "=== $name === ${System.currentTimeMillis() - started} ms, ${raw.length} chars")
        Log.i(TAG, raw)
        return raw
    }

    private companion object {
        const val TAG = "TeacherQuality"

        /** What [Prompts] asks for, so the logged ratio moves when a prompt does. */
        const val KEY_POINTS_ASKED = 5
        const val QUIZ_ITEMS_ASKED = 3

        /** The question below is answerable from the text, so NOT_IN_TEXT would be a real miss. */
        const val QUESTION = "Where does the plant get water?"

        /**
         * A whole textbook page, paragraphs and all.
         *
         * **Fed through the real [chunk] and truncated to [Prompts.MAX_CHUNKS], because that is
         * exactly what `LessonPipeline` does.** An earlier version of this test hand-built a single
         * short chunk, measured a prompt fix as working, and then the app still produced one quiz
         * item from the same document — the model's willingness to keep going turns out to depend on
         * how much text precedes the instruction, so a hand-trimmed passage measures the wrong thing.
         * Anything that changes chunking changes this input, which is the point.
         */
        val CHUNKS: List<Chunk> get() = chunk(SOURCE).take(Prompts.MAX_CHUNKS)

        val SOURCE = """
            Photosynthesis

            Green plants make their own food. This process is called photosynthesis. It happens
            mainly in the leaves.

            Leaves contain a green pigment called chlorophyll. Chlorophyll absorbs energy from
            sunlight. The plant takes in carbon dioxide from the air through tiny pores called
            stomata. The roots absorb water from the soil and carry it up the stem to the leaves.

            Using the energy from sunlight, the plant combines carbon dioxide and water to make
            glucose, a simple sugar. Oxygen is released into the air as a waste product. The glucose
            is used by the plant for growth, and some of it is stored as starch.

            Photosynthesis is important for all life on Earth. It produces the oxygen that animals
            breathe, and it is the beginning of almost every food chain. Without sunlight,
            photosynthesis stops, which is why plants kept in darkness turn pale and die.
        """.trimIndent()
    }
}
