package com.rokidnav.phone.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {
    @Test fun openGrokBotOpensGrokBot() {
        listOf("open grok bot", "OPEN GROK BOT", "Open Grok Bot.", "open grokbot", "please open grok bot", "open grok")
            .forEach { assertTrue(it, VoiceCommands.opensGrokBot(it)) }
    }

    @Test fun likelyMisspellingsOfGrokRightAfterOpenCount() {
        listOf("open grock", "open groc", "open grog", "open groke", "open rock", "open grow", "open brock", "open crock",
            "open grak", "open rock bot", "open grockbot")
            .forEach { assertTrue(it, VoiceCommands.opensGrokBot(it)) }
    }

    @Test fun withoutOpenRightBeforeItNothingCounts() {
        listOf("", "open", "grok", "grok bot", "rock music", "grow up", "open the grok bot", "open my rock")
            .forEach { assertFalse(it, VoiceCommands.opensGrokBot(it)) }
    }

    @Test fun otherWordsAfterOpenAreNotGrok() {
        listOf("open maps", "open group", "open book", "open work", "open broke", "open gross", "open bot")
            .forEach { assertFalse(it, VoiceCommands.opensGrokBot(it)) }
    }
}
