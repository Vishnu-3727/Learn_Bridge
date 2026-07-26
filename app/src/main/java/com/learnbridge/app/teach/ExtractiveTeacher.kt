package com.learnbridge.app.teach

import com.learnbridge.app.doc.Chunk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Purpose:  A [Teacher] with no language model behind it. Selects and reshapes sentences the document
 *           already contains, using classical extractive methods.
 * Owns:     Nothing.
 * Lifetime: Cheap; no release needed.
 * Thread:   Any. Pure CPU work on strings, fast enough to run inline.
 *
 * **Why this exists, and why it is not a stub.**
 *
 * It is the tutor on a device that cannot hold a language model alongside a 605 MB translation
 * engine, and it is the tutor that ships if on-device generation turns out to be unusable. Both are
 * real possibilities, and in both the app must still teach — a study companion that shows an error on
 * low-end hardware has failed exactly the student it was built for.
 *
 * It is also the only implementation that cannot hallucinate. Every sentence it emits appears verbatim
 * or near-verbatim in the source document, which for a study aid is a genuine advantage rather than a
 * consolation: nothing it says can be wrong about the text, and the Hindi renderer downstream is
 * translating real content rather than a confident invention.
 *
 * What it gives up is paraphrase. It cannot rewrite a hard sentence in simpler words, so its key
 * points are the document's own sentences, chosen well. Openly a weaker teacher, not a broken one.
 *
 * Methods: TextRank-style centrality for selection, TF-IDF for term weighting, cloze deletion for
 * questions. All standard, all decades old, all about sixty lines each.
 */
class ExtractiveTeacher(
    /**
     * Pause between emitted lines. Non-zero by default because the screen renders this exactly like
     * model output, and a result that appears all at once reads as canned. Tests pass 0.
     */
    private val chunkDelayMs: Long = 120L,
) : Teacher {

    override fun stream(request: TeachRequest): Flow<String> = flow {
        val text = request.chunks.joinToString(" ") { it.text }
        val sentences = splitSentences(text)

        val response = when (request) {
            is TeachRequest.Explain -> explain(sentences)
            is TeachRequest.Ask -> answer(request.question, sentences)
            is TeachRequest.Quiz -> quiz(sentences)
        }

        // Emitted in pieces at a readable pace rather than all at once. The screen renders this the
        // same way it renders model output, and a result that simply appears reads as a canned
        // response even when it is not.
        for (line in response.lines()) {
            if (chunkDelayMs > 0) delay(chunkDelayMs)
            emit(line + "\n")
        }
    }

    override fun release() = Unit

    // --- Explain: the most central sentences, in document order ---

    /**
     * TextRank without the graph iteration: score each sentence by its summed similarity to every
     * other sentence, take the top few, and restore reading order.
     *
     * Skipping the power iteration is a deliberate simplification — one round of centrality scoring
     * ranks nearly identically to converged PageRank on documents of a few hundred sentences, and the
     * eigenvector refinement mostly reorders items that are already adjacent in the ranking.
     */
    private fun explain(sentences: List<String>): String {
        if (sentences.isEmpty()) return ""

        val tokenized = sentences.map { tokens(it) }
        val idf = inverseDocumentFrequency(tokenized)

        val scored = sentences.indices.map { i ->
            var score = 0.0
            for (j in sentences.indices) {
                if (i != j) score += similarity(tokenized[i], tokenized[j], idf)
            }
            // Length normalisation, or the longest sentence always wins on raw overlap alone.
            i to score / sqrt(tokenized[i].size.coerceAtLeast(1).toDouble())
        }

        return scored.sortedByDescending { it.second }
            .take(KEY_POINTS)
            .sortedBy { it.first }
            .joinToString("\n") { "- " + shorten(sentences[it.first]) }
    }

    // --- Ask: the sentences that best overlap the question ---

    /**
     * Retrieval, not generation. Returns the sentences most similar to the question, or the
     * not-in-text sentinel when nothing overlaps meaningfully.
     *
     * Emitting the same sentinel the generative prompt uses is what lets the UI treat both
     * implementations identically — [LessonParser.parseAnswer] already maps it to "not covered here".
     */
    private fun answer(question: String, sentences: List<String>): String {
        if (sentences.isEmpty()) return Prompts.NOT_IN_TEXT

        val tokenized = sentences.map { tokens(it) }
        val idf = inverseDocumentFrequency(tokenized)
        val asked = tokens(question)

        val ranked = sentences.indices
            .map { i -> i to similarity(asked, tokenized[i], idf) }
            .filter { it.second > MIN_ANSWER_OVERLAP }
            .sortedByDescending { it.second }

        if (ranked.isEmpty()) return Prompts.NOT_IN_TEXT

        return ranked.take(ANSWER_SENTENCES)
            .sortedBy { it.first }
            .joinToString("\n") { shorten(sentences[it.first]) }
    }

    // --- Quiz: cloze deletion over informative sentences ---

    /**
     * Builds fill-in-the-blank questions: take a well-connected sentence, blank its highest-IDF noun-ish
     * word, and offer that word against similarly weighted words drawn from elsewhere in the document.
     *
     * Distractors come from the same document on purpose. Words pulled from a general vocabulary would
     * be obviously wrong and the question trivial; words from the same text are plausible and force
     * the student to actually recall which term belongs.
     *
     * Emitted in the same `Q:`/`A:`/`X:` shape the generative path uses, so [LessonParser.parseQuiz]
     * handles both without knowing which teacher produced it.
     */
    private fun quiz(sentences: List<String>): String {
        val tokenized = sentences.map { tokens(it) }
        val idf = inverseDocumentFrequency(tokenized)
        val candidates = topicTerms(sentences, tokenized, idf)

        if (candidates.size < 4) return ""

        val builder = StringBuilder()
        var asked = 0

        // Term-first, not sentence-first. Iterating sentences and blanking each one's highest-scoring
        // word is what produced answers like "thing", "every" and "inside": every sentence yields
        // *some* word, so every sentence produced a question whether or not it had a concept in it.
        //
        // Driving the loop from the vetted term list instead means an answer is always a term that
        // passed the filter, and terms the document explicitly defines get asked about first. A
        // document that yields three good questions gets three, not five with two duds.
        for (term in candidates) {
            if (asked >= QUIZ_ITEMS) break

            val index = sentences.indices.firstOrNull { i ->
                tokenized[i].size >= MIN_QUIZ_SENTENCE_WORDS && tokenized[i].contains(term)
            } ?: continue

            // The blank has to be recoverable from what remains, not guessable from nothing.
            val stem = blankOut(sentences[index], term)
            if (stem == sentences[index]) continue

            val distractors = candidates
                .filter { it != term && !tokenized[index].contains(it) }
                .take(DISTRACTOR_POOL)
                .shuffled(kotlin.random.Random(term.hashCode()))
                .take(2)
            if (distractors.size < 2) continue

            asked++
            builder.append("Q: ").append(shorten(stem)).append('\n')
            builder.append("A: ").append(term).append('\n')
            distractors.forEach { builder.append("X: ").append(it).append('\n') }
        }

        return builder.toString().trimEnd()
    }

    // --- Shared text mechanics ---

    /**
     * Splits into candidate sentences, **after discarding titles and section headings.**
     *
     * This matters more than it looks. A heading is short, capitalised, and grammatical, so it scores
     * well on centrality and reads as a confident key point — the first version of this class happily
     * offered "What the plant needs" and the document's own title as two of five key facts.
     *
     * The rule that removes them: real prose ends in terminal punctuation, headings do not. Applied
     * line-by-line before sentence splitting, because once a heading has been glued to the paragraph
     * that follows it there is no reliable way to separate them again.
     *
     * The word-count ceiling keeps a genuinely long line that merely lost its full stop — the last
     * line of a file, say — from being thrown away with the headings.
     */
    private fun splitSentences(text: String): List<String> {
        val prose = paragraphs(text)
            // A whole paragraph with no terminal punctuation, short enough to be a label, is a
            // heading or a title. Judged per paragraph rather than per line — see [paragraphs].
            .filterNot { !it.endsWithTerminator() && it.wordCount() < HEADING_MAX_WORDS }
            .joinToString(" ")

        return prose.split(Regex("(?<=[.!?।])\\s+"))
            .map { it.trim() }
            .filter { it.length >= MIN_SENTENCE_CHARS && it.contains(' ') && it.endsWithTerminator() }
    }

    /**
     * Reflows text into paragraphs, treating a blank line as the only real break.
     *
     * **This exists because OCR does not emit paragraphs, it emits visual lines.** A photographed page
     * comes back as `"leaves, using nothing but sunlight, water"` / `"and air."` — wrapped fragments,
     * neither of which is a sentence. An earlier version judged headings line by line and therefore
     * discarded almost every line of every photographed page, producing a lesson with zero key points
     * from a perfectly good OCR result.
     *
     * Joining wrapped lines first, then judging whole paragraphs, handles both shapes: a text file
     * where blank lines separate paragraphs, and OCR output where they mostly do not.
     */
    private fun paragraphs(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (current.isNotEmpty()) {
                    result += current.toString().trim()
                    current.clear()
                }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(line)
            }
        }
        if (current.isNotEmpty()) result += current.toString().trim()
        return result
    }

    private fun String.endsWithTerminator(): Boolean = isNotEmpty() && last() in TERMINATORS

    private fun String.wordCount(): Int =
        if (isBlank()) 0 else trim().split(Regex("\\s+")).size

    private fun tokens(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }

    /**
     * The document's subject-matter vocabulary, best first.
     *
     * **Rarity alone is the wrong signal, and this is the most important lesson in this class.** The
     * first version ranked candidates by IDF only, which produced the cloze question
     * "Every living ______ needs energy to stay alive." — answer "thing", distractors "release" and
     * "tiger". Every one of those words appears exactly once, so all three scored beautifully on
     * rarity while being educationally worthless.
     *
     * A term worth testing is one the document keeps coming back to: "chlorophyll", "glucose",
     * "stomata" recur, filler nouns do not. So require a document frequency of at least two, then rank
     * the survivors by IDF. Recurrence supplies importance; IDF supplies distinctiveness.
     *
     * This also fixes the distractors for free, because they are drawn from the same pool — the wrong
     * answers become other real concepts from the same document, which is what makes a question worth
     * answering instead of a vocabulary lottery.
     *
     * Falls back to single-occurrence terms only when a document is too short to have recurring ones,
     * so a very short page still gets a quiz rather than nothing.
     */
    private fun topicTerms(
        sentences: List<String>,
        tokenized: List<List<String>>,
        idf: Map<String, Double>,
    ): List<String> {
        val documentFrequency = HashMap<String, Int>()
        for (sentence in tokenized) {
            for (token in sentence) documentFrequency[token] = (documentFrequency[token] ?: 0) + 1
        }

        fun eligible(term: String) = term.length >= MIN_TERM_LENGTH && term !in STOP_WORDS

        // Terms the document explicitly introduces come first. This is the strongest signal available
        // without a part-of-speech tagger, because textbook prose announces its own vocabulary:
        // "tiny structures called chloroplasts", "a green substance called chlorophyll", "small
        // openings called stomata". A word the author bothered to name is a word worth testing.
        val defined = LinkedHashSet<String>()
        for (sentence in sentences) {
            for (match in DEFINITION_CUE.findAll(sentence)) {
                val term = match.groupValues[2].lowercase()
                if (eligible(term)) defined += term
            }
        }

        val recurring = documentFrequency.entries
            .filter { eligible(it.key) && it.value >= MIN_TERM_OCCURRENCES && it.key !in defined }
            .sortedByDescending { idf[it.key] ?: 0.0 }
            .map { it.key }

        val ranked = defined.toList() + recurring
        if (ranked.size >= MIN_CANDIDATES) return ranked

        val singles = documentFrequency.entries
            .filter { eligible(it.key) && it.value < MIN_TERM_OCCURRENCES && it.key !in defined }
            .sortedByDescending { idf[it.key] ?: 0.0 }
            .map { it.key }
        return ranked + singles
    }

    /** Standard smoothed IDF over sentences-as-documents. */
    private fun inverseDocumentFrequency(tokenized: List<List<String>>): Map<String, Double> {
        val documentCount = tokenized.size.coerceAtLeast(1)
        val appearances = HashMap<String, Int>()
        for (document in tokenized) {
            for (token in document.toSet()) appearances[token] = (appearances[token] ?: 0) + 1
        }
        return appearances.mapValues { (_, count) -> ln(1.0 + documentCount.toDouble() / count) }
    }

    /** IDF-weighted overlap, normalised by the smaller token set so short sentences are not penalised. */
    private fun similarity(a: List<String>, b: List<String>, idf: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.toSet().intersect(b.toSet())
        if (shared.isEmpty()) return 0.0
        val weight = shared.sumOf { idf[it] ?: 0.0 }
        return weight / ln(2.0 + minOf(a.size, b.size).toDouble())
    }

    /** Replaces the first whole-word occurrence of [term], preserving the original casing elsewhere. */
    private fun blankOut(sentence: String, term: String): String =
        sentence.replaceFirst(Regex("\\b" + Regex.escape(term) + "\\b", RegexOption.IGNORE_CASE), BLANK)

    /**
     * Trims a sentence toward the length the rest of the app expects.
     *
     * The translation engine truncates long single calls, and the Hindi renderer splits over-long
     * sentences to avoid that — and split clauses translate with visibly worse grammar than whole
     * sentences. Handing the renderer something that already fits means it never has to split, which
     * is the cheapest available quality win. Cuts at a clause boundary when there is one, never
     * mid-word.
     */
    private fun shorten(sentence: String): String {
        val words = sentence.trim().split(Regex("\\s+"))
        if (words.size <= MAX_WORDS) return sentence.trim()

        val clauseEnd = words.take(MAX_WORDS).indexOfLast { it.endsWith(",") || it.endsWith(";") }
        val cut = if (clauseEnd >= MIN_CLAUSE_WORDS) clauseEnd + 1 else MAX_WORDS

        // Ends with a full stop, not an ellipsis. An ellipsis reads as honest about the truncation, but
        // it is not a sentence terminator: the Hindi renderer therefore attaches no danda, and
        // IndicTrans2 given a trailing "…" produced a question rather than a statement. A clean
        // sentence translates correctly and reads aloud correctly, which matters more here.
        return words.take(cut).joinToString(" ").trimEnd(',', ';', '.') + "."
    }

    // Not private: ExtractiveTeacherTest asserts against DEFINITION_CUE directly, so the test checks
    // the pattern the implementation actually uses rather than a copy of it that can drift.
    companion object {
        const val KEY_POINTS = 5
        const val QUIZ_ITEMS = 5
        const val ANSWER_SENTENCES = 3
        const val DISTRACTOR_POOL = 12

        const val MAX_WORDS = 18
        const val MIN_CLAUSE_WORDS = 6
        const val MIN_SENTENCE_CHARS = 25

        val TERMINATORS = charArrayOf('.', '!', '?', '।')

        /** A punctuation-free line shorter than this is a heading, not a sentence that lost its stop. */
        const val HEADING_MAX_WORDS = 12
        /** Six, not five: it alone removes "every", "thing", "parts" and most short function words. */
        const val MIN_TERM_LENGTH = 6
        const val MIN_QUIZ_SENTENCE_WORDS = 6

        /** A term the document mentions only once is trivia, not its subject. */
        const val MIN_TERM_OCCURRENCES = 2

        /** Below this many recurring terms, a document is too short to be picky. */
        const val MIN_CANDIDATES = 6
        const val MIN_ANSWER_OVERLAP = 0.15

        const val BLANK = "______"

        /**
         * Marks a term the document is introducing. Group 2 is the term itself.
         *
         * Matches "structures called chloroplasts", "a pigment known as chlorophyll", "openings named
         * stomata". Kept to explicit naming cues only — patterns like "X is a Y" match far too much
         * ordinary prose to be a useful signal.
         */
        val DEFINITION_CUE = Regex(
            """\b(called|known as|named|termed|referred to as)\s+([\p{L}]{4,})""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Function words, common verbs and generic nouns — none of which make a cloze answer worth
         * asking. Kept deliberately clear of subject vocabulary: "cell", "energy", "state", "force"
         * are stop words in some general-purpose lists and are exactly what a science document is
         * about, so they are absent here on purpose.
         *
         * This list grew from ~90 to what it is because the short version produced two bad questions
         * in a row on device: the answer "thing", then the answer "every". Function words are frequent
         * *and* recurring, so neither a length filter nor a recurrence filter excludes them — only
         * naming them does.
         */
        val STOP_WORDS = setOf(
            // determiners, pronouns, prepositions, conjunctions
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was",
            "one", "our", "out", "day", "get", "has", "him", "his", "how", "its", "may", "new", "old",
            "see", "two", "who", "did", "she", "use", "way", "many", "then", "them", "this", "that",
            "with", "have", "from", "they", "will", "would", "there", "their", "what", "about", "which",
            "when", "make", "like", "time", "just", "know", "take", "into", "year", "your", "some",
            "could", "other", "than", "also", "because", "these", "those", "such", "only", "very",
            "more", "most", "much", "been", "being", "does", "each", "were", "every", "either",
            "neither", "both", "another", "together", "through", "throughout", "between", "among",
            "during", "before", "after", "above", "below", "under", "over", "again", "once", "while",
            "where", "whether", "since", "until", "unless", "although", "however", "therefore",
            "instead", "rather", "almost", "always", "never", "often", "usually", "sometimes",
            "already", "still", "even", "back", "down", "away", "here", "along", "around", "across",
            "without", "within", "upon", "toward", "towards", "against", "itself", "themselves",
            // Prepositions and other closed-class words. Enumerating these is not whack-a-mole: the
            // class is finite and closed, and it is precisely what a stop list is for. Their absence
            // is what let "inside" become a quiz answer.
            "inside", "outside", "beside", "besides", "behind", "beneath", "underneath", "beyond",
            "onto", "into", "unto", "amongst", "whilst", "alongside", "despite", "except", "regarding",
            "concerning", "including", "following", "further", "furthermore", "moreover", "nevertheless",
            "otherwise", "meanwhile", "afterwards", "beforehand", "hence", "thus", "indeed", "perhaps",
            "maybe", "quite", "rather", "somewhat", "enough", "several", "various", "certain",
            "particular", "similar", "different", "possible", "important", "common",
            // common verbs — a blanked verb tests grammar, not understanding
            "make", "makes", "made", "making", "take", "takes", "taken", "taking", "give", "gives",
            "given", "come", "comes", "call", "calls", "called", "release", "releases", "released",
            "become", "becomes", "turns", "turned", "form", "forms", "formed", "need", "needs",
            "needed", "happen", "happens", "start", "starts", "started", "found", "look", "looks",
            "means", "meant", "must", "should", "cannot", "using", "used", "uses",
            // generic nouns
            "thing", "things", "kind", "kinds", "sort", "part", "parts", "number", "amount", "example",
            "others", "something", "anything", "everything", "place", "places", "point", "points",
        )
    }
}
