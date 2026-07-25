package com.learnbridge.app.hindi

import com.bhashabridge.app.Direction
import com.learnbridge.app.ModelHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * One translatable piece of text plus whatever punctuation followed it in the original.
 *
 * Keeping the trailing characters beside the fragment instead of trying to rebuild punctuation
 * afterwards is what makes reassembly exact: the translator never sees the punctuation, so it can
 * never mangle it, and the rejoin is a concatenation rather than a guess.
 */
internal data class Fragment(val text: String, val trailing: String)

/**
 * Purpose:  Renders English tutor output into Hindi through the offline translation engine.
 * Owns:     A memo of previously translated fragments.
 * Lifetime: One per lesson screen is fine; the memo is the only state.
 * Thread:   [toHindi] is suspend and does its own dispatching.
 *
 * Two engine constraints shape this entire class, and neither is negotiable from here because the
 * translation engine is frozen:
 *
 *  1. **A single translate() call caps at roughly fourteen Hindi words.** The decoder's step limit is
 *     raised where the engine is constructed (see ModelHost), but greedy decoding still drifts and
 *     loops on long outputs, so the reliable fix is to hand it short fragments. That is why text is
 *     split before translation rather than after.
 *
 *  2. **Acquiring the engine may cost a model swap.** On a memory-constrained device the generative
 *     model is released to make room, which takes seconds. So every fragment of every artifact is
 *     translated inside ONE withTranslator block. A renderer that acquired per sentence would pay
 *     that swap forty times and turn a six-second render into minutes — the single most expensive
 *     mistake available in this file.
 */
class HindiRenderer(private val modelHost: ModelHost) {

    /**
     * Quiz options and key points repeat across a document, so the memo earns its keep immediately.
     * Concurrent map because the renderer outlives any single coroutine, even though ModelHost's
     * mutex means only one translation runs at a time.
     */
    private val memo = ConcurrentHashMap<String, String>()

    /**
     * Translates [lines] to Hindi, preserving line structure — one English line in, one Hindi line
     * out, so callers can keep rendering a bulleted list as a bulleted list.
     *
     * All work happens in a single engine acquisition. Call it once with everything that needs
     * translating, not once per item.
     */
    suspend fun toHindi(lines: List<String>): List<String> {
        val plans = lines.map { splitForTranslation(it) }
        val needed = plans.flatten()
            .map { it.text }
            .filter { it.isNotBlank() && !memo.containsKey(it) }
            .distinct()

        if (needed.isNotEmpty()) {
            modelHost.withTranslator(Direction.EN_TO_HI) { engine ->
                // translate() is synchronous, blocking and not thread-safe. One fragment at a time,
                // off the main thread, inside the single acquisition.
                withContext(Dispatchers.Default) {
                    for (fragment in needed) {
                        memo[fragment] = runCatching { engine.translate(fragment) }
                            // A fragment the engine chokes on falls back to the English text rather
                            // than blanking the line. Partly-translated output beats a hole.
                            .getOrDefault(fragment)
                    }
                }
            }
        }

        return plans.map { fragments -> rejoin(fragments) }
    }

    private fun rejoin(fragments: List<Fragment>): String =
        fragments.joinToString("") { fragment ->
            joinTranslated(memo[fragment.text] ?: fragment.text, fragment.trailing)
        }.trim()

    companion object {

        /**
         * Target words per fragment.
         *
         * **Raised from 10 to 18 after seeing the output on device, and the reason matters: splitting
         * is not free.** Ten words was calibrated against the engine's inherited 18-step decode cap,
         * but ModelHost now constructs the decoder with a 48-step cap, so that ceiling is gone — and
         * over-splitting was actively destroying quality. "A plant makes its own food inside its
         * leaves, using nothing but sunlight, water and air." was split at the comma, and the orphaned
         * clause came back as "कुछ भी नहीं पानी और हवा" — grammatically broken, because a translation
         * model needs the whole clause to resolve the grammar.
         *
         * So: split only when a sentence genuinely cannot fit. A whole sentence translated well beats
         * two fragments translated badly, every time.
         */
        internal const val MAX_WORDS = 18

        private val SENTENCE_END = charArrayOf('.', '!', '?', '।')

        /**
         * Clause boundaries used only when a sentence is too long on its own. Ordered longest-first so
         * " which " is not matched as a prefix of something shorter.
         */
        private val CLAUSE_WORDS = listOf(
            " because ", " which ", " although ", " however ", " whereas ",
            " while ", " that ", " and ", " but ", " so ",
        )

        /**
         * Splits [text] into fragments no longer than [MAX_WORDS] words where possible, carrying each
         * fragment's trailing punctuation and spacing alongside it.
         *
         * Deliberately a pure function with no engine or context, so the splitting rules — the part
         * most likely to be wrong — are unit-testable without a device or a loaded model.
         *
         * Sentence-final `.` becomes `।` (danda), the correct Hindi full stop. Small touch, but
         * it is the difference between output that reads as Hindi and output that reads as English
         * punctuation with Hindi words in it.
         */
        internal fun splitForTranslation(text: String): List<Fragment> {
            if (text.isBlank()) return emptyList()

            val fragments = mutableListOf<Fragment>()
            for (sentence in splitSentences(text)) {
                val body = sentence.text
                if (body.isBlank()) continue

                val pieces = if (wordCount(body) <= MAX_WORDS) listOf(body) else splitClauses(body)
                for ((index, piece) in pieces.withIndex()) {
                    val isLast = index == pieces.lastIndex
                    fragments += Fragment(
                        text = piece.trim(),
                        // Only the final piece of a sentence carries that sentence's punctuation;
                        // internal splits are rejoined with a plain space.
                        trailing = if (isLast) sentence.trailing else " ",
                    )
                }
            }
            return fragments.filter { it.text.isNotEmpty() }
        }

        /** Sentence text paired with the punctuation and whitespace that ended it. */
        private data class Sentence(val text: String, val trailing: String)

        private fun splitSentences(text: String): List<Sentence> {
            val result = mutableListOf<Sentence>()
            val body = StringBuilder()
            var i = 0

            while (i < text.length) {
                val c = text[i]
                if (c in SENTENCE_END) {
                    // Consume a run of terminators ("?!") plus any trailing whitespace as one unit.
                    val punctuation = StringBuilder()
                    while (i < text.length && text[i] in SENTENCE_END) {
                        punctuation.append(if (text[i] == '.') '।' else text[i])
                        i++
                    }
                    val spacing = StringBuilder()
                    while (i < text.length && text[i].isWhitespace()) {
                        spacing.append(text[i])
                        i++
                    }
                    result += Sentence(body.toString(), punctuation.toString() + spacing.toString())
                    body.clear()
                } else {
                    body.append(c)
                    i++
                }
            }
            if (body.isNotBlank()) result += Sentence(body.toString(), "")
            return result
        }

        /**
         * Breaks an over-long sentence at commas and conjunctions, then hard-splits anything still
         * over the limit. The hard split is a real quality compromise — it can cut mid-clause — but a
         * fragment that exceeds the engine's cap gets silently truncated, which is strictly worse.
         */
        private fun splitClauses(sentence: String): List<String> {
            var parts = sentence.split(',', ';', ':').map { it.trim() }.filter { it.isNotEmpty() }

            parts = parts.flatMap { part ->
                if (wordCount(part) <= MAX_WORDS) listOf(part) else splitAtClauseWord(part)
            }

            return parts.flatMap { part ->
                if (wordCount(part) <= MAX_WORDS) listOf(part) else hardSplit(part)
            }
        }

        private fun splitAtClauseWord(part: String): List<String> {
            for (word in CLAUSE_WORDS) {
                val at = part.indexOf(word, ignoreCase = true)
                // Require a real left-hand side: splitting on a leading "And ..." gains nothing.
                if (at > 0) {
                    val left = part.substring(0, at).trim()
                    val right = part.substring(at).trim()
                    if (left.isNotEmpty() && right.isNotEmpty()) {
                        return listOf(left) + if (wordCount(right) <= MAX_WORDS) listOf(right) else splitAtClauseWord(right)
                    }
                }
            }
            return listOf(part)
        }

        private fun hardSplit(part: String): List<String> =
            part.split(Regex("\\s+")).filter { it.isNotEmpty() }.chunked(MAX_WORDS) { it.joinToString(" ") }

        internal fun wordCount(text: String): Int =
            if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size

        /**
         * Appends a fragment's original punctuation to its translation — **unless the translation
         * already ends in a terminator of its own.**
         *
         * IndicTrans2 supplies a sentence-final danda itself, so appending the mapped English
         * terminator unconditionally produced "…होती है ।।" on every rendered line. Whitespace in the
         * original trailing text is preserved either way, so fragments still separate correctly.
         */
        internal fun joinTranslated(translated: String, trailing: String): String {
            val body = translated.trimEnd()
            val bodyTerminated = body.isNotEmpty() && body.last() in SENTENCE_END
            val trailingStartsWithTerminator = trailing.isNotEmpty() && trailing.first() in SENTENCE_END
            return if (bodyTerminated && trailingStartsWithTerminator) {
                body + trailing.dropWhile { it in SENTENCE_END }
            } else {
                body + trailing
            }
        }
    }
}
