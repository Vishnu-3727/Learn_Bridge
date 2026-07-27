package com.learnbridge.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import com.learnbridge.app.doc.DocStore
import com.learnbridge.app.lang.LessonTranslator
import com.learnbridge.app.lang.SupportedLanguage
import com.learnbridge.app.teach.ExtractiveTeacher
import com.learnbridge.app.teach.GemmaTeacher
import com.learnbridge.app.teach.LessonPipeline
import com.learnbridge.app.teach.Teacher
import java.io.File

/**
 * Purpose:  Process entry point and the sole owner of every native resource. Hands out [ModelHost];
 *           nothing else in the app constructs a model.
 * Owns:     One [ModelHost].
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
     * May be [SupportedLanguage.ENGLISH], which is not a translation target but the source: the
     * import then skips the translation pass and the lesson exists in English alone until one is
     * added from the lesson screen.
     *
     * All of these come out of one 472 MB export: the target language is only the second input token,
     * so supporting six costs no extra weights and no extra memory.
     *
     * Writing this also moves the app's own chrome into that language. Every caller that changes the
     * teaching language goes through here, so the two cannot drift apart — and without it the tabs,
     * buttons and errors of an app whose thesis is "learn in your language" stay English unless the
     * whole phone is already set to that language, which on a shared or hand-me-down device it
     * usually is not.
     */
    var targetLanguage: SupportedLanguage
        get() = prefs.getString(KEY_TARGET_LANG, null)
            ?.let { SupportedLanguage.byCode(it) }
            ?: SupportedLanguage.DEFAULT_TARGET
        set(value) {
            prefs.edit().putString(KEY_TARGET_LANG, value.code).apply()
            applyChromeLanguage(value)
        }

    /**
     * Applies [language] to the UI. AppCompat persists the choice itself and restores it before the
     * first Activity of the next launch, so this is not re-applied at startup — doing that would
     * force Hindi chrome on a student who has never opened the picker, purely because Hindi is the
     * default *teaching* target.
     *
     * On API 33+ this delegates to the platform LocaleManager; below that AppCompat recreates the
     * visible Activities. Either way the caller's screen redraws in the new language.
     *
     * ponytail: there is no way back to English chrome from the in-app picker, because English is
     * the source language and never a teaching target. Settings › Apps › LearnBridge › Language
     * offers it. Add an English row here if that turns out to be where students look.
     */
    private fun applyChromeLanguage(language: SupportedLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.code))
    }

    /**
     * A content Uri the system camera app may write a captured page into.
     *
     * Returns null when the provider cannot produce one, so the caller shows "no camera available"
     * instead of crashing — page capture is a convenience, and importing a file is always available.
     */
    fun newCaptureUri(): Uri? = runCatching {
        val file = File(capturesDir.apply { mkdirs() }, "page_${System.currentTimeMillis()}.jpg")
        FileProvider.getUriForFile(this, "$packageName.files", file)
    }.onFailure { Log.w(TAG, "Could not create a capture Uri: ${it.message}") }.getOrNull()

    /**
     * Writes every (name, content) pair into `filesDir/export/` and returns shareable Uris for them.
     * Empty if anything failed — a partial export is worse than none, because the share sheet would
     * hand out a Uri for a file that is not there.
     *
     * Takes all the files at once rather than one per call, and that is the whole point of the
     * signature: the directory is emptied first, so writing the second file through a one-at-a-time
     * version deleted the first. It did exactly that, and the share silently offered a Uri pointing
     * at nothing. Clearing and writing belong in one operation.
     *
     * The directory is emptied so a stale export cannot be shared by mistake, and so a file saying
     * what the app has concluded about a student does not sit on disk indefinitely afterwards.
     */
    fun writeExports(files: List<Pair<String, String>>): List<Uri> = runCatching {
        val dir = exportDir.apply {
            listFiles()?.forEach { it.delete() }
            mkdirs()
        }
        files.map { (name, content) ->
            val file = File(dir, name)
            file.writeText(content)
            FileProvider.getUriForFile(this, "$packageName.files", file)
        }
    }.onFailure {
        Log.w(TAG, "Could not write the export: ${it.message}")
        // A half-written export is not just useless, it is a file of learner data left behind by a
        // failure nobody was told about. Clear it rather than wait for the next export to do it.
        clearExports()
    }.getOrDefault(emptyList())

    /**
     * Everything the student can ask to be forgotten, removed in one call.
     *
     * One method rather than three at the call site, and that is the point: deletion used to be
     * "empty the database, then prune the captures", and the export directory — which holds a file
     * spelling out what the app concluded about the student — was simply not on that list. A control
     * labelled "delete everything" that leaves their data on disk is the one bug in here that would
     * have mattered most. Anything added to the app that persists learner data belongs in this
     * method, not in a caller.
     */
    fun eraseAllLearnerData() {
        docStore.deleteEverything()
        pruneCaptures()
        clearExports()
    }

    private fun clearExports() {
        exportDir.listFiles()?.forEach { it.delete() }
    }

    private val exportDir: File get() = File(filesDir, "export")

    /**
     * Deletes every captured page. Ingest reads the photo once and never needs it again, and each one
     * is 2-4 MB on a device chosen for being cheap — nothing else ever removed them.
     *
     * Deletes the whole directory rather than one named file so that a capture stranded by a cancelled
     * camera intent or a process kill mid-import is collected too. Safe because the only file in here
     * that matters is the one an ingest is currently reading, and every caller runs after that.
     */
    fun pruneCaptures() {
        capturesDir.listFiles()?.forEach { it.delete() }
    }

    private val capturesDir: File get() = File(filesDir, "captures")

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
    }

    private companion object {
        const val TAG = "LearnBridgeApp"
        const val PREFS = "learnbridge_prefs"
        const val KEY_NO_CO_RESIDENCY = "no_co_residency"
        const val KEY_EXTRACTIVE_ONLY = "extractive_only"
        const val KEY_TARGET_LANG = "target_language"
    }
}
