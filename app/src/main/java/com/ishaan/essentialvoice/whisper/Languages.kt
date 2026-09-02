package com.ishaan.essentialvoice.whisper

/**
 * The hundred languages whisper's multilingual models were trained on.
 *
 * Taken from `g_lang` in whisper.cpp rather than from Android's own locale
 * list, and that is the point: this table is exactly what the *model* can be
 * asked for. A locale the decoder has no token for is not a language the app
 * can offer, however sensible it looks in a picker.
 *
 * The codes are whisper's, not BCP 47 — "jw" for Javanese and "yue" for
 * Cantonese are both whisper's spellings and both would be wrong as Android
 * locales. They are only ever handed to `whisper_full_params.language`, so they
 * are kept as whisper writes them.
 *
 * Names are English rather than endonyms. An endonym list is friendlier to read
 * and much easier to get subtly wrong in a hundred scripts, and the picker has
 * a search box over these.
 */
data class Language(val code: String, val name: String)

object Languages {

    const val DEFAULT = "en"

    /** whisper's own order, which is roughly by training hours. */
    val all: List<Language> = listOf(
        Language("en", "English"),
        Language("zh", "Chinese (Mandarin)"),
        Language("de", "German"),
        Language("es", "Spanish"),
        Language("ru", "Russian"),
        Language("ko", "Korean"),
        Language("fr", "French"),
        Language("ja", "Japanese"),
        Language("pt", "Portuguese"),
        Language("tr", "Turkish"),
        Language("pl", "Polish"),
        Language("ca", "Catalan"),
        Language("nl", "Dutch"),
        Language("ar", "Arabic"),
        Language("sv", "Swedish"),
        Language("it", "Italian"),
        Language("id", "Indonesian"),
        Language("hi", "Hindi"),
        // Hindi spoken with English words in it, which is how most of the
        // people this app was written for actually talk. Not a whisper
        // language and not a BCP-47 one either: it is "en-IN" sent to Google,
        // whose Indian English pack transcribes the English words as English
        // and the Hindi ones phonetically, which is what Hinglish looks like
        // written down. On whisper it falls back to Hindi, which is the
        // honest second best. See GoogleSpeech.whisperToBcp47.
        Language("hi-en", "Hinglish"),
        Language("fi", "Finnish"),
        Language("vi", "Vietnamese"),
        Language("he", "Hebrew"),
        Language("uk", "Ukrainian"),
        Language("el", "Greek"),
        Language("ms", "Malay"),
        Language("cs", "Czech"),
        Language("ro", "Romanian"),
        Language("da", "Danish"),
        Language("hu", "Hungarian"),
        Language("ta", "Tamil"),
        Language("no", "Norwegian"),
        Language("th", "Thai"),
        Language("ur", "Urdu"),
        Language("hr", "Croatian"),
        Language("bg", "Bulgarian"),
        Language("lt", "Lithuanian"),
        Language("la", "Latin"),
        Language("mi", "Maori"),
        Language("ml", "Malayalam"),
        Language("cy", "Welsh"),
        Language("sk", "Slovak"),
        Language("te", "Telugu"),
        Language("fa", "Persian"),
        Language("lv", "Latvian"),
        Language("bn", "Bengali"),
        Language("sr", "Serbian"),
        Language("az", "Azerbaijani"),
        Language("sl", "Slovenian"),
        Language("kn", "Kannada"),
        Language("et", "Estonian"),
        Language("mk", "Macedonian"),
        Language("br", "Breton"),
        Language("eu", "Basque"),
        Language("is", "Icelandic"),
        Language("hy", "Armenian"),
        Language("ne", "Nepali"),
        Language("mn", "Mongolian"),
        Language("bs", "Bosnian"),
        Language("kk", "Kazakh"),
        Language("sq", "Albanian"),
        Language("sw", "Swahili"),
        Language("gl", "Galician"),
        Language("mr", "Marathi"),
        Language("pa", "Punjabi"),
        Language("si", "Sinhala"),
        Language("km", "Khmer"),
        Language("sn", "Shona"),
        Language("yo", "Yoruba"),
        Language("so", "Somali"),
        Language("af", "Afrikaans"),
        Language("oc", "Occitan"),
        Language("ka", "Georgian"),
        Language("be", "Belarusian"),
        Language("tg", "Tajik"),
        Language("sd", "Sindhi"),
        Language("gu", "Gujarati"),
        Language("am", "Amharic"),
        Language("yi", "Yiddish"),
        Language("lo", "Lao"),
        Language("uz", "Uzbek"),
        Language("fo", "Faroese"),
        Language("ht", "Haitian Creole"),
        Language("ps", "Pashto"),
        Language("tk", "Turkmen"),
        Language("nn", "Norwegian Nynorsk"),
        Language("mt", "Maltese"),
        Language("sa", "Sanskrit"),
        Language("lb", "Luxembourgish"),
        Language("my", "Burmese"),
        Language("bo", "Tibetan"),
        Language("tl", "Tagalog"),
        Language("mg", "Malagasy"),
        Language("as", "Assamese"),
        Language("tt", "Tatar"),
        Language("haw", "Hawaiian"),
        Language("ln", "Lingala"),
        Language("ha", "Hausa"),
        Language("ba", "Bashkir"),
        Language("jw", "Javanese"),
        Language("su", "Sundanese"),
        Language("yue", "Cantonese"),
    )

    /** For the picker, which shows them all and is searched by name. */
    val alphabetical: List<Language> = all.sortedBy { it.name.lowercase() }

    fun byCode(code: String): Language =
        all.firstOrNull { it.code == code } ?: all.first()

    /** The name alone, for a settings row. */
    fun nameOf(code: String): String = byCode(code).name

    /**
     * Whether [code] is served by the English-only models.
     *
     * The whole language feature turns on this one question: `.en` models carry
     * no language tokens at all, so English is not "one of a hundred choices"
     * here — it is a different set of files, and a better one. See
     * [QualityTier].
     */
    fun isEnglish(code: String): Boolean = code == DEFAULT

    /**
     * What to hand whisper for [code].
     *
     * Only Hinglish differs: it is this app's entry rather than one of
     * whisper's, and the nearest thing whisper has is Hindi. Google is the
     * engine that can actually do it — see
     * [com.ishaan.essentialvoice.speech.GoogleSpeech].
     */
    fun whisperCode(code: String): String = if (code == "hi-en") "hi" else code
}
