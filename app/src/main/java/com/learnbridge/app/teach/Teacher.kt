package com.learnbridge.app.teach

import com.learnbridge.app.doc.Chunk
import kotlinx.coroutines.flow.Flow

/**
 * What the app wants taught, expressed as intent rather than as a prompt string.
 *
 * This is deliberately not "a prompt": a prompt is one implementation's way of asking. Passing
 * pre-formatted Gemma text through the [Teacher] interface would mean every implementation had to
 * either speak Gemma's dialect or reverse-engineer the chunks back out of an instruction block — and
 * the non-generative implementation, which is what ships on a device that cannot hold a language
 * model, has no use for prompt text at all.
 */
sealed interface TeachRequest {
    val chunks: List<Chunk>

    /** Explain the document: a short list of plain-language key points. */
    data class Explain(override val chunks: List<Chunk>) : TeachRequest

    /** Answer a specific question, grounded in the supplied chunks. */
    data class Ask(val question: String, override val chunks: List<Chunk>) : TeachRequest

    /** Multiple-choice questions over the document. */
    data class Quiz(override val chunks: List<Chunk>) : TeachRequest

    /** Hard vocabulary with short definitions. */
    data class Glossary(override val chunks: List<Chunk>) : TeachRequest
}

/**
 * Purpose:  The tutor, seen as one method. Everything above this interface — the lesson pipeline, the
 *           Ask flow, the quiz builder — depends only on "request in, token stream out", never on
 *           which model, runtime, or quantization is behind it.
 * Owns:     Whatever native resources the implementation holds, released by [release].
 * Lifetime: Owned by ModelHost at process scope. Callers borrow inside `withTeacher { }`.
 * Thread:   [stream] is a cold Flow; collect it off the main thread. One generation at a time,
 *           enforced by ModelHost's mutex.
 *
 * This seam is not speculative — it has three implementations, and the app must ship all three:
 *  - [GemmaTeacher]     — Gemma 3 1B int4 via MediaPipe. The good one.
 *  - [ExtractiveTeacher] — no language model at all. What runs on a device that cannot hold one, and
 *                          what ships if on-device generation proves unusable.
 *  - [FakeTeacher]      — canned streaming, so UI and pipeline are testable with no model present.
 *
 * A device that cannot run generation swaps the implementation and keeps every feature. That is the
 * point: the app must never show an error where a feature belongs.
 */
interface Teacher {

    /**
     * Streams a response to [request], one chunk at a time.
     *
     * Chunks are whatever the implementation emits — often a partial word, not a whole one. Callers
     * append them in order and must not assume word boundaries.
     *
     * The Flow completes when the response ends, and fails if generation breaks mid-stream, so
     * callers can render partial output rather than discarding it. Half an explanation beats an error.
     */
    fun stream(request: TeachRequest): Flow<String>

    /** Releases native resources. Only call site is the process-scoped owner. */
    fun release()
}
