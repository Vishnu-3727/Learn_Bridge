package com.learnbridge.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.learnbridge.app.LearnBridgeApp
import com.learnbridge.app.doc.Retrieval
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
    /** The language the Explain/Quiz artifacts are requested in. Ask always answers in English —
     *  see [LessonViewModel.sendQuestion]. */
    val lang: String = LessonPipeline.LANG_EN,
    /** Whether this document has any Hindi artifacts at all. Gates the toggle; see [LessonViewModel.refreshHindiAvailability]. */
    val hindiAvailable: Boolean = false,
    val explain: ExplainUi = ExplainUi(),
    val ask: AskUi = AskUi(),
    val quiz: QuizUi = QuizUi(),
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
    val busy: Boolean get() = output is AskOutput.InProgress || output is AskOutput.Streaming
}

/** The Ask pane's result, one state per stage of a single question. */
sealed interface AskOutput {
    data object Empty : AskOutput
    data object InProgress : AskOutput
    data object Streaming : AskOutput

    /** [answer] is null when [LessonParser.parseAnswer] decided the document does not cover it. */
    data class Final(val answer: String?) : AskOutput
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
) {
    val total: Int get() = items.size
    val isDone: Boolean get() = items.isNotEmpty() && currentIndex >= items.size

    /**
     * Records a tap on option [selected] of the current question. Pure — no I/O; the caller
     * ([LessonViewModel.answerQuiz]) is responsible for persisting the result alongside this.
     * A no-op once the current question is already answered, or once the quiz is done, so a
     * double-tap can never double-count a score.
     */
    fun answer(selected: Int): QuizUi {
        if (answered || isDone) return this
        val (_, correctIndex) = items[currentIndex].shuffledOptions()
        return copy(
            selectedIndex = selected,
            answered = true,
            score = if (selected == correctIndex) score + 1 else score,
        )
    }

    /** Moves to the next question, resetting per-question state. A no-op before an answer is given. */
    fun next(): QuizUi =
        if (!answered) this else copy(currentIndex = currentIndex + 1, selectedIndex = null, answered = false)
}

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

    private val _tokens = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val tokens: SharedFlow<String> = _tokens.asSharedFlow()

    /** Called once from `onCreate`. Re-entrant: a rotation calls this again with the same doc, and
     *  that must not reset quiz progress or re-hit the database for no reason. */
    fun start(docId: Long, docTitle: String) {
        if (_state.value.docId == docId) return
        _state.value = LessonUiState(docId = docId, docTitle = docTitle)
        loadExplain()
        loadQuiz()
        refreshHindiAvailability()
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
        if (!current.hindiAvailable) return
        val newLang = otherLang(current.lang)
        _state.update { it.copy(lang = newLang) }
        loadExplain()
        loadQuiz(preserveProgress = true)
    }

    private fun loadExplain() {
        val (docId, lang) = _state.value.let { it.docId to it.lang }
        val other = otherLang(lang)
        viewModelScope.launch(Dispatchers.IO) {
            val primary = docStore.artifacts(docId, LessonPipeline.KIND_EXPLANATION, lang)
            val fallback = if (primary.isEmpty()) {
                docStore.artifacts(docId, LessonPipeline.KIND_EXPLANATION, other)
            } else {
                emptyList()
            }
            val resolved = resolveFallback(lang, other, primary, fallback)
            _state.update {
                it.copy(explain = ExplainUi(resolved.rows, resolved.lang, resolved.fellBack))
            }
        }
    }

    /**
     * [preserveProgress] keeps score/currentIndex across a language toggle mid-quiz — swapping which
     * language a question is worded in should not cost the student their progress through it.
     */
    private fun loadQuiz(preserveProgress: Boolean = false) {
        val (docId, lang) = _state.value.let { it.docId to it.lang }
        val other = otherLang(lang)
        viewModelScope.launch(Dispatchers.IO) {
            val primaryRaw = docStore.artifacts(docId, LessonPipeline.KIND_QUIZ, lang)
            val fallbackRaw = if (primaryRaw.isEmpty()) {
                docStore.artifacts(docId, LessonPipeline.KIND_QUIZ, other)
            } else {
                emptyList()
            }
            val primary = primaryRaw.mapNotNull { QuizItem.decode(it) }
            val fallback = fallbackRaw.mapNotNull { QuizItem.decode(it) }
            val resolved = resolveFallback(lang, other, primary, fallback)
            _state.update {
                val quiz = if (preserveProgress) it.quiz.copy(items = resolved.rows) else QuizUi(items = resolved.rows)
                it.copy(quiz = quiz)
            }
        }
    }

    /**
     * Doc-level: true if EITHER artifact kind has a Hindi row. Checked once per doc, not per pane, so
     * the toggle can be disabled up front rather than discovered broken after a tap.
     */
    private fun refreshHindiAvailability() {
        val docId = _state.value.docId
        viewModelScope.launch(Dispatchers.IO) {
            val hiExplain = docStore.artifacts(docId, LessonPipeline.KIND_EXPLANATION, LessonPipeline.LANG_HI)
            val hiQuiz = docStore.artifacts(docId, LessonPipeline.KIND_QUIZ, LessonPipeline.LANG_HI)
            val available = hiExplain.isNotEmpty() || hiQuiz.isNotEmpty()
            _state.update { it.copy(hindiAvailable = available) }
        }
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
            _state.update { it.copy(ask = it.ask.copy(output = AskOutput.Final(answer))) }
        }
    }

    fun answerQuiz(selectedIndex: Int) {
        val current = _state.value
        val quiz = current.quiz
        if (quiz.answered || quiz.isDone) return

        val itemOrdinal = quiz.currentIndex // matches the artifact ordinal LessonPipeline wrote it at.
        val correct = selectedIndex == quiz.items[itemOrdinal].shuffledOptions().second

        _state.update { it.copy(quiz = it.quiz.answer(selectedIndex)) }
        viewModelScope.launch(Dispatchers.IO) { docStore.recordQuizResult(current.docId, itemOrdinal, correct) }
    }

    fun nextQuizQuestion() {
        _state.update { it.copy(quiz = it.quiz.next()) }
    }

    private fun otherLang(lang: String): String =
        if (lang == LessonPipeline.LANG_EN) LessonPipeline.LANG_HI else LessonPipeline.LANG_EN

    private companion object {
        const val NO_DOC = -1L
    }
}
