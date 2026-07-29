package com.learnbridge.app.teach

import android.content.Context
import android.net.Uri
import android.util.Log
import com.learnbridge.app.ModelHost
import com.learnbridge.app.doc.Chunk
import com.learnbridge.app.doc.DocImport
import com.learnbridge.app.doc.DocStore
import com.learnbridge.app.doc.ImportResult
import com.learnbridge.app.doc.chunk
import com.learnbridge.app.lang.LessonTranslator
import com.learnbridge.app.lang.SupportedLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/** What the ingest screen renders. Every failure is a state, never an exception reaching the UI. */
sealed interface IngestProgress {
    data object Reading : IngestProgress

    /**
     * Generation is under way on section [part] of [total], both 1-based.
     *
     * Carries a position because a long document is taught section by section and the whole pass can
     * run for many minutes — a single unchanging "thinking…" line over twenty minutes reads as a hang.
     */
    data class Teaching(val part: Int, val total: Int) : IngestProgress
    data object Translating : IngestProgress
    data class Done(val docId: Long, val title: String) : IngestProgress
    data class Failed(val reason: ImportResult.Reason) : IngestProgress
}

/**
 * Purpose:  Turns a picked file into a finished, persisted lesson — extract, chunk, generate, render
 *           Hindi, store. The one place the ingest sequence is defined.
 * Owns:     Nothing; borrows the models through ModelHost and writes through [store].
 * Lifetime: One per ingest, created by the importing screen.
 * Thread:   [ingest] is a cold Flow doing heavy work; collect it off the main thread.
 *
 * **Why generation happens here, once, and not when a screen opens.**
 *
 * A 20-page document is roughly 12,000 tokens and will not fit any context this app can afford, and
 * generation on mid-range silicon runs at single-digit tokens per second. Generating on demand would
 * mean a spinner every time a student opened a lesson. So everything the lesson needs — key points,
 * quiz, and the Hindi of both — is produced in one pass at import and written to `artifacts`, behind a
 * progress screen the user expects to wait on. Opening a lesson afterwards is a database read.
 *
 * **Why the whole document is taught, and what it costs.**
 *
 * Because it does not fit one context, the document is taught in prompt-sized sections and their
 * output is concatenated: section 1's key points are ordinals 0-4, section 2's are 5-9, and so on, so
 * every reader downstream still sees one ordered lesson and needed no change. The import is therefore
 * linear in document length — around 90 s per section on the target device, so a 30-page PDF is a
 * ~15-minute wait with nothing to show until it finishes. That is a deliberate trade, chosen over
 * teaching only the opening pages; if it ever needs to be cheaper, the lever is generating section 1
 * at import and the rest on first open, which needs a per-section artifact key rather than a flat
 * ordinal.
 *
 * **Why the ordering is English-first, then all Hindi at the end.**
 *
 * On a memory-constrained device the generative model and the translation engine cannot both stay
 * resident, so acquiring one releases the other — measured at ~14 s cold on the target hardware.
 * Interleaving generation and translation would pay that swap per item. Instead: generate every
 * English artifact under one `withTeacher`, then translate every fragment of all of them under one
 * `withTranslator`. **Exactly one model swap per document.**
 *
 * That is also what makes the language toggle free: the Hindi is already in the database, so
 * switching languages never loads a model at all.
 */
class LessonPipeline(
    private val context: Context,
    private val store: DocStore,
    private val translator: LessonTranslator,
    private val modelHost: ModelHost,
    /**
     * Which language the lesson is rendered into at import. Only one is rendered up front — a second
     * costs another pass over every fragment — but adding one later is cheap, because switching target
     * language needs no model reload. See [translateInto].
     */
    private val target: SupportedLanguage = SupportedLanguage.DEFAULT_TARGET,
) {

    /**
     * The whole ingest, as a stream of states.
     *
     * **Every throwable becomes [IngestProgress.Failed], and a document that does not finish is
     * deleted.** The header above says failure is a state and never an exception, and this is where
     * that is enforced rather than merely intended. Two things were wrong before:
     *
     *  1. `LibraryActivity` collects this flow with no `catch`, so anything thrown here — a full
     *     disk, an ML Kit failure, a native generation error — crashed the process and left the
     *     blocking ingest overlay up with both buttons disabled.
     *  2. Only the empty-chunks path cleaned up after itself. Every other failure left a row at
     *     `status = "importing"` that the library rendered as a normal lesson, opened empty, and
     *     offered no way to retry or remove.
     */
    fun ingest(uri: Uri): Flow<IngestProgress> {
        // Holds the row to clean up if this ingest does not reach STATUS_READY. Assigned inside the
        // builder, read by the terminal operators below, which is why it lives out here.
        var pending: Long? = null

        return flow {
            emit(IngestProgress.Reading)

            val imported = DocImport.import(context, uri)
            if (imported is ImportResult.Failure) {
                emit(IngestProgress.Failed(imported.reason))
                return@flow
            }
            val doc = imported as ImportResult.Success

            val docId = store.insertDocument(doc.title, uri.toString(), doc.wordCount)
            pending = docId

            store.saveText(docId, doc.text)

            val chunks = chunk(doc.text)
            if (chunks.isEmpty()) {
                store.deleteDocument(docId)
                pending = null
                emit(IngestProgress.Failed(ImportResult.Reason.EMPTY))
                return@flow
            }
            store.insertChunks(docId, chunks)

            // The WHOLE document, in prompt-sized sections, not just its opening.
            //
            // This used to be `chunks.take(Prompts.MAX_CHUNKS)` — four chunks, ~720 words. That is
            // the right context for a photographed page and quietly wrong for a file: a 30-page PDF
            // measured 7,527 words, so a student importing their unit notes got a lesson about
            // pages 1-3 and a quiz that could not ask about anything else. The rest was stored and
            // indexed, so Ask could already reach it, which made the gap look like a parser bug.
            //
            // The cost is linear and it is not small: every section is its own Explain and Quiz
            // pass, ~90 s each on the target device, so that 30-page PDF is a ~15-minute import.
            // Sections are not batched into one bigger prompt because they cannot be — the model
            // gives up sooner the more text precedes the instruction (see Prompts.quiz).
            val sections = chunks.chunked(Prompts.MAX_CHUNKS)

            emit(IngestProgress.Teaching(1, sections.size))
            val keyPoints = generateEnglish(docId, sections) { part ->
                emit(IngestProgress.Teaching(part, sections.size))
            }

            // Not announced when the target is English: translateInto returns immediately in that
            // case, and "Preparing the English version…" would name work that never happens.
            if (target != SupportedLanguage.ENGLISH) {
                emit(IngestProgress.Translating)
                translateInto(docId, keyPoints, target)
            }

            store.setStatus(docId, DocStore.STATUS_READY)
            // Committed: from here the document is a real lesson and must survive a cancelled
            // collector — the student may well have navigated away while it finished.
            pending = null
            emit(IngestProgress.Done(docId, doc.title))
        }
            // `.catch`, not a try/catch around the body. A try/catch wrapping `emit` also catches
            // exceptions thrown by the *collector*, and emitting again from there fails with "Flow
            // exception transparency is violated" — masking the real error with a worse one.
            .catch { t ->
                Log.e(TAG, "doc ${pending ?: -1}: ingest failed", t)
                discard(pending)
                pending = null
                emit(IngestProgress.Failed(ImportResult.Reason.UNREADABLE))
            }
            // Cancellation does not pass through `.catch`. The collecting scope dies whenever the
            // student leaves the screen mid-ingest, and without this the half-built row would
            // outlive it: visible in the library, openable, permanently empty.
            .onCompletion { cause ->
                if (cause != null) discard(pending)
            }
    }

    /**
     * Removes a document that never finished importing.
     *
     * Best-effort by design: this runs on a failure path, and a failure to clean up after a failure
     * must not replace the original error with a second one.
     */
    private fun discard(docId: Long?) {
        if (docId == null) return
        runCatching { store.deleteDocument(docId) }
            .onFailure { Log.w(TAG, "doc $docId: could not remove the unfinished document", it) }
    }

    /**
     * Everything the generative model produces for every section, inside ONE acquisition. Returns the
     * English key points in document order so the Hindi pass does not have to read them back out of
     * the database.
     *
     * The loop lives inside `withTeacher` deliberately: acquiring the tutor costs a model swap
     * (~14 s), and a ten-section document that acquired per section would pay it ten times. [onPart]
     * is invoked from inside that block, which is safe for a flow's `emit` because ModelHost's gate is
     * a Mutex — it suspends without changing coroutine context.
     *
     * Each artifact is attempted independently: a quiz the model mangles must not cost the student
     * their explanation, and one bad section must not cost them the other nine.
     */
    private suspend fun generateEnglish(
        docId: Long,
        sections: List<List<Chunk>>,
        onPart: suspend (Int) -> Unit,
    ): List<String> {
        val keyPoints = mutableListOf<String>()
        var quizCount = 0

        modelHost.withTeacher { teacher ->
            sections.forEachIndexed { index, section ->
                onPart(index + 1)

                // Attempted independently, exactly like the quiz below. Generation can fail mid-stream
                // (Teacher.stream documents this), and a document with a quiz but no explanation is
                // still worth keeping — the same reasoning that already protected the quiz.
                val points = attempt(docId, "key-point generation", emptyList()) {
                    LessonParser.parseKeyPoints(teacher.collect(TeachRequest.Explain(section)))
                }
                points.forEach { point ->
                    store.putArtifact(docId, KIND_EXPLANATION, LANG_EN, keyPoints.size, point)
                    keyPoints += point
                }

                val quiz = attempt(docId, "quiz generation", emptyList<QuizItem>()) {
                    LessonParser.parseQuiz(teacher.collect(TeachRequest.Quiz(section)))
                }
                quiz.forEach { item ->
                    store.putArtifact(docId, KIND_QUIZ, LANG_EN, quizCount++, item.encode())
                }

                Log.i(
                    TAG,
                    "doc $docId: section ${index + 1}/${sections.size}: " +
                        "${points.size} key points, ${quiz.size} quiz items",
                )
            }
        }

        Log.i(TAG, "doc $docId: ${keyPoints.size} key points, $quizCount quiz items in total")
        return keyPoints
    }

    /**
     * Runs [block], degrading a failure to [fallback] — but never swallowing cancellation.
     *
     * `runCatching` was used here and it catches [CancellationException] like any other throwable.
     * With one generation pass per document that was survivable; with one per section it is not, because
     * a student who leaves the screen would cancel a scope that the loop then ignores, and the tutor
     * would keep generating — and keep the model resident — through every remaining section.
     */
    private suspend fun <T> attempt(docId: Long, what: String, fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Log.w(TAG, "doc $docId: $what failed (${t.message})")
            fallback
        }

    /**
     * One acquisition of the translation engine for every fragment of every artifact.
     *
     * Quiz items are translated here too rather than lazily per question. Lazily would be cheaper in
     * total work, but it would move a model swap into the middle of someone answering a quiz, and the
     * swap is the expensive part — not the translating.
     */
    suspend fun translateInto(
        docId: Long,
        keyPointsOrNull: List<String>? = null,
        language: SupportedLanguage,
    ) {
        if (language == SupportedLanguage.ENGLISH) return

        val keyPoints = keyPointsOrNull ?: store.artifacts(docId, KIND_EXPLANATION, LANG_EN)
        if (keyPoints.isEmpty()) return

        val quizItems = store.artifacts(docId, KIND_QUIZ, LANG_EN).mapNotNull { QuizItem.decode(it) }

        // Flattened so the translator makes a single pass, then split back apart by count.
        val quizLines = quizItems.flatMap { listOf(it.question, it.correct) + it.distractors }
        // attempt(), not runCatching, for the reason documented there: rendering a long document is
        // minutes of work and must stop when the student walks away.
        val translated = attempt(docId, "${language.code} rendering", emptyList<String>()) {
            translator.render(keyPoints + quizLines, language)
        }
        if (translated.isEmpty()) return

        translated.take(keyPoints.size).forEachIndexed { i, point ->
            store.putArtifact(docId, KIND_EXPLANATION, language.code, i, point)
        }

        var cursor = keyPoints.size
        quizItems.forEachIndexed { i, item ->
            val size = 2 + item.distractors.size
            if (cursor + size > translated.size) return@forEachIndexed
            val slice = translated.subList(cursor, cursor + size)
            cursor += size
            store.putArtifact(
                docId,
                KIND_QUIZ,
                language.code,
                i,
                QuizItem(slice[0], slice[1], slice.drop(2)).encode(),
            )
        }
        Log.i(TAG, "doc $docId: rendered into ${language.code}")
    }

    companion object {
        private const val TAG = "LessonPipeline"

        const val KIND_EXPLANATION = "explanation"
        const val KIND_QUIZ = "quiz"
        const val LANG_EN = "en"

        /**
         * The fallback target language code, used when a document carries no translation at all.
         * Not "the language this app translates into" — that is [SupportedLanguage], thirteen wide.
         */
        const val LANG_HI = "hi"
    }
}

/** Collects a whole stream into one string. Generation here is batch work; nothing renders it live. */
private suspend fun Teacher.collect(request: TeachRequest): String {
    val builder = StringBuilder()
    stream(request).collect { builder.append(it) }
    return builder.toString()
}
