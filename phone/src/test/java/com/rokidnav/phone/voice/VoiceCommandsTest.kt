package com.rokidnav.phone.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {
    @Test fun openGrokBotOpensGrokBot() {
        listOf("open grok bot", "OPEN GROK BOT", "Open Grok Bot.", "open grokbot", "please open grok bot")
            .forEach { assertTrue(it, VoiceCommands.opensGrokBot(it)) }
    }

    @Test fun openGrokAlsoCountsBecauseTheLastWordMayBeDropped() {
        assertTrue(VoiceCommands.opensGrokBot("open grok"))
    }

    @Test fun otherSpeechOpensNothing() {
        listOf("", "open", "grok", "grok bot", "open maps", "open rock", "open grock", "open the grok bot", "grokking")
            .forEach { assertFalse(it, VoiceCommands.opensGrokBot(it)) }
    }
}
