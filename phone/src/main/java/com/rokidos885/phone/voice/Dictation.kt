package com.rokidos885.phone.voice

/**
 * Turns the recognizer's plain lower-case words into the user's message style: a capital letter at the start of each
 * sentence and for the word "I", and no punctuation (the user writes short replies). The recognizer gives no sentence
 * breaks, so a reply is one sentence.
 */
object Dictation {
    private val SPACES = Regex("\\s+")
    private val PUNCTUATION = Regex("[.,!?;:\"]")
    private val LONE_I = Regex("\\bi\\b(?=$|[\\s']|'(m|ll|ve|d))")
    private val SENTENCE_START = Regex("(^|[.!?]\\s+)(\\p{Ll})")

    fun format(raw: String): String {
        var text = raw.replace(PUNCTUATION, " ").trim().replace(SPACES, " ")
        if (text.isEmpty()) return ""
        text = LONE_I.replace(text, "I")
        return text.replaceFirstChar { it.uppercase() }
    }
}
