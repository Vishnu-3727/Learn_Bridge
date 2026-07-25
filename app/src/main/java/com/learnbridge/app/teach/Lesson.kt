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

/** One glossary entry. */
data class GlossaryEntry(val term: String, val definition: String)

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
            val q = question
            val a = correct
            val wrong = distractors.distinct().filterNot { it.equals(a, ignoreCase = true) }
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

    /** Glossary entries from `T:` / `D:` pairs. A term with no definition is dropped. */
    fun parseGlossary(raw: String, max: Int = 6): List<GlossaryEntry> {
        val entries = mutableListOf<GlossaryEntry>()
        var term: String? = null

        for (line in raw.lineSequence()) {
            val text = clean(line)
            if (text.isEmpty()) continue
            val (tag, body) = splitTag(text) ?: continue
            if (body.isEmpty()) continue

            when (tag) {
                "T" -> term = body
                "D" -> term?.let { entries += GlossaryEntry(it, body); term = null }
            }
        }
        return entries.distinctBy { it.term.lowercase() }.take(max)
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
