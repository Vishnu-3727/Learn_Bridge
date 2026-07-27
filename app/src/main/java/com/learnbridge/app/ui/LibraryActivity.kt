package com.learnbridge.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.learnbridge.app.LearnBridgeApp
import com.learnbridge.app.R
import com.learnbridge.app.doc.DocStore
import com.learnbridge.app.doc.ImportResult
import com.learnbridge.app.lang.SupportedLanguage
import com.learnbridge.app.teach.IngestProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Purpose:  Launcher screen. Lists imported documents and offers the two ways in — photograph a page
 *           or import a file.
 * Owns:     Nothing. Borrows models via [LearnBridgeApp].
 * Lifetime: Activity.
 * Thread:   Main; every model and database call is dispatched off it.
 */
class LibraryActivity : AppCompatActivity() {

    private val app: LearnBridgeApp get() = application as LearnBridgeApp

    private val viewModel: LibraryViewModel by viewModels()

    private lateinit var documentList: LinearLayout
    private lateinit var emptyState: View
    private lateinit var ingestOverlay: View
    private lateinit var ingestStatus: TextView
    private lateinit var photoButton: Button
    private lateinit var importButton: Button
    private lateinit var languageChooser: TextView

    /**
     * OpenDocument rather than GetContent: it returns a durably readable Uri, and the document's
     * original text is re-readable later without a storage permission.
     */
    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { ingest(it) }
    }

    /**
     * ACTION_IMAGE_CAPTURE hands off to the system camera app, which owns preview, focus and capture.
     * No CameraX dependency, no preview surface, and — because the manifest never declares CAMERA —
     * no permission prompt.
     */
    private val capturePage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        Log.i(TAG, "capture returned: resultCode=${result.resultCode} uri=$uri")

        // The photo on disk, not the result code, decides. A camera app writes the file before it
        // returns, and some — Samsung's among the reported cases — then return something other than
        // RESULT_OK anyway. Trusting the code alone silently threw away a page the student had
        // already framed, shot and confirmed: the screen simply came back with nothing on it.
        //
        // A genuine cancel leaves no file, so it still falls through to silence, which is correct.
        when {
            uri == null -> Log.w(TAG, "Capture returned with no pending Uri — the photo is lost")
            hasContent(uri) -> ingest(uri)
            else -> Log.i(TAG, "Capture cancelled — nothing was written")
        }
    }

    /**
     * True when [uri] resolves to at least one byte. Reads a single byte rather than stat-ing a path,
     * because what came back is a content Uri and the question is only whether the camera wrote
     * anything to it.
     */
    private fun hasContent(uri: Uri): Boolean = runCatching {
        contentResolver.openInputStream(uri)?.use { it.read() != -1 } ?: false
    }.onFailure { Log.w(TAG, "Could not read back the capture: ${it.message}") }.getOrDefault(false)

    /**
     * Which file the camera was told to write. Restored in [onCreate] because the camera runs in its
     * own process and this one can be killed behind it — on the low-memory phones this app targets,
     * that is a routine event, not an edge case. Losing this field meant the result arrived with
     * nowhere to read the photo from, and the import was dropped without a word.
     */
    private var pendingCaptureUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        pendingCaptureUri = savedInstanceState?.getString(KEY_PENDING_CAPTURE)?.let(Uri::parse)

        documentList = findViewById(R.id.documentList)
        emptyState = findViewById(R.id.emptyState)
        ingestOverlay = findViewById(R.id.ingestOverlay)
        ingestStatus = findViewById(R.id.ingestStatus)
        photoButton = findViewById(R.id.photoButton)
        importButton = findViewById(R.id.importButton)

        photoButton.setOnClickListener { launchCamera() }
        importButton.setOnClickListener { pickDocument.launch(IMPORTABLE_TYPES) }

        languageChooser = findViewById(R.id.languageChooser)
        languageChooser.setOnClickListener { chooseLanguage() }
        renderLanguage()

        // repeatOnLifecycle(STARTED), not a bare launch: a Done arriving while the app is backgrounded
        // must not try to start the lesson Activity — the platform blocks background starts and the
        // navigation would simply be lost. Held in the ViewModel until the screen is visible again.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.progress.collect { onProgress(it) }
            }
        }
    }

    private fun renderLanguage() {
        val language = app.targetLanguage
        languageChooser.text = getString(R.string.teach_me_in) + ":  ${language.endonym}  ▾"
    }

    /**
     * Chosen before import rather than inside the lesson, because the lesson is rendered into this
     * language during ingest — changing it afterwards means translating the document again.
     *
     * English is offered alongside the thirteen targets even though it is the *source* language, not
     * a translation target. Picking it means the import skips the translation pass entirely: faster,
     * and the honest choice for a student who reads English comfortably and wants the tutoring rather
     * than the translating. A language can still be added later from the lesson's own chooser.
     */
    private fun chooseLanguage() {
        val options = listOf(SupportedLanguage.ENGLISH) + SupportedLanguage.targets
        val labels = options.map { it.endonym }.toTypedArray()
        val current = options.indexOf(app.targetLanguage)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.choose_language_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                app.targetLanguage = options[which]
                renderLanguage()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingCaptureUri?.let { outState.putString(KEY_PENDING_CAPTURE, it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        refreshDocuments()
    }

    private fun refreshDocuments() {
        lifecycleScope.launch {
            val rows = withDb { app.docStore.listDocuments() }
            renderDocuments(rows)
        }
    }

    private fun renderDocuments(rows: List<DocStore.DocumentRow>) {
        documentList.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (row in rows) {
            val item = inflater.inflate(R.layout.item_document, documentList, false)
            item.findViewById<TextView>(R.id.docTitle).text = row.title
            item.findViewById<TextView>(R.id.docMeta).text =
                getString(R.string.document_meta, row.wordCount)
            item.setOnClickListener { openLesson(row) }
            item.setOnLongClickListener {
                confirmDelete(row)
                true
            }
            documentList.addView(item)
        }

        val empty = rows.isEmpty()
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        documentList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun openLesson(row: DocStore.DocumentRow) {
        startActivity(
            Intent(this, LessonActivity::class.java)
                .putExtra(LessonActivity.EXTRA_DOC_ID, row.id)
                .putExtra(LessonActivity.EXTRA_DOC_TITLE, row.title),
        )
    }

    private fun confirmDelete(row: DocStore.DocumentRow) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(row.title)
            .setMessage(R.string.delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withDb { app.docStore.deleteDocument(row.id) }
                    refreshDocuments()
                }
            }
            .show()
    }

    private fun launchCamera() {
        val uri = app.newCaptureUri()
        if (uri == null) {
            Toast.makeText(this, R.string.error_capture_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        pendingCaptureUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.error_capture_unavailable, Toast.LENGTH_SHORT).show()
            pendingCaptureUri = null
            return
        }
        capturePage.launch(intent)
    }

    /**
     * Hands the import to [LibraryViewModel], which owns it from here.
     *
     * The overlay is deliberately blocking and deliberately honest about taking a while: this is the
     * one moment in the app that genuinely needs tens of seconds, because it generates the lesson and
     * its translation in a single pass so that opening the lesson later is instant. Naming each stage
     * as it happens is what makes the wait read as work rather than as a hang.
     */
    private fun ingest(uri: Uri) {
        setBusy(true)
        viewModel.ingest(uri)
    }

    private fun onProgress(progress: IngestProgress?) {
        when (progress) {
            // Nothing in flight: either no import has run, or a terminal one has been acted on.
            null -> setBusy(false)

            IngestProgress.Reading -> {
                setBusy(true)
                ingestStatus.setText(R.string.ingest_reading)
            }

            IngestProgress.Teaching -> {
                setBusy(true)
                ingestStatus.setText(R.string.ingest_thinking)
            }

            IngestProgress.Translating -> {
                setBusy(true)
                ingestStatus.text = getString(R.string.ingest_translating, app.targetLanguage.endonym)
            }

            is IngestProgress.Done -> {
                setBusy(false)
                // Consumed before navigating: this is an event, and the flow would otherwise replay
                // it and reopen the lesson every time the library came back to the foreground.
                viewModel.consumeResult()
                refreshDocuments()
                startActivity(
                    Intent(this, LessonActivity::class.java)
                        .putExtra(LessonActivity.EXTRA_DOC_ID, progress.docId)
                        .putExtra(LessonActivity.EXTRA_DOC_TITLE, progress.title),
                )
            }

            is IngestProgress.Failed -> {
                setBusy(false)
                viewModel.consumeResult()
                Toast.makeText(this, progress.reason.message(), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        ingestOverlay.visibility = if (busy) View.VISIBLE else View.GONE
        photoButton.isEnabled = !busy
        importButton.isEnabled = !busy
    }

    private fun ImportResult.Reason.message(): Int = when (this) {
        ImportResult.Reason.UNREADABLE -> R.string.ingest_failed_unreadable
        ImportResult.Reason.EMPTY -> R.string.ingest_failed_empty
        ImportResult.Reason.UNSUPPORTED -> R.string.ingest_failed_unsupported
    }

    private suspend fun <T> withDb(block: () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private companion object {
        const val TAG = "LibraryActivity"
        const val KEY_PENDING_CAPTURE = "pending_capture_uri"

        /**
         * Plain text and markdown first — the path with no extraction failure modes — plus PDF and
         * images. A text wildcard rather than "text/plain" exactly, because content providers label
         * markdown files inconsistently.
         */
        val IMPORTABLE_TYPES = arrayOf("text/*", "application/pdf", "image/*")
    }
}
