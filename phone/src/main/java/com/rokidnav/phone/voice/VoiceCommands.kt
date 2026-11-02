package com.rokidnav.phone.voice

/** Spoken commands from the glasses (tap on the empty idle screen, then speak). There is one so far: "open Grok Bot". */
object VoiceCommands {
    private val WORD = Regex("[a-z]+")

    /**
     * True when [heard] contains "open Grok Bot" (or "open grokbot"). "open Grok" also counts: speech to text may drop the
     * last word. Nothing else does; a miss shows the words heard on the glasses.
     */
    fun opensGrokBot(heard: String): Boolean {
        val words = WORD.findAll(heard.lowercase()).map { it.value }.toList()
        return words.zipWithNext().any { (a, b) -> a == "open" && (b == "grok" || b == "grokbot") }
    }
}
