package com.rokidos885.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechVocabularyTest {
    @Test fun `hotwords are lower case, one per line, without blanks or repeats`() {
        assertEquals("deepseek\npull request\n", SpeechVocabulary.hotwords(listOf(" DeepSeek", "", "pull request", "deepseek")))
    }

    @Test fun `bpe vocab keeps every token with a tab and scores common pieces higher`() {
        val vocab = SpeechVocabulary.bpeVocab("<unk> 0\ns 1\n▁the 2\n<blk> 1024\n")
        assertEquals("<unk>\t0\ns\t-1\n▁the\t-2\n<blk>\t0\n", vocab)
    }

    @Test fun `only T3 dictation favours words`() {
        assertTrue(ListenLimits.REPLY.hotwords.isEmpty())
        assertTrue("deepseek" in ListenLimits.DICTATION.hotwords)
    }
}
