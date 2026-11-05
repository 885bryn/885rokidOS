package com.rokidos885.phone.voice

/**
 * Wrong words of a recognized text against the phrase that was read: substitutions, insertions and deletions, as
 * `tools/stt-bench` counts them. Case, punctuation and hyphens do not count; "I'm" = "I am", and Whisper's digits 1–10 =
 * the words.
 */
object WordErrors {
    fun count(reference: String, heard: String): Int {
        val r = words(reference)
        val h = words(heard)
        var row = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val next = IntArray(h.size + 1).also { it[0] = i }
            for (j in 1..h.size) next[j] = minOf(row[j] + 1, next[j - 1] + 1, row[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
            row = next
        }
        return row[h.size]
    }

    fun words(text: String): List<String> = text.lowercase().replace('-', ' ').replace(Regex("[^a-z0-9' ]"), " ")
        .replace(Regex("\\bi'm\\b"), "i am").split(' ').filter { it.isNotBlank() }.map { DIGITS[it] ?: it }

    private val DIGITS = mapOf("1" to "one", "2" to "two", "3" to "three", "4" to "four", "5" to "five", "6" to "six",
        "7" to "seven", "8" to "eight", "9" to "nine", "10" to "ten")
}
