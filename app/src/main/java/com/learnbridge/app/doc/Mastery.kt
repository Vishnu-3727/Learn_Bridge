package com.learnbridge.app.doc

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Purpose:  What the app has learned about the student, for one document, and when to show it again.
 * Owns:     Nothing — an immutable value. [DocStore] persists it; [afterQuiz] produces the next one.
 * Lifetime: Value type.
 * Thread:   Immutable, so any.
 *
 * **Why this is a fixed-shape record and not a history of attempts.**
 *
 * The obvious design is a table of every quiz answer, queried later to work out how the student is
 * doing. That table existed here once and was deleted, unread, because nothing ever asked it a
 * question. The reason to prefer this shape is not that the log was unused, though: a log grows
 * without bound for the life of the app, and the thing anyone actually wants from it — how well is
 * this understood, how sure are they, when will they forget it — is a handful of numbers that can be
 * updated in place. Everything below aggregates into a row of fixed width. Nothing accumulates.
 *
 * **Why mastery and confidence are two numbers rather than one.**
 *
 * A student can be confident and wrong, or correct and unsure. Those need opposite responses — the
 * first is a misconception to correct, the second is fragile knowledge to reinforce — and a single
 * combined score cannot tell them apart. Mastery is measured from answers; confidence is inferred
 * from how long the answers took.
 */
data class Mastery(
    val docId: Long,
    /** [0,1]. How much of this document the student demonstrably knows. */
    val mastery: Double,
    /** [0,1]. How sure they appeared to be, from answer latency. Deliberately not derived from [mastery]. */
    val confidence: Double,
    /** How many times this document has been quizzed. */
    val exposureCount: Int,
    val lastSeen: Long,
    /** Days until the next review. SM-2's interval. */
    val intervalDays: Int,
    /** SM-2's ease factor, clamped to [MIN_EASE]..[MAX_EASE]. Lower means this document keeps slipping. */
    val easeFactor: Double,
    val dueAt: Long,
) {
    /** Whole percent, for display. */
    val masteryPercent: Int get() = (mastery * 100).roundToInt()

    fun isDue(now: Long = System.currentTimeMillis()): Boolean = now >= dueAt

    /**
     * True when the student answered quickly and got it wrong — the state worth interrupting for,
     * because they do not know that they do not know.
     */
    val isConfidentlyWrong: Boolean get() = confidence >= 0.6 && mastery < 0.5

    /**
     * The next record after a completed quiz: [correct] of [total] right, taking [medianLatencyMs]
     * per answer.
     *
     * Mastery moves toward the observed score rather than being replaced by it, so one unlucky run
     * cannot erase a history of good ones and one lucky run cannot claim mastery. The weight is
     * fixed.
     *
     * ponytail: a fixed learning rate, not the item-response-theory update this wants. IRT needs a
     * calibrated difficulty per question and the generator does not emit one — every question here is
     * assumed equally hard, so a hard question answered correctly counts no more than an easy one.
     * Upgrade path: have the quiz generator persist a difficulty estimate per item, then weight this
     * update by it.
     */
    fun afterQuiz(
        correct: Int,
        total: Int,
        medianLatencyMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Mastery {
        if (total <= 0) return this

        val observed = correct.toDouble() / total
        val nextMastery = (mastery + LEARNING_RATE * (observed - mastery)).coerceIn(0.0, 1.0)
        val nextConfidence =
            (confidence + LEARNING_RATE * (confidenceFrom(medianLatencyMs) - confidence)).coerceIn(0.0, 1.0)

        // SM-2, graded from the score rather than from a self-rating the app never asks for.
        val ease: Double
        val interval: Int
        when {
            observed >= 0.8 -> {
                ease = easeFactor + 0.1
                interval = if (exposureCount == 0) 1 else ceil(intervalDays * easeFactor).toInt()
            }
            observed >= 0.5 -> {
                ease = easeFactor - 0.15
                interval = if (exposureCount == 0) 1 else ceil(intervalDays * 1.2).toInt()
            }
            // Reset, not reduced: a document scored below half is one the student has lost, and
            // spacing it out again from a long interval would be scheduling them to forget it.
            else -> {
                ease = easeFactor - 0.2
                interval = 1
            }
        }
        val clampedEase = ease.coerceIn(MIN_EASE, MAX_EASE)
        val clampedInterval = interval.coerceIn(1, MAX_INTERVAL_DAYS)

        return copy(
            mastery = nextMastery,
            confidence = nextConfidence,
            exposureCount = exposureCount + 1,
            lastSeen = now,
            intervalDays = clampedInterval,
            easeFactor = clampedEase,
            dueAt = now + clampedInterval * DAY_MS,
        )
    }

    /**
     * Mastery as it stands [now], with the forgetting since [lastSeen] applied.
     *
     * Decay is computed on read rather than written by a background job: there is nothing to schedule,
     * nothing to run while the app is closed, and no way for a missed job to leave a stale number in
     * the database. The stored [mastery] is always "what they knew when last tested", which is the
     * honest thing to store; what they probably know today is derived.
     *
     * Exponential, halving over one interval's worth of days past the due date — the standard shape,
     * not a novel model.
     */
    fun decayed(now: Long = System.currentTimeMillis()): Double {
        val overdueDays = (now - dueAt).toDouble() / DAY_MS
        if (overdueDays <= 0) return mastery
        val halfLife = intervalDays.coerceAtLeast(1).toDouble()
        return mastery * Math.pow(0.5, overdueDays / halfLife)
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        private const val LEARNING_RATE = 0.4
        private const val MIN_EASE = 1.3
        private const val MAX_EASE = 2.5
        private const val MAX_INTERVAL_DAYS = 365

        /** Answered within this, the student was sure. */
        private const val FAST_MS = 6_000.0

        /** Past this, they were working it out rather than recalling it. */
        private const val SLOW_MS = 20_000.0

        /** The record for a document that has never been quizzed. Due immediately: it is all new. */
        fun initial(docId: Long, now: Long = System.currentTimeMillis()) = Mastery(
            docId = docId,
            mastery = 0.0,
            confidence = 0.0,
            exposureCount = 0,
            lastSeen = now,
            intervalDays = 1,
            easeFactor = 2.5,
            dueAt = now,
        )

        /**
         * Confidence from how long an answer took. Fast is sure, slow is working it out, and the
         * range between the two is linear.
         *
         * Latency is the only confidence signal available without asking the student "how sure were
         * you?" after every question, which is a real technique and a worse experience.
         */
        fun confidenceFrom(medianLatencyMs: Long): Double = when {
            medianLatencyMs <= 0 -> 0.5
            medianLatencyMs <= FAST_MS -> 1.0
            medianLatencyMs >= SLOW_MS -> 0.0
            else -> 1.0 - (medianLatencyMs - FAST_MS) / (SLOW_MS - FAST_MS)
        }
    }
}
