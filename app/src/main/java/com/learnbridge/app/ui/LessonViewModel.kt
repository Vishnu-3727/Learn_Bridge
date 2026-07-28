package com.learnbridge.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.learnbridge.app.LearnBridgeApp
import com.learnbridge.app.doc.Mastery
import com.learnbridge.app.doc.Retrieval
import com.learnbridge.app.lang.LessonTranslator
import com.learnbridge.app.lang.SupportedLanguage
import com.learnbridge.app.teach.LessonParser
import com.learnbridge.app.teach.LessonPipeline
import com.learnbridge.app.teach.Prompts
import com.learnbridge.app.teach.TeachRequest
import com.learnbridge.app.teach.QuizItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the three panes is on screen. */
enum class LessonTab { EXPLAIN, ASK, QUIZ }

/**
 * Every fact [LessonActivity] needs to draw one frame. Immutable — a new state is a new instance,
 * never a mutation, so `render(state)` can always trust what it is handed.
 */
data class LessonUiState(
    val docId: Long,
    val docTitle: String,
    val tab: LessonTab = LessonTab.EXPLAIN,
    /** The language the Explain/Quiz artifacts are requested in, and the one an Ask answer is
     *  rendered into once generated — see [LessonViewModel.sendQuestion]. */
    val lang: String = LessonPipeline.LANG_EN,
    /**
     * Whether this document has artifacts in its target language at all. Gates the toggle; see
     * [LessonViewModel.refreshTranslationAvailability]. Named for the language generally, not Hindi:
     * a document may have been imported in any of the thirteen.
     */
    val translationAvailable: Boolean = false,
    /**
     * The non-English language the toggle currently switches to. Read from the document's own rows,
     * so a lesson imported in Marathi keeps showing Marathi even if the app's preference later
     * changes; changed by [LessonViewModel.chooseLanguage] when a second language is added.
     */
    val translationLang: String = LessonPipeline.LANG_HI,
    /** Every non-English language this document already holds, so the chooser can mark them ready. */
    val renderedLangs: List<String> = emptyList(),
    /** The language being rendered right now, or null. Non-null blocks a second render and is what
     *  puts the wait on screen — this is a model load plus every fragment of every artifact. */
    val rendering: String? = null,
    /** One-shot: the last chosen language produced no rows. Cleared by [LessonViewModel.consumeRenderFailure]. */
    val renderFailed: Boolean = false,
    val explain: ExplainUi = ExplainUi(),
    val ask: AskUi = AskUi(),
    val quiz: QuizUi = QuizUi(),
    /** What the app has learned about this student on this document, or null before their first quiz. */
    val mastery: Mastery? = null,
)

/** The Explain pane: a database read, never a generation, so there is no "loading" variant. */
data class ExplainUi(
    val points: List<String> = emptyList(),
    /** The language actually shown, which may differ from [LessonUiState.lang] — see [fellBack]. */
    val displayedLang: String = LessonPipeline.LANG_EN,
    /** True when the requested language had no rows and [points] is the other language's instead. */
    val fellBack: Boolean = false,
)

data class AskUi(
    val question: String = "",
    val output: AskOutput = AskOutput.Empty,
) {
    /** True while a question is in flight, for disabling the send button. */
    val busy: Boolean get() = output is AskOutput.InProgress ||
        output is AskOutput.Streaming ||
        output is AskOutput.Rendering
}

/** The Ask pane's result, one state per stage of a single question. */
sealed interface AskOutput {
    data object Empty : AskOutput
    data object InProgress : AskOutput
    data object Streaming : AskOutput

    /** The generated English answer is being rendered into [lang]. A visible state rather than a
     *  silent pause because it can cost a model swap — see [LessonViewModel.sendQuestion]. */
    data class Rendering(val lang: String) : AskOutput

    /**
     * [answer] is null when [LessonParser.parseAnswer] decided the document does not cover it.
     * [lang] is the language [answer] is actually written in, which stays English when translation
     * was not asked for or did not succeed — the Listen button reads it to pick a voice.
     */
    data class Final(val answer: String?, val lang: String = LessonPipeline.LANG_EN) : AskOutput
    data class Failed(val message: String) : AskOutput
}

/** True only for the specific "document doesn't cover this" outcome, not any other terminal state. */
fun AskOutput.isNoAnswer(): Boolean = this is AskOutput.Final && answer == null

data class QuizUi(
    val items: List<QuizItem> = emptyList(),
    val currentIndex: Int = 0,
    val score: Int = 0,
    val selectedIndex: Int? = null,
    val answered: Boolean = false,
    /** When the current question was first shown. The clock behind [latenciesMs]. */
    val shownAt: Long = 0L,
    /** How long each answered question took, in order. Feeds the Twin's confidence estimate. */
    val latenciesMs: List<Long> = emptyList(),
) {
    val total: Int get() = items.size
    val isDone: Boolean get() = items.isNotEmpty() && currentIndex >= items.size

    /**
     * The question on screen, or null when there is none — an empty quiz, or one already finished.
     *
     * Every read of [items] goes through this. Indexing directly is what let an empty quiz reach
     * `items[0]`: [isDone] is false when there are no items at all, so a guard written in terms of
     * it does not cover the one case where there is nothing to index.
     */
    val current: QuizItem? get() = items.getOrNull(currentIndex)

    /**
     * The middle answer time, or 0 when nothing has been answered.
     *
     * Median rather than mean: one question interrupted by a phone call would drag an average into
     * "not confident" and misrepresent the whole quiz.
     */
    val medianLatencyMs: Long
        get() = latenciesMs.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }

    /**
     * Records a tap on option [selected] of the current question. Pure — no I/O; the caller
     * ([LessonViewModel.answerQuiz]) is responsible for persisting the result alongside this.
     * A no-op once the current question is already answered, or once the quiz is done, so a
     * double-tap can never double-count a score.
     */
    fun answer(selected: Int, now: Long = System.currentTimeMillis()): QuizUi {
        if (answered) return this
        // Not `isDone`: that is false for a quiz with no questions, which is exactly the state where
        // there is nothing to index. Asking for the current question answers both cases at once.
        val question = current ?: return this
        val (_, correctIndex) = question.shuffledOptions()
        // shownAt is 0 for a quiz restored without a display timestamp; recording a latency measured
        // from the epoch would report roughly fifty-six years of hesitation.
        val elapsed = if (shownAt > 0) now - shownAt else 0L
        return copy(
            selectedIndex = selected,
            answered = true,
            score = if (selected == correctIndex) score + 1 else score,
            latenciesMs = if (elapsed > 0) latenciesMs + elapsed else latenciesMs,
        )
    }

    /**
     * Moves to the next question, resetting per-question state. A no-op before an answer is given —
     * which also covers the empty quiz, since nothing there can ever be answered.
     */
    fun next(now: Long = System.currentTimeMillis()): QuizUi =
        if (!answered) this
        else copy(currentIndex = currentIndex + 1, selectedIndex = null, answered = false, shownAt = now)
}

/**
 * The language an answer should be rendered into: the one the lesson is being read in, or null when
 * that is English — or a code no longer in [SupportedLanguage] — and the model's own English output
 * already suits. Pure, so the decision is testable without an engine.
 */
internal fun answerTarget(lang: String): SupportedLanguage? =
    SupportedLanguage.byCode(lang)?.takeIf { it != SupportedLanguage.ENGLISH }

/** The decision behind the language toggle and Explain's fallback: what to show, and in what language. */
data class FallbackResult<T>(val rows: List<T>, val lang: String, val fellBack: Boolean)

/**
 * Picks [primary] when it has rows, otherwise [fallback] — pure so the fallback decision (the one
 * DB-free rule this screen has) is unit-testable without a database.
 */
fun <T> resolveFallback(
    requestedLang: String,
    otherLang: String,
    primary: List<T>,
    fallback: List<T>,
): FallbackResult<T> = when {
    primary.isNotEmpty() -> FallbackResult(primary, requestedLang, fellBack = false)
    fallback.isNotEmpty() -> FallbackResult(fallback, otherLang, fellBack = true)
    else -> FallbackResult(emptyList(), requestedLang, fellBack = false)
}

/**
 * Purpose:  All business logic for the lesson screen — Explain's DB read, Ask's retrieve-then-stream,
 *           Quiz's advance/score — behind one [state] flow. [LessonActivity] only renders it.
 * Owns:     Nothing durable; borrows [com.learnbridge.app.doc.DocStore] and
 *           [com.learnbridge.app.ModelHost] through [LearnBridgeApp], same as every other screen.
 * Lifetime: One per Activity instance, survives rotation (AndroidViewModel).
 * Thread:   [state] and [tokens] may be updated from any dispatcher; both are thread-safe. Every
 *           database or model call here is dispatched off Main before touching either.
 *
 * Streamed tokens are deliberately NOT part of [state]: folding one token per emission into the
 * state class would re-render the whole screen at generation speed (10-40 times a second) for a
 * change only one TextView cares about. [tokens] carries just the raw text; [LessonActivity] appends
 * it directly.
 */
class LessonViewModel(application: Application) : AndroidViewModel(application) {

    private val app: LearnBridgeApp get() = getApplication()
    private val docStore get() = app.docStore
    private val modelHost get() = app.modelHost

    private val _state = MutableStateFlow(LessonUiState(docId = NO_DOC, docTitle = ""))
    val state: StateFlow<LessonUiState> = _state.asStateFlow()

    /** One per screen, not per question, so its memo spans a whole session of asking. */
    private val translator by lazy { LessonTranslator(modelHost) }

    private val _tokens = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val tokens: SharedFlow<String> = _tokens.asSharedFlow()

    /** Called once from `onCreate`. Re-entrant: a rotation calls this again with the same doc, and
     *  that must not reset quiz progress or re-hit the database for no reason. */
    fun start(docId: Long, docTitle: String) {
        if (_state.value.docId == docId) return
        _state.value = LessonUiState(docId = docId, docTitle = docTitle)
        loadExplain()
        loadQuiz()
        refreshTranslationAvailability()
        loadMastery()
    }

    private fun loadMastery() {
        val docId = _state.value.docId
        viewModelScope.launch(Dispatchers.IO) {
            val record = runCatching { docStore.mastery(docId) }.getOrNull()
            _state.update { it.copy(mastery = record) }
        }
    }

    fun selectTab(tab: LessonTab) {
        _state.update { it.copy(tab = tab) }
    }

    fun updateQuestion(text: String) {
        _state.update { it.copy(ask = it.ask.copy(question = text)) }
    }

    /**
     * Swaps which language Explain/Quiz read. A plain DB read on both — no model is touched, which is
     * exactly why this can be instant: the Hindi was rendered once at import (see [LessonPipeline]),
     * never on demand.
     */
    fun toggleLanguage() {
        val current = _state.value
        if (!current.translationAvailable) return
        val newLang = otherLang(current.lang)
        _state.update { it.copy(lang = newLang) }
        reloadPanes()
    }

    /**
     * Re-reads both panes after the language changed underneath them.
     *
     * Always with `preserveProgress`, because every caller is a language change and none of them is a
     * reason to lose a half-finished quiz — the questions are being reworded, not replaced.
     */
    private fun reloadPanes() {
        loadExplain()
        loadQuiz(preserveProgress = true)
    }

    private fun loadExplain() {
        val (docId, lang) = _state.value.let { it.docId to it.lang }
        viewModelScope.launch(Dispatchers.IO) {
            val resolved = readWithFallback(docId, LessonPipeline.KIND_EXPLANATION, lang) { it }
            _state.update {
                it.copy(explain = ExplainUi(resolved.rows, resolved.lang, resolved.fellBack))
            }
        }
    }

    /**
     * Reads one kind of artifact in [lang], falling back to the other language when that has no rows.
     *
     * The fallback query only runs when the first came back empty, which is the whole reason this is
     * not two unconditional reads: on the common path — a document that has the language being asked
     * for — it costs exactly one query, same as before.
     *
     * [decode] turns stored rows into whatever the pane wants; Explain keeps the strings, Quiz parses
     * them and drops anything unreadable.
     *
     * **Emptiness is judged on the stored rows, before [decode], deliberately.** A language that has
     * rows has been rendered into, whether or not every one of them still parses, and treating an
     * unparseable row as an absent language would quietly show the other language's content under a
     * heading claiming this one.
     */
    private fun <T> readWithFallback(
        docId: Long,
        kind: String,
        lang: String,
        decode: (List<String>) -> List<T>,
    ): FallbackResult<T> {
        val other = otherLang(lang)
        val primaryRows = docStore.artifacts(docId, kind, lang)
        val fallbackRows = if (primaryRows.isEmpty()) docStore.artifacts(docId, kind, other) else emptyList()
        return resolveFallback(lang, other, decode(primaryRows), decode(fallbackRows))
    }

    /**
     * [preserveProgress] carries score and position across a language toggle mid-quiz — swapping
     * which language a question is worded in should not cost the student their progress through it.
     *
     * **It deliberately does not carry the current selection.** [QuizItem.shuffledOptions] seeds its
     * shuffle from the question text, so the options of the translated question come back in a
     * different order. Keeping `selectedIndex` across that meant the tick and cross were drawn
     * against the *previous* permutation: after toggling, the ✓ jumped to another row and the ✗ could
     * land on an option the student never touched. The score was already banked and stayed correct,
     * which made it read as the app having forgotten the answer.
     */
    private fun loadQuiz(preserveProgress: Boolean = false) {
        val (docId, lang) = _state.value.let { it.docId to it.lang }
        viewModelScope.launch(Dispatchers.IO) {
            val resolved = readWithFallback(docId, LessonPipeline.KIND_QUIZ, lang) { rows ->
                rows.mapNotNull { QuizItem.decode(it) }
            }
            _state.update {
                val quiz = if (preserveProgress) {
                    // Only what is language-independent. See this function's header for why the
                    // per-question selection cannot come along. Latencies do: how long the student
                    // took to answer is a fact about them, not about the language it was shown in.
                    QuizUi(
                        items = resolved.rows,
                        currentIndex = it.quiz.currentIndex,
                        score = it.quiz.score,
                        shownAt = System.currentTimeMillis(),
                        latenciesMs = it.quiz.latenciesMs,
                    )
                } else {
                    QuizUi(items = resolved.rows, shownAt = System.currentTimeMillis())
                }
                it.copy(quiz = quiz)
            }
        }
    }

    /**
     * Doc-level: which non-English languages this document holds. Checked once per doc, not per pane,
     * so the toggle can be disabled up front rather than discovered broken after a tap.
     *
     * [preferred] keeps a language the student just chose selected; otherwise the first rendered one
     * wins, which for a document with a single translation is the one it was imported with.
     */
    private fun refreshTranslationAvailability(preferred: String? = null) {
        val docId = _state.value.docId
        viewModelScope.launch(Dispatchers.IO) {
            // Which languages this document actually carries, read from its rows rather than from the
            // app's current preference — a document keeps whatever it was rendered into.
            val codes = docStore.translationLanguages(docId)
            val selected = preferred?.takeIf { it in codes } ?: codes.firstOrNull()
            val explain = selected?.let { docStore.artifacts(docId, LessonPipeline.KIND_EXPLANATION, it) }.orEmpty()
            val quiz = selected?.let { docStore.artifacts(docId, LessonPipeline.KIND_QUIZ, it) }.orEmpty()
            val available = explain.isNotEmpty() || quiz.isNotEmpty()
            _state.update {
                it.copy(
                    translationAvailable = available,
                    translationLang = selected ?: LessonPipeline.LANG_HI,
                    renderedLangs = codes,
                )
            }
        }
    }

    /**
     * Renders this document into [language] and shows it, or just switches to it when the rows are
     * already there.
     *
     * The expensive path is the whole of [LessonPipeline.translateInto] — a model acquisition plus
     * every fragment of every artifact — which is why [LessonUiState.rendering] exists: the wait is
     * on screen rather than behind a frozen button, and a second request during it is refused.
     *
     * A failed render leaves the lesson exactly as it was: translateInto logs and returns without
     * writing partial rows, and the refresh below then finds no rows for [language] and keeps the
     * previous selection.
     */
    fun chooseLanguage(language: SupportedLanguage) {
        val current = _state.value
        if (current.rendering != null) return

        if (language == SupportedLanguage.ENGLISH) {
            _state.update { it.copy(lang = LessonPipeline.LANG_EN) }
            reloadPanes()
            return
        }

        if (language.code in current.renderedLangs) {
            _state.update { it.copy(lang = language.code, translationLang = language.code) }
            reloadPanes()
            return
        }

        _state.update { it.copy(rendering = language.code) }
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                runCatching { app.lessonPipeline().translateInto(docId = current.docId, language = language) }
                    .onFailure { Log.w(TAG, "doc ${current.docId}: ${language.code} render failed (${it.message})") }
            }
            val rows = withContext(Dispatchers.IO) {
                docStore.artifacts(current.docId, LessonPipeline.KIND_EXPLANATION, language.code)
            }
            val rendered = rows.isNotEmpty()
            _state.update {
                it.copy(
                    rendering = null,
                    lang = if (rendered) language.code else it.lang,
                    renderFailed = !rendered,
                )
            }
            refreshTranslationAvailability(preferred = language.code)
            if (rendered) reloadPanes()
        }
    }

    /** Clears the one-shot "that language could not be rendered" flag once the screen has shown it. */
    fun consumeRenderFailure() {
        _state.update { it.copy(renderFailed = false) }
    }

    /**
     * Retrieves off Main, builds the grounded prompt, then streams the teacher's answer token by
     * token through [tokens] while [AskUi.output] just tracks which stage this is.
     *
     * A generation failure keeps whatever partial text arrived rather than discarding it: per
     * [com.learnbridge.app.teach.Teacher.stream]'s contract the model may throw mid-generation, and a
     * half explanation the student can read beats an error where an answer belonged.
     */
    fun sendQuestion() {
        val current = _state.value
        if (current.ask.busy) return
        val question = current.ask.question.trim()
        if (question.isEmpty()) return

        _state.update { it.copy(ask = it.ask.copy(output = AskOutput.InProgress)) }

        viewModelScope.launch {
            val chunks = try {
                withContext(Dispatchers.IO) { Retrieval(docStore).retrieve(current.docId, question, Prompts.MAX_CHUNKS) }
            } catch (t: Throwable) {
                _state.update { it.copy(ask = it.ask.copy(output = AskOutput.Failed(t.message ?: "retrieval failed"))) }
                return@launch
            }

            val request = TeachRequest.Ask(question, chunks)
            _state.update { it.copy(ask = it.ask.copy(output = AskOutput.Streaming)) }

            val full = StringBuilder()
            withContext(Dispatchers.Default) {
                try {
                    modelHost.withTeacher { teacher ->
                        teacher.stream(request).collect { token ->
                            full.append(token)
                            _tokens.emit(token)
                        }
                    }
                } catch (t: Throwable) {
                    // Swallowed: [full] keeps whatever streamed before the failure, and that partial
                    // text is still parsed and shown below.
                }
            }

            val answer = LessonParser.parseAnswer(full.toString())
            // Read now, not from `current`: the student may have toggled the lesson's language while
            // the answer was streaming, and the answer belongs to the language they are reading in.
            val target = answerTarget(_state.value.lang)
            if (answer == null || target == null) {
                _state.update { it.copy(ask = it.ask.copy(output = AskOutput.Final(answer))) }
                return@launch
            }

            _state.update { it.copy(ask = it.ask.copy(output = AskOutput.Rendering(target.code))) }
            // English text into the lesson's language. The teacher generates in English whatever the
            // lesson language is, so without this a Tamil lesson answered in English, in an English
            // voice. Unlike Explain and Quiz — rendered once at import — this is per question and can
            // cost a model swap, which is what AskOutput.Rendering puts on screen.
            val rendered = runCatching { translator.render(answer.lines(), target).joinToString("\n") }
                // A translation failure keeps the English answer rather than losing it. Same rule as
                // the generation failure above: readable in the wrong language beats nothing.
                .getOrNull()
            _state.update {
                val output = if (rendered == null) {
                    AskOutput.Final(answer)
                } else {
                    AskOutput.Final(rendered, target.code)
                }
                it.copy(ask = it.ask.copy(output = output))
            }
        }
    }

    fun answerQuiz(selectedIndex: Int) {
        val quiz = _state.value.quiz
        if (quiz.answered || quiz.isDone) return
        _state.update { it.copy(quiz = it.quiz.answer(selectedIndex)) }
    }

    /**
     * Advances, and commits the result to the Learning Twin on the question that ends the quiz.
     *
     * Once per quiz rather than once per answer, because the Twin's unit of evidence is a completed
     * attempt: a single question answered in isolation is a much noisier signal than a run of them,
     * and updating per answer would let a student walk away mid-quiz having moved their own score.
     */
    fun nextQuizQuestion() {
        val before = _state.value.quiz
        _state.update { it.copy(quiz = it.quiz.next()) }
        val after = _state.value.quiz
        if (after.isDone && !before.isDone) recordQuizResult(after)
    }

    private fun recordQuizResult(quiz: QuizUi) {
        val docId = _state.value.docId
        if (docId == NO_DOC || quiz.total == 0) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val current = docStore.mastery(docId) ?: Mastery.initial(docId)
                val updated = current.afterQuiz(quiz.score, quiz.total, quiz.medianLatencyMs)
                docStore.putMastery(updated)
                Log.i(
                    TAG,
                    "doc $docId: mastery ${updated.masteryPercent}%, confidence " +
                        "${(updated.confidence * 100).toInt()}%, due in ${updated.intervalDays}d",
                )
                _state.update { it.copy(mastery = updated) }
            }.onFailure { Log.w(TAG, "doc $docId: could not record the quiz result", it) }
        }
    }

    private fun otherLang(lang: String): String =
        if (lang == LessonPipeline.LANG_EN) _state.value.translationLang else LessonPipeline.LANG_EN

    private companion object {
        const val NO_DOC = -1L
        const val TAG = "LessonViewModel"
    }
}
