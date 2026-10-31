package com.rokidnav.phone.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    private val message = PhoneNotice("com.whatsapp", "msg", ongoing = false, groupSummary = false, title = "Sam", text = "On my way")

    @Test fun forwardsMessagesAndUncategorizedPosts() {
        assertTrue(NotificationFilter.shouldForward(message))
        assertTrue(NotificationFilter.shouldForward(message.copy(category = "")))
    }

    @Test fun dropsNoise() {
        assertFalse(NotificationFilter.shouldForward(message.copy(packageName = "com.rokidnav.phone")))
        assertFalse(NotificationFilter.shouldForward(message.copy(ongoing = true)))
        assertFalse(NotificationFilter.shouldForward(message.copy(groupSummary = true)))
        assertFalse(NotificationFilter.shouldForward(message.copy(category = "promo")))
        assertFalse(NotificationFilter.shouldForward(message.copy(title = "", text = " ")))
    }
}
