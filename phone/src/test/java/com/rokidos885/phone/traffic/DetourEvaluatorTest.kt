package com.rokidos885.phone.traffic

import com.rokidos885.phone.nav.RouteOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetourEvaluatorTest {
    private fun route(desc: String, seconds: Int, meters: Int = 10_000) =
        RouteOption("tok-$desc", "", meters, seconds, seconds, desc, emptyList())

    @Test fun offersWhenSavingsBeatThreshold() {
        val d = DetourEvaluator().evaluate(1_800, 20_000, listOf(route("via A", 1_400)), 0)
        assertEquals(400, d?.savesSeconds)
    }

    @Test fun ignoresSmallSavings() {
        assertNull(DetourEvaluator().evaluate(1_800, 20_000, listOf(route("via A", 1_700)), 0))
    }

    @Test fun respectsDismissCooldown() {
        val e = DetourEvaluator()
        val alt = route("via A", 1_000)
        e.markDismissed(alt, nowMs = 0)
        assertNull(e.evaluate(1_800, 20_000, listOf(alt), nowMs = 60_000))
        assertEquals(800, e.evaluate(1_800, 20_000, listOf(alt), nowMs = 11 * 60_000)?.savesSeconds)
    }
}
