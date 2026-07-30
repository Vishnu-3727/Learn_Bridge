package com.learnbridge.app.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The format guessing behind "import any document".
 *
 * Plain JUnit, no Robolectric: [TextExtract] deliberately holds no Android types, and this is the
 * half of importing where a mistake is silent. A wrong branch here does not crash — it produces a
 * lesson made of XML attributes, or an empty one, and the student is told the document looks empty.
 */
class TextExtractTest {

    // --- what a file is ---

    @Test
    fun `signatures identify the containers that get mislabelled`() {
        assertEquals(TextExtract.Magic.PDF, TextExtract.sniff("%PDF-1.7\n".toByteArray()))
        assertEquals(TextExtract.Magic.ZIP, TextExtract.sniff(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertEquals(TextExtract.Magic.RTF, TextExtract.sniff("{\\rtf1\\ansi".toByteArray()))
        assertEquals(TextExtract.Magic.OTHER, TextExtract.sniff("Chapter one".toByteArray()))
        // Truncated to less than a signature: must not throw, must not guess.
        assertEquals(TextExtract.Magic.OTHER, TextExtract.sniff(byteArrayOf(0x50)))
        assertEquals(TextExtract.Magic.OTHER, TextExtract.sniff(ByteArray(0)))
    }

    @Test
    fun `text is read as text and binary is refused`() {
        assertFalse(TextExtract.looksBinary("Water evaporates from the ocean.".toByteArray()))
        assertFalse(TextExtract.looksBinary("जल वाष्पित होता है।".toByteArray()))
        assertFalse(TextExtract.looksBinary("a,b,c\r\n1,2,3\r\n".toByteArray()))
        assertFalse(TextExtract.looksBinary(ByteArray(0)))

        // A NUL is the giveaway, and one is enough — this is the case that stops a student importing
        // a video and getting a lesson made of mojibake.
        assertTrue(TextExtract.looksBinary(byteArrayOf(0x4D, 0x5A, 0x00, 0x01)))
        assertTrue(TextExtract.looksBinary(ByteArray(64) { 0x1B }))
    }

    // --- which parts of a container hold words ---

    @Test
    fun `each container's text part is picked out`() {
        assertEquals(
            listOf("word/document.xml"),
            TextExtract.textEntries(
                listOf("[Content_Types].xml", "word/document.xml", "word/styles.xml", "docProps/app.xml"),
            ),
        )
        assertEquals(
            listOf("content.xml"),
            TextExtract.textEntries(listOf("mimetype", "styles.xml", "content.xml", "meta.xml")),
        )
        assertEquals(
            listOf("xl/sharedStrings.xml"),
            TextExtract.textEntries(listOf("xl/workbook.xml", "xl/sharedStrings.xml", "xl/worksheets/sheet1.xml")),
        )
        // Nothing readable in it: a zip of photos is not a document, and reporting that beats
        // importing an empty lesson.
        assertTrue(TextExtract.textEntries(listOf("DCIM/a.jpg", "DCIM/b.jpg")).isEmpty())
    }

    @Test
    fun `slides come back in slide order, not string order`() {
        val entries = TextExtract.textEntries(
            listOf(
                "ppt/slides/slide10.xml",
                "ppt/slides/slide2.xml",
                "ppt/slides/slide1.xml",
                "ppt/slideLayouts/slideLayout1.xml",
                "ppt/presentation.xml",
            ),
        )
        // The bug this guards: sorted alphabetically, slide10 lands between slide1 and slide2 and the
        // lesson teaches the deck out of order — plausible-looking and wrong.
        assertEquals(
            listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/slides/slide10.xml"),
            entries,
        )
        assertFalse("layouts are templates, not content", entries.any { it.contains("slideLayout") })
    }

    // --- markup to paragraphs ---

    @Test
    fun `word paragraphs survive as paragraphs`() {
        val docx = """
            <w:document><w:body>
            <w:p><w:pPr><w:jc w:val="both"/></w:pPr><w:r><w:t>Photosynthesis needs light.</w:t></w:r></w:p>
            <w:p><w:r><w:t>Chlorophyll</w:t></w:r><w:r><w:t xml:space="preserve"> absorbs it.</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent()

        val text = TextExtract.fromMarkup(docx)

        // Runs inside one paragraph join up; separate paragraphs stay separate, because that is what
        // chunk() splits on.
        assertEquals("Photosynthesis needs light.\n\nChlorophyll absorbs it.", text)
    }

    @Test
    fun `paragraph properties are not mistaken for paragraphs`() {
        // <w:pPr> and <w:p> differ by two characters and the regex has to tell them apart, or every
        // styled paragraph gains a spurious break in the middle of itself.
        val text = TextExtract.fromMarkup("<w:p><w:pPr><w:spacing/></w:pPr><w:r><w:t>One idea here.</w:t></w:r></w:p>")
        assertEquals("One idea here.", text)
    }

    @Test
    fun `a web page loses its script and style, not its prose`() {
        val html = """
            <html><head><style>body { color: red; }</style>
            <script>var x = 1; if (x < 2) { alert("hi"); }</script></head>
            <body><h1>Roots</h1><p>Roots absorb water.</p><p>Leaves make food.</p></body></html>
        """.trimIndent()

        val text = TextExtract.fromMarkup(html)

        assertEquals("Roots\n\nRoots absorb water.\n\nLeaves make food.", text)
    }

    @Test
    fun `entities decode, and decode once`() {
        assertEquals("H₂O & CO₂", TextExtract.decodeEntities("H&#8322;O &amp; CO&#x2082;"))
        assertEquals("a < b", TextExtract.decodeEntities("a &lt; b"))
        // "&amp;lt;" is an escaped entity: it means the literal text "&lt;", not "<". Decoding
        // &amp; first would turn it into "<" and lose the distinction.
        assertEquals("&lt;", TextExtract.decodeEntities("&amp;lt;"))
        assertEquals("no entities here", TextExtract.decodeEntities("no entities here"))
    }

    @Test
    fun `rtf control words are dropped and paragraphs kept`() {
        val rtf = """{\rtf1\ansi\deff0{\fonttbl{\f0 Arial;}}\f0\fs24 First point.\par Second point.\par}"""

        val text = TextExtract.fromRtf(rtf)

        assertTrue("lost the prose: $text", text.contains("First point."))
        assertTrue("lost the prose: $text", text.contains("Second point."))
        assertFalse("kept a control word: $text", text.contains("\\"))
        assertFalse("kept a font table: $text", text.contains("Arial"))
    }

    @Test
    fun `normalize leaves one blank line between paragraphs and no empty ones`() {
        val messy = "  Title  \n\n\n\n   Body   text\t\there \n \n Last line\n"

        assertEquals("Title\n\nBody text here\n\nLast line", TextExtract.normalize(messy))
    }

    @Test
    fun `word count matches what a thin text layer has to beat`() {
        assertEquals(0, TextExtract.wordCount("   \n  "))
        assertEquals(1, TextExtract.wordCount(" one "))
        assertEquals(5, TextExtract.wordCount("one two\tthree\nfour  five"))
    }
}
