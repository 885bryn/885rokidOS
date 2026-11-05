package com.rokidos885.phone.code

/**
 * T3 replies are Markdown written for a screen. [spoken] turns one into sentences for text-to-speech: code blocks,
 * tables and links become a short word, the Markdown marks go, and a long reply stops at a sentence end near [MAX_CHARS].
 */
object T3Speech {
    /** Sent after each dictated message, so T3 knows how its answer arrives and starts with something worth hearing. */
    const val VOICE_NOTE = "\n\n(Dictated on smart glasses by speech recognition, so some words may be wrong. " +
        "Your reply is read aloud to me: start with a plain summary of 1 to 3 short sentences, without code, paths or tables.)"

    const val MAX_CHARS = 1_200

    fun spoken(markdown: String): String {
        val text = markdown
            .replace(CODE_BLOCK, " Code block. ")
            .lines().joinToString("\n") { line ->
                when {
                    TABLE_ROW.matches(line) -> ""
                    // A heading or list item is its own sentence, so the voice pauses after it.
                    HEADING.containsMatchIn(line) || BULLET.containsMatchIn(line) -> sentence(line.replace(HEADING, "").replace(BULLET, ""))
                    else -> line.replace(QUOTE, "")
                }
            }
            .replace(LINK, "$1")
            .replace(URL, "link")
            .replace(INLINE_CODE, "$1")
            .replace(EMPHASIS, "")
            .replace(Regex("\\n{2,}"), ". ")
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\.\\s*\\.(\\s*\\.)*"), ".")
            .trim()
        if (text.length <= MAX_CHARS) return text
        val cut = text.take(MAX_CHARS)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return (if (end > MAX_CHARS / 2) cut.take(end + 1) else cut) + " The rest is on the PC."
    }

    /**
     * Splits [spoken] text into parts of whole sentences, at most [PART_CHARS] each (a longer sentence splits at a word).
     * The phone reads one part at a time and the glasses show the part being read, so text and voice stay together.
     */
    fun parts(spoken: String): List<String> {
        val parts = ArrayList<String>()
        var current = ""
        fun add(piece: String) {
            current = if (current.isEmpty()) piece else if (current.length + 1 + piece.length <= PART_CHARS) "$current $piece"
                else { parts += current; piece }
        }
        for (sentence in spoken.split(SENTENCE_END).map(String::trim).filter(String::isNotEmpty)) {
            if (sentence.length <= PART_CHARS) add(sentence)
            else sentence.split(' ').fold("") { acc, word ->
                if (acc.isEmpty()) word else if (acc.length + 1 + word.length <= PART_CHARS) "$acc $word" else { add(acc); word }
            }.let { if (it.isNotEmpty()) add(it) }
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }

    /** About 4 lines on the glasses. */
    const val PART_CHARS = 160

    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")

    private fun sentence(line: String) = line.trimEnd().let { if (it.isEmpty() || it.last() in ".!?:;") it else "$it." }

    private val CODE_BLOCK = Regex("```[\\s\\S]*?(```|$)")
    private val TABLE_ROW = Regex("^\\s*\\|.*\\|?\\s*$")
    private val HEADING = Regex("^\\s*#{1,6}\\s*")
    private val QUOTE = Regex("^\\s*>\\s?")
    private val BULLET = Regex("^\\s*([-*+]|\\d+[.)])\\s+")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val URL = Regex("https?://\\S+")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val EMPHASIS = Regex("\\*\\*|__|(?<=\\s|^)[*_]|[*_](?=\\s|$|[.,;:!?])")
}
