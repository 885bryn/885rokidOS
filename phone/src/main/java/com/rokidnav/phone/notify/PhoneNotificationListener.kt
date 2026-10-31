package com.rokidnav.phone.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.rokidnav.phone.RokidNavApp

/**
 * Reads every phone notification once the user grants "Notification access" (Settings screen) and
 * hands the ones [NotificationFilter] accepts to the coordinator. Turn off Rokid's own notification
 * forwarding in Hi Rokid, or the glasses show both.
 */
class PhoneNotificationListener : NotificationListenerService() {
    /** Last text sent per key: apps re-post the same notification for small updates (e.g. timestamps). */
    private val lastSent = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val notice = PhoneNotice(
            packageName = sbn.packageName,
            category = sbn.notification.category.orEmpty(),
            ongoing = sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            groupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
        )
        if (!NotificationFilter.shouldForward(notice)) return
        val body = notice.title + "\n" + notice.text
        if (lastSent.put(sbn.key, body) == body) return
        RokidNavApp.instance.coordinator.forwardNotification(
            id = sbn.key,
            app = appLabel(sbn.packageName),
            // Payload size is undocumented; the card shows one line of each anyway.
            title = notice.title.take(MAX_TITLE),
            text = notice.text.take(MAX_TEXT),
            postedAtMs = sbn.postTime,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastSent.remove(sbn.key)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        const val MAX_TITLE = 40
        const val MAX_TEXT = 120
    }
}
