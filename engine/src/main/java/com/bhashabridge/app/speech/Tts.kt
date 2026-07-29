package com.bhashabridge.app.speech

import android.content.Context
import android.content.Intent
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

    private val app = context.applicationContext

    /**
     * The engine package this instance speaks through, or null to accept the system default.
     *
     * **Why this is not left to the default.** The default on a Samsung device is Samsung TTS, which
     * on the SM-M315F carries English and Korean; Google's engine is the one with voices for all
     * thirteen lesson languages. A student whose phone prefers an engine that cannot speak Tamil
     * would be told to install a voice that engine will never have.
     */
    private val enginePackage = preferredEngine(app)

    private val engine = TextToSpeech(app, { status -> handleInit(status) }, enginePackage)

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
     * Whether this device can speak [locale] **right now, offline**.
     *
     * Callers use it to say "install voice data for தமிழ்" instead of silently substituting a voice
     * the student did not ask for. Returns false before the engine finishes initialising.
     *
     * **Why this no longer trusts `isLanguageAvailable`.** It used to, and that is why Listen
     * appeared to work in English only. Google TTS answers that question with what it could
     * *obtain*, not with what is present: measured on the SM-M315F, all thirteen lesson languages
     * returned `LANG_COUNTRY_AVAILABLE` while `engine.voices` reported 39 installed English voices,
     * 9 Hindi, and **zero** for the other eleven. No missing-data code was ever returned, so
     * [speak]'s fallback never fired and the engine was handed text in a voice it did not have —
     * silence, with no warning, on exactly the languages this app exists to serve.
     *
     * A [android.speech.tts.Voice] carrying [TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED] is one
     * the engine would have to download, and downloading needs a network this app deliberately never
     * has. Only a voice without that feature can be spoken here.
     */
    fun voiceAvailable(locale: Locale): Boolean = ready && installedVoiceExists(locale)

    /**
     * Where to send the student to get a missing voice, best first, for a caller to try in order.
     *
     * **This app cannot download the voice itself and never will** — it holds no `INTERNET`
     * permission, which is the point of it. The speech engine has its own network access, so the
     * download happens inside the engine, over the engine's connection, under the student's control.
     * That is also why it cannot be silent or automatic: the bytes are the engine's to fetch and the
     * consent is the student's to give.
     *
     * First entry is the engine's own voice-data installer, aimed at [enginePackage] so a device with
     * two engines does not open the one that lacks Indic voices. Second is the platform's
     * text-to-speech settings page, which reaches the same downloads by hand — kept because
     * `INSTALL_TTS_DATA` is optional for an engine to implement, and a dead first choice would
     * otherwise leave the student with instructions and no door.
     */
    fun voiceDataIntents(): List<Intent> = listOf(
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).also { it.`package` = enginePackage },
        Intent("com.android.settings.TTS_SETTINGS"),
    )

    /**
     * Matched on language alone, not language-and-country: the engine ships `hi-IN` voices for a
     * request for `hi`, and a student who has the language installed for a different region should
     * still be read to rather than told to install something they already have.
     */
    private fun installedVoiceExists(locale: Locale): Boolean = runCatching {
        engine.voices.orEmpty().any { voice ->
            voice.locale.language == locale.language &&
                !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
    }.getOrDefault(false)

    /**
     * Speaks [text] in [locale], falling back to the English voice when that language has no
     * installed voice. Deliberate: hearing the wrong voice beats silence with no explanation, and
     * callers can check [voiceAvailable] first when they want to say so instead.
     */
    fun speak(text: String, locale: Locale) {
        if (!ready || text.isBlank()) return
        try {
            if (installedVoiceExists(locale)) {
                engine.setLanguage(locale)
            } else {
                logWarn(LogTag.SPEECH, "No installed voice for $locale; speaking with the English voice")
                engine.setLanguage(Locale.ENGLISH)
            }
            // QUEUE_FLUSH for the first segment so a new request replaces whatever is speaking, then
            // QUEUE_ADD to keep the rest of the same request behind it.
            segmentsFor(text).forEachIndexed { i, segment ->
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(segment, mode, null, "$UTTERANCE_ID-$i")
            }
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

    internal companion object {

        /**
         * The installed speech engine most likely to be able to speak an Indic language, or null when
         * none of the known ones is present and the system default is as good a guess as any.
         *
         * Chosen by package name rather than by asking each engine what it supports: an engine
         * reports availability for voices it would have to download (see [voiceAvailable]), so that
         * question cannot separate "has Tamil" from "could fetch Tamil" at construction time.
         */
        private fun preferredEngine(context: Context): String? = runCatching {
            val installed = context.packageManager
                .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.packageName }
            PREFERRED_ENGINES.firstOrNull(installed::contains)
        }.getOrNull()

        /**
         * Google's engine first: it is the only one carrying voices for all thirteen lesson
         * languages. `com.samsung.SMT` is next only because it is the default on the test device and
         * beats no engine at all; anything else falls through to the system default.
         */
        private val PREFERRED_ENGINES = listOf("com.google.android.tts", "com.samsung.SMT")

        /**
         * Splits [text] into pieces the engine will accept, preferring sentence ends and then word
         * boundaries so a split is never heard mid-word.
         *
         * `TextToSpeech.speak` **rejects** input longer than [TextToSpeech.getMaxSpeechInputLength]
         * (4,000 characters) outright: it returns ERROR and says nothing at all. One key-point list
         * used to be five lines and could not approach that. A whole document's lesson is now every
         * section's key points concatenated — a 30-page PDF reaches roughly 7,000 characters — so
         * without this, Listen would go silent on exactly the longest documents, which is the least
         * debuggable way for it to fail.
         */
        internal fun segmentsFor(text: String, limit: Int = MAX_INPUT): List<String> {
            if (text.length <= limit) return listOf(text)

            val segments = mutableListOf<String>()
            var rest = text
            while (rest.length > limit) {
                val window = rest.substring(0, limit)
                // A sentence end late in the window, else a space, else a hard cut — the last case
                // only happens for text with no breaks at all in 4,000 characters.
                val cut = window.lastIndexOfAny(SENTENCE_ENDS).takeIf { it > limit / 2 }?.plus(1)
                    ?: window.lastIndexOf(' ').takeIf { it > limit / 2 }
                    ?: limit
                segments += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trimStart()
            }
            if (rest.isNotBlank()) segments += rest
            return segments
        }

        private const val UTTERANCE_ID = "bb_tts_out"

        /** [TextToSpeech.getMaxSpeechInputLength] is 4,000; kept just under it. */
        private const val MAX_INPUT = 3_900

        private val SENTENCE_ENDS = charArrayOf('.', '!', '?', '।', '۔', '\n')
    }
}
