package com.bitchat.android.translate

/**
 * Script families used to guess a message's language on-device.
 *
 * Detection is deliberately local: message text is never sent to a language-identification service,
 * so a probe is a Unicode range count with no network, no model download, and no data disclosure.
 * It is a hint for the translation engine, not a linguistic proof.
 */
enum class Script {
    Latin,
    Cyrillic,
    Greek,
    Arabic,
    Hebrew,
    Hangul,
    Kana,
    Han,
    Thai,
    Devanagari,
    Armenian,
    Georgian,
    Unknown
}

object LanguageProbe {
    private const val MIN_MEANINGFUL_CHARS = 3

    /**
     * Letter marks that separate the Slavic languages sharing Cyrillic. Written as escapes so the
     * source stays plain ASCII and cannot be damaged by encoding conversion.
     */
    private val UKRAINIAN_MARKS = charArrayOf('\u0457', '\u0454', '\u0491')
    private val SERBIAN_MARKS = charArrayOf('\u0452', '\u0458', '\u0459', '\u045A', '\u045B')
    private val BULGARIAN_MARKS = charArrayOf('\u044A', '\u0455')
    private val BELARUSIAN_MARKS = charArrayOf('\u0456', '\u040E')
    private val RUSSIAN_MARKS = charArrayOf('\u044B', '\u044D', '\u0451')

    /**
     * Frequency word hints for Latin-script languages. A language is chosen only when its hint words
     * appear, so an unknown Latin message falls back to [DEFAULT_TAG].
     *
     * Chat traffic is short, so the lists favour greetings, one-liners and fragment replies, not just
     * full prose. One hint word wins when no other language scores higher.
     */
    private val LATIN_HINTS = linkedMapOf(
        "en" to listOf("the", "and", "is", "are", "you", "of", "in", "i", "my", "what", "hello",
            "thanks", "please", "have", "not", "this", "that", "it", "we", "they", "mesh", "channel"),
        "fr" to listOf("le", "la", "les", "des", "une", "un", "est", "sont", "avec", "pour", "que",
            "qui", "je", "tu", "vous", "nous", "dans", "sur", "pas", "bonjour", "merci", "salut",
            "comment", "oui", "non", "monde", "bien", "comme", "aussi", "tout", "votre", "notre",
            "ce", "cette", "suis", "a"),
        "de" to listOf("und", "der", "die", "das", "mit", "nicht", "ich", "sie", "ist", "bin", "sind",
            "mein", "wir", "auch", "guten", "hallo", "danke", "wie", "sehr"),
        "es" to listOf("los", "las", "que", "con", "para", "pero", "m\u00E1s", "muy", "por", "como",
            "no", "s\u00ED", "todo", "bien", "hola", "gracias", "c\u00F3mo", "un", "una"),
        "it" to listOf("il", "che", "non", "con", "per", "sono", "gli", "la", "un", "una", "mi", "ti",
            "come", "molto", "anche", "questo", "buon"),
        "pt" to listOf("\u00E7\u00E3o", "voc\u00EA", "com", "os", "uma", "muito", "bem", "como", "que",
            "para", "obrigado", "n\u00E3o"),
        "nl" to listOf("het", "een", "van", "met", "de", "en", "ik", "ben", "zijn", "ook", "als", "goed"),
        "pl" to listOf("sie", "nie", "ze", "jest", "bardzo", "mam", "jak", "co", "dobrze", "jestes"),
        "tr" to listOf("bir", "ve", "bu", "degil", "\u00E7ok", "nas\u0131l", "sen", "benim", "i\u00E7in"),
        "sv" to listOf("och", "att", "det", "inte", "jag", "\u00E4r", "med", "s\u00E5", "mycket"),
        "fi" to listOf("ja", "se", "ett\u00E4", "min\u00E4", "olen", "kiitos", "paljon", "hyv\u00E4",
            "olla"),
        "id" to listOf("dan", "yang", "untuk", "tidak", "saya", "apa", "halo", "terima")
    )

    /**
     * Letter marks belonging to one language's orthography. Chat text often carries only a few accents,
     * so a mark settles a tie that word hints alone cannot.
     */
    private val LATIN_MARKS = linkedMapOf(
        "fr" to charArrayOf('\u00E0', '\u00E2', '\u00E4', '\u00E7', '\u00E9', '\u00E8', '\u00EA',
            '\u00EB', '\u00EE', '\u00EF', '\u00F4', '\u00FB', '\u00FF'),
        "de" to charArrayOf('\u00E4', '\u00F6', '\u00FC', '\u00DF'),
        "es" to charArrayOf('\u00E1', '\u00ED', '\u00F3', '\u00FA', '\u00F1'),
        "pt" to charArrayOf('\u00E3', '\u00E7', '\u00E9', '\u00F4', '\u00ED', '\u00FA'),
        "pl" to charArrayOf('\u0105', '\u0107', '\u0119', '\u0142', '\u0144', '\u015B', '\u017A', '\u017C'),
        "tr" to charArrayOf('\u0131', '\u011F', '\u015F', '\u00F6', '\u00E7', '\u00FC'),
        "sv" to charArrayOf('\u00E5', '\u00E4', '\u00F6'),
        "cs" to charArrayOf('\u011B', '\u0161', '\u010D', '\u0159', '\u017E'),
        "hu" to charArrayOf('\u0151', '\u0171'),
        "it" to charArrayOf('\u00E0', '\u00E8', '\u00EC', '\u00F2', '\u00F9')
    )

    /** Tag used when a Latin message has no hint words, matching the app's main UI language. */
    const val DEFAULT_TAG = "en"

    /**
     * Best-guess tag for [text], or null when there is too little text to judge.
     *
     * Script ranges come first: text in a non-Latin script is identified by script, not vocabulary,
     * because a wrong source-language hint degrades the translation the engine will produce.
     */
    fun guessLanguage(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length < MIN_MEANINGFUL_CHARS) return null
        return when (dominantScript(trimmed)) {
            Script.Kana -> "ja"
            Script.Han -> "zh"
            Script.Hangul -> "ko"
            Script.Arabic -> "ar"
            Script.Hebrew -> "he"
            Script.Thai -> "th"
            Script.Greek -> "el"
            Script.Devanagari -> "hi"
            Script.Armenian -> "hy"
            Script.Georgian -> "ka"
            Script.Cyrillic -> slavicLanguage(trimmed)
            Script.Latin -> latinLanguage(trimmed)
            Script.Unknown -> null
        }
    }

    /** Dominant script of [text]; ties resolve in declaration order. */
    fun dominantScript(text: String): Script {
        val counts = HashMap<Script, Int>()
        for (char in text) {
            val script = classify(char)
            if (script == Script.Unknown) continue
            counts[script] = (counts[script] ?: 0) + 1
        }
        return counts.entries.maxByOrNull { it.value }?.key ?: Script.Unknown
    }

    private fun slavicLanguage(text: String): String {
        val lower = text.lowercase()
        if (lower.any { it in SERBIAN_MARKS }) return "sr"
        if (lower.any { it in UKRAINIAN_MARKS }) return "uk"
        if (lower.any { it in BELARUSIAN_MARKS }) return "be"
        if (lower.any { it in BULGARIAN_MARKS }) return "bg"
        if (lower.any { it in RUSSIAN_MARKS }) return "ru"
        return "ru"
    }

    /**
     * Scores every Latin language by hint words plus orthographic marks, and picks the highest.
     *
     * Punctuation is turned into spaces so word hints match on boundaries; accented characters count as
     * one signal each, because a single accented letter is stronger evidence than one shared word.
     */
    private fun latinLanguage(text: String): String {
        val lower = text.lowercase()
        val padded = " " + lower.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("") + " "
        var bestTag = DEFAULT_TAG
        var bestScore = 0
        LATIN_HINTS.forEach { (tag, hints) ->
            val words = hints.count { hint -> padded.contains(" $hint ") }
            val marks = LATIN_MARKS[tag]?.count { mark -> lower.contains(mark) } ?: 0
            val score = words + marks
            if (score > bestScore) {
                bestScore = score
                bestTag = tag
            }
        }
        return bestTag
    }

    private fun classify(char: Char): Script = when (char.code) {
        in 0x0400..0x04FF -> Script.Cyrillic
        in 0x0370..0x03FF -> Script.Greek
        in 0x0600..0x06FF -> Script.Arabic
        in 0x0750..0x077F -> Script.Arabic
        in 0xFB50..0xFDFF -> Script.Arabic
        in 0xFE70..0xFEFF -> Script.Arabic
        in 0x0590..0x05FF -> Script.Hebrew
        in 0x1100..0x11FF -> Script.Hangul
        in 0xAC00..0xD7AF -> Script.Hangul
        in 0x3130..0x318F -> Script.Hangul
        in 0x3040..0x30FF -> Script.Kana
        in 0x31F0..0x31FF -> Script.Kana
        in 0x4E00..0x9FFF -> Script.Han
        in 0x3400..0x4DBF -> Script.Han
        in 0x0E00..0x0E7F -> Script.Thai
        in 0x0900..0x097F -> Script.Devanagari
        in 0x0530..0x058F -> Script.Armenian
        in 0x10A0..0x10FF -> Script.Georgian
        in 0x0041..0x005A -> Script.Latin
        in 0x0061..0x007A -> Script.Latin
        in 0x00C0..0x024F -> Script.Latin
        else -> Script.Unknown
    }
}
