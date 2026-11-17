package com.example.audio.tts

/**
 * Smart Arabic Text Diacritics & Punctuation Normalizer before Cloud TTS synthesis:
 * - Normalizes irregular spaces, tatweel stretching, and multiple punctuation marks.
 * - Ensures clear pause spacing after Arabic/Latin commas, semicolons, and periods so Neural TTS
 *   delivers natural breath phrasing instead of rushing sentences together.
 * - Adds safe, unambiguous diacritization hints for ultra-common Arabic particles, prepositions,
 *   and connective conjunctions only when the word has no user-provided tashkeel already.
 */
object ArabicTextDiacritizer {

    private val DIACRITICS_REGEX = Regex("[\\u064B-\\u065F\\u0670]")

    private val SAFE_FUNCTION_WORD_HINTS: Map<String, String> = mapOf(
        "في" to "فِي",
        "من" to "مِنْ",
        "إلى" to "إِلَى",
        "الى" to "إِلَى",
        "على" to "عَلَى",
        "عن" to "عَنْ",
        "مع" to "مَعَ",
        "بين" to "بَيْنَ",
        "بعد" to "بَعْدَ",
        "قبل" to "قَبْلَ",
        "حول" to "حَوْلَ",
        "ضد" to "ضِدَّ",
        "نحو" to "نَحْوَ",
        "تحت" to "تَحْتَ",
        "فوق" to "فَوْقَ",
        "أمام" to "أَمَامَ",
        "خلف" to "خَلْفَ",
        "ثم" to "ثُمَّ",
        "لكن" to "لَكِنَّ",
        "كما" to "كَمَا",
        "لذلك" to "لِذَلِكَ",
        "أيضا" to "أَيْضًا",
        "ايضا" to "أَيْضًا",
        "جدا" to "جِدًّا",
        "دائما" to "دَائِمًا",
        "أهلا" to "أَهْلًا",
        "مرحبا" to "مَرْحَبًا",
        "شكرا" to "شُكْرًا",
        "فعلا" to "فِعْلًا",
        "حقا" to "حَقًّا",
        "مثلا" to "مَثَلًا",
        "أولا" to "أَوَّلًا",
        "ثانيا" to "ثَانِيًا",
        "إن" to "إِنَّ",
        "أن" to "أَنَّ",
        "كان" to "كَانَ",
        "كانت" to "كَانَتْ",
        "ليس" to "لَيْسَ",
        "هذا" to "هَذَا",
        "هذه" to "هَذِهِ",
        "ذلك" to "ذَلِكَ",
        "تلك" to "تِلْكَ",
        "الذي" to "الَّذِي",
        "التي" to "الَّتِي",
        "الذين" to "الَّذِينَ",
        "اللهم" to "اللَّهُمَّ",
    )

    fun normalizeAndHintForTts(rawText: String, applyLightDiacritics: Boolean = true): String {
        val cleaned = rawText
            // Strip decorative tatweel 'ـ' characters that stall TTS pronunciation
            .replace("\u0640", "")
            // Normalize English comma inside Arabic blocks to Arabic comma for proper prosody
            .replace(Regex("(?<=[\\u0600-\\u06FF])\\s*,\\s*(?=[\\u0600-\\u06FF])"), "، ")
            // Ensure consistent spacing after pauses & punctuation
            .replace(Regex("\\s*([،؛:!.؟?])\\s*"), "$1 ")
            .replace(Regex("[\\t\\r ]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        if (cleaned.isEmpty() || !applyLightDiacritics) return cleaned
        val containsArabic = cleaned.any { it in '\u0600'..'\u06FF' }
        if (!containsArabic) return cleaned

        // Process word-by-word preserving punctuation boundaries
        val tokens = cleaned.split(Regex("(?<=\\s)|(?=\\s)"))
        val out = StringBuilder(cleaned.length + 32)
        for (token in tokens) {
            if (token.isBlank()) {
                out.append(token)
                continue
            }
            val leading = token.takeWhile { !it.isLetterOrDigit() }
            val trailing = token.takeLastWhile { !it.isLetterOrDigit() }
            val core = token.substring(leading.length, token.length - trailing.length)

            val transformed = if (core.isEmpty() || DIACRITICS_REGEX.containsMatchIn(core)) {
                core
            } else {
                SAFE_FUNCTION_WORD_HINTS[core] ?: applySafeSuffixHint(core)
            }
            out.append(leading).append(transformed).append(trailing)
        }
        return out.toString().trim()
    }

    private fun applySafeSuffixHint(word: String): String {
        // Common adverbial tanwin on clean Arabic stems without altering ambiguous words
        if (word.length >= 4 && word.endsWith("اً")) {
            return word.dropLast(2) + "ًا"
        }
        return word
    }
}
