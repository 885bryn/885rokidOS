package com.rokidnav.phone.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyCacheTest {
    @Test fun keepsAReplyForTwoMinutesThenForgetsIt() {
        var now = 0L
        val cache = ReplyCache<String>(ttlMs = 120_000, clock = { now })
        cache.put("n1", "reply-1")
        now = 119_000
        assertEquals("reply-1", cache.get("n1"))
        now = 121_000
        assertNull(cache.get("n1"))
    }

    @Test fun anUpdatedNotificationReplacesTheOldReplyAndTheCacheStaysSmall() {
        var now = 0L
        val cache = ReplyCache<Int>(max = 3, clock = { now })
        cache.put("a", 1); cache.put("a", 2)
        assertEquals(2, cache.get("a"))
        cache.put("b", 3); cache.put("c", 4); cache.put("d", 5)
        assertNull(cache.get("a"))
        assertEquals(5, cache.get("d"))
        cache.remove("d")
        assertNull(cache.get("d"))
    }
}
