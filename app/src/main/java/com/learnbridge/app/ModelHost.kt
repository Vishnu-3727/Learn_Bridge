package com.learnbridge.app

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.bhashabridge.app.Direction
import com.bhashabridge.app.mt.DecodeConfig
import com.bhashabridge.app.mt.GreedyDecoder
import com.bhashabridge.app.mt.MtEngine
import com.learnbridge.app.teach.Teacher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Purpose:  Decides which large models may be resident at once, and hands them out one caller at a
 *           time. This is the only place in the app that loads or releases a model.
 * Owns:     One [Teacher] and one [MtEngine] per [Direction].
 * Lifetime: Process. Created by [LearnBridgeApp].
 * Thread:   Every entry point is suspend and serialised by [gate]. Safe from any coroutine.
 *
 * Why this exists at all: the translation engine costs ~605 MB resident and the generative model
 * costs an estimated ~2.1 GB. On the 6 GB target device the two together sit close enough to the
 * low-memory killer's attention that co-residency is a measurement, not an assumption. So residency
 * is a runtime decision with three outcomes ([Tier]), and callers never see which one they got.
 *
 * The mutex does double duty. It serialises model loading, and because an entire generation — prefill
 * plus every decoded token — happens inside one [withTeacher] block, it is also what guarantees no
 * other coroutine can swap a model out from under an in-flight turn. There is no separate mid-turn
 * guard because none is needed.
 */
class ModelHost(private val app: LearnBridgeApp) {

    /** How much may be resident simultaneously. Decided once per process, may downgrade, never upgrades. */
    enum class Tier {
        /** Both models stay loaded. Language toggles are instant. */
        CO_RESIDENT,

        /** One large model at a time. A language toggle costs a swap (~7 s). */
        EXCLUSIVE,

        /** No generative model at all. The extractive tutor and translation only. */
        EXTRACTIVE,
    }

    private val gate = Mutex()

    private var teacher: Teacher? = null
    private val engines = HashMap<Direction, MtEngine>()

    /** Which model was handed out last. In [Tier.EXCLUSIVE] this is the one currently resident. */
    private var lastUsed: Slot? = null

    private enum class Slot { TEACHER, TRANSLATOR }

    @Volatile
    var tier: Tier = initialTier()
        private set

    /**
     * The borrow point for generation. The block runs with the mutex held, so prefill and the whole
     * decode are protected from a concurrent swap.
     *
     * In [Tier.EXCLUSIVE] this may release the translation engine first, which costs the caller a
     * load. Callers must therefore surface a "working" state before entering, not after.
     */
    suspend fun <T> withTeacher(block: suspend (Teacher) -> T): T = gate.withLock {
        if (tier == Tier.EXCLUSIVE && lastUsed == Slot.TRANSLATOR) releaseTranslatorsLocked()
        lastUsed = Slot.TEACHER
        block(teacherLocked())
    }

    /**
     * The borrow point for translation.
     *
     * Callers must batch: acquire once and translate every fragment inside a single block. The
     * failure mode this prevents is a renderer that acquires per sentence — in [Tier.EXCLUSIVE] that
     * would pay a full model swap per fragment and turn a 6-second render into minutes.
     */
    suspend fun <T> withTranslator(
        direction: Direction = Direction.EN_TO_HI,
        block: suspend (MtEngine) -> T,
    ): T = gate.withLock {
        if (tier == Tier.EXCLUSIVE && lastUsed == Slot.TEACHER) releaseTeacherLocked()
        lastUsed = Slot.TRANSLATOR
        block(translatorLocked(direction))
    }

    // --- Loading. Both are called only with [gate] held. ---

    private fun teacherLocked(): Teacher =
        teacher ?: app.createTeacher(tier).also {
            Log.i(TAG, "Teacher loaded (tier=$tier)")
            teacher = it
        }

    /**
     * Note the decoder config. The engine's own default is `maxSteps = 18, minTargetLen = 14`, which
     * caps ANY single translation at roughly fourteen Hindi words — fine for the phrase-sized inputs
     * it was tuned for, ruinous for an explanation or a quiz stem. That default was inherited to
     * match an older build's parity gate, not chosen from a quality measurement.
     *
     * Raising it here is a call-site change: [MtEngine] takes the decoder as a constructor parameter,
     * so nothing inside the frozen engine module is touched. Callers still split long text into short
     * fragments (see HindiRenderer) — this raises the ceiling, it does not remove the need for that.
     */
    private fun translatorLocked(direction: Direction): MtEngine =
        engines.getOrPut(direction) {
            Log.i(TAG, "MT engine loading: $direction")
            MtEngine(
                app,
                direction,
                GreedyDecoder(DecodeConfig(maxSteps = LONG_FORM_MAX_STEPS, minTargetLen = LONG_FORM_MIN_TARGET)),
            )
        }

    // --- Releasing. All callers hold [gate], except [onTrimMemory] which is documented below. ---

    private fun releaseTeacherLocked() {
        teacher?.let {
            Log.i(TAG, "Releasing teacher to make room for translation")
            it.release()
        }
        teacher = null
    }

    private fun releaseTranslatorsLocked() {
        if (engines.isEmpty()) return
        Log.i(TAG, "Releasing ${engines.size} MT engine(s) to make room for generation")
        engines.values.forEach { it.release() }
        engines.clear()
    }

    /**
     * Memory-pressure response, called from [LearnBridgeApp.onTrimMemory].
     *
     * Deliberately does NOT take [gate]: onTrimMemory arrives on the main thread and blocking it
     * behind an in-flight generation would freeze the UI for seconds. Instead:
     *  - At CRITICAL we only downgrade the tier, so the *next* borrow frees the idle model. Nothing
     *    is released underneath a running generation.
     *  - At COMPLETE the app is backgrounded and a kill candidate, so releasing immediately is both
     *    safe and the entire point.
     *
     * The downgrade is permanent for the process. A device that hit critical pressure once with both
     * models loaded will hit it again, and re-trying co-residency mid-session is how a demo dies.
     */
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.i(TAG, "Trim $level — releasing every model")
                releaseTeacherLocked()
                releaseTranslatorsLocked()
            }

            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && tier == Tier.CO_RESIDENT -> {
                Log.w(TAG, "Trim $level — downgrading CO_RESIDENT to EXCLUSIVE for this process")
                tier = Tier.EXCLUSIVE
                app.rememberNoCoResidency()
            }
        }
    }

    /**
     * Called when loading the generative model throws [OutOfMemoryError] or the runtime reports it
     * cannot initialise. Drops to the extractive tutor for the rest of the process and remembers it,
     * so the next launch does not repeat a load that is known to fail on this hardware.
     */
    fun degradeToExtractive() {
        Log.w(TAG, "Generative model unavailable — falling back to the extractive tutor")
        tier = Tier.EXTRACTIVE
        teacher = null
        app.rememberExtractiveOnly()
    }

    /**
     * Tier selection from physical memory, with one wrinkle worth stating: co-residency on a 6 GB
     * device is plausible but unproven, because the generative model's peak footprint is a
     * third-party figure rather than a measured one. So devices in that band *attempt* co-residency
     * once; if it fails or the OS complains, [rememberNoCoResidency] makes the answer stick and the
     * app never retries. Try-once-and-remember beats a hardcoded threshold nobody can tune.
     */
    private fun initialTier(): Tier {
        if (app.extractiveOnlyRemembered()) return Tier.EXTRACTIVE

        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) return Tier.EXTRACTIVE

        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = info.totalMem / (1024 * 1024)
        Log.i(TAG, "Device totalMem = $totalMb MB, lowRam = ${am.isLowRamDevice}")

        return when {
            totalMb < EXTRACTIVE_CEILING_MB -> Tier.EXTRACTIVE
            app.noCoResidencyRemembered() -> Tier.EXCLUSIVE
            totalMb >= CO_RESIDENT_FLOOR_MB -> Tier.CO_RESIDENT
            else -> Tier.EXCLUSIVE
        }
    }

    private companion object {
        const val TAG = "ModelHost"

        /** ~605 MB of MT plus ~2.1 GB of generative model cannot fit under this. */
        const val EXTRACTIVE_CEILING_MB = 3_500

        /**
         * A phone advertised as 8 GB reports roughly 7.4–7.8 GB of totalMem; a 6 GB one reports
         * ~5.6 GB. This floor admits the former outright. The band below it attempts co-residency
         * once and remembers the outcome.
         */
        const val CO_RESIDENT_FLOOR_MB = 6_500

        /**
         * The decoder's inherited default was 18 steps / 14 minimum, which caps any single call at
         * roughly fourteen Hindi words — fine for the phrase-sized inputs it was tuned for, ruinous
         * for a full sentence.
         *
         * 48 costs nothing for short inputs, because decoding stops at the end-of-sentence token
         * regardless: observed real translations finish in 3-14 steps. A high cap does not lengthen
         * output, it only stops long output from being cut off mid-sentence.
         *
         * Raised 32 -> 48 so HindiRenderer can send whole 18-word sentences instead of splitting them
         * at commas — measured on device, split clauses translate with visibly worse grammar than the
         * sentence they came from.
         */
        const val LONG_FORM_MAX_STEPS = 48
        const val LONG_FORM_MIN_TARGET = 48
    }
}
