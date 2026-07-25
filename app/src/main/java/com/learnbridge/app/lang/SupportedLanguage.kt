package com.learnbridge.app.lang

import java.util.Locale

/**
 * Purpose:  The languages LearnBridge can teach in, and everything that differs between them.
 * Owns:     Nothing — pure data.
 * Lifetime: Constant.
 * Thread:   Any.
 *
 * **Why there is more than one, and why it cost no extra megabytes.**
 *
 * The translation export ships a single shared target vocabulary and a single set of ONNX graphs. The
 * target language is nothing more than the *second token of the source sequence*, so switching it
 * requires no reload, no second model, and no additional memory — one loaded engine serves every
 * language here.
 *
 * **Why this list is shorter than the 34 tags in the vocabulary.** A tag being present does not mean
 * the model can produce that language. Measured coverage in the target vocabulary:
 *
 * | Script      | Multi-character subword pieces |
 * |-------------|-------------------------------|
 * | Devanagari  | 72,356                        |
 * | Arabic      | 16,949                        |
 * | Latin       | 11,414                        |
 * | Tamil       | **0**                         |
 * | Telugu, Kannada, Malayalam, Gurmukhi, Odia | **0** each |
 *
 * Tamil, Telugu, Kannada, Malayalam, Punjabi and Odia have their alphabets in the vocabulary and
 * nothing else — no learned subwords, so they cannot be generated and are deliberately absent.
 * Shipping them would produce confident garbage, which is worse than not offering them.
 *
 * Everything listed here was verified by translating on device, not inferred from the vocabulary.
 */
enum class SupportedLanguage(
    /** FLORES-style tag, resolved against the translation vocabulary at runtime. */
    val tag: String,

    /** BCP-47 code, used as the `lang` column in the artifacts table. */
    val code: String,

    /** Shown on the language button, in the language itself. */
    val endonym: String,

    /** Sentence terminator. Not universal: Devanagari uses danda, Urdu uses the Arabic full stop. */
    val terminator: Char,

    /** Voice to request from the platform text-to-speech engine. */
    val locale: Locale,

    /** Right-to-left script. */
    val rtl: Boolean = false,
) {
    ENGLISH("eng_Latn", "en", "English", '.', Locale.UK),
    HINDI("hin_Deva", "hi", "हिंदी", '।', Locale("hi", "IN")),
    MARATHI("mar_Deva", "mr", "मराठी", '।', Locale("mr", "IN")),
    URDU("urd_Arab", "ur", "اردو", '۔', Locale("ur", "IN"), rtl = true),
    NEPALI("npi_Deva", "ne", "नेपाली", '।', Locale("ne", "NP")),
    SANSKRIT("san_Deva", "sa", "संस्कृतम्", '।', Locale("sa", "IN")),
    ;

    companion object {
        /** The default target. Hindi has by far the widest subword coverage and the best voice support. */
        val DEFAULT_TARGET = HINDI

        /** Every language a lesson can be rendered into — that is, everything except the source. */
        val targets: List<SupportedLanguage> get() = entries.filter { it != ENGLISH }

        fun byCode(code: String): SupportedLanguage? = entries.firstOrNull { it.code == code }

        /** Every terminator any supported language uses, for punctuation-aware sentence splitting. */
        val allTerminators: CharArray = (entries.map { it.terminator } + listOf('!', '?')).distinct().toCharArray()
    }
}
