package com.learnbridge.app.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.learnbridge.app.R
import com.learnbridge.app.lang.SupportedLanguage

/**
 * The "which language?" dialog, in the one place both screens ask it.
 *
 * The library asks before an import, to decide what the lesson gets rendered into; the lesson asks
 * afterwards, to add a fourteenth reading of a document it already has. Different consequences,
 * identical question — and the part worth keeping identical is the offer itself: **English first,
 * then every target, always in the same order.** Two copies of that list drift, and a chooser whose
 * options move between screens is a chooser students stop trusting.
 *
 * [label] exists because only one caller decorates: the lesson screen marks the languages that would
 * cost a full render, and the library cannot, since before an import nothing is rendered at all.
 */
internal fun Activity.showLanguageChooser(
    selected: SupportedLanguage?,
    label: (SupportedLanguage) -> String = { it.endonym },
    onPick: (SupportedLanguage) -> Unit,
) {
    // English leads because it is the source language, not a translation of anything — picking it
    // means "skip the translating", which is a different kind of answer to the twelve below it.
    val options = listOf(SupportedLanguage.ENGLISH) + SupportedLanguage.targets

    AlertDialog.Builder(this)
        .setTitle(R.string.choose_language_title)
        .setSingleChoiceItems(
            options.map(label).toTypedArray(),
            // -1 when the current language is not in the list, which AlertDialog reads as "nothing
            // checked" — the honest rendering of a state where no option is the one in use.
            options.indexOf(selected),
        ) { dialog, which ->
            onPick(options[which])
            dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
