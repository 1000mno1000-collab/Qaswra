package com.example.audio.tts

object TtsTextLimits {
    const val MAX_WORDS = 500

    private val wordPattern = Regex("\\S+")

    fun countWords(text: String): Int = wordPattern.findAll(text).take(MAX_WORDS + 1).count()

    fun limitWords(text: String): String {
        val words = wordPattern.findAll(text).take(MAX_WORDS + 1).toList()
        if (words.size <= MAX_WORDS) return text
        return text.substring(0, words[MAX_WORDS - 1].range.last + 1).trimEnd()
    }
}
