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
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bhashabridge.app.speech.Tts
import com.learnbridge.app.R
import com.learnbridge.app.lang.SupportedLanguage
import com.learnbridge.app.teach.LessonPipeline
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Purpose:  The demo spine. One document, three modes — Explain, Ask, Quiz — with a language toggle
 *           and read-aloud across all of them.
 * Owns:     One [Tts] engine, released in [onDestroy].
 * Lifetime: Activity.
 * Thread:   Main. A pure renderer of [LessonViewModel.state]: [render] reads one snapshot and mutates
 *           views: it holds no business state of its own. The one exception is the Ask [EditText]'s
 *           own text, which is left to Android's normal view-state handling rather than bound from
 *           state, so typing never fights a State->View->State feedback loop, and [streamed], which
 *           buffers the token stream the state flow deliberately does not carry.
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

    /** Tokens of the in-flight answer, so the Ask pane can be redrawn from scratch after the student
     *  looks at another tab mid-stream. Not in [LessonUiState] for the reason its header gives. */
    private val streamed = StringBuilder()

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
        // Tokens are a separate stream from state on purpose — see LessonViewModel's header. They go
        // into [streamed] always, but onto the screen only while Ask is the visible pane: contentText
        // is shared with Explain, and appending regardless typed the answer onto the end of the key
        // points. Switching back still catches up, because renderAsk replays [streamed].
        lifecycleScope.launch {
            viewModel.tokens.collect { token ->
                val first = streamed.isEmpty()
                streamed.append(token)
                if (lastState?.tab == LessonTab.ASK) {
                    // The first token is what retires the "thinking" line; there is no state change
                    // at this moment for render() to react to.
                    if (first) inlineStatus.visibility = View.GONE
                    contentText.append(token)
                }
            }
        }
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

        // With two languages in the database this swaps between them. With one — an English-only
        // import — there is nothing to swap to, so the tap offers the chooser rather than doing
        // nothing at all.
        languageToggle.setOnClickListener {
            if (lastState?.translationAvailable == true) viewModel.toggleLanguage() else chooseLanguage()
        }
        // Long-press rather than a second button: switching between the two languages a lesson
        // already has is the common action and stays one tap, while adding a thirteenth-language
        // rendering — minutes of model work — is deliberately the less casual gesture. Announced in
        // the toggle's contentDescription so it is not discoverable by accident only.
        languageToggle.setOnLongClickListener {
            chooseLanguage()
            true
        }
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
            LessonTab.QUIZ -> renderQuiz(state.quiz, state.lang)
        }

        renderRendering(state)
        // Disabled only while a rendering runs: the model is busy, and a second request would be
        // refused by the ViewModel anyway — better to say so with the button than to swallow the tap.
        //
        // Deliberately NOT also gated on translationAvailable. A document imported in English carries
        // no translation, and the long press is the only route to adding one — gating the button
        // disabled that route and stranded the lesson in English permanently. The tap is handled
        // instead: with nothing to toggle between, it opens the chooser.
        languageToggle.isEnabled = state.rendering == null
        // Labelled with the target language's own endonym — हिंदी, मराठी, தமிழ், اردو — never a
        // hardcoded language name. A Tamil lesson must not offer a button that says "हिंदी".
        val target = SupportedLanguage.byCode(state.translationLang) ?: SupportedLanguage.HINDI
        val switchingToTarget = state.lang == LessonPipeline.LANG_EN
        languageToggle.text = if (switchingToTarget) target.endonym else SupportedLanguage.ENGLISH.endonym
        languageToggle.contentDescription = getString(
            if (switchingToTarget) R.string.cd_switch_to_target else R.string.cd_switch_to_english,
            target.endonym,
        ) + " " + getString(R.string.cd_long_press_for_languages)
    }

    /**
     * The wait while a lesson is rendered into a language it does not have yet, and the note when
     * that fails.
     *
     * Shown on whichever pane is up rather than as a blocking overlay: unlike an import, there is
     * already a readable lesson on screen in another language, and the student can keep reading it.
     */
    private fun renderRendering(state: LessonUiState) {
        val language = state.rendering?.let { SupportedLanguage.byCode(it) }
        if (language != null) {
            inlineStatus.text = getString(R.string.ingest_translating, language.endonym)
            inlineStatus.visibility = View.VISIBLE
        }
        if (state.renderFailed) {
            Toast.makeText(this, R.string.language_render_failed, Toast.LENGTH_LONG).show()
            viewModel.consumeRenderFailure()
        }
    }

    /**
     * Offers every supported language, not only the ones already rendered: the whole point is adding
     * one. The already-rendered ones are the instant path; anything else costs a full render, which
     * [renderRendering] then puts on screen.
     */
    private fun chooseLanguage() {
        val state = lastState ?: return
        if (state.rendering != null) return

        val options = listOf(SupportedLanguage.ENGLISH) + SupportedLanguage.targets
        val labels = options.map { language ->
            when {
                language == SupportedLanguage.ENGLISH -> language.endonym
                language.code in state.renderedLangs -> language.endonym
                // Marked because choosing it is a minutes-long model run, not a database read.
                else -> getString(R.string.language_needs_rendering, language.endonym)
            }
        }.toTypedArray()
        val current = options.indexOfFirst { it.code == state.lang }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.choose_language_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                viewModel.chooseLanguage(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    /**
     * Lays a view out for the script its text is written in.
     *
     * The platform gets bidi inside an Urdu paragraph right on its own, but nothing was reading
     * [SupportedLanguage.rtl], so the paragraph itself sat left-aligned in an LTR layout — correct
     * character order, wrong page. Alignment is VIEW_START so it follows whichever direction is set.
     */
    private fun applyDirection(view: TextView, lang: String) {
        val rtl = SupportedLanguage.byCode(lang)?.rtl == true
        view.textDirection = if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        view.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    /** A database read, never a generation — so there is no spinner here, ever. */
    private fun renderExplain(explain: ExplainUi, requestedLang: String) {
        applyDirection(contentText, explain.displayedLang)
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
        // The generated answer is English until Final says otherwise — including while it streams.
        applyDirection(contentText, (ask.output as? AskOutput.Final)?.lang ?: LessonPipeline.LANG_EN)
        askInput.isEnabled = !ask.busy
        askSend.isEnabled = !ask.busy && ask.question.isNotBlank()

        when (val output = ask.output) {
            AskOutput.Empty -> {
                contentText.text = ""
                inlineStatus.visibility = View.GONE
            }

            AskOutput.InProgress -> {
                // Cleared here, once, before any token arrives — see LessonViewModel.sendQuestion,
                // which always sets InProgress before Streaming.
                streamed.setLength(0)
                contentText.text = ""
                inlineStatus.text = getString(R.string.ask_thinking)
                inlineStatus.visibility = View.VISIBLE
            }

            is AskOutput.Rendering -> {
                // The English answer stays on screen underneath: it is a real answer, and reading it
                // beats an empty pane while the translator loads.
                val language = SupportedLanguage.byCode(output.lang) ?: SupportedLanguage.HINDI
                inlineStatus.text = getString(R.string.ingest_translating, language.endonym)
                inlineStatus.visibility = View.VISIBLE
            }

            AskOutput.Streaming -> {
                // Replayed rather than appended: this branch runs whenever Ask becomes visible again,
                // including after a mid-stream detour through Explain, which overwrote contentText.
                // .toString(): TextView keeps the CharSequence it is handed, and handing it the live
                // builder means every later append mutates text the view believes it already laid out.
                contentText.text = streamed.toString()
                // Streaming starts before the first token exists — acquiring the teacher can take
                // seconds when a model has to be swapped in. Dropping the line there left an empty
                // pane with a disabled button, which reads as a hang. Observed on device.
                inlineStatus.text = getString(R.string.ask_thinking)
                inlineStatus.visibility = if (streamed.isEmpty()) View.VISIBLE else View.GONE
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

    private fun renderQuiz(quiz: QuizUi, lang: String) {
        applyDirection(quizQuestion, lang)
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

        // Both branches above already return, so this cannot be null — asking anyway costs nothing
        // and means a future edit to either guard cannot turn this line into an index crash.
        val item = quiz.current ?: return
        quizQuestion.text = item.question
        val (options, correctIndex) = item.shuffledOptions()

        quizOptions.removeAllViews()
        val inflater = LayoutInflater.from(this)
        options.forEachIndexed { index, optionText ->
            val row = inflater.inflate(R.layout.view_quiz_item, quizOptions, false) as TextView
            applyDirection(row, lang)
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

    /**
     * Speaks whatever is currently on screen, in the language it is actually written in.
     *
     * The voice comes from [SupportedLanguage.locale], not from a two-valued direction. Passing a
     * direction here is what made every non-English lesson — Tamil, Bengali, Urdu, all of them —
     * come out of the Hindi voice.
     *
     * Ask reads [AskOutput.Final.lang] rather than the lesson's language: the answer is generated in
     * English and rendered afterwards, and a rendering that failed stays English, so what is on
     * screen is the only trustworthy source for which voice to use.
     */
    private fun speakCurrent() {
        val state = lastState ?: return
        if (!tts.ready) return
        tts.stop()

        val (text, locale) = when (state.tab) {
            LessonTab.EXPLAIN ->
                // displayedLang, not state.lang: Explain falls back to English when the requested
                // language has no rows yet, and the voice must follow what is on screen.
                state.explain.points.joinToString(". ") to localeFor(state.explain.displayedLang)
            LessonTab.ASK -> (state.ask.output as? AskOutput.Final).let {
                (it?.answer ?: "") to localeFor(it?.lang ?: LessonPipeline.LANG_EN)
            }
            LessonTab.QUIZ -> quizSpeechFor(state)
        }
        if (text.isBlank()) return

        // Most devices ship no voice data for Odia, Sanskrit or Kannada. Tts falls back to the
        // English voice rather than going silent, but the student deserves to know why the words
        // sound wrong, and how to fix it.
        if (!tts.voiceAvailable(locale)) {
            val language = SupportedLanguage.entries.firstOrNull { it.locale == locale }
            Toast.makeText(
                this,
                getString(R.string.tts_voice_missing, language?.endonym ?: locale.displayLanguage),
                Toast.LENGTH_LONG,
            ).show()
        }
        tts.speak(text, locale)
    }

    private fun quizSpeechFor(state: LessonUiState): Pair<String, Locale> {
        val quiz = state.quiz
        // One question — "is there something on screen to read aloud" — instead of two guards that
        // have to be kept in step with how the quiz decides it has a current question.
        val item = quiz.current ?: return "" to SupportedLanguage.ENGLISH.locale
        val (options, _) = item.shuffledOptions()
        return (listOf(item.question) + options).joinToString(". ") to localeFor(state.lang)
    }

    /** The voice for a language code, falling back to English for an unknown one. */
    private fun localeFor(lang: String): Locale =
        (SupportedLanguage.byCode(lang) ?: SupportedLanguage.ENGLISH).locale

    companion object {
        const val EXTRA_DOC_ID = "doc_id"
        const val EXTRA_DOC_TITLE = "doc_title"
    }
}
