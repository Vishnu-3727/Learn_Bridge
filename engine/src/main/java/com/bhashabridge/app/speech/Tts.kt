package com.bhashabridge.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import com.bhashabridge.app.LogTag
import com.bhashabridge.app.logDebug
import com.bhashabridge.app.logError
import com.bhashabridge.app.logWarn
import java.util.Locale

/**
 * Purpose:  Speaks text in a requested language.
 * Owns:     One `TextToSpeech` engine.
 * Lifetime: Held by whichever screen reads content aloud; [shutdown] when that screen is destroyed.
 * Thread:   [speak] from the main thread. [onReady] fires on the engine's own callback thread.
 *
 * Initialisation is asynchronous and may report that a language's voice is missing — that is a
 * user-fixable device state ("install voice data"), not an app failure, so it is reported through
 * [voiceAvailable] rather than swallowed.
 *
 * **Why [speak] takes a [Locale] and not a [Direction].**
 *
 * It used to take a Direction, which has exactly two values, and mapped EN_TO_HI to a hardcoded
 * Hindi locale. That was correct in the two-language app this class came from. In an app that
 * teaches in thirteen languages it meant Tamil, Telugu, Kannada, Malayalam, Bengali, Gujarati,
 * Punjabi, Odia, Marathi, Nepali, Sanskrit and Urdu were all read aloud by the **Hindi** voice.
 * The caller knows the language; it should say so.
 */
class Tts(
    context: Context,
    private val onReady: () -> Unit,
) {

    private val engine = TextToSpeech(context.applicationContext) { status -> handleInit(status) }

    @Volatile var ready = false
        private set

    private fun handleInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            // No fallback engine is constructed here. v3.4.1 replaced the field with an explicit
            // Google-TTS instance, which on a device without that package simply failed a second
            // time; the user-visible outcome ("no speech") is the same, minus a second engine.
            logError(LogTag.SPEECH, "TTS engine failed to initialise (status=$status)")
            return
        }
        ready = true
        logDebug(LogTag.SPEECH) { "TTS ready" }
        onReady()
    }

    /**
     * Whether this device has usable voice data for [locale].
     *
     * Callers use it to say "install voice data for தமிழ்" instead of silently substituting a voice
     * the student did not ask for. Returns false before the engine finishes initialising.
     */
    fun voiceAvailable(locale: Locale): Boolean {
        if (!ready) return false
        val status = runCatching { engine.isLanguageAvailable(locale) }.getOrNull() ?: return false
        return status != TextToSpeech.LANG_MISSING_DATA && status != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * Speaks [text] in [locale], falling back to the English voice if that language's data is
     * missing. Deliberate: hearing the wrong voice beats silence with no explanation, and callers
     * can check [voiceAvailable] first when they want to say so instead.
     */
    fun speak(text: String, locale: Locale) {
        if (!ready || text.isBlank()) return
        try {
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                logWarn(LogTag.SPEECH, "Voice for $locale unavailable; speaking with the English voice")
                engine.setLanguage(Locale.ENGLISH)
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (e: Exception) {
            // Swallowed on purpose: a TTS failure must never disturb the text on screen.
            logError(LogTag.SPEECH, "TTS speak failed", e)
        }
    }

    fun stop() {
        if (ready) engine.stop()
    }

    fun shutdown() {
        engine.shutdown()
    }

    private companion object {
        const val UTTERANCE_ID = "bb_tts_out"
    }
}
