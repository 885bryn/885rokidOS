package com.rokidos885.phone.notify

/**
 * Plain copy of the parts of a StatusBarNotification the filter needs, so the filter runs in unit
 * tests without Android. [category] is Notification.CATEGORY_* ("msg", "call", "transport", ...) or "".
 */
data class PhoneNotice(
    val packageName: String,
    val category: String,
    /** FLAG_ONGOING_EVENT: music players, navigation, downloads, foreground services. */
    val ongoing: Boolean,
    /** FLAG_GROUP_SUMMARY: the "3 new messages" header above a group; the children carry the content. */
    val groupSummary: Boolean,
    val title: String,
    val text: String,
)

/** Decides which phone notifications reach the glasses. Driver safety: fewer is better. */
object NotificationFilter {
    /** Packages whose notifications never go to the glasses (our own trip notification, Rokid's app). */
    val IGNORED_PACKAGES = setOf(
        "com.rokidos885.phone",
        "com.rokid.sprite.aiapp", "com.rokid.sprite.global.aiapp", "com.rokid.uxr",
    )

    /** Categories that are never worth a glance while driving. Many apps set no category ("") and pass. */
    val BLOCKED_CATEGORIES = setOf("promo", "progress", "transport", "service", "status", "sys", "recommendation")

    /** Block-list, not allow-list: an allow-list would drop every app that sets no category. */
    fun shouldForward(n: PhoneNotice): Boolean =
        n.packageName !in IGNORED_PACKAGES &&
            // Ongoing: music, navigation, downloads. They re-post constantly; music gets its own feature.
            !n.ongoing &&
            // The summary repeats its children, which carry the real text.
            !n.groupSummary &&
            n.category !in BLOCKED_CATEGORIES &&
            (n.title.isNotBlank() || n.text.isNotBlank())
}
