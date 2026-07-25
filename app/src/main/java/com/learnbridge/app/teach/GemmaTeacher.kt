package com.learnbridge.app.teach

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Purpose:  The generative tutor, backed by Gemma 3 1B int4 running on-device through MediaPipe's
 *           LLM Inference API. Adapts MediaPipe's callback streaming into a [Flow].
 * Owns:     One native [LlmInference] engine (~1 GB resident once loaded), plus one short-lived
 *           [LlmInferenceSession] per generation.
 * Lifetime: Created and released by ModelHost. Never constructed directly by a feature.
 * Thread:   [stream] is a cold Flow. One generation at a time — the underlying task runner is not
 *           built for concurrent calls, and ModelHost's residency mutex already serialises callers.
 *
 * Two facts about this API are worth writing down, because the published Android guide currently
 * documents an older shape and following it does not compile against 0.10.27:
 *
 *  1. The progress listener is passed **per call** — `generateResponseAsync(prompt, listener)` —
 *     not registered once on the engine options. So there is no shared callback sink to multiplex.
 *  2. Sampling parameters (temperature, topK, topP, randomSeed) live on
 *     [LlmInferenceSession.LlmInferenceSessionOptions], not on the engine options. Only
 *     modelPath / maxTokens / maxTopK / preferredBackend are engine-level.
 *
 * Signatures were read from the artifact itself rather than the guide.
 *
 * MediaPipe's LLM Inference API is in maintenance-only mode, with LiteRT-LM as its successor. That
 * is deliberate here: maintenance mode means published, stable, and not moving underneath a build
 * with a deadline.
 */
class GemmaTeacher private constructor(
    private val engine: LlmInference,
    val backend: Backend,
) : Teacher {

    override fun stream(request: TeachRequest): Flow<String> = streamPrompt(Prompts.of(request))

    private fun streamPrompt(prompt: String): Flow<String> = callbackFlow {
        // A fresh session per generation, deliberately. Sessions accumulate context through
        // addQueryChunk, and every prompt this app sends is an independent one-shot instruction —
        // reusing one session would leak the previous document's content into the next answer.
        //
        // ponytail: session creation allocates the KV cache, so this pays a small setup cost per
        // generation. Reuse a single session with an explicit reset if that ever shows up in a
        // profile; it does not today, next to a multi-second decode.
        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions())

        try {
            session.addQueryChunk(prompt)

            val future = session.generateResponseAsync { partial, done ->
                // Empty partials do occur; the final callback often carries done=true with no text.
                if (!partial.isNullOrEmpty()) trySend(partial)
                if (done) channel.close()
            }

            // The listener reports completion but not failure. Without this, a failed generation
            // would hang the collector forever waiting for a done=true that never arrives.
            future.addListener(
                {
                    runCatching { future.get() }
                        .onFailure { channel.close(it) }
                },
                Runnable::run,
            )
        } catch (t: Throwable) {
            channel.close(t)
        }

        awaitClose {
            // Cancel first: closing a session mid-generation is not safe, and a collector that was
            // cancelled early (user left the screen) leaves generation running otherwise.
            runCatching { session.cancelGenerateResponseAsync() }
            runCatching { session.close() }
        }
    }

    override fun release() {
        runCatching { engine.close() }
            .onFailure { Log.w(TAG, "Engine close failed: ${it.message}") }
    }

    private fun sessionOptions(): LlmInferenceSession.LlmInferenceSessionOptions =
        LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(TOP_K)
            .setTemperature(TEMPERATURE)
            .setRandomSeed(RANDOM_SEED)
            .build()

    /**
     * Which compute unit to prefer. Kept as this class's own type so the choice can be logged and
     * persisted without leaking MediaPipe's enum up through ModelHost.
     *
     * On the reference device (S24 Ultra, 2048 context) GPU wins prefill by ~7x — 2,531 vs 379
     * tok/s — but is no faster at decode (49 vs 55 tok/s) and costs ~200 MB more. Interactive turns
     * are decode-bound, so CPU is the right default; GPU is worth trying only if prefill on a long
     * retrieved prompt turns out to dominate.
     */
    enum class Backend {
        CPU,
        GPU,
        ;

        fun toMediaPipe(): LlmInference.Backend = when (this) {
            CPU -> LlmInference.Backend.CPU
            GPU -> LlmInference.Backend.GPU
        }
    }

    companion object {
        private const val TAG = "GemmaTeacher"

        /**
         * Must not exceed the context length baked into the `.task` file — the published variants are
         * 1280 and 4096. Asking for more than the file supports fails at load, not at generation.
         *
         * This is also why retrieval exists: a 20-page document is ~12,000 tokens and will not fit
         * here under any setting, so chunks are selected before the prompt is built.
         */
        private const val MAX_TOKENS = 2048

        /**
         * Deliberately cool. Every prompt in this app is extraction-shaped — "copy the important
         * sentences, then rewrite them simpler" — where creative sampling is a liability. A fixed
         * seed on top of low temperature keeps output reproducible, which matters because the demo
         * documents are verified by repeated runs.
         */
        private const val TEMPERATURE = 0.3f
        private const val TOP_K = 40
        private const val RANDOM_SEED = 1

        /** Dev-mode location: `adb push`ed here so a rebuild does not reinstall a ~1.5 GB APK. */
        const val MODEL_NAME = "gemma3-1b-it-int4.task"

        /**
         * Resolves the model file, preferring the adb-pushed copy so development iteration stays
         * cheap, then the app-private copy a release build would use.
         *
         * Returns null when no model is staged. That is a normal state, not an error: development
         * happens with the weights pushed rather than packaged, and a fresh clone has none.
         */
        fun modelFile(context: Context): File? =
            listOfNotNull(
                context.getExternalFilesDir(null)?.let { File(it, MODEL_NAME) },
                File(context.filesDir, MODEL_NAME),
            ).firstOrNull { it.isFile && it.length() > 0 }

        /**
         * Loads the engine. Blocking and slow — call off the main thread.
         *
         * Throws on any failure, including [OutOfMemoryError] on a device that cannot hold the model.
         * Callers catch and degrade rather than propagate: an unavailable model must produce a
         * simpler tutor, never an error screen.
         */
        fun create(context: Context, model: File, backend: Backend = Backend.CPU): GemmaTeacher {
            Log.i(TAG, "Loading ${model.name} (${model.length() / (1024 * 1024)} MB) on $backend")

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(model.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setMaxTopK(TOP_K)
                .setPreferredBackend(backend.toMediaPipe())
                .build()

            val started = System.currentTimeMillis()
            val engine = LlmInference.createFromOptions(context, options)
            Log.i(TAG, "Engine ready in ${System.currentTimeMillis() - started} ms")

            return GemmaTeacher(engine, backend)
        }
    }
}
