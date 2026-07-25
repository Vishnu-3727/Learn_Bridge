package com.learnbridge.app.lang

/**
 * Purpose:  Converts Devanagari-encoded Indic text into another Brahmic script.
 * Owns:     Nothing — a pure character mapping.
 * Lifetime: Stateless.
 * Thread:   Any.
 *
 * **Why this exists at all.**
 *
 * IndicTrans2 does not emit Tamil in Tamil script. It normalises every Indic language to a single
 * script-unified Devanagari representation, and the reference pipeline transliterates back to the
 * native script as a *post-processing* step. The ONNX export carries the model but not that step, so
 * asking for Tamil produced perfectly good Tamil written in Devanagari:
 *
 * ```
 * नीर् चुऴऱ्चि ऎऩ्ऱु अऴैक्कप्पटुकिऱतु     (what the model emits)
 * நீர் சுழற்சி என்று அழைக்கப்படுகிறது      (what it should read as)
 * ```
 *
 * That is a missing 60-line function, not a missing model — which is why the South Indian languages
 * are reachable from the same 472 MB of weights as Hindi.
 *
 * **Why a fixed offset is correct rather than a lookup table.**
 *
 * The Unicode Brahmic blocks are deliberately laid out in parallel: each is 128 code points, and the
 * same position in each block is the same phoneme. So Devanagari → Tamil is `codepoint + 0x280`,
 * Devanagari → Telugu is `+ 0x300`, and so on. Checked by hand against the model's own output —
 * `प`+0x280 → `ப`, `ि`+0x280 → `ி`, `ऴ`+0x280 → `ழ`, all exact.
 *
 * IndicTrans2 relies on this too: it uses the extended Devanagari letters `ऴ ऱ ऩ ळ` precisely because
 * they have counterparts at the same offsets in the southern scripts, which makes the round trip
 * lossless.
 *
 * **The one thing an offset cannot do.** Not every position is assigned in every block — Tamil has no
 * aspirated or voiced stops, so `ख` maps to an unassigned Tamil code point. A model translating *into*
 * Tamil should never emit those, but "should never" is not "cannot", so unmapped results are dropped
 * rather than written out as tofu boxes.
 */
object BrahmicTransliterator {

    private const val DEVANAGARI_START = 0x0900
    private const val DEVANAGARI_END = 0x097F

    /**
     * Danda and double danda sit in the Devanagari block but are shared punctuation — every Brahmic
     * script uses them as-is. Offsetting them lands on unrelated letters.
     */
    private val SHARED_PUNCTUATION = setOf(0x0964, 0x0965)

    /**
     * Devanagari digits map to the target script's own digits, which exist at the same offset. Left in
     * the general path; listed here only to note the behaviour is intentional.
     */
    private val DEVANAGARI_DIGITS = 0x0966..0x096F

    /**
     * Rewrites [text] from Devanagari into [targetOffset]'s script.
     *
     * Characters outside the Devanagari block — spaces, Latin, punctuation, digits the model copied
     * from the source — pass through untouched.
     */
    fun transliterate(text: String, targetOffset: Int): String {
        if (targetOffset == 0) return text

        val out = StringBuilder(text.length)
        for (ch in text) {
            val cp = ch.code
            when {
                cp !in DEVANAGARI_START..DEVANAGARI_END -> out.append(ch)
                cp in SHARED_PUNCTUATION -> out.append(ch)
                else -> {
                    val mapped = cp + targetOffset
                    // Skip rather than emit an unassigned code point: a missing character is less
                    // damaging on screen than a row of replacement boxes.
                    if (isAssigned(mapped)) out.append(mapped.toChar())
                }
            }
        }
        return out.toString()
    }

    /** Whether a code point exists in Unicode at all. Guards the gaps in the southern blocks. */
    private fun isAssigned(codePoint: Int): Boolean =
        Character.getType(codePoint) != Character.UNASSIGNED.toInt()

    /**
     * Devanagari digits are also remapped, so a translated sentence does not mix scripts. Exposed for
     * tests, which is the only reason it is not inlined.
     */
    internal fun isDevanagariDigit(codePoint: Int): Boolean = codePoint in DEVANAGARI_DIGITS
}
