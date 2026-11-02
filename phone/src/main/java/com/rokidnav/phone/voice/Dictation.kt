package com.rokidnav.phone.voice

/** Turns the recognizer's plain lower-case words into a message that reads like typed text. */
object Dictation {
    private val SPACES = Regex("\\s+")
    private val LONE_I = Regex("\\bi\\b(?=$|[\\s,.!?']|'(m|ll|ve|d))")

    fun format(raw: String): String {
        var text = raw.trim().replace(SPACES, " ")
        if (text.isEmpty()) return ""
        text = LONE_I.replace(text, "I")
        text = text.replaceFirstChar { it.uppercase() }
        return if (text.last() in ".!?") text else "$text."
    }
}
