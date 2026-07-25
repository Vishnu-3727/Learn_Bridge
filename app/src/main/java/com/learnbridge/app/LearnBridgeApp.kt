package com.learnbridge.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.bhashabridge.app.speech.VoskModels
import com.learnbridge.app.doc.DocStore
import com.learnbridge.app.lang.LessonTranslator
import com.learnbridge.app.lang.SupportedLanguage
import com.learnbridge.app.teach.ExtractiveTeacher
import com.learnbridge.app.teach.GemmaTeacher
import com.learnbridge.app.teach.LessonPipeline
import com.learnbridge.app.teach.Teacher
import java.io.File

/**
 * Purpose:  Process entry point and the sole owner of every native resource. Hands out [ModelHost]
 *           and the speech models; nothing else in the app constructs a model.
 * Owns:     One [ModelHost], one [VoskModels].
 * Lifetime: Process.
 * Thread:   Main. [ModelHost] handles its own synchronisation.
 *
 * A hand-written service locator, not a DI framework. Three dependencies do not justify KSP, an
 * annotation processor on every build, and a graph nobody will read.
 *
 * The structural rule this class enforces: large native resources are owned at process scope, so a
 * destroyed Activity cannot orphan them and a rotation cannot trigger a multi-second model reload.
 * Activities and ViewModels borrow; they never construct or release.
 */
class LearnBridgeApp : Application() {

    val modelHost: ModelHost by lazy { ModelHost(this) }

    /** The document/artifact database. One instance per process; SQLiteOpenHelper is thread-safe. */
    val docStore: DocStore by lazy { DocStore(this) }

    /**
     * A new pipeline per ingest rather than a shared instance: it holds a [LessonTranslator], whose memo
     * is only useful within one document and would otherwise grow for the life of the process.
     */
    fun lessonPipeline(): LessonPipeline =
        LessonPipeline(this, docStore, LessonTranslator(modelHost), modelHost, targetLanguage)

    /**
     * The language lessons are rendered into. Chosen before import, because rendering happens during
     * ingest — switching afterwards means translating that document again.
     *
     * All of these come out of one 472 MB export: the target language is only the second input token,
     * so supporting six costs no extra weights and no extra memory.
     */
    var targetLanguage: SupportedLanguage
        get() = prefs.getString(KEY_TARGET_LANG, null)
            ?.let { SupportedLanguage.byCode(it) }
            ?: SupportedLanguage.DEFAULT_TARGET
        set(value) = prefs.edit().putString(KEY_TARGET_LANG, value.code).apply()

    /**
     * A content Uri the system camera app may write a captured page into.
     *
     * Returns null when the provider cannot produce one, so the caller shows "no camera available"
     * instead of crashing — page capture is a convenience, and importing a file is always available.
     */
    fun newCaptureUri(): Uri? = runCatching {
        val dir = File(filesDir, "captures").apply { mkdirs() }
        val file = File(dir, "page_${System.currentTimeMillis()}.jpg")
        FileProvider.getUriForFile(this, "$packageName.files", file)
    }.onFailure { Log.w(TAG, "Could not create a capture Uri: ${it.message}") }.getOrNull()

    /**
     * Vosk acoustic models for spoken questions. Lazy inside [VoskModels] too, so a session that
     * never uses the microphone never pays for it.
     */
    private val speechModelsLazy = lazy { VoskModels(this) }
    val speechModels: VoskModels get() = speechModelsLazy.value

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /**
     * Builds the [Teacher] appropriate to [tier]. Called by [ModelHost] with its mutex held, so this
     * may block: loading the generative model takes seconds.
     *
     * The degradation order is deliberate and every step is a working product, not an error:
     *   1. [ModelHost.Tier.EXTRACTIVE] or no staged model file  -> non-generative tutor
     *   2. Gemma loads                                          -> full generative tutor
     *   3. Gemma throws (including OutOfMemoryError)            -> degrade and remember, tutor still works
     *
     * A missing model file is a normal state, not a bug: development happens with the weights
     * `adb push`ed rather than packaged, and a fresh clone has no weights at all.
     */
    fun createTeacher(tier: ModelHost.Tier): Teacher {
        if (tier == ModelHost.Tier.EXTRACTIVE) {
            Log.i(TAG, "Tier is EXTRACTIVE — skipping the generative model")
            return fallbackTeacher()
        }

        val model = GemmaTeacher.modelFile(this)
        if (model == null) {
            Log.w(TAG, "No .task model staged — using the fallback tutor")
            return fallbackTeacher()
        }

        return try {
            GemmaTeacher.create(this, model)
        } catch (t: Throwable) {
            // Catches OutOfMemoryError too, which is the whole point: a device that cannot hold the
            // model must lose generation quality, never the feature.
            Log.e(TAG, "Generative model failed to load (${t.javaClass.simpleName}: ${t.message})")
            modelHost.degradeToExtractive()
            fallbackTeacher()
        }
    }

    /**
     * The tutor with no language model: TextRank selection, TF-IDF term weighting, cloze questions.
     * Weaker than Gemma at paraphrasing, but it cannot hallucinate and it cannot fail to load.
     */
    private fun fallbackTeacher(): Teacher = ExtractiveTeacher()

    // --- Persisted residency decisions. See ModelHost.initialTier for why these are remembered. ---

    fun noCoResidencyRemembered(): Boolean = prefs.getBoolean(KEY_NO_CO_RESIDENCY, false)

    fun rememberNoCoResidency() = prefs.edit().putBoolean(KEY_NO_CO_RESIDENCY, true).apply()

    fun extractiveOnlyRemembered(): Boolean = prefs.getBoolean(KEY_EXTRACTIVE_ONLY, false)

    fun rememberExtractiveOnly() = prefs.edit().putBoolean(KEY_EXTRACTIVE_ONLY, true).apply()

    /**
     * The single release trigger for process-lifetime native resources. Delegated to [ModelHost],
     * which decides between downgrading the tier and actually freeing memory — see its docs for why
     * that distinction matters on the main thread.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        modelHost.onTrimMemory(level)
        // Guarded rather than `speechModels.release()`: touching the property would construct the
        // models we are trying to avoid holding.
        if (speechModelsLazy.isInitialized()) {
            speechModelsLazy.value.release()
        }
    }

    private companion object {
        const val TAG = "LearnBridgeApp"
        const val PREFS = "learnbridge_prefs"
        const val KEY_NO_CO_RESIDENCY = "no_co_residency"
        const val KEY_EXTRACTIVE_ONLY = "extractive_only"
        const val KEY_TARGET_LANG = "target_language"
    }
}
