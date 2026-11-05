package com.rokidos885.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class WordErrorsTest {
    @Test fun countsWrongMissingAndExtraWords() {
        assertEquals(0, WordErrors.count("pull latest branch", "Pull latest branch."))
        assertEquals(1, WordErrors.count("pull latest branch", "pole latest branch"))
        assertEquals(1, WordErrors.count("pull latest branch", "pull branch"))
        assertEquals(2, WordErrors.count("on my way", "on my way home now"))
        assertEquals(3, WordErrors.count("good to know", ""))
    }

    @Test fun caseDigitsAndContractionsDoNotCount() {
        assertEquals(0, WordErrors.count("I am running about ten minutes late", "I'm running about 10 minutes late."))
        assertEquals(0, WordErrors.count("start a thread", "Start a-thread!"))
        assertEquals(0, WordErrors.count("be there in twenty minutes", "Be there in 20 minutes."))
        assertEquals(1, WordErrors.count("be there in twenty minutes", "Be there in 2 minutes."))
    }
}
