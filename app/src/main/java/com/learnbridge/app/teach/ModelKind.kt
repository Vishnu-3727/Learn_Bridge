package com.learnbridge.app.teach

import java.io.File

/**
 * Purpose:  The staged model's identity — its filename on disk and the chat template it was tuned
 *           with. The single place a second model's format lives.
 * Owns:     Nothing. Pure data.
 * Lifetime: Stateless.
 * Thread:   Any.
 *
 * This exists because the turn markers are a property of the *weights*, not of the app. Gemma was
 * instruction-tuned with `<start_of_turn>`; Qwen with ChatML's `<|im_start|>`. Sending one model the
 * other's markers does not fail loudly — it degrades output quality in exactly the way that is
 * hardest to attribute, so the two must move together.
 *
 * Keeping both entries rather than editing one in place is deliberate: the quiz-quality question
 * ("is the 1B ceiling real, or is it the prompt?") is only answerable by running the same documents
 * through both, and [GemmaTeacher.modelFile] resolves whichever file is actually staged. That makes
 * the A/B an `adb push`, not a rebuild.
 */
enum class ModelKind(
    val fileName: String,
    val startUser: String,
    val endTurn: String,
    val startModel: String,
) {
    /** Gemma 3 1B int4, 529 MB. The packaged default — see `app/src/main/assets`. */
    GEMMA3_1B(
        fileName = "gemma3-1b-it-int4.task",
        startUser = "<start_of_turn>user\n",
        endTurn = "<end_of_turn>\n",
        startModel = "<start_of_turn>model\n",
    ),

    /**
     * Qwen2.5 1.5B Instruct, dynamic int8, 1.6 GB — `multi-prefill-seq`, `ekv4096`.
     *
     * ekv4096 rather than the ekv1280 build because [GemmaTeacher.MAX_TOKENS] is 2048, and asking
     * for more context than the file carries fails at load rather than at generation.
     *
     * ChatML omits the optional system turn here, matching the Gemma arm's shape so the only
     * variable between the two is the model itself.
     */
    QWEN25_1_5B(
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
        startUser = "<|im_start|>user\n",
        endTurn = "<|im_end|>\n",
        startModel = "<|im_start|>assistant\n",
    ),
    ;

    companion object {
        /** The kind a staged file is, or null when the name is not one this build knows. */
        fun of(file: File): ModelKind? = entries.firstOrNull { it.fileName == file.name }
    }
}
