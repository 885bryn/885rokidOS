package com.rokidnav.phone.voice

/** Spoken commands from the glasses (tap on the empty idle screen, then speak). There is one so far: "open Grok Bot". */
object VoiceCommands {
    private val WORD = Regex("[a-z]+")
    /** Ways the offline model writes "Grok" that are more than one letter away from it. */
    private val GROK_SPELLINGS = setOf("rock", "brock", "crock")

    /**
     * True when "open" is directly followed by "Grok" or a likely misspelling of it ("grock", "groc", "rock", "grow",
     * "brock"; "bot" may follow, or be joined: "grokbot"). The model does not know the word "Grok". Without "open" right
     * before it nothing counts, so "rock music" opens nothing; a miss shows the words heard on the glasses.
     */
    fun opensGrokBot(heard: String): Boolean {
        val words = WORD.findAll(heard.lowercase()).map { it.value }.toList()
        return words.zipWithNext().any { (a, b) -> a == "open" && isGrok(b.removeSuffix("bot").ifEmpty { b }) }
    }

    private fun isGrok(word: String) = word in GROK_SPELLINGS || withinOneEdit(word, "grok")

    /** True when [a] becomes [b] by changing, adding or removing at most one letter. */
    private fun withinOneEdit(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
