package com.learnbridge.app.teach

import android.content.Context
import android.net.Uri
import android.util.Log
import com.learnbridge.app.doc.Chunk
import com.learnbridge.app.doc.DocImport
import com.learnbridge.app.doc.DocStore
import com.learnbridge.app.doc.ImportResult
import com.learnbridge.app.doc.chunk
import com.learnbridge.app.hindi.HindiRenderer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What the ingest screen renders. Every failure is a state, never an exception reaching the UI. */
sealed interface IngestProgress {
    data object Reading : IngestProgress
    data object Teaching : IngestProgress
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
    private val hindi: HindiRenderer,
    private val modelHost: com.learnbridge.app.ModelHost,
) {

    fun ingest(uri: Uri): Flow<IngestProgress> = flow {
        emit(IngestProgress.Reading)

        val imported = DocImport.import(context, uri)
        if (imported is ImportResult.Failure) {
            emit(IngestProgress.Failed(imported.reason))
            return@flow
        }
        val doc = imported as ImportResult.Success

        val docId = store.insertDocument(doc.title, uri.toString(), doc.wordCount)
        store.saveText(docId, doc.text)

        val chunks = chunk(doc.text)
        if (chunks.isEmpty()) {
            store.deleteDocument(docId)
            emit(IngestProgress.Failed(ImportResult.Reason.EMPTY))
            return@flow
        }
        store.insertChunks(docId, chunks)

        // The opening chunks, not retrieved ones. For "teach me this document" the beginning is the
        // right context; retrieval exists for answering a specific question later.
        val lead = chunks.take(Prompts.MAX_CHUNKS)

        emit(IngestProgress.Teaching)
        val keyPoints = generateEnglish(docId, lead)

        emit(IngestProgress.Translating)
        renderHindi(docId, keyPoints)

        store.setStatus(docId, STATUS_READY)
        emit(IngestProgress.Done(docId, doc.title))
    }

    /**
     * Everything the generative model produces, inside one acquisition. Returns the English key
     * points so the Hindi pass does not have to read them back out of the database.
     *
     * Each artifact is attempted independently: a quiz the model mangles must not cost the student
     * their explanation. A document that ends up with key points and no quiz is still a lesson.
     */
    private suspend fun generateEnglish(docId: Long, lead: List<Chunk>): List<String> {
        var keyPoints = emptyList<String>()

        modelHost.withTeacher { teacher ->
            keyPoints = LessonParser.parseKeyPoints(teacher.collect(TeachRequest.Explain(lead)))
            keyPoints.forEachIndexed { i, point ->
                store.putArtifact(docId, KIND_EXPLANATION, LANG_EN, i, point)
            }
            Log.i(TAG, "doc $docId: ${keyPoints.size} key points")

            val quiz = runCatching { LessonParser.parseQuiz(teacher.collect(TeachRequest.Quiz(lead))) }
                .getOrElse {
                    Log.w(TAG, "doc $docId: quiz generation failed (${it.message})")
                    emptyList()
                }
            quiz.forEachIndexed { i, item ->
                store.putArtifact(docId, KIND_QUIZ, LANG_EN, i, item.encode())
            }
            Log.i(TAG, "doc $docId: ${quiz.size} quiz items")
        }

        return keyPoints
    }

    /**
     * One acquisition of the translation engine for every fragment of every artifact.
     *
     * Quiz items are translated here too rather than lazily per question. Lazily would be cheaper in
     * total work, but it would move a model swap into the middle of someone answering a quiz, and the
     * swap is the expensive part — not the translating.
     */
    private suspend fun renderHindi(docId: Long, keyPoints: List<String>) {
        if (keyPoints.isEmpty()) return

        val quizItems = store.artifacts(docId, KIND_QUIZ, LANG_EN).mapNotNull { QuizItem.decode(it) }

        // Flattened so the renderer makes a single pass, then split back apart by count.
        val quizLines = quizItems.flatMap { listOf(it.question, it.correct) + it.distractors }
        val translated = runCatching { hindi.toHindi(keyPoints + quizLines) }
            .getOrElse {
                Log.w(TAG, "doc $docId: Hindi rendering failed (${it.message})")
                return
            }

        translated.take(keyPoints.size).forEachIndexed { i, point ->
            store.putArtifact(docId, KIND_EXPLANATION, LANG_HI, i, point)
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
                LANG_HI,
                i,
                QuizItem(slice[0], slice[1], slice.drop(2)).encode(),
            )
        }
    }

    companion object {
        private const val TAG = "LessonPipeline"

        const val KIND_EXPLANATION = "explanation"
        const val KIND_QUIZ = "quiz"
        const val LANG_EN = "en"
        const val LANG_HI = "hi"
        const val STATUS_READY = "ready"
    }
}

/** Collects a whole stream into one string. Generation here is batch work; nothing renders it live. */
private suspend fun Teacher.collect(request: TeachRequest): String {
    val builder = StringBuilder()
    stream(request).collect { builder.append(it) }
    return builder.toString()
}
