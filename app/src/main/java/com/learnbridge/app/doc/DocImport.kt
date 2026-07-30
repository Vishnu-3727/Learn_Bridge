package com.learnbridge.app.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/** The outcome of [import]. Expected failures are data, not exceptions — the UI renders [Failure] directly. */
sealed class ImportResult {
    data class Success(val title: String, val text: String, val wordCount: Int) : ImportResult()
    data class Failure(val reason: Reason) : ImportResult()

    enum class Reason { UNREADABLE, EMPTY, UNSUPPORTED }
}

/**
 * Purpose:  Turns whatever a user picked into plain text — a text file, a PDF (text layer or
 *           scanned), a photo, a Word/PowerPoint/Excel/OpenDocument/EPUB file, HTML, RTF, or an
 *           unlabelled file that turns out to be readable. The only place in the app that touches a
 *           content [Uri] for document ingestion.
 * Owns:     Nothing persistent — a stateless extraction step. [DocStore] owns what happens to the
 *           result.
 * Lifetime: Called once per document pick, from the importing screen's coroutine scope.
 * Thread:   [import] is suspend and hops to [Dispatchers.IO] itself; safe to call from the main thread.
 *
 * Every branch reports an expected failure as [ImportResult.Failure] instead of throwing, because the
 * UI needs to render "this PDF is a scanned image with no text layer" the same way it renders a
 * success — as data, not a crash.
 *
 * **Format support is decided by signature first, extension second, MIME label last.** Content
 * providers and messaging apps mislabel routinely, and the picker now accepts everything (see
 * `LibraryActivity.IMPORTABLE_TYPES`), so an unrecognised file is *attempted* as text and only
 * refused when its bytes are not plausibly text at all. That is what makes "import any document"
 * true without a `when` branch per file extension in the world.
 */
object DocImport {

    private const val TAG = "DocImport"
    private const val TITLE_MAX_LEN = 60

    /**
     * A PDF whose text layer holds fewer words than this per page gets OCR'd instead.
     *
     * Measured, not guessed: a 51-page slide deck of semiconductor notes (126 embedded images)
     * yielded 652 words — 13 a page — because everything that mattered was *inside* the images, and
     * `PDFTextStripper` cannot see those. It taught as two sections when it should have been a
     * dozen. Ordinary prose PDFs run 200-500 words a page and keep their text layer, which is both
     * faster and more accurate than re-reading a rendered page.
     */
    private const val MIN_WORDS_PER_PAGE = 60

    /**
     * ponytail: OCR stops after this many pages. At roughly 1-2 s a page it is the difference
     * between a slow import and one a student assumes has hung — and every page OCR'd also becomes
     * more sections to generate, at ~40 s each. Raise it, or make it a setting, when someone
     * actually imports a scanned book.
     */
    private const val MAX_OCR_PAGES = 60

    /** Long edge of the bitmap each PDF page is rendered into before OCR. */
    private const val OCR_RENDER_LONG_EDGE_PX = 2000

    /**
     * Extracts the text of [uri].
     *
     * [onPage] reports OCR progress as (page, total) for a scanned PDF, which is the one extraction
     * that can run for minutes. It is called from [Dispatchers.IO]; callers that forward it into a
     * flow must use a channel-backed builder, which is why [com.learnbridge.app.teach.LessonPipeline]
     * uses `channelFlow` — a plain `flow { emit(...) }` rejects emissions from another coroutine.
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        onPage: suspend (Int, Int) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        val kind = classify(context, uri)
        val text = try {
            when (kind) {
                DocKind.TEXT -> extractPlainText(context, uri)
                DocKind.MARKUP -> TextExtract.fromMarkup(extractPlainText(context, uri))
                DocKind.RTF -> TextExtract.fromRtf(extractPlainText(context, uri))
                DocKind.ZIP_XML -> extractZippedXml(context, uri)
                DocKind.PDF -> extractPdf(context, uri, onPage)
                DocKind.IMAGE -> extractImageText(context, uri)
                DocKind.UNKNOWN -> return@withContext ImportResult.Failure(ImportResult.Reason.UNSUPPORTED)
            }
        } catch (cancellation: CancellationException) {
            // Not a failure to report — the caller went away. Rethrown so structured concurrency
            // still works; catching it below as an ordinary Exception would silently swallow it.
            throw cancellation
        } catch (e: Exception) {
            // Deliberately broad. IOException and SecurityException were the only cases handled, and
            // neither covers what the extractors actually throw: PdfBox raises its own unchecked
            // types for a malformed page tree or an encrypted file, and ML Kit resumes the
            // coroutine with whatever its recognizer failed with. Those reached the collector as a
            // crash. Every extraction failure is the same thing to a student — "no text could be
            // read from that file" — so they are reported as that, and logged for us.
            Log.w(TAG, "Extraction failed for $kind: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext ImportResult.Failure(ImportResult.Reason.UNREADABLE)
        } catch (e: OutOfMemoryError) {
            // A very large PDF can exhaust heap inside PdfBox despite the temp-file setting. The app
            // must lose the import, not the process.
            Log.w(TAG, "Out of memory extracting $kind")
            return@withContext ImportResult.Failure(ImportResult.Reason.UNREADABLE)
        }

        if (text.isBlank()) return@withContext ImportResult.Failure(ImportResult.Reason.EMPTY)

        val title = deriveTitle(text, context, uri)
        ImportResult.Success(title, text, TextExtract.wordCount(text))
    }

    // --- .txt / .md — no dependency, no failure modes beyond "can't open it". Build and debug against
    // this path first; every downstream stage (Chunker, DocStore, Retrieval) is verified against it
    // before a single PDF or image is involved. ---
    private fun extractPlainText(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("openInputStream returned null for $uri")
        return stream.bufferedReader().use { it.readText() }
    }

    /**
     * DOCX, PPTX, XLSX, ODT/ODP/ODS and EPUB — one reader, because they are all the same thing: a ZIP
     * of XML. [TextExtract.textEntries] decides which parts carry words.
     *
     * The archive is read in a single sequential pass and the wanted parts are held as strings, then
     * ordered afterwards, because `ZipInputStream` cannot seek. That costs memory proportional to the
     * document's text — a few MB for a long thesis, against the 528 MB model this app already holds.
     */
    private fun extractZippedXml(context: Context, uri: Uri): String {
        val names = readZip(context, uri) { _, _ -> null }.keys.toList()

        val wanted = TextExtract.textEntries(names)
        if (wanted.isEmpty()) {
            Log.w(TAG, "Zip carries no document part: ${names.take(5)}")
            throw IOException("not a document archive")
        }

        val parts = readZip(context, uri) { name, read -> if (name in wanted) read() else null }

        return TextExtract.normalize(
            wanted.mapNotNull { parts[it] }.joinToString("\n\n") { TextExtract.fromMarkup(it) },
        )
    }

    /**
     * Walks the archive once, returning every entry name mapped to whatever [read] made of it.
     *
     * Two passes over the file rather than one, because `ZipInputStream` cannot seek and the *set* of
     * entries decides which ones are worth reading — see [TextExtract.textEntries]. Reading a
     * 3 MB container's directory twice is cheaper than holding every part of it in memory.
     */
    private fun readZip(
        context: Context,
        uri: Uri,
        read: (name: String, content: () -> String) -> String?,
    ): Map<String, String> {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("openInputStream returned null for $uri")
        val parts = LinkedHashMap<String, String>()
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // zip.reader(), never wrapped in use(): closing that reader closes the whole
                // ZipInputStream and the walk ends after the first entry read.
                parts[entry.name] = read(entry.name) { zip.reader().readText() } ?: ""
            }
        }
        return parts
    }

    /**
     * A PDF's text layer, or an OCR of its rendered pages when that layer is nearly empty.
     *
     * The fallback is the fix for a real, measured failure: a slide deck or a scanned chapter has a
     * text layer holding page furniture and nothing else, and the app taught a 51-page document from
     * 652 words. Rendering the page and reading it back gets both the live text and the text baked
     * into diagrams, because it reads the page as a student sees it.
     *
     * `PdfRenderer` is the platform's own renderer — no new dependency, and the ML Kit recognizer is
     * already in the APK for photographed pages. The two halves of this method are therefore both
     * free; only the seconds are not.
     *
     * MemoryUsageSetting.setupTempFileOnly(): a 40-page PDF must not spike heap alongside a
     * potentially co-resident generative model (see ModelHost). PDFBoxResourceLoader.init is
     * idempotent-by-caller-count; calling it before every load is cheap and avoids a separate
     * process-scoped init hook that this package doesn't otherwise need.
     */
    private suspend fun extractPdf(
        context: Context,
        uri: Uri,
        onPage: suspend (Int, Int) -> Unit,
    ): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("openInputStream returned null for $uri")
        val document = stream.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
        // Page count and text out of one load: parsing a large PDF twice to ask two questions of it
        // is the kind of thing that turns a slow import into a failed one on a 4 GB device.
        val (layer, pageCount) = document.use { PDFTextStripper().getText(it) to it.numberOfPages }
        // A malformed page tree can report zero pages, which would make the threshold below zero and
        // silently skip the OCR fallback on exactly the broken files that need it most.
        val pages = pageCount.coerceAtLeast(1)

        val layerWords = TextExtract.wordCount(layer)
        if (layerWords >= MIN_WORDS_PER_PAGE * pages) return layer

        Log.i(TAG, "PDF text layer is thin ($layerWords words over $pages pages) — OCRing pages")
        val ocr = runCatching { ocrPdfPages(context, uri, onPage) }
            .onFailure { Log.w(TAG, "PDF OCR failed (${it.javaClass.simpleName}: ${it.message})") }
            .getOrDefault("")

        // Whichever read more. A PDF with no text layer at all makes this obvious, but a deck with
        // real prose on three slides and pictures on twenty must not lose the prose if OCR misfires.
        val ocrWords = TextExtract.wordCount(ocr)
        Log.i(TAG, "PDF: text layer $layerWords words, OCR $ocrWords words")
        return if (ocrWords > layerWords) ocr else layer
    }

    /**
     * Renders each page and reads it with the bundled recognizer.
     *
     * One recognizer for the whole document, unlike the single photographed page: constructing one
     * per page over sixty pages is not free, and the pages are read back to back.
     */
    private suspend fun ocrPdfPages(
        context: Context,
        uri: Uri,
        onPage: suspend (Int, Int) -> Unit,
    ): String {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("openFileDescriptor returned null for $uri")
        val text = StringBuilder()
        val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        try {
            descriptor.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val count = min(renderer.pageCount, MAX_OCR_PAGES)
                    if (renderer.pageCount > MAX_OCR_PAGES) {
                        Log.w(TAG, "OCR limited to $MAX_OCR_PAGES of ${renderer.pageCount} pages")
                    }
                    for (index in 0 until count) {
                        onPage(index + 1, count)
                        val rendered = renderer.openPage(index).use { page -> page.toBitmap() }
                        try {
                            val recognized = recognizer.process(InputImage.fromBitmap(rendered, 0))
                                .await()
                                .text
                            if (recognized.isNotBlank()) text.append(recognized).append("\n\n")
                        } finally {
                            // Freed page by page: sixty 2000px ARGB_8888 bitmaps are ~1 GB if held.
                            rendered.recycle()
                        }
                    }
                }
            }
        } finally {
            recognizer.close()
        }
        return TextExtract.normalize(text.toString())
    }

    /**
     * Renders one PDF page at [OCR_RENDER_LONG_EDGE_PX] on its long edge.
     *
     * `eraseColor(WHITE)` is load-bearing: `PdfRenderer` draws onto whatever the bitmap already
     * holds, which for a fresh ARGB_8888 bitmap is transparent black — OCR of dark text on that
     * returns nothing at all, silently.
     */
    private fun PdfRenderer.Page.toBitmap(): Bitmap {
        val scale = OCR_RENDER_LONG_EDGE_PX.toFloat() / maxOf(width, height).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(Color.WHITE)
        render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    /**
     * On-device OCR for a photographed page.
     *
     * Uses the bundled ML Kit Devanagari recognizer, which carries its model inside the APK rather
     * than downloading it from Play Services — that is what keeps the "works with no network and no
     * INTERNET permission" claim true. `TextRecognition`/`TextRecognizer`/`Text` arrive transitively
     * via `play-services-mlkit-text-recognition-common`, and `DevanagariTextRecognizerOptions` via
     * `play-services-mlkit-text-recognition-devanagari`; no extra dependency is required.
     *
     * `await()` rather than `Tasks.await()`: the latter blocks the calling thread until the
     * recognizer finishes, which would tie up a Dispatchers.IO worker for the whole inference.
     * Suspending hands the thread back.
     *
     * ponytail: one recognizer per import, closed after. Recognizer construction is cheap next to
     * inference; hold a process-scoped instance only if profiling ever says otherwise.
     */
    private suspend fun extractImageText(context: Context, uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        return try {
            recognizer.process(image).await().text
        } finally {
            recognizer.close()
        }
    }

    /**
     * Bridges ML Kit's [Task] to a coroutine. Written here rather than pulled in as a dependency
     * because `kotlinx-coroutines-play-services` exists solely to provide this one function.
     */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> cont.resume(result) }
        addOnFailureListener { e -> cont.resumeWithException(e) }
        addOnCanceledListener { cont.cancel() }
    }

    private enum class DocKind { TEXT, MARKUP, RTF, ZIP_XML, PDF, IMAGE, UNKNOWN }

    /**
     * Decides how to read [uri]: name and MIME label when they say something definite, the file's own
     * first bytes when they do not.
     *
     * **The byte sniff is the fallback, not the first move, and that is deliberate.** It is the only
     * thing that can identify a PDF a chat app handed over as `application/octet-stream` or a `.docx`
     * a download manager stripped the extension from — which is what used to make those files
     * "unsupported". But it costs an extra open of the stream, and for the overwhelming majority of
     * imports the name already settles it. Cheap path first, honest path always.
     *
     * Anything still unidentified is TEXT rather than UNKNOWN — see the class header — so UNKNOWN is
     * now reached only by a file whose bytes are not plausibly text at all.
     */
    private fun classify(context: Context, uri: Uri): DocKind {
        val mime = context.contentResolver.getType(uri)
        val name = displayName(context, uri) ?: uri.lastPathSegment.orEmpty()

        val labelled = when {
            mime?.startsWith("image/") == true || name.hasExtension(IMAGE_EXTENSIONS) -> DocKind.IMAGE
            mime == "application/pdf" || name.hasExtension(listOf(".pdf")) -> DocKind.PDF
            name.hasExtension(ZIP_EXTENSIONS) || mime in ZIP_MIMES -> DocKind.ZIP_XML
            mime == "application/rtf" || mime == "text/rtf" || name.hasExtension(listOf(".rtf")) -> DocKind.RTF
            mime == "text/html" || mime == "text/xml" || mime == "application/xml" ||
                name.hasExtension(MARKUP_EXTENSIONS) -> DocKind.MARKUP
            mime?.startsWith("text/") == true || name.hasExtension(TEXT_EXTENSIONS) -> DocKind.TEXT
            else -> null
        }
        if (labelled != null) return labelled

        val head = readHead(context, uri)
        when (TextExtract.sniff(head)) {
            TextExtract.Magic.PDF -> return DocKind.PDF
            TextExtract.Magic.ZIP -> return DocKind.ZIP_XML
            TextExtract.Magic.RTF -> return DocKind.RTF
            TextExtract.Magic.OTHER -> Unit
        }

        if (TextExtract.looksBinary(head)) {
            Log.w(TAG, "Not text and no known signature (mime=$mime, name=$name)")
            return DocKind.UNKNOWN
        }
        Log.i(TAG, "Unlabelled file read as text (mime=$mime, name=$name)")
        return DocKind.TEXT
    }

    /** The first [HEAD_BYTES] of [uri], or empty if it cannot be opened — never a thrown exception. */
    private fun readHead(context: Context, uri: Uri): ByteArray = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(HEAD_BYTES)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    }.getOrNull() ?: ByteArray(0)

    private fun String.hasExtension(extensions: List<String>): Boolean =
        extensions.any { endsWith(it, ignoreCase = true) }

    private const val HEAD_BYTES = 512
    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".bmp")
    private val MARKUP_EXTENSIONS = listOf(".html", ".htm", ".xhtml", ".xml", ".svg")
    private val ZIP_EXTENSIONS =
        listOf(".docx", ".pptx", ".xlsx", ".odt", ".odp", ".ods", ".epub", ".docm", ".pptm", ".xlsm")
    private val ZIP_MIMES = setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/epub+zip",
    )

    /**
     * Extensions read as plain text. Not a whitelist of what is importable — the class header covers
     * why — just the ones worth deciding without opening the file.
     */
    private val TEXT_EXTENSIONS =
        listOf(".txt", ".md", ".markdown", ".csv", ".tsv", ".json", ".log", ".srt", ".vtt", ".tex")

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        return null
    }

    /** The document's first non-blank line, truncated to ~60 chars, falling back to the Uri's display name. */
    private fun deriveTitle(text: String, context: Context, uri: Uri): String {
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val raw = firstLine ?: displayName(context, uri) ?: uri.lastPathSegment ?: "Untitled"
        return if (raw.length > TITLE_MAX_LEN) raw.take(TITLE_MAX_LEN).trimEnd() else raw
    }
}
