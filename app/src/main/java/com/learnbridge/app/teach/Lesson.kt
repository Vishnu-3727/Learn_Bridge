package com.learnbridge.app.teach

import kotlin.random.Random

/** One multiple-choice question. [correct] is the right answer; [distractors] are the wrong ones. */
data class QuizItem(
    val question: String,
    val correct: String,
    val distractors: List<String>,
) {
    /**
     * Options in a stable but non-obvious order, plus the index of the correct one.
     *
     * Shuffled because the prompt asks the model to always put the answer on the `A:` line, so
     * without this the right answer is always first and the quiz is worthless. Seeded by the question
     * text so a given item shuffles the same way every time it is rendered — a re-render must not move
     * the options under the student's finger, and a demo must be reproducible.
     */
    fun shuffledOptions(): Pair<List<String>, Int> {
        val options = (listOf(correct) + distractors).shuffled(Random(question.hashCode()))
        return options to options.indexOf(correct)
    }

    /**
     * Newline-separated: question, correct answer, then distractors.
     *
     * ponytail: one artifact row per question rather than JSON or a fourth table. The strings are
     * model output the parser has already collapsed to single lines, so the delimiter is safe, and
     * one row per item keeps quiz translation identical to every other `(kind, lang)` artifact.
     * Revisit if an item ever needs structure beyond a list of strings.
     */
    fun encode(): String = (listOf(question, correct) + distractors).joinToString("\n")

    companion object {
        /** Returns null for a row that cannot form a usable question, rather than a broken item. */
        fun decode(encoded: String): QuizItem? {
            val parts = encoded.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 3) return null
            return QuizItem(parts[0], parts[1], parts.drop(2))
        }
    }
}

/**
 * Purpose:  Parses the tutor's raw text output into structured lesson content.
 * Owns:     Nothing — pure functions over strings.
 * Lifetime: Stateless.
 * Thread:   Any.
 *
 * These parsers are deliberately forgiving, and that is a correctness decision rather than a lazy
 * one. The producer is a 1B-parameter model that will, unprompted: wrap prefixes in markdown
 * (`**Q:**`), number its own lines (`1. Q:`), change case, emit an extra blank line, use `B:`/`C:`
 * because it has seen a thousand lettered quizzes, stop after three questions when asked for five, or
 * append a closing pleasantry.
 *
 * Given an unreliable producer, the correct algorithm is to salvage whatever parsed and silently drop
 * whatever did not. Three good quiz questions is a working feature; an exception where five were
 * expected is a failed demo. Nothing here throws on malformed input.
 */
object LessonParser {

    private val LEADING_NUMBER = Regex("""^\d+[.)]\s*""")
    private val TRAILING_EMPHASIS = Regex("""[*_]*\s*$""")
    private val BULLETS = listOf("**", "__", "*", "_", "-", "•", ">")

    /**
     * Word overlap at or above which two options are the same option wearing different words.
     *
     * Measured against real device output rather than picked. The pair this exists for is a
     * SystemVerilog packed/unpacked question where the model wrote its answer twice, swapping
     * "is a type of array where dimensions are declared" for "is used to refer to dimensions
     * declared" and "contiguous group of bits" for "contiguous set of bits" — 0.74. A legitimate
     * distractor, true about the text but not the answer to the question asked
     * ("Photosynthesis requires chlorophyll and light energy." against "Photosynthesis converts
     * light energy into chemical energy.") — 0.33. 0.6 sits in the gap with room either side.
     *
     * Raising this lets unanswerable questions through; lowering it starts discarding the
     * true-but-not-the-answer distractors, which are the good ones. Re-measure on device before
     * moving it, the way the numbers above were got.
     */
    internal const val SAME_OPTION = 0.6

    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Key points from the explain prompt: lines the model marked with `- `, `* `, or a number.
     *
     * Falls back to treating every non-empty line as a key point when the model ignored the bullet
     * instruction entirely — which it sometimes does, while still producing perfectly good content.
     * Losing the whole lesson over a missing hyphen would be absurd.
     */
    fun parseKeyPoints(raw: String, max: Int = 6): List<String> {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        val bulleted = lines
            .filter { it.startsWith("-") || it.startsWith("*") || it.firstOrNull()?.isDigit() == true }
            .map { clean(it) }
            .filter { it.isNotEmpty() }

        val chosen = if (bulleted.isNotEmpty()) bulleted else lines.map { clean(it) }.filter { it.isNotEmpty() }

        return chosen
            .filterNot { it.isPromptEcho() }
            .distinct()
            .take(max)
    }

    /**
     * Quiz items from `Q:` / `A:` / `X:` lines.
     *
     * Accepts `B:`/`C:`/`D:` as distractors too, because a model trained on lettered multiple choice
     * reaches for them regardless of the format it was given. An item is emitted only when it has a
     * question, an answer, and at least one wrong option — anything less is not a usable question, so
     * it is dropped rather than shown half-built.
     */
    fun parseQuiz(raw: String, max: Int = 5): List<QuizItem> {
        val items = mutableListOf<QuizItem>()

        var question: String? = null
        var correct: String? = null
        val distractors = mutableListOf<String>()

        // Always clears its state, on every path. An early return here would leak one item's
        // distractors into the next question, which reads as the model malfunctioning.
        fun flush() {
            // A copied slot in the question or the answer makes the whole item unusable; one in a
            // distractor only costs that option, so it is handled inside usableDistractors.
            val q = question?.takeUnless { isCopiedSlot(it) }
            val a = correct?.takeUnless { isCopiedSlot(it) }
            val wrong = if (a == null) emptyList() else usableDistractors(distractors, a)
            if (q != null && a != null && wrong.isNotEmpty()) {
                items += QuizItem(q, a, wrong)
            }
            question = null
            correct = null
            distractors.clear()
        }

        for (line in raw.lineSequence()) {
            val text = clean(line)
            if (text.isEmpty()) continue
            val (tag, body) = splitTag(text) ?: continue
            if (body.isEmpty()) continue

            when (tag) {
                "Q" -> {
                    flush()
                    question = body
                }
                "A" -> if (question != null && correct == null) correct = body else distractors += body
                "X", "B", "C", "D" -> if (question != null) distractors += body
            }
        }
        flush()

        return items.take(max)
    }

    /**
     * An answer from the ask prompt, or null when the model used the not-in-text sentinel.
     *
     * Matched as "contains", not "equals": a model told to reply with exactly one token will still
     * sometimes wrap it in a sentence. Null means "say so in the UI", never an empty answer bubble.
     */
    fun parseAnswer(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains(Prompts.NOT_IN_TEXT, ignoreCase = true)) return null

        val body = trimmed.lineSequence()
            .map { clean(it) }
            .filter { it.isNotEmpty() && !it.isPromptEcho() }
            .joinToString("\n")

        return body.ifEmpty { null }
    }

    /**
     * The distractors that are genuinely different from [answer] and from each other.
     *
     * The model regularly restates its own answer as the wrong option, in different words. That
     * produces a question with two options saying the same thing, one of them arbitrarily marked
     * wrong — the student picks a statement that says what the correct one says and is told "Not
     * quite", with no explanation. That is worse than a missing question: it teaches distrust of
     * the app, and there is nothing to learn from it.
     *
     * So a near-duplicate is dropped, and when nothing survives here the caller drops the whole
     * item. **Deliberately no floor that keeps the least-similar option.** Keeping one leaves
     * either the unanswerable pair that started this or a multiple-choice question with a single
     * choice; both are the bug. A quiz with two answerable questions beats three where one cannot
     * be answered, and an empty quiz already has its own screen (`quiz_unavailable`).
     *
     * Order is preserved, so the first distractor the model wrote is the one kept.
     */
    private fun usableDistractors(distractors: List<String>, answer: String): List<String> {
        val kept = mutableListOf<String>()
        for (option in distractors) {
            if (isCopiedSlot(option)) continue
            if (similarity(option, answer) >= SAME_OPTION) continue
            if (kept.any { similarity(option, it) >= SAME_OPTION }) continue
            kept += option
        }
        return kept
    }

    /**
     * Whether the model handed back one of the prompt's own template slots as content.
     *
     * Scored rather than matched exactly, because the copy arrives with the model's own casing and
     * punctuation and sometimes a word moved. Reuses [SAME_OPTION]: a slot restated is the same
     * text by the same measure that catches an answer restated.
     */
    private fun isCopiedSlot(text: String): Boolean =
        Prompts.QUIZ_SLOTS.any { similarity(text, it) >= SAME_OPTION }

    /**
     * Jaccard overlap of the two texts' word sets, from 0.0 (nothing shared) to 1.0 (same words).
     *
     * Set overlap rather than edit distance because the failure being caught is rewording, not
     * typos: the duplicate options share their vocabulary and differ in phrasing and order, which
     * is exactly what this scores high and what character-level distance scores low. Case and
     * punctuation are dropped first, so this also subsumes the exact-duplicate check it replaced.
     *
     * ponytail: unigrams, no stemming and no stopword list. "bits"/"bit" and "declare"/"declared"
     * count as different words, which costs a little sensitivity, and shared "the"/"of" costs a
     * little precision; the measured gap between a duplicate (0.74) and a real distractor (0.33)
     * is wide enough to absorb both. Add stemming only if a real pair lands in the middle.
     */
    internal fun similarity(a: String, b: String): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return if (x == y) 1.0 else 0.0
        val shared = x.intersect(y).size
        return shared.toDouble() / (x.size + y.size - shared)
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(NON_WORD).filterTo(mutableSetOf()) { it.isNotEmpty() }

    /** Splits `X: body` into tag and body. Returns null when the line carries no single-letter tag. */
    private fun splitTag(line: String): Pair<String, String>? {
        val colon = line.indexOf(':')
        if (colon != 1) return null
        val tag = line.substring(0, 1).uppercase()
        return tag to line.substring(colon + 1).trim()
    }

    /**
     * Strips the decoration a model wraps its own output in.
     *
     * Iterative rather than a single regex because the pieces arrive in any order — `**1. text**`,
     * `1. **text**`, `- **2) text**` are all real. A fixed-order pattern strips markdown then
     * numbering and leaves the numbering behind whenever it came second. Terminates because each pass
     * either shortens the string or changes nothing.
     */
    private fun clean(line: String): String {
        var s = line.trim()
        var previous: String
        do {
            previous = s
            for (bullet in BULLETS) s = s.removePrefix(bullet)
            s = s.replace(LEADING_NUMBER, "").trim()
        } while (s != previous)
        return s.replace(TRAILING_EMPHASIS, "").trim()
    }

    /**
     * Models sometimes restate the instruction before obeying it. A line that is clearly the prompt
     * coming back is not content, and showing it to a student would look like a bug.
     */
    private fun String.isPromptEcho(): Boolean {
        val lower = lowercase()
        return lower.startsWith("here are") ||
            lower.startsWith("sure,") ||
            lower.startsWith("okay") ||
            lower.startsWith("text:") ||
            lower.startsWith("question:") ||
            lower == "rules:"
    }
}
