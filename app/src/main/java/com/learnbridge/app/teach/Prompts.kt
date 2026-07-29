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

    /**
     * The chat template to wrap prompts in. Set by [GemmaTeacher.create] from whichever weights
     * actually loaded, because the markers belong to the model rather than to the app — see
     * [ModelKind]. Defaults to Gemma, which is the packaged model.
     */
    var modelKind: ModelKind = ModelKind.GEMMA3_1B

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
     * **Three, not five, and the number is measured rather than chosen.** Seven versions of this
     * prompt ran against the real weights on the SM-M315F (`TeacherQualityDeviceTest`), the last four
     * against a whole page put through the real [chunk] — which matters, because the model gives up
     * sooner the more text precedes the instruction, and a hand-trimmed passage flatters it:
     *
     * | prompt | usable items | wrong options |
     * |---|---|---|
     * | "five questions", four-line shape shown once | **1** | real |
     * | + "five Q lines, twenty lines in total" | **5** | **placeholder text, copied verbatim** |
     * | + bracketed slots instead of literal placeholders | **2** | real |
     * | asked for three (short passage) | **3** | real |
     * | asked for three (whole page) | **2** | real |
     * | + the count repeated *after* the text | **2** | real |
     * | one line per question, `Q: … \| answer \| wrong \| wrong` | **5** | **only one per line, some of them true** |
     * | + "exactly three \| characters", eight words per part | **5** | **"Correct answer \| Wrong answer"** |
     *
     * One finding explains every row. **Push this model toward a count and it satisfies the count by
     * copying the template; ask it for content it has to invent and it stops after two or three.** The
     * single-line format is the clearest case: it fixes the count outright — five lines, every time,
     * five correct answers — and pays for it in the distractors, which collapse to one per line and
     * are sometimes *true*, which is worse than a missing question. That is a capacity ceiling on a
     * 1B int4 model, not a wording problem, so further prompt tuning is not the lever. Recorded here
     * because it looks eminently retryable and is not.
     *
     * What ships is the row that produces correct questions with plausible wrong answers and simply
     * makes fewer of them. Asking for a number the model will not reach only hides the shortfall
     * behind [LessonParser]'s forgiving parse, where nobody sees it. **Real distractors are the thing
     * to protect: a student can learn from two good questions and is actively taught wrongly by five
     * where the wrong answer is correct.**
     *
     * If a larger model is ever staged, raise the count and re-run that test — it prints parsed counts
     * and every raw response, so the next person re-measures instead of re-arguing.
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
        if (applyTurnMarkers) {
            modelKind.startUser + body + "\n" + modelKind.endTurn + modelKind.startModel
        } else {
            body
        }
}
