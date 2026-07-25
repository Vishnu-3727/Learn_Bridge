package com.learnbridge.app.teach

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Purpose:  A [Teacher] that streams canned text at a realistic pace, with no model behind it.
 * Owns:     Nothing.
 * Lifetime: Cheap to construct; no release needed.
 * Thread:   Any.
 *
 * Written before the real one deliberately. Every screen, parser, and pipeline stage can then be
 * built and unit-tested on the JVM without a device, a 554 MB model file, or a working MediaPipe
 * integration — so a failure in on-device generation blocks only on-device generation.
 *
 * The default delay mimics ~10 tokens/second, which is the order of magnitude expected on a
 * mid-range CPU. Keeping the fake *slow* is the point: UI built against an instant fake hides
 * exactly the jank and layout-shift problems that streaming causes in production.
 */
class FakeTeacher(
    private val response: String = DEFAULT_RESPONSE,
    private val chunkDelayMs: Long = 100L,
) : Teacher {

    override fun stream(request: TeachRequest): Flow<String> = flow {
        // Split on whitespace but keep it: real runtimes emit sub-word chunks including the
        // leading space, and consumers must handle that rather than re-joining with " ".
        for (chunk in response.split(WORD_BOUNDARY)) {
            if (chunk.isEmpty()) continue
            delay(chunkDelayMs)
            emit(chunk)
        }
    }

    override fun release() = Unit

    private companion object {
        /** Splits before each space, so every chunk after the first carries its own leading space. */
        val WORD_BOUNDARY = Regex("(?= )")

        val DEFAULT_RESPONSE = """
            Photosynthesis is how plants make food.
            They use sunlight, water and carbon dioxide.
            The leaves hold a green pigment called chlorophyll.
            Chlorophyll captures energy from sunlight.
            The plant turns that energy into sugar.
            Oxygen is released into the air.
        """.trimIndent()
    }
}
