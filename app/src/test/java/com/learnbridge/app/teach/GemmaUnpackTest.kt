package com.learnbridge.app.teach

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The unpack path runs once, on a judge's phone, on a first launch — never in the loop a developer
 * watches. So its failure modes are tested here rather than discovered there: no space, and a copy
 * that dies mid-stream leaving a partial file that must not be loaded as if it were the model.
 *
 * Robolectric only for `android.util.Log`; nothing here touches a real asset manager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GemmaUnpackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val bytes = ByteArray(4096) { (it % 251).toByte() }

    @Test
    fun `unpacks the model and leaves no partial file`() {
        val out = GemmaTeacher.unpackTo(temp.root, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

        assertEquals(File(temp.root, GemmaTeacher.MODEL_NAME), out)
        assertArrayEquals(bytes, out!!.readBytes())
        assertFalse(File(temp.root, "${GemmaTeacher.MODEL_NAME}.part").exists())
    }

    @Test
    fun `a copy that fails midway leaves nothing loadable`() {
        val out = GemmaTeacher.unpackTo(temp.root, bytes.size.toLong()) { dyingStream() }

        assertNull(out)
        assertFalse(File(temp.root, GemmaTeacher.MODEL_NAME).exists())
        assertFalse(File(temp.root, "${GemmaTeacher.MODEL_NAME}.part").exists())
    }

    @Test
    fun `refuses when the device cannot hold the model`() {
        val out = GemmaTeacher.unpackTo(temp.root, Long.MAX_VALUE) { ByteArrayInputStream(bytes) }

        assertNull(out)
        assertFalse(File(temp.root, GemmaTeacher.MODEL_NAME).exists())
    }

    /** Yields one block, then fails — the shape of a copy interrupted by a full disk. */
    private fun dyingStream(): InputStream = object : InputStream() {
        private var served = false
        override fun read(): Int = throw IOException("stream died")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served) throw IOException("stream died")
            served = true
            return minOf(len, 512).also { bytes.copyInto(b, off, 0, it) }
        }
    }
}
