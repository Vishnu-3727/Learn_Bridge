package com.learnbridge.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.learnbridge.app.LearnBridgeApp
import com.learnbridge.app.R
import com.learnbridge.app.doc.DocStore
import com.learnbridge.app.doc.ImportResult
import com.learnbridge.app.lang.SupportedLanguage
import com.learnbridge.app.teach.IngestProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
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
        if (result.resultCode == RESULT_OK && uri != null) ingest(uri)
    }

    private var pendingCaptureUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

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
    }

    private fun renderLanguage() {
        val language = app.targetLanguage
        languageChooser.text = getString(R.string.teach_me_in) + ":  ${language.endonym}  ▾"
    }

    /**
     * Chosen before import rather than inside the lesson, because the lesson is rendered into this
     * language during ingest — changing it afterwards means translating the document again.
     */
    private fun chooseLanguage() {
        val options = SupportedLanguage.targets
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
     * Runs the whole ingest with the overlay up.
     *
     * The overlay is deliberately blocking and deliberately honest about taking a while: this is the
     * one moment in the app that genuinely needs tens of seconds, because it generates the lesson and
     * its Hindi in a single pass so that opening the lesson later is instant. Naming each stage as it
     * happens is what makes the wait read as work rather than as a hang.
     */
    private fun ingest(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch {
            app.lessonPipeline()
                .ingest(uri)
                // Before flowOn, so the deletion runs on IO. Terminal for every outcome — imported,
                // failed, or cancelled by leaving the screen — which is exactly when the photo stops
                // being needed. A file import prunes too: it only ever finds stale captures.
                .onCompletion { app.pruneCaptures() }
                .flowOn(Dispatchers.IO)
                .collect { progress -> onProgress(progress) }
        }
    }

    private fun onProgress(progress: IngestProgress) {
        when (progress) {
            IngestProgress.Reading -> ingestStatus.setText(R.string.ingest_reading)
            IngestProgress.Teaching -> ingestStatus.setText(R.string.ingest_thinking)
            IngestProgress.Translating ->
                ingestStatus.text = getString(R.string.ingest_translating, app.targetLanguage.endonym)

            is IngestProgress.Done -> {
                setBusy(false)
                refreshDocuments()
                startActivity(
                    Intent(this, LessonActivity::class.java)
                        .putExtra(LessonActivity.EXTRA_DOC_ID, progress.docId)
                        .putExtra(LessonActivity.EXTRA_DOC_TITLE, progress.title),
                )
            }

            is IngestProgress.Failed -> {
                setBusy(false)
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
        /**
         * Plain text and markdown first — the path with no extraction failure modes — plus PDF and
         * images. A text wildcard rather than "text/plain" exactly, because content providers label
         * markdown files inconsistently.
         */
        val IMPORTABLE_TYPES = arrayOf("text/*", "application/pdf", "image/*")
    }
}
