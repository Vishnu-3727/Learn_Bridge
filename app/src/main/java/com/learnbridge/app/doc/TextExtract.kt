package com.learnbridge.app.doc

/**
 * Purpose:  The format knowledge behind [DocImport] — what a byte stream is, which entries of a
 *           zipped office document carry text, and how to get plain paragraphs out of markup.
 * Owns:     Nothing. Pure functions over strings and bytes.
 * Lifetime: Stateless.
 * Thread:   Any.
 *
 * Separate from [DocImport] for one reason: none of this needs a `Context`, a `Uri` or a content
 * resolver, so all of it is unit-testable on the JVM — and format guessing is exactly the code that
 * needs tests, because the failure mode is a silently empty lesson rather than a crash.
 *
 * No new dependency for any of it. DOCX, PPTX, XLSX, ODT/ODP/ODS and EPUB are all ZIP archives of
 * XML, so `java.util.zip` plus tag-stripping covers every one of them; Apache POI is megabytes of
 * dependency to read text this can read with a regex.
 */
object TextExtract {

    /** What a file actually is, decided from its first bytes rather than its name or MIME label. */
    enum class Magic { PDF, ZIP, RTF, OTHER }

    /**
     * Classifies [head] (the first few bytes of a file) by signature.
     *
     * Content pickers and messaging apps mislabel constantly — a document arriving as
     * `application/octet-stream` is normal, and a file shared without an extension has nothing else
     * to go on. The signature is the one thing that cannot be wrong.
     */
    fun sniff(head: ByteArray): Magic = when {
        head.startsWith("%PDF") -> Magic.PDF
        // Local file header. Every OOXML/ODF/EPUB container starts with one.
        head.startsWith("PK") -> Magic.ZIP
        head.startsWith("{\\rt") -> Magic.RTF
        else -> Magic.OTHER
    }

    private fun ByteArray.startsWith(prefix: String): Boolean {
        if (size < prefix.length) return false
        return prefix.indices.all { this[it].toInt() and 0xFF == prefix[it].code }
    }

    /**
     * True when [bytes] are not plausibly text in any encoding this app can read.
     *
     * The last resort of [DocImport]: an unrecognised file is *tried* as text rather than refused,
     * because "any document" includes the `.tex`, `.srt`, `.csv` and extensionless notes nobody will
     * enumerate in a `when`. That only works if binary rubbish is caught here — otherwise a student
     * imports a video and gets a lesson made of mojibake.
     *
     * ponytail: a NUL byte or a lot of control bytes, nothing cleverer. UTF-16 text is full of NULs
     * and will be rejected; add a BOM check if that ever shows up in practice.
     */
    fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var control = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0) return true
            if (v < 0x09 || v in 0x0E..0x1F) control++
        }
        return control * 100 / bytes.size > 5
    }

    /**
     * The archive entries that carry a document's readable text, in reading order.
     *
     * One function rather than a class per format: every container here answers the same question
     * ("which XML holds the words?") and the answer is a name pattern. Empty means "this zip is not
     * a document" — a zip of holiday photos, say — which [DocImport] reports as unsupported.
     *
     * ponytail: EPUB chapters come back in name order, not spine order from the OPF. Alphabetical is
     * right for every generator that numbers its files and wrong for the ones that do not; parse
     * `content.opf` if a real book ever comes out shuffled.
     */
    fun textEntries(names: List<String>): List<String> {
        val byLowerName = names.associateBy { it.lowercase() }

        byLowerName["word/document.xml"]?.let { return listOf(it) }

        val slides = names.filter { SLIDE.matches(it.lowercase()) }
        if (slides.isNotEmpty()) return slides.sortedBy { trailingNumber(it) }

        // ODF (ODT/ODP/ODS) puts the whole document in one part.
        byLowerName["content.xml"]?.let { return listOf(it) }

        // A spreadsheet's words live in the shared string table; the sheets themselves are mostly
        // numbers and formulas, which are not study material.
        byLowerName["xl/sharedstrings.xml"]?.let { return listOf(it) }

        return names.filter { name ->
            val lower = name.lowercase()
            (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) &&
                !lower.contains("nav")
        }.sorted()
    }

    private val SLIDE = Regex("ppt/slides/slide\\d+\\.xml")

    /** The last run of digits in [name], so slide2 orders before slide10. */
    private fun trailingNumber(name: String): Int =
        Regex("(\\d+)(?!.*\\d)").find(name)?.value?.toIntOrNull() ?: 0

    /**
     * Plain paragraphs out of any XML or HTML: OOXML, ODF, XHTML chapters, a saved web page.
     *
     * Paragraph *boundaries* are the point, not just tag removal. [chunk] splits on blank lines and
     * packs the pieces to a word budget, so markup whose every `</w:p>` collapsed to nothing would
     * arrive as one 4,000-word paragraph and be split mid-sentence instead of between ideas.
     */
    fun fromMarkup(markup: String): String {
        val text = markup
            // Before anything else: a saved web page carries kilobytes of JavaScript and CSS between
            // ordinary tags, and stripping tags alone would leave all of it in the "text".
            .replace(SCRIPT_OR_STYLE, " ")
            .replace(TAB_TAGS, " ")
            .replace(BLOCK, "\n\n")
            .replace(ANY_TAG, "")
        return normalize(decodeEntities(text))
    }

    /**
     * Plain text out of RTF.
     *
     * ponytail: control words and `\'hh` escapes are dropped, so accented Latin-1 text loses its
     * accents and anything non-Latin is lost. RTF is a 1987 interchange format that turns up as the
     * odd exported note; a real parser is not worth its size until one arrives.
     */
    fun fromRtf(rtf: String): String {
        val text = dropRtfMetadata(rtf)
            .replace(RTF_PARAGRAPH, "\n\n")
            .replace(RTF_CONTROL, " ")
            .replace(RTF_BRACE, "")
        return normalize(text)
    }

    /**
     * Removes the RTF groups that hold no body text — the font and colour tables, the stylesheet, the
     * document properties, and every `\*\` extension group.
     *
     * A brace-depth scan rather than a regex, and not for elegance: these groups nest (`{\fonttbl{\f0
     * Arial;}}`), and a regex that stops at the first `}` leaves "Arial;" in the lesson while one that
     * stops at the last eats the document. Fifteen lines of counting is the smaller risk.
     */
    private fun dropRtfMetadata(rtf: String): String {
        val out = StringBuilder(rtf.length)
        var index = 0
        while (index < rtf.length) {
            val char = rtf[index]
            if (char == '{' && SKIPPED_GROUP.containsMatchIn(rtf.substring(index, minOf(index + 14, rtf.length)))) {
                var depth = 0
                while (index < rtf.length) {
                    if (rtf[index] == '{') depth++
                    if (rtf[index] == '}') {
                        depth--
                        if (depth == 0) {
                            index++
                            break
                        }
                    }
                    index++
                }
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }

    private val SKIPPED_GROUP =
        Regex("^\\{\\\\(\\*|fonttbl|colortbl|stylesheet|info|listtable|revtbl|generator)")

    /**
     * Collapses whitespace and drops empty lines, leaving one blank line between paragraphs.
     *
     * Every extracted line becomes its own paragraph, which reads as over-splitting and is not:
     * [chunk] packs paragraphs up to its word budget, so more and smaller units only ever improve
     * where a chunk boundary lands.
     */
    fun normalize(text: String): String = text
        .lineSequence()
        .map { it.replace(SPACES, " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

    /** The handful of entities that appear in real OOXML and HTML, plus every numeric one. */
    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        return NUMERIC_ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            val code = if (body.startsWith("x", ignoreCase = true)) {
                body.drop(1).toIntOrNull(16)
            } else {
                body.toIntOrNull()
            }
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            // Last, so "&amp;lt;" decodes to the text "&lt;" and not to "<".
            .replace("&amp;", "&")
    }

    /** Words, counted the way [DocImport] counts them, for "is this text layer worth anything". */
    fun wordCount(text: String): Int {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) 0 else trimmed.split(WHITESPACE).size
    }

    private val SCRIPT_OR_STYLE = Regex(
        "<(script|style)\\b[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    // `\b` is what keeps <w:p> from also matching <w:pPr> (paragraph properties, which carry no
    // text): there is no word boundary between "p" and "P".
    private val BLOCK = Regex(
        "</?(w:p|a:p|text:p|text:h|w:br|a:br|text:line-break|p|div|li|tr|h[1-6]|br)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val TAB_TAGS = Regex("<(w:tab|text:tab|text:s)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ANY_TAG = Regex("<[^>]*>")
    private val NUMERIC_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")
    private val SPACES = Regex("[ \\t\\u00A0\\u2028\\u2029]+")
    private val WHITESPACE = Regex("\\s+")

    private val RTF_PARAGRAPH = Regex("\\\\(par|line|pard)\\b")
    private val RTF_CONTROL = Regex("\\\\[a-zA-Z]+-?\\d* ?|\\\\'[0-9a-fA-F]{2}|\\\\[*\\\\{}]")
    private val RTF_BRACE = Regex("[{}]")
}
