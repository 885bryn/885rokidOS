package com.rokidos885.phone.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Sends a reply the way the "Reply" button in the notification shade does: fill the app's own [RemoteInput]
 * with the text and fire the action's PendingIntent. No account access. Only works while the notification
 * is still posted (see [ReplyCache]).
 */
class NotificationReplier(private val context: Context) {
    private val actions = ReplyCache<Notification.Action>()

    /** Remembers the notification's free-text reply action, if it has one. Returns whether it has one. */
    fun remember(sbn: StatusBarNotification): Boolean {
        val action = sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        if (action != null) actions.put(sbn.key, action) else actions.remove(sbn.key)
        Log.i(TAG, "reply available=${action != null} pkg=${sbn.packageName}")
        return action != null
    }

    fun forget(key: String) = actions.remove(key)

    /** Fires the reply. False when the notification is gone, has no reply action or the app refused it. */
    fun reply(key: String, text: String): Boolean {
        val action = actions.get(key) ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        return runCatching { action.actionIntent.send(context, 0, fill); true }.getOrDefault(false)
    }

    private companion object { const val TAG = "885rokidOSReply" }
}
