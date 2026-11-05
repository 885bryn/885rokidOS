package com.rokidos885.phone.voice

/**
 * Words the speech model should favour (sherpa-onnx "hotwords"). Pure Kotlin so it is unit-tested.
 *
 * Tested on the PC with the 1040 ms model (2026-11-05, docs/VOICE.md): on the user's T3 dictations a list of names fixed
 * "deep sea" to "deepseek" and "amoges" to "emojis", with no new errors. On the ten short message replies no list helped, and a
 * boost of 5 or more made the model invent phrases. So only T3 dictation uses a list, and the boost stays at [SCORE].
 */
object SpeechVocabulary {
    /** Names and rare words in T3 instructions. Keep to words the model does not know; common words do not gain. */
    val T3_WORDS = listOf(
        "deepseek", "github", "codex", "claude", "opencode", "grok", "emojis", "kotlin", "gradle", "sherpa",
        "rokid", "pull request", "dictation", "notification",
    )

    /** Boost per token. 2.0 with 8 paths fixed the most words on the PC; 3.0 also turned "stretch" into "stread". */
    const val SCORE = 2.0f
    const val PATHS = 8

    /** The hotwords file: one phrase per line, lower case (the model's tokens are lower case). */
    fun hotwords(words: List<String>): String =
        words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString("\n", postfix = "\n")

    /**
     * A `bpe.vocab` made from the model's `tokens.txt` ("<token> <id>" per line). sherpa-onnx needs it to split hotwords
     * into the model's tokens; the NeMo model ships without one. Lower ids are the more common pieces, so they score higher.
     */
    fun bpeVocab(tokensTxt: String): String = tokensTxt.lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotEmpty() }
        .joinToString("\n", postfix = "\n") { line ->
            val token = line.substringBeforeLast(' ')
            val id = line.substringAfterLast(' ').toInt()
            val score = if (token.startsWith("<")) 0 else -id
            "$token\t$score"
        }
}
