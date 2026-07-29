package com.learnbridge.app.lang

import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.bhashabridge.app.speech.Tts
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Which of the thirteen lesson languages this device can actually speak.
 *
 * Not an assertion about the app — an assertion about the *device*, which is why it asserts almost
 * nothing and logs everything. "Listen only works for English" is the report this exists to answer,
 * and the answer is not in the app's code: [com.bhashabridge.app.speech.Tts] already selects the
 * voice from [SupportedLanguage.locale], and falls back to the English voice when the requested
 * language has no data installed. So a student who hears English for every language is a student
 * whose phone has no Indic voice data — a fixable device state that looks exactly like an app bug.
 *
 * Run it before changing any speech code:
 * ```
 * adb shell am instrument -w -e class com.learnbridge.app.lang.TtsVoiceDeviceTest \
 *   com.learnbridge.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
class TtsVoiceDeviceTest {

    @Test
    fun report_which_lesson_languages_this_device_can_speak() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        lateinit var engine: TextToSpeech

        engine = TextToSpeech(context) {
            status = it
            ready.countDown()
        }

        try {
            check(ready.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "TTS never initialised" }
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "no TTS engine on this device (status=$status)")
                return
            }

            Log.i(TAG, "engine: ${engine.defaultEngine}")

            val speakable = mutableListOf<String>()
            val missing = mutableListOf<String>()

            for (language in SupportedLanguage.entries) {
                val result = engine.isLanguageAvailable(language.locale)
                val verdict = when (result) {
                    TextToSpeech.LANG_NOT_SUPPORTED -> "NOT SUPPORTED by this engine"
                    TextToSpeech.LANG_MISSING_DATA -> "voice data NOT INSTALLED"
                    TextToSpeech.LANG_AVAILABLE -> "available"
                    TextToSpeech.LANG_COUNTRY_AVAILABLE -> "available (country match)"
                    TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "available (exact match)"
                    else -> "unknown code $result"
                }
                if (result >= TextToSpeech.LANG_AVAILABLE) speakable += language.code else missing += language.code
                Log.i(TAG, "${language.code} (${language.endonym}) ${language.locale}: $verdict")
            }

            Log.i(TAG, "SPEAKABLE: ${speakable.joinToString()}")
            Log.i(TAG, "SILENT (falls back to the English voice): ${missing.joinToString()}")

            // isLanguageAvailable is not the whole answer. Google TTS reports a language as
            // available when it *could* fetch the voice, and fetching needs a network this app
            // deliberately never has. The Voice's own feature set is where that shows up.
            for (language in SupportedLanguage.entries) {
                val voices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
                    .filter { it.locale.language == language.locale.language }
                if (voices.isEmpty()) {
                    Log.i(TAG, "${language.code}: no Voice object at all")
                    continue
                }
                val installed = voices.filterNot {
                    it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }
                Log.i(
                    TAG,
                    "${language.code}: ${voices.size} voices, ${installed.size} installed" +
                        if (installed.isEmpty()) "  <-- WOULD NEED A DOWNLOAD" else "",
                )
            }
        } finally {
            engine.shutdown()
        }
    }

    /**
     * The app's own [Tts] must agree with what the engine actually has installed.
     *
     * This is the regression guard for the trap that made Listen look English-only: `voiceAvailable`
     * was built on `isLanguageAvailable`, which reported every lesson language as available on a
     * device that had installed voices for two of them. Any future rewrite that reaches for that API
     * again fails here rather than in a student's hands.
     */
    @Test
    fun the_apps_own_availability_check_matches_what_is_installed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        val tts = Tts(context) { ready.countDown() }

        try {
            check(ready.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "TTS never initialised" }

            val probe = TextToSpeech(context) {}
            Thread.sleep(PROBE_SETTLE_MILLIS)
            val installed = try {
                SupportedLanguage.entries.filter { language ->
                    probe.voices.orEmpty().any {
                        it.locale.language == language.locale.language &&
                            !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                    }
                }
            } finally {
                probe.shutdown()
            }

            Log.i(TAG, "installed per the engine: ${installed.map { it.code }}")
            Log.i(TAG, "reported by Tts: ${SupportedLanguage.entries.filter { tts.voiceAvailable(it.locale) }.map { it.code }}")

            for (language in SupportedLanguage.entries) {
                assertEquals(
                    "Tts disagrees with the engine about ${language.code}",
                    language in installed,
                    tts.voiceAvailable(language.locale),
                )
            }
        } finally {
            tts.shutdown()
        }
    }

    private companion object {
        const val TAG = "TtsVoices"
        const val INIT_TIMEOUT_SECONDS = 20L

        /** The voice list is populated a moment after onInit; without this the probe sees none. */
        const val PROBE_SETTLE_MILLIS = 1_500L
    }
}
