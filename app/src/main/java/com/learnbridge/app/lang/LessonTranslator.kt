package com.learnbridge.app.lang

import com.bhashabridge.app.Direction
import com.learnbridge.app.ModelHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * One translatable piece of text plus whatever punctuation followed it in the original.
 *
 * Keeping the trailing characters beside the fragment instead of rebuilding punctuation afterwards is
 * what makes reassembly exact: the translator never sees the punctuation, so it can never mangle it,
 * and the rejoin is a concatenation rather than a guess.
 */
internal data class Fragment(val text: String, val trailing: String)

/**
 * Purpose:  Renders English lesson text into any [SupportedLanguage] through the offline engine.
 * Owns:     A memo of previously translated fragments, keyed by language and text.
 * Lifetime: One per lesson render; the memo is the only state.
 * Thread:   [render] is suspend and does its own dispatching.
 *
 * Two engine constraints shape this class, and neither is negotiable from here:
 *
 *  1. **A single translate() call has a decode-step ceiling.** ModelHost raises it well above the
 *     engine's inherited default, but greedy decoding still drifts on very long outputs, so text is
 *     split before translation rather than truncated after.
 *
 *  2. **Acquiring the engine may cost a model swap** — measured at ~14 s when the generative model has
 *     to be released first. So every fragment of every artifact is translated inside ONE
 *     `withTranslator` block. A renderer that acquired per sentence would pay that swap forty times.
 *
 * Switching *language*, by contrast, is free: the target language is only the second input token, so
 * one loaded engine serves every language with no reload.
 */
class LessonTranslator(private val modelHost: ModelHost) {

    /**
     * Keyed by language and source text, because the same English sentence renders differently per
     * language. Concurrent because the translator outlives any single coroutine, even though
     * ModelHost's mutex means only one translation runs at a time.
     */
    private val memo = ConcurrentHashMap<Pair<String, String>, String>()

    /**
     * Translates [lines] into [language], preserving line structure — one English line in, one
     * translated line out, so callers can keep rendering a list as a list.
     *
     * All work happens in a single engine acquisition. Call it once with everything that needs
     * translating, not once per item.
     */
    suspend fun render(lines: List<String>, language: SupportedLanguage): List<String> {
        if (language == SupportedLanguage.ENGLISH) return lines

        val plans = lines.map { splitForTranslation(it, language) }
        val needed = plans.flatten()
            .map { it.text }
            .filter { it.isNotBlank() && !memo.containsKey(language.code to it) }
            .distinct()

        if (needed.isNotEmpty()) {
            modelHost.withTranslator(Direction.EN_TO_HI) { engine ->
                // Resolved once per render, not per sentence. Falls back to the engine's own default
                // when a vocabulary somehow lacks the tag, which yields Hindi rather than an error.
                val targetId = engine.languageId(language.tag)

                // translate() is synchronous, blocking and not thread-safe. One fragment at a time,
                // off the main thread, inside the single acquisition.
                withContext(Dispatchers.Default) {
                    for (fragment in needed) {
                        memo[language.code to fragment] = runCatching {
                            // The model writes every Indic language in script-unified Devanagari, so
                            // southern and eastern scripts are converted here rather than by the
                            // engine. A no-op for Hindi, Marathi, Nepali, Sanskrit and Urdu.
                            BrahmicTransliterator.transliterate(
                                engine.translate(fragment, targetId),
                                language.scriptOffset,
                            )
                        }
                            // A fragment the engine chokes on falls back to the English text rather
                            // than blanking the line. Partly-translated output beats a hole.
                            .getOrDefault(fragment)
                    }
                }
            }
        }

        return plans.map { fragments -> rejoin(fragments, language) }
    }

    private fun rejoin(fragments: List<Fragment>, language: SupportedLanguage): String =
        fragments.joinToString("") { fragment ->
            joinTranslated(memo[language.code to fragment.text] ?: fragment.text, fragment.trailing)
        }.trim()

    companion object {

        /**
         * Target words per fragment.
         *
         * Split only when a sentence genuinely cannot fit. Splitting is not free: an earlier ten-word
         * limit cut "A plant makes its own food inside its leaves, using nothing but sunlight, water
         * and air." at the comma, and the orphaned clause came back as "कुछ भी नहीं पानी और हवा" —
         * grammatically broken, because a translation model needs the whole clause to resolve grammar.
         * A whole sentence translated well beats two fragments translated badly.
         */
        internal const val MAX_WORDS = 18

        /** Every terminator any supported language uses, plus the shared `!` and `?`. */
        private val TERMINATORS = SupportedLanguage.allTerminators

        /**
         * Clause boundaries used only when a sentence is too long on its own. Ordered longest-first so
         * " which " is not matched as a prefix of something shorter.
         */
        private val CLAUSE_WORDS = listOf(
            " because ", " which ", " although ", " however ", " whereas ",
            " while ", " that ", " and ", " but ", " so ",
        )

        /**
         * Splits [text] into fragments of at most [MAX_WORDS] words where possible, carrying each
         * fragment's trailing punctuation alongside it.
         *
         * Deliberately pure — no engine, no context — so the splitting rules, the part most likely to
         * be wrong, are unit-testable without a device or a loaded model.
         *
         * The English full stop is mapped to [language]'s own terminator: danda for Devanagari, the
         * Arabic full stop for Urdu. Small touch, but it is the difference between output that reads
         * as the target language and output that reads as English punctuation with other words in it.
         */
        internal fun splitForTranslation(text: String, language: SupportedLanguage): List<Fragment> {
            if (text.isBlank()) return emptyList()

            val fragments = mutableListOf<Fragment>()
            for (sentence in splitSentences(text, language)) {
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

        private fun splitSentences(text: String, language: SupportedLanguage): List<Sentence> {
            val result = mutableListOf<Sentence>()
            val body = StringBuilder()
            var i = 0

            while (i < text.length) {
                val c = text[i]
                if (c in TERMINATORS) {
                    // Consume a run of terminators ("?!") plus any trailing whitespace as one unit.
                    val punctuation = StringBuilder()
                    while (i < text.length && text[i] in TERMINATORS) {
                        punctuation.append(if (text[i] == '.') language.terminator else text[i])
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
         * Breaks an over-long sentence at commas and conjunctions, then hard-splits anything still over
         * the limit. The hard split is a real quality compromise — it can cut mid-clause — but a
         * fragment that exceeds the decode ceiling gets silently truncated, which is strictly worse.
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
         * The engine supplies a sentence-final terminator itself, so appending unconditionally
         * produced "…होती है ।।" in Hindi and "…ہوتا ہے۔।" in Urdu, where a danda was being stuck onto
         * text that already ended with the Arabic full stop.
         */
        internal fun joinTranslated(translated: String, trailing: String): String {
            val body = translated.trimEnd()
            val bodyTerminated = body.isNotEmpty() && body.last() in TERMINATORS
            val trailingStartsWithTerminator = trailing.isNotEmpty() && trailing.first() in TERMINATORS
            return if (bodyTerminated && trailingStartsWithTerminator) {
                body + trailing.dropWhile { it in TERMINATORS }
            } else {
                body + trailing
            }
        }
    }
}
