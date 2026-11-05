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
}
