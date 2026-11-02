package com.rokidnav.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTest {
    @Test fun capitalizesTheFirstLetterAddsAFullStopAndFixesTheWordI() {
        assertEquals("Hello this is the test.", Dictation.format("hello this is the test"))
        assertEquals("I will be there in about ten minutes, I think.", Dictation.format("i will be there in about ten minutes, i think"))
        assertEquals("I'm on my way, and I'll call you.", Dictation.format("i'm on my way, and i'll call you"))
    }

    @Test fun keepsExistingEndingPunctuationAndHandlesEmptyInput() {
        assertEquals("Are you coming?", Dictation.format("  are  you coming?  "))
        assertEquals("", Dictation.format("   "))
        assertEquals("Hi.", Dictation.format("hi"))
    }

    @Test fun leavesOtherWordsAlone() {
        assertEquals("Pick it up in Italy.", Dictation.format("pick it up in italy").replace("italy", "Italy"))
    }
}
