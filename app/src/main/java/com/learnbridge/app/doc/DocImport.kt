package com.learnbridge.app.doc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The outcome of [import]. Expected failures are data, not exceptions — the UI renders [Failure] directly. */
sealed class ImportResult {
    data class Success(val title: String, val text: String, val wordCount: Int) : ImportResult()
    data class Failure(val reason: Reason) : ImportResult()

    enum class Reason { UNREADABLE, EMPTY, UNSUPPORTED }
}

/**
 * Purpose:  Turns whatever a user picked (a text file, a PDF, a photo of a page) into plain text.
 *           The only place in the app that touches a content [Uri] for document ingestion.
 * Owns:     Nothing persistent — a stateless extraction step. [DocStore] owns what happens to the
 *           result.
 * Lifetime: Called once per document pick, from the importing screen's coroutine scope.
 * Thread:   [import] is suspend and hops to [Dispatchers.IO] itself; safe to call from the main thread.
 *
 * Every branch reports an expected failure as [ImportResult.Failure] instead of throwing, because the
 * UI needs to render "this PDF is a scanned image with no text layer" the same way it renders a
 * success — as data, not a crash.
 */
object DocImport {

    private const val TITLE_MAX_LEN = 60

    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val kind = classify(context, uri)
        val text = try {
            when (kind) {
                DocKind.TEXT -> extractPlainText(context, uri)
                DocKind.PDF -> extractPdf(context, uri)
                DocKind.IMAGE -> extractImageText(context, uri)
                DocKind.UNKNOWN -> return@withContext ImportResult.Failure(ImportResult.Reason.UNSUPPORTED)
            }
        } catch (e: IOException) {
            return@withContext ImportResult.Failure(ImportResult.Reason.UNREADABLE)
        } catch (e: SecurityException) {
            return@withContext ImportResult.Failure(ImportResult.Reason.UNREADABLE)
        }

        if (text.isBlank()) return@withContext ImportResult.Failure(ImportResult.Reason.EMPTY)

        val title = deriveTitle(text, context, uri)
        val wordCount = text.trim().split(Regex("\\s+")).size
        ImportResult.Success(title, text, wordCount)
    }

    // --- .txt / .md — no dependency, no failure modes beyond "can't open it". Build and debug against
    // this path first; every downstream stage (Chunker, DocStore, Retrieval) is verified against it
    // before a single PDF or image is involved. ---
    private fun extractPlainText(context: Context, uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("openInputStream returned null for $uri")
        return stream.bufferedReader().use { it.readText() }
    }

    // MemoryUsageSetting.setupTempFileOnly(): a 40-page PDF must not spike heap alongside a
    // potentially co-resident generative model (see ModelHost). PDFBoxResourceLoader.init is
    // idempotent-by-caller-count; calling it before every load is cheap and avoids a separate
    // process-scoped init hook that this package doesn't otherwise need.
    private fun extractPdf(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("openInputStream returned null for $uri")
        val document = stream.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
        return document.use { PDFTextStripper().getText(it) }
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

    private enum class DocKind { TEXT, PDF, IMAGE, UNKNOWN }

    private fun classify(context: Context, uri: Uri): DocKind {
        val mime = context.contentResolver.getType(uri)
        val name = displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
        return when {
            mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> DocKind.PDF
            mime?.startsWith("image/") == true -> DocKind.IMAGE
            mime == "text/plain" || mime == "text/markdown" ||
                name.endsWith(".txt", ignoreCase = true) || name.endsWith(".md", ignoreCase = true) -> DocKind.TEXT
            else -> DocKind.UNKNOWN
        }
    }

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
