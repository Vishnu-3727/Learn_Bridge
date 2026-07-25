package com.learnbridge.app.doc

/** One retrieval-sized unit of a document. [ordinal] is its position in reading order, starting at 0. */
data class Chunk(val ordinal: Int, val text: String)

private const val TARGET_WORDS = 180

/** An overlap "sentence" this long or longer is punctuation-free noise, not a real sentence — cap it. */
private const val OVERLAP_WORD_CAP = 25

private val BLANK_LINE = Regex("\n\\s*\n+")
private val WHITESPACE = Regex("\\s+")

// Splits after a sentence terminator followed by whitespace. Good enough for the prose this ingests
// (explanations, textbook excerpts) — it is not a general-purpose sentence tokenizer.
private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")

/**
 * Purpose:  Splits extracted document text into retrieval-sized chunks. The only place in the app
 *           that decides how a document is cut up for FTS indexing and prompt assembly.
 * Owns:     Nothing — pure function over a [String].
 * Lifetime: Stateless; called once per import, off the main thread (caller's responsibility).
 * Thread:   Pure and reentrant. Safe from any thread.
 *
 * Paragraph-greedy, not a configurable recursive splitter: paragraphs are the natural unit a writer
 * already chose, so preserving them costs nothing and reads better than a fixed-width window. 180
 * words is ~250 tokens, so four chunks is ~1000 tokens — what fits the generative model's usable
 * context budget next to the prompt scaffold (see ModelHost's decoder ceiling; this is the
 * retrieval-side half of that same budget).
 *
 * One sentence of overlap is carried from the end of chunk N into the start of chunk N+1, so a fact
 * that lands right on a chunk boundary is still findable from either side.
 */
fun chunk(text: String): List<Chunk> {
    val paragraphs = text.split(BLANK_LINE).map { it.trim() }.filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) return emptyList()

    // Flatten to units no larger than TARGET_WORDS: a short paragraph is one unit; an over-long
    // paragraph is pre-split at sentence boundaries into several.
    val units = paragraphs.flatMap { p ->
        if (wordCount(p) <= TARGET_WORDS) listOf(p) else splitLongParagraph(p)
    }

    val chunks = mutableListOf<Chunk>()
    var currentParts = mutableListOf<String>()
    var currentWords = 0
    var previousBody = "" // previous chunk's own content, pre-overlap — the overlap source

    fun flush() {
        if (currentParts.isEmpty()) return
        val body = currentParts.joinToString("\n\n")
        val overlap = if (chunks.isEmpty()) "" else lastSentence(previousBody)
        val fullText = if (overlap.isEmpty()) body else "$overlap $body"
        chunks += Chunk(chunks.size, fullText)
        previousBody = body
        currentParts = mutableListOf()
        currentWords = 0
    }

    for (unit in units) {
        val words = wordCount(unit)
        if (currentWords > 0 && currentWords + words > TARGET_WORDS) flush()
        currentParts += unit
        currentWords += words
    }
    flush()

    return chunks
}

/** Packs a single over-long paragraph's sentences into pieces of up to [TARGET_WORDS] words each. */
private fun splitLongParagraph(paragraph: String): List<String> {
    val sentences = paragraph.split(SENTENCE_BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }
    val pieces = mutableListOf<String>()
    var current = mutableListOf<String>()
    var words = 0
    for (s in sentences) {
        val sw = wordCount(s)
        if (words > 0 && words + sw > TARGET_WORDS) {
            pieces += current.joinToString(" ")
            current = mutableListOf()
            words = 0
        }
        current += s
        words += sw
    }
    if (current.isNotEmpty()) pieces += current.joinToString(" ")
    return pieces
}

/** The last sentence of [text], capped at [OVERLAP_WORD_CAP] words for punctuation-free runs. */
private fun lastSentence(text: String): String {
    val sentences = text.trim().split(SENTENCE_BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }
    val last = sentences.lastOrNull() ?: return ""
    val words = last.split(WHITESPACE)
    return if (words.size > OVERLAP_WORD_CAP) words.takeLast(OVERLAP_WORD_CAP).joinToString(" ") else last
}

private fun wordCount(s: String): Int {
    val trimmed = s.trim()
    return if (trimmed.isEmpty()) 0 else trimmed.split(WHITESPACE).size
}
