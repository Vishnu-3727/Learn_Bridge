package com.learnbridge.app.teach

import com.learnbridge.app.doc.Chunk

/**
 * Purpose:  Builds the three prompts the tutor ever sends. The single place prompt wording lives.
 * Owns:     Nothing — pure string assembly.
 * Lifetime: Stateless.
 * Thread:   Any.
 *
 * Every prompt here is shaped as **extraction, not generation**, and that is the most important
 * design decision in the whole teaching path. A 1B-parameter model is genuinely good at "find the
 * important sentences in this text and say them more simply" and genuinely bad at "explain
 * photosynthesis" — the first is grounded in text it can see, the second invites it to invent. So
 * every instruction below points at the supplied text and forbids adding to it.
 *
 * The "maximum ten words per line" rule appears in all three and does triple duty:
 *  - a small model follows length constraints more reliably than any other kind of instruction;
 *  - short lines are what the Hindi renderer needs, because the translation engine truncates long
 *    single calls (see LessonTranslator);
 *  - short lines read better on a phone.
 *
 * Output formats are deliberately line-prefixed rather than JSON. A 1B model produces malformed JSON
 * often enough that a strict parser would throw away otherwise usable output; line prefixes degrade
 * gracefully, so three good quiz questions survive where five were asked for. See [Lesson]'s parsers.
 */
object Prompts {

    /**
     * Gemma's instruction-tuned turn markers.
     *
     * **VERIFIED on device 2026-07-28** (SM-M315F, `gemma3-1b-it-int4.task`, CPU) by
     * `TurnMarkerDeviceTest` — the A/B that F16 asked for. Both arms answered cleanly and neither
     * echoed a marker back, so MediaPipe is not double-wrapping destructively: 5 bullet lines each,
     * 276 chars with markers vs 222 without, same five facts in the same order. Keeping this true
     * because the two are indistinguishable in quality and matching the model's documented format is
     * the safer default if the bundle ever changes.
     *
     * If a future bundle does start echoing `<start_of_turn>`, that test fails on the assertion
     * rather than on judgement — flip this to false and re-run before touching any prompt wording.
     */
    var applyTurnMarkers: Boolean = true

    private const val TURN_START_USER = "<start_of_turn>user\n"
    private const val TURN_END = "<end_of_turn>\n"
    private const val TURN_START_MODEL = "<start_of_turn>model\n"

    /** How many retrieved chunks are worth including. Four ~180-word chunks land near 1,000 tokens. */
    const val MAX_CHUNKS = 4

    /** The sentinel an answer uses when the document does not cover the question. */
    const val NOT_IN_TEXT = "NOT_IN_TEXT"

    /** Maps a request to the prompt that asks a language model for it. */
    fun of(request: TeachRequest): String = when (request) {
        is TeachRequest.Explain -> explain(request.chunks)
        is TeachRequest.Ask -> ask(request.question, request.chunks)
        is TeachRequest.Quiz -> quiz(request.chunks)
    }

    /**
     * The lesson: five plain-language key points drawn from the document.
     *
     * Generated once at import and persisted, not on every screen open — at ~10 tokens/second this
     * takes seconds, and paying that once per document is the difference between a usable app and a
     * spinner.
     */
    fun explain(chunks: List<Chunk>): String = wrap(
        """
        You are a patient teacher. A student must understand the text below.

        Write the five most important facts from the text.

        Rules:
        - Write exactly five lines.
        - Start every line with "- ".
        - Maximum ten words per line.
        - Rewrite each fact in simple words a beginner understands.
        - Use only facts that appear in the text. Add nothing new.

        TEXT:
        ${chunks.joinToString("\n\n") { it.text }}
        """.trimIndent(),
    )

    /**
     * A grounded answer to the student's own question.
     *
     * The [NOT_IN_TEXT] escape hatch is the anti-hallucination lever: without it, a model asked
     * something the document does not cover will confidently invent an answer, and the Hindi renderer
     * will then translate that invention into fluent, convincing Hindi. Giving the model a permitted
     * way to decline is cheaper and more reliable than trying to detect the invention afterwards.
     */
    fun ask(question: String, chunks: List<Chunk>): String = wrap(
        """
        Answer the student's question using only the text below.

        Rules:
        - Maximum ten words per sentence.
        - One sentence per line.
        - Use at most three lines.
        - Use only facts from the text.
        - If the text does not answer the question, reply with exactly: $NOT_IN_TEXT

        TEXT:
        ${chunks.joinToString("\n\n") { it.text }}

        QUESTION: $question
        """.trimIndent(),
    )

    /**
     * Three multiple-choice questions.
     *
     * `Q:` / `A:` / `X:` rather than `A:` / `B:` / `C:` on purpose. Lettered options force the model
     * to also decide *which letter* is correct and state that somewhere, which is a second thing to
     * get wrong and a second thing to parse. Naming the roles instead — answer versus wrong option —
     * means the correct choice is structural, and the UI shuffles before display so the answer is not
     * always first. See [QuizItem.shuffledOptions].
     *
     * **Three, not five, and every word of the count is measured rather than chosen.** Four versions
     * of this prompt ran against the real weights on the SM-M315F (`TeacherQualityDeviceTest`):
     *
     * | prompt | parsed items | options |
     * |---|---|---|
     * | "five questions", shape shown once | **1** of 5 | real |
     * | + "five Q lines, twenty lines in total" | **5** of 5 | **placeholder text, copied verbatim** |
     * | + bracketed slots instead of literal placeholders | **2** of 5 | real |
     * | asked for three | **3** of 3 | real |
     *
     * Two separate findings sit in that table. The count only obeys a *countable* constraint — the same
     * reason [explain]'s "exactly five lines" always worked. And the five-block run only reached five
     * because copying `X: a wrong answer` five times is free: given slots it must actually fill, this
     * model invents roughly two or three good distractor sets and then stops. So five substantive
     * questions is not available in one pass on this hardware, and asking for a number it will not
     * reach just moves the shortfall behind [LessonParser]'s forgiving parse, where nobody sees it.
     *
     * Three real questions beats five where two are usable. If a larger model is ever staged, raise
     * this and re-run that test — it prints parsed counts and every raw response.
     */
    fun quiz(chunks: List<Chunk>): String = wrap(
        """
        Write three quiz questions about the text below.

        Each question is four lines:
        Q: (the question)
        A: (the correct answer)
        X: (a wrong option)
        X: (a different wrong option)

        Rules:
        - Write all three questions: three Q lines, twelve lines in total.
        - Every Q line must be followed by its own A line and two X lines.
        - Replace every bracket above with real words from the text. Never copy a bracket.
        - Maximum twelve words per line.
        - The A line must correctly answer its own Q line, according to the text.
        - The X lines must be clearly wrong answers to that Q line, but about the same topic.
        - Ask about a different fact each time.
        - Use only facts from the text.
        """.trimIndent() + "\n\nTEXT:\n" + chunks.joinToString("\n\n") { it.text },
    )

    private fun wrap(body: String): String =
        if (applyTurnMarkers) TURN_START_USER + body + "\n" + TURN_END + TURN_START_MODEL else body
}
