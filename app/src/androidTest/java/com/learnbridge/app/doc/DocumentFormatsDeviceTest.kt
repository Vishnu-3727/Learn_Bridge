package com.learnbridge.app.doc

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The import paths that only exist on a device: zipped office documents read through a real
 * ContentResolver, and the OCR fallback for a PDF whose text layer is nearly empty.
 *
 * None of this is reachable from a JVM test. [TextExtract] carries the format logic and is unit
 * tested; what is left here is `PdfRenderer`, ML Kit, and the fact that a Uri can be opened twice —
 * which is what the two-pass zip read depends on and what Robolectric cannot model.
 *
 * The PDF half needs a real scanned or slide-based PDF, which cannot be committed. Stage one and it
 * runs; without it the test logs why and passes, because an absent fixture is not a regression:
 *
 * ```
 * adb push "UNIT II.pdf" /sdcard/Android/data/com.learnbridge.app/files/ocr-test.pdf
 * adb shell am instrument -w -e class com.learnbridge.app.doc.DocumentFormatsDeviceTest \
 *   com.learnbridge.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DocumentFormatsDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun zipUri(name: String, entries: List<Pair<String, String>>): Uri {
        val file = File(context.cacheDir, name)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (entryName, content) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return Uri.fromFile(file)
    }

    @Test
    fun a_word_document_imports_as_paragraphs() = runBlocking {
        val body = listOf(
            "Nitrogen makes up seventy eight percent of the air, but plants cannot use it directly.",
            "Bacteria in the soil fix nitrogen into ammonia, which other bacteria turn into nitrates.",
        ).joinToString("") { "<w:p><w:pPr><w:spacing/></w:pPr><w:r><w:t>$it</w:t></w:r></w:p>" }

        val uri = zipUri(
            "nitrogen.docx",
            listOf(
                "[Content_Types].xml" to "<Types/>",
                "word/styles.xml" to "<styles><w:t>Heading 1</w:t></styles>",
                "word/document.xml" to "<w:document><w:body>$body</w:body></w:document>",
            ),
        )

        val result = DocImport.import(context, uri)
        assertTrue("expected Success, got $result", result is ImportResult.Success)
        val text = (result as ImportResult.Success).text
        Log.i(TAG, "docx: ${result.wordCount} words / ${text.length} chars")

        assertTrue("lost a paragraph: $text", text.contains("seventy eight percent"))
        assertTrue("lost a paragraph: $text", text.contains("turn into nitrates"))
        // styles.xml is not a document part — reading it would put stylesheet strings in the lesson.
        assertTrue("read a non-content part: $text", !text.contains("Heading 1"))
        // Two paragraphs, so one blank line between them: that is what chunk() splits on.
        assertEquals(2, text.split("\n\n").size)
    }

    @Test
    fun a_presentation_imports_its_slides_in_slide_order() = runBlocking {
        fun slide(text: String) = "<p:sld><p:cSld><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:cSld></p:sld>"

        val uri = zipUri(
            "oxidation.pptx",
            // Written out of order, and including 10 and 12, because string ordering puts those
            // between 1 and 2 and would teach the deck scrambled.
            listOf(
                "ppt/slides/slide10.xml" to slide("Tenth slide is about oxide defects."),
                "ppt/slides/slide2.xml" to slide("Second slide is about dry oxidation."),
                "ppt/slides/slide1.xml" to slide("First slide is the unit title."),
                "ppt/slides/slide12.xml" to slide("Twelfth slide is the summary."),
                "ppt/slideLayouts/slideLayout1.xml" to slide("Click to edit Master title style"),
            ),
        )

        val result = DocImport.import(context, uri)
        assertTrue("expected Success, got $result", result is ImportResult.Success)
        val text = (result as ImportResult.Success).text
        Log.i(TAG, "pptx: ${result.wordCount} words\n$text")

        val order = listOf("First slide", "Second slide", "Tenth slide", "Twelfth slide")
            .map { text.indexOf(it) }
        assertTrue("a slide is missing: $text", order.none { it < 0 })
        assertEquals("slides are out of order: $order", order.sorted(), order)
        assertTrue("read a layout template: $text", !text.contains("Master title"))
    }

    /**
     * The fix this whole change exists for: a PDF whose text lives inside its images.
     *
     * Asserts against the text layer measured on the same file rather than a fixed number — the claim
     * is "OCR reads more of this document than the stripper does", which holds for any scanned or
     * slide-based PDF and stays true for whatever file is staged.
     */
    @Test
    fun a_pdf_whose_text_is_inside_its_images_is_ocred() = runBlocking {
        val staged = File(context.getExternalFilesDir(null), "ocr-test.pdf")
        if (!staged.exists()) {
            Log.w(TAG, "No ${staged.name} staged — skipping. See this class's KDoc for the adb push.")
            return@runBlocking
        }

        PDFBoxResourceLoader.init(context)
        val (layerWords, pages) = staged.inputStream().use { input ->
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                TextExtract.wordCount(PDFTextStripper().getText(document)) to document.numberOfPages
            }
        }

        val started = System.currentTimeMillis()
        val result = DocImport.import(context, Uri.fromFile(staged)) { page, total ->
            if (page == 1 || page % 10 == 0) Log.i(TAG, "OCR page $page/$total")
        }
        val elapsed = System.currentTimeMillis() - started

        assertTrue("expected Success, got $result", result is ImportResult.Success)
        val imported = (result as ImportResult.Success).wordCount
        Log.i(
            TAG,
            "pdf: $pages pages, text layer $layerWords words, imported $imported words in ${elapsed}ms",
        )
        Log.i(TAG, "pdf first 400 chars:\n${result.text.take(400)}")

        assertTrue(
            "OCR read no more than the text layer ($imported vs $layerWords words)",
            imported > layerWords,
        )
    }

    private companion object {
        const val TAG = "DocumentFormats"
    }
}
