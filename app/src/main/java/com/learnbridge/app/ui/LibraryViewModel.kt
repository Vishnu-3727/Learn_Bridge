package com.learnbridge.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.learnbridge.app.LearnBridgeApp
import com.learnbridge.app.teach.IngestProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * Purpose:  Owns the one long operation the library screen starts — a 30-60 s import — so it outlives
 *           the Activity that started it.
 * Owns:     The ingest [Job] and its latest [IngestProgress].
 * Lifetime: Survives configuration changes; cancelled when the screen is finished for good.
 * Thread:   [progress] is updated from the collector on Main; the pipeline itself runs on IO.
 *
 * Why this exists: the import used to be collected on `lifecycleScope`. Backgrounding the app,
 * resizing it into multi-window or changing the system font size mid-import cancelled the flow, and
 * [com.learnbridge.app.teach.LessonPipeline.ingest]'s cancellation handler then correctly discarded
 * the half-built document — correct behaviour for a genuinely abandoned import, and a lost minute of
 * translation for someone who just checked a message.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app: LearnBridgeApp get() = getApplication()

    /** Null when no import is in flight and none is waiting to be acted on. */
    private val _progress = MutableStateFlow<IngestProgress?>(null)
    val progress: StateFlow<IngestProgress?> = _progress.asStateFlow()

    private var job: Job? = null

    /** Starts an import. A no-op while one is already running, so a double tap cannot start two. */
    fun ingest(uri: Uri) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            app.lessonPipeline()
                .ingest(uri)
                // Before flowOn, so the deletion runs on IO. Terminal for every outcome — imported,
                // failed, or cancelled with the screen — which is when the photo stops being needed.
                // A file import prunes too: it only ever finds stale captures.
                .onCompletion { app.pruneCaptures() }
                .flowOn(Dispatchers.IO)
                .collect { _progress.value = it }
        }
    }

    /**
     * Clears a terminal result once the screen has acted on it.
     *
     * [IngestProgress.Done] and [IngestProgress.Failed] are events, not states: without this, coming
     * back to the library after a rotation would reopen the lesson or re-show the error, because the
     * collector replays whatever the flow last held.
     */
    fun consumeResult() {
        _progress.value = null
    }
}
