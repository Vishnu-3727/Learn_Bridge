package com.learnbridge.app.doc

/**
 * Purpose:  Decides what the student should study next, across every document they have.
 * Owns:     Nothing — pure functions over rows the caller already read.
 * Lifetime: Stateless.
 * Thread:   Any. No I/O.
 *
 * **Why a transparent weighted sum and not a learned ranker.**
 *
 * A learned model needs training data this app does not have and, worse, cannot explain itself. The
 * student is entitled to know why they are being asked to revise something, and "because you scored
 * 40% on it and it fell due yesterday" is an answer a weighted sum can give and a neural ranker
 * cannot. The weights are tunable without retraining and work from the first quiz rather than after
 * a cold-start period.
 *
 * Ordering, not a schedule: this says what matters most right now. Capping it to a day's work is the
 * caller's business, and today the caller shows exactly one.
 */
object Revision {

    /** How much of the score comes from not knowing it. */
    private const val W_MASTERY_GAP = 1.0

    /** How much comes from being overdue. Deliberately below the gap weight: something the student
     *  never learned matters more than something they learned and are slightly late revisiting. */
    private const val W_OVERDUE = 0.6

    /** How much comes from having answered quickly and wrongly. Small, but it breaks ties toward the
     *  misconceptions — the student does not know they have those, so they will never pick them. */
    private const val W_CONFIDENTLY_WRONG = 0.3

    /** Past this, more lateness stops adding urgency: a month overdue and a year overdue are both
     *  "forgotten", and without a cap one ancient document would outrank everything forever. */
    private const val OVERDUE_SATURATION_DAYS = 14.0

    data class Candidate(
        val document: DocStore.DocumentRow,
        val mastery: Mastery?,
        val score: Double,
    )

    /**
     * Every document worth studying, most urgent first.
     *
     * A document that has never been quizzed is included with the maximum mastery gap — it is
     * entirely unknown, which is exactly the state revision exists to fix. Anything already at or
     * above [MASTERED] and not yet due is dropped: re-testing what the student demonstrably knows is
     * the specific waste this ranking exists to avoid.
     */
    fun rank(
        documents: List<DocStore.DocumentRow>,
        mastery: Map<Long, Mastery>,
        now: Long = System.currentTimeMillis(),
    ): List<Candidate> = documents
        .map { doc -> Candidate(doc, mastery[doc.id], score(mastery[doc.id], now)) }
        .filter { it.score > 0.0 }
        .sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.document.ingestedAt })

    /** The single thing to do next, or null when nothing is worth surfacing. */
    fun next(
        documents: List<DocStore.DocumentRow>,
        mastery: Map<Long, Mastery>,
        now: Long = System.currentTimeMillis(),
    ): Candidate? = rank(documents, mastery, now).firstOrNull()

    private fun score(record: Mastery?, now: Long): Double {
        // Never quizzed: nothing is known about it, so the gap is total.
        if (record == null || record.exposureCount == 0) return W_MASTERY_GAP

        val known = record.decayed(now)
        if (known >= MASTERED && !record.isDue(now)) return 0.0

        val gap = (1.0 - known) * W_MASTERY_GAP
        val overdueDays = ((now - record.dueAt).toDouble() / Mastery.DAY_MS).coerceAtLeast(0.0)
        val overdue = (overdueDays / OVERDUE_SATURATION_DAYS).coerceAtMost(1.0) * W_OVERDUE
        val misconception = if (record.isConfidentlyWrong) W_CONFIDENTLY_WRONG else 0.0

        return gap + overdue + misconception
    }

    /** At or above this, and not yet due, a document is left alone. */
    const val MASTERED = 0.85
}
