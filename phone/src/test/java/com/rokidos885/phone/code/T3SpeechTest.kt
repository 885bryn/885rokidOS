package com.rokidos885.phone.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T3SpeechTest {
    @Test fun markdownBecomesPlainSentences() {
        val reply = """
            ## Result
            All **214 tests** pass. I changed `HudStore.kt` only.

            - Fixed the *wake* tap
            - See [the docs](https://example.com/x)

            ```kotlin
            val x = 1
            ```
            | a | b |
            |---|---|
            Done.
        """.trimIndent()
        assertEquals("Result. All 214 tests pass. I changed HudStore.kt only. Fixed the wake tap. See the docs. Code block. Done.",
            T3Speech.spoken(reply))
    }

    @Test fun aLongReplyStopsAtASentenceEnd() {
        val spoken = T3Speech.spoken("This is one sentence. ".repeat(100))
        assertTrue(spoken.length < T3Speech.MAX_CHARS + 40)
        assertTrue(spoken.endsWith("sentence. The rest is on the PC."))
    }

    @Test fun partsAreWholeSentencesOfAtMost160Characters() {
        val sentence = "This sentence has exactly forty chars.. "
        val parts = T3Speech.parts("Short one. " + "Word ".repeat(50).trim() + ". " + sentence.repeat(6))
        assertEquals("Short one.", parts[0].substringBefore(" Word"))
        assertTrue(parts.all { it.length <= T3Speech.PART_CHARS })
        assertEquals(parts.joinToString(" ").split(" ").size, ("Short one. " + "Word ".repeat(50).trim() + ". " + sentence.repeat(6)).trim().split(" ").size)
        assertEquals(listOf("One. Two."), T3Speech.parts("One. Two."))
    }
}
