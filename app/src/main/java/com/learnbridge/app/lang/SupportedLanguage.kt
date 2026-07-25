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
 * **Why the vocabulary looked like it only supported three scripts, and why that was misleading.**
 *
 * Counting subword pieces in the target vocabulary gives Devanagari 72,356, Arabic 16,949, Latin
 * 11,414 — and **zero** multi-character pieces for Tamil, Telugu, Kannada, Malayalam, Gurmukhi and
 * Odia. The obvious conclusion, that those languages cannot be generated, is wrong.
 *
 * IndicTrans2 normalises every Indic language into a single script-unified **Devanagari**
 * representation and transliterates back to the native script as post-processing. So the model
 * generates Tamil perfectly well; it writes it in Devanagari, and the missing piece was a character
 * mapping rather than model capability. See [BrahmicTransliterator].
 *
 * Urdu is the exception that proves it: Perso-Arabic is not a Brahmic script, cannot be reached by an
 * offset, and therefore has genuine subword coverage of its own in the vocabulary.
 *
 * Every language here was verified by translating on device and reading the output — not inferred.
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

    /**
     * Offset from the Devanagari Unicode block to this language's own script, or 0 when the model's
     * output already needs no conversion.
     *
     * The model emits every Indic language in a script-unified Devanagari, so anything written in a
     * southern or eastern script needs transliterating afterwards. See [BrahmicTransliterator].
     */
    val scriptOffset: Int = 0,
) {
    ENGLISH("eng_Latn", "en", "English", '.', Locale.UK),

    // Devanagari-script languages: the model's output is already in the right script.
    HINDI("hin_Deva", "hi", "हिंदी", '।', Locale("hi", "IN")),
    MARATHI("mar_Deva", "mr", "मराठी", '।', Locale("mr", "IN")),
    NEPALI("npi_Deva", "ne", "नेपाली", '।', Locale("ne", "NP")),
    SANSKRIT("san_Deva", "sa", "संस्कृतम्", '।', Locale("sa", "IN")),

    // Perso-Arabic: its own vocabulary coverage, no transliteration involved.
    URDU("urd_Arab", "ur", "اردو", '۔', Locale("ur", "IN"), rtl = true),

    // Transliterated out of Devanagari. The offsets are the distance between Unicode's parallel
    // Brahmic blocks — see BrahmicTransliterator for why a fixed offset is the correct mapping.
    TAMIL("tam_Taml", "ta", "தமிழ்", '.', Locale("ta", "IN"), scriptOffset = 0x280),
    TELUGU("tel_Telu", "te", "తెలుగు", '.', Locale("te", "IN"), scriptOffset = 0x300),
    KANNADA("kan_Knda", "kn", "ಕನ್ನಡ", '.', Locale("kn", "IN"), scriptOffset = 0x380),
    MALAYALAM("mal_Mlym", "ml", "മലയാളം", '.', Locale("ml", "IN"), scriptOffset = 0x400),
    BENGALI("ben_Beng", "bn", "বাংলা", '।', Locale("bn", "IN"), scriptOffset = 0x80),
    GUJARATI("guj_Gujr", "gu", "ગુજરાતી", '।', Locale("gu", "IN"), scriptOffset = 0x180),
    PUNJABI("pan_Guru", "pa", "ਪੰਜਾਬੀ", '।', Locale("pa", "IN"), scriptOffset = 0x100),
    ODIA("ory_Orya", "or", "ଓଡ଼ିଆ", '।', Locale("or", "IN"), scriptOffset = 0x200),
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
