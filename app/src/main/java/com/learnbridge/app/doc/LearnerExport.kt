package com.learnbridge.app.doc

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Purpose:  Turns what the app has learned about a student into two files they can keep.
 * Owns:     Nothing — pure formatting over rows the caller already read.
 * Lifetime: Stateless.
 * Thread:   Any. No I/O; the caller writes the strings.
 *
 * **Two formats, because "export your data" means two different things to two different people.**
 * A parent wants to read it. A student moving to another device, or anyone checking the claim that
 * this is a compact model rather than a transcript, wants to parse it. Emitting only JSON would
 * satisfy the letter of an export and none of the point; emitting only prose would make the data
 * unusable anywhere else.
 *
 * Neither file contains document text. The export is what the app concluded — mastery, confidence,
 * schedule — not a copy of the student's own material, which they already have.
 */
object LearnerExport {

    /** Bumped when the JSON shape changes, so an importer can tell what it is looking at. */
    const val SCHEMA_VERSION = 1

    data class Row(val document: DocStore.DocumentRow, val mastery: Mastery?)

    /** What a person reads. */
    fun asText(rows: List<Row>, now: Long = System.currentTimeMillis()): String {
        val out = StringBuilder()
        out.appendLine("LearnBridge — what this app has learned about you")
        out.appendLine("Exported ${timestamp(now)}")
        out.appendLine()
        out.appendLine("This file lists what LearnBridge worked out from your quiz answers.")
        out.appendLine("It does not contain your documents, only conclusions about them.")
        out.appendLine()

        if (rows.isEmpty()) {
            out.appendLine("Nothing has been learned yet — no document has been quizzed.")
            return out.toString()
        }

        for (row in rows) {
            out.appendLine(row.document.title)
            val m = row.mastery
            if (m == null || m.exposureCount == 0) {
                out.appendLine("  Not quizzed yet.")
            } else {
                out.appendLine("  Learned:      ${percent(m.decayed(now))}  (${percent(m.mastery)} when last tested)")
                out.appendLine("  Confidence:   ${percent(m.confidence)}")
                out.appendLine("  Quizzes taken: ${m.exposureCount}")
                out.appendLine("  Last studied: ${timestamp(m.lastSeen)}")
                out.appendLine("  Next review:  ${timestamp(m.dueAt)}${if (m.isDue(now)) "  (due now)" else ""}")
                if (m.isConfidentlyWrong) {
                    out.appendLine("  Note: answered quickly but incorrectly — likely a misunderstanding.")
                }
            }
            out.appendLine()
        }
        return out.toString()
    }

    /** What another program reads. */
    fun asJson(rows: List<Row>, now: Long = System.currentTimeMillis()): String {
        val documents = JSONArray()
        for (row in rows) {
            val entry = JSONObject()
                .put("title", row.document.title)
                .put("wordCount", row.document.wordCount)
                .put("importedAt", row.document.ingestedAt)
            val m = row.mastery
            if (m != null && m.exposureCount > 0) {
                entry.put(
                    "mastery",
                    JSONObject()
                        .put("score", round(m.mastery))
                        .put("scoreToday", round(m.decayed(now)))
                        .put("confidence", round(m.confidence))
                        .put("quizzesTaken", m.exposureCount)
                        .put("lastSeen", m.lastSeen)
                        .put("reviewIntervalDays", m.intervalDays)
                        .put("easeFactor", round(m.easeFactor))
                        .put("dueAt", m.dueAt),
                )
            }
            documents.put(entry)
        }

        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAt", now)
            .put("documents", documents)
            .toString(2)
    }

    private fun percent(value: Double) = "${(value * 100).toInt()}%"

    private fun round(value: Double) = Math.round(value * 1000.0) / 1000.0

    // Locale.US for the timestamp, deliberately: this file may be read by a program or sent to
    // someone else, and a date whose format depends on the exporting phone's locale is a date that
    // has to be guessed at.
    private fun timestamp(millis: Long) =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
}
