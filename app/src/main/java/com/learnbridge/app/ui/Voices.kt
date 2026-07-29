package com.learnbridge.app.ui

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bhashabridge.app.speech.Tts
import com.learnbridge.app.R
import com.learnbridge.app.lang.SupportedLanguage

/**
 * Purpose:  Read-aloud voices: which ones this phone has, and how to get one it does not.
 * Owns:     Nothing. Both functions borrow a caller's [Tts] and show a dialog.
 * Lifetime: Stateless.
 * Thread:   Main.
 *
 * Shared by the Library (where a student picks a voice deliberately) and the Lesson screen (where a
 * missing voice announces itself the moment Listen is pressed). One copy because the second copy
 * would be the one that forgets `runCatching`.
 *
 * **Nothing here downloads anything.** The app declares no `INTERNET` permission — that is the whole
 * promise of it — so every path ends at the speech engine's own installer, which has its own network
 * and its own consent. See [Tts.voiceDataIntents].
 */

/**
 * Lists every lesson language with whether this phone can already speak it, and sends a tap on a
 * missing one to the engine's installer.
 *
 * A list rather than a "download all" button: thirteen voices is a few hundred megabytes over what
 * may be metered data, and a student studying in Tamil needs Tamil. Nothing is fetched until they
 * choose a language, and the ones already installed are shown so the choice is informed rather than
 * a guess.
 */
fun AppCompatActivity.showVoiceCatalog(tts: Tts) {
    if (isFinishing || isDestroyed) return

    val languages = SupportedLanguage.entries
    val rows = languages.map { language ->
        val label = if (tts.voiceAvailable(language.locale)) R.string.voice_row_installed
        else R.string.voice_row_missing
        getString(label, language.endonym)
    }

    AlertDialog.Builder(this)
        .setTitle(R.string.voices_title)
        .setItems(rows.toTypedArray()) { _, index ->
            val language = languages[index]
            if (tts.voiceAvailable(language.locale)) {
                Toast.makeText(
                    this,
                    getString(R.string.voice_row_installed, language.endonym),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                startVoiceDownload(tts, language.endonym)
            }
        }
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

/**
 * Opens the first of [Tts.voiceDataIntents] that resolves, falling back to the old notice when none
 * does.
 *
 * The engine's screen lists its whole voice catalogue and takes no "install this language" extra —
 * `ACTION_INSTALL_TTS_DATA` has none — so [name] is what tells the student which one they came for.
 *
 * Not gated on `resolveActivity`: from API 30 it returns null for a package this app cannot see, so it
 * reports failure on exactly the devices where the screen opens. Attempt and catch instead — the same
 * trap the camera and share paths already hit.
 */
fun AppCompatActivity.startVoiceDownload(tts: Tts, name: String) {
    val opened = tts.voiceDataIntents().any { runCatching { startActivity(it) }.isSuccess }
    if (!opened) {
        Toast.makeText(this, getString(R.string.tts_voice_missing, name), Toast.LENGTH_LONG).show()
    }
}
