package com.rokidnav.phone.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
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
            title = oneLine(extras.getCharSequence(Notification.EXTRA_TITLE)),
            // BigText holds the whole message; EXTRA_TEXT is often cut to one line by the app.
            text = oneLine(extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)),
        )
        // No notification text in logs: only the package and the decision.
        if (!NotificationFilter.shouldForward(notice)) {
            Log.i(TAG, "dropped by filter pkg=${notice.packageName} category=${notice.category} ongoing=${notice.ongoing} summary=${notice.groupSummary}")
            return
        }
        val body = notice.title + "\n" + notice.text
        if (lastSent.put(sbn.key, body) == body) return
        RokidNavApp.instance.coordinator.forwardNotification(
            id = sbn.key,
            app = appLabel(sbn.packageName),
            // Payload size is undocumented; these limits are well under the route chunks we already send.
            title = notice.title.take(MAX_TITLE),
            text = notice.text.take(MAX_TEXT),
            postedAtMs = sbn.postTime,
        )
    }

    /** Notification access was just granted (or the service restarted): media sessions are now readable. */
    override fun onListenerConnected() {
        RokidNavApp.instance.media.start()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastSent.remove(sbn.key)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Line breaks and runs of spaces become one space: the glasses wrap the text themselves. */
    private fun oneLine(value: CharSequence?): String = value?.toString().orEmpty().replace(SPACES, " ").trim()

    private companion object {
        const val TAG = "RokidNavNotify"
        const val MAX_TITLE = 60
        const val MAX_TEXT = 300
        val SPACES = Regex("\\s+")
    }
}
