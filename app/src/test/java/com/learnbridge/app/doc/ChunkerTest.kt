package com.learnbridge.app.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {

    private fun words(n: Int, w: String = "lorem") = List(n) { w }.joinToString(" ") + "."

    @Test
    fun `empty string yields no chunks`() {
        assertEquals(emptyList<Chunk>(), chunk(""))
    }

    @Test
    fun `whitespace-only text yields no chunks`() {
        assertEquals(emptyList<Chunk>(), chunk("   \n\n\t  \n "))
    }

    @Test
    fun `single word yields one chunk containing it`() {
        val result = chunk("Hello")
        assertEquals(1, result.size)
        assertEquals(0, result[0].ordinal)
        assertEquals("Hello", result[0].text)
    }

    @Test
    fun `text with no blank lines is treated as one paragraph`() {
        val result = chunk("line one\nline two\nline three")
        assertEquals(1, result.size)
        assertTrue(result[0].text.contains("line one"))
        assertTrue(result[0].text.contains("line three"))
    }

    @Test
    fun `a paragraph at exactly the word target stays one chunk`() {
        val result = chunk(words(180))
        assertEquals(1, result.size)
    }

    @Test
    fun `adding one more paragraph over the target starts a new chunk`() {
        val text = words(180) + "\n\n" + "extra"
        val result = chunk(text)
        assertEquals(2, result.size)
        assertEquals(0, result[0].ordinal)
        assertEquals(1, result[1].ordinal)
    }

    @Test
    fun `one sentence of overlap carries into the next chunk`() {
        val paragraphA = words(150) + " Zzzsentinel marks the end of paragraph alpha."
        val paragraphB = words(150)
        val result = chunk("$paragraphA\n\n$paragraphB")

        assertEquals(2, result.size)
        assertTrue(
            "expected chunk 1 to start with the overlap sentence, was: ${result[1].text.take(80)}",
            result[1].text.startsWith("Zzzsentinel marks the end of paragraph alpha."),
        )
        // The overlap sentence must not itself be duplicated inside chunk 0's own text.
        assertTrue(result[0].text.endsWith("Zzzsentinel marks the end of paragraph alpha."))
    }

    @Test
    fun `a single over-long paragraph splits at sentence boundaries`() {
        // 20 sentences of 20 words each = 400 words, well over the 180-word target, and no blank
        // lines at all, so this exercises the sentence-boundary split path, not the paragraph path.
        val paragraph = (1..20).joinToString(" ") { words(20) }
        val result = chunk(paragraph)

        assertTrue("expected the paragraph to split into multiple chunks, got ${result.size}", result.size > 1)
        // Chunk 0 carries no overlap, so its word count is a direct read of the packing target.
        val chunk0Words = result[0].text.trim().split(Regex("\\s+")).size
        assertTrue("chunk 0 has $chunk0Words words, expected <= 180", chunk0Words <= 180)
    }

    @Test
    fun `chunks are numbered in reading order starting at zero`() {
        val text = words(180) + "\n\n" + words(180) + "\n\n" + words(10)
        val result = chunk(text)
        result.forEachIndexed { i, c -> assertEquals(i, c.ordinal) }
    }
}
