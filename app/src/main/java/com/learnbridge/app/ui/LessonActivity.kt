package com.learnbridge.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bhashabridge.app.Direction
import com.bhashabridge.app.speech.Tts
import com.learnbridge.app.R
import com.learnbridge.app.lang.SupportedLanguage
import com.learnbridge.app.teach.LessonPipeline
import kotlinx.coroutines.launch

/**
 * Purpose:  The demo spine. One document, three modes — Explain, Ask, Quiz — with a language toggle
 *           and read-aloud across all of them.
 * Owns:     One [Tts] engine, released in [onDestroy].
 * Lifetime: Activity.
 * Thread:   Main. A pure renderer of [LessonViewModel.state]: [render] reads one snapshot and mutates
 *           views: it holds no business state of its own. The one exception is the Ask [EditText]'s
 *           own text, which is left to Android's normal view-state handling rather than bound from
 *           state, so typing never fights a State->View->State feedback loop.
 */
class LessonActivity : AppCompatActivity() {

    private val viewModel: LessonViewModel by viewModels()

    private lateinit var docTitleView: TextView
    private lateinit var tabExplain: TextView
    private lateinit var tabAsk: TextView
    private lateinit var tabQuiz: TextView
    private lateinit var askInputRow: LinearLayout
    private lateinit var askInput: EditText
    private lateinit var askSend: Button
    private lateinit var contentScroll: View
    private lateinit var inlineStatus: TextView
    private lateinit var contentText: TextView
    private lateinit var quizScroll: View
    private lateinit var quizUnavailable: TextView
    private lateinit var quizProgress: TextView
    private lateinit var quizQuestion: TextView
    private lateinit var quizOptions: LinearLayout
    private lateinit var quizFeedback: TextView
    private lateinit var quizScore: TextView
    private lateinit var quizNext: Button
    private lateinit var languageToggle: Button
    private lateinit var listenButton: Button

    private lateinit var tts: Tts

    /** The last rendered snapshot, so click handlers (Listen) act on exactly what is on screen. */
    private var lastState: LessonUiState? = null

    private val selectableBackgroundRes: Int by lazy {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        value.resourceId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lesson)
        bindViews()
        wireListeners()

        tts = Tts(this) { }

        val docId = intent.getLongExtra(EXTRA_DOC_ID, -1L)
        val docTitle = intent.getStringExtra(EXTRA_DOC_TITLE).orEmpty()
        viewModel.start(docId, docTitle)

        lifecycleScope.launch { viewModel.state.collect { render(it) } }
        // Tokens are a separate stream from state on purpose — see LessonViewModel's header. Appended
        // unconditionally: if the student switches away from Ask mid-stream the text still
        // accumulates in the hidden TextView, so switching back shows it caught up, not restarted.
        lifecycleScope.launch { viewModel.tokens.collect { token -> contentText.append(token) } }
    }

    override fun onPause() {
        super.onPause()
        tts.stop()
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        docTitleView = findViewById(R.id.docTitle)
        tabExplain = findViewById(R.id.tabExplain)
        tabAsk = findViewById(R.id.tabAsk)
        tabQuiz = findViewById(R.id.tabQuiz)
        askInputRow = findViewById(R.id.askInputRow)
        askInput = findViewById(R.id.askInput)
        askSend = findViewById(R.id.askSend)
        contentScroll = findViewById(R.id.contentScroll)
        inlineStatus = findViewById(R.id.inlineStatus)
        contentText = findViewById(R.id.contentText)
        quizScroll = findViewById(R.id.quizScroll)
        quizUnavailable = findViewById(R.id.quizUnavailable)
        quizProgress = findViewById(R.id.quizProgress)
        quizQuestion = findViewById(R.id.quizQuestion)
        quizOptions = findViewById(R.id.quizOptions)
        quizFeedback = findViewById(R.id.quizFeedback)
        quizScore = findViewById(R.id.quizScore)
        quizNext = findViewById(R.id.quizNext)
        languageToggle = findViewById(R.id.languageToggle)
        listenButton = findViewById(R.id.listenButton)
    }

    private fun wireListeners() {
        tabExplain.setOnClickListener { viewModel.selectTab(LessonTab.EXPLAIN) }
        tabAsk.setOnClickListener { viewModel.selectTab(LessonTab.ASK) }
        tabQuiz.setOnClickListener { viewModel.selectTab(LessonTab.QUIZ) }

        askInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = viewModel.updateQuestion(s?.toString().orEmpty())
        })
        // Accepts more than IME_ACTION_SEND on purpose: a hardware or docked-keyboard Return arrives
        // as IME_ACTION_UNSPECIFIED with a key event rather than as the declared action, and some
        // keyboards substitute DONE or GO regardless of imeOptions. Pressing Return should always ask.
        askInput.setOnEditorActionListener { _, actionId, event ->
            val isSend = actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                (actionId == EditorInfo.IME_ACTION_UNSPECIFIED && event?.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isSend) {
                viewModel.sendQuestion()
                true
            } else {
                false
            }
        }
        askSend.setOnClickListener { viewModel.sendQuestion() }

        quizNext.setOnClickListener { viewModel.nextQuizQuestion() }

        languageToggle.setOnClickListener { viewModel.toggleLanguage() }
        listenButton.setOnClickListener { speakCurrent() }
    }

    // --- Rendering. One function per pane, all driven by the same immutable snapshot. ---

    private fun render(state: LessonUiState) {
        lastState = state

        docTitleView.text = state.docTitle
        renderTabs(state.tab)

        askInputRow.visibility = if (state.tab == LessonTab.ASK) View.VISIBLE else View.GONE
        contentScroll.visibility = if (state.tab == LessonTab.QUIZ) View.GONE else View.VISIBLE
        quizScroll.visibility = if (state.tab == LessonTab.QUIZ) View.VISIBLE else View.GONE

        when (state.tab) {
            LessonTab.EXPLAIN -> renderExplain(state.explain, state.lang)
            LessonTab.ASK -> renderAsk(state.ask)
            LessonTab.QUIZ -> renderQuiz(state.quiz)
        }

        languageToggle.isEnabled = state.translationAvailable
        // Labelled with the target language's own endonym — हिंदी, मराठी, தமிழ், اردو — never a
        // hardcoded language name. A Tamil lesson must not offer a button that says "हिंदी".
        val target = SupportedLanguage.byCode(state.translationLang) ?: SupportedLanguage.HINDI
        val switchingToTarget = state.lang == LessonPipeline.LANG_EN
        languageToggle.text = if (switchingToTarget) target.endonym else SupportedLanguage.ENGLISH.endonym
        languageToggle.contentDescription = getString(
            if (switchingToTarget) R.string.cd_switch_to_target else R.string.cd_switch_to_english,
            target.endonym,
        )
    }

    /** Selection is never colour-only: bold weight and [View.isSelected] (read aloud by screen
     *  readers) both change alongside the background. */
    private fun renderTabs(tab: LessonTab) {
        style(tabExplain, tab == LessonTab.EXPLAIN)
        style(tabAsk, tab == LessonTab.ASK)
        style(tabQuiz, tab == LessonTab.QUIZ)
    }

    private fun style(tab: TextView, selected: Boolean) {
        tab.isSelected = selected
        tab.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else selectableBackgroundRes)
        tab.setTextColor(ContextCompat.getColor(this, if (selected) R.color.text_primary else R.color.text_muted))
        tab.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    /** A database read, never a generation — so there is no spinner here, ever. */
    private fun renderExplain(explain: ExplainUi, requestedLang: String) {
        if (explain.points.isEmpty()) {
            contentText.text = getString(R.string.explain_unavailable)
            inlineStatus.visibility = View.GONE
            return
        }
        contentText.text = explain.points.joinToString("\n\n") { "• $it" }
        if (explain.fellBack) {
            // Named in the language's own script, because the document may have been imported in
            // any of the thirteen — "Hindi isn't ready" was wrong for twelve of them.
            val requested = SupportedLanguage.byCode(requestedLang) ?: SupportedLanguage.HINDI
            inlineStatus.text = getString(R.string.explain_fallback_english, requested.endonym)
            inlineStatus.visibility = View.VISIBLE
        } else {
            inlineStatus.visibility = View.GONE
        }
    }

    private fun renderAsk(ask: AskUi) {
        askInput.isEnabled = !ask.busy
        askSend.isEnabled = !ask.busy && ask.question.isNotBlank()

        when (val output = ask.output) {
            AskOutput.Empty -> {
                contentText.text = ""
                inlineStatus.visibility = View.GONE
            }

            AskOutput.InProgress -> {
                // Cleared here, once, before any token arrives — see LessonViewModel.sendQuestion,
                // which always sets InProgress before Streaming. Streaming itself never touches
                // contentText: the token collector in onCreate owns it from this point on.
                contentText.text = ""
                inlineStatus.text = getString(R.string.ask_thinking)
                inlineStatus.visibility = View.VISIBLE
            }

            AskOutput.Streaming -> {
                inlineStatus.visibility = View.GONE
            }

            is AskOutput.Final -> {
                inlineStatus.visibility = View.GONE
                contentText.text = output.answer ?: getString(R.string.ask_no_answer)
            }

            is AskOutput.Failed -> {
                inlineStatus.visibility = View.GONE
                contentText.text = getString(R.string.error_generation_failed)
            }
        }
    }

    private fun renderQuiz(quiz: QuizUi) {
        quizFeedback.visibility = View.GONE
        quizNext.visibility = View.GONE
        quizScore.visibility = View.GONE

        if (quiz.items.isEmpty()) {
            quizUnavailable.visibility = View.VISIBLE
            quizProgress.visibility = View.GONE
            quizQuestion.visibility = View.GONE
            quizOptions.visibility = View.GONE
            return
        }
        quizUnavailable.visibility = View.GONE

        if (quiz.isDone) {
            quizProgress.visibility = View.GONE
            quizQuestion.visibility = View.GONE
            quizOptions.visibility = View.GONE
            quizScore.visibility = View.VISIBLE
            quizScore.text = getString(R.string.quiz_score, quiz.score, quiz.total)
            return
        }

        quizProgress.visibility = View.VISIBLE
        quizQuestion.visibility = View.VISIBLE
        quizOptions.visibility = View.VISIBLE
        quizProgress.text = getString(R.string.quiz_progress, quiz.currentIndex + 1, quiz.total)

        val item = quiz.items[quiz.currentIndex]
        quizQuestion.text = item.question
        val (options, correctIndex) = item.shuffledOptions()

        quizOptions.removeAllViews()
        val inflater = LayoutInflater.from(this)
        options.forEachIndexed { index, optionText ->
            val row = inflater.inflate(R.layout.view_quiz_item, quizOptions, false) as TextView
            val isCorrectRow = quiz.answered && index == correctIndex
            val isWrongSelected = quiz.answered && index == quiz.selectedIndex && index != correctIndex

            // Correct/incorrect is never colour-only either: a check/cross prefix carries the same
            // information as the background tint.
            row.text = when {
                isCorrectRow -> "✓ $optionText"
                isWrongSelected -> "✗ $optionText"
                else -> optionText
            }
            row.contentDescription = getString(R.string.cd_quiz_option, optionText)
            row.setBackgroundResource(
                when {
                    isCorrectRow -> R.drawable.bg_quiz_option_correct
                    isWrongSelected -> R.drawable.bg_quiz_option_wrong
                    else -> R.drawable.bg_quiz_option
                },
            )
            row.isEnabled = !quiz.answered
            if (!quiz.answered) {
                row.setOnClickListener { viewModel.answerQuiz(index) }
            }
            quizOptions.addView(row)
        }

        if (quiz.answered) {
            val correct = quiz.selectedIndex == correctIndex
            quizFeedback.visibility = View.VISIBLE
            quizFeedback.text = getString(if (correct) R.string.quiz_correct else R.string.quiz_incorrect)
            quizFeedback.setTextColor(
                ContextCompat.getColor(this, if (correct) R.color.accent else R.color.emergency),
            )
            quizNext.visibility = View.VISIBLE
        }
    }

    /** Speaks whatever is currently on screen, in the language currently displayed. Ask is always
     *  answered in English (see [LessonViewModel.sendQuestion]), so it always uses the English voice. */
    private fun speakCurrent() {
        val state = lastState ?: return
        if (!tts.ready) return
        tts.stop()

        val (text, direction) = when (state.tab) {
            LessonTab.EXPLAIN -> state.explain.points.joinToString(". ") to directionFor(state.explain.displayedLang)
            LessonTab.ASK -> ((state.ask.output as? AskOutput.Final)?.answer ?: "") to Direction.HI_TO_EN
            LessonTab.QUIZ -> quizSpeechFor(state)
        }
        if (text.isNotBlank()) tts.speak(text, direction)
    }

    private fun quizSpeechFor(state: LessonUiState): Pair<String, Direction> {
        val quiz = state.quiz
        if (quiz.items.isEmpty() || quiz.isDone) return "" to Direction.HI_TO_EN
        val item = quiz.items[quiz.currentIndex]
        val (options, _) = item.shuffledOptions()
        return (listOf(item.question) + options).joinToString(". ") to directionFor(state.lang)
    }

    private fun directionFor(lang: String): Direction =
        // Direction picks the voice: EN_TO_HI selects the target-language voice, HI_TO_EN the English
        // one. Any non-English lesson language uses the former.
        if (lang != LessonPipeline.LANG_EN) Direction.EN_TO_HI else Direction.HI_TO_EN

    companion object {
        const val EXTRA_DOC_ID = "doc_id"
        const val EXTRA_DOC_TITLE = "doc_title"
    }
}
