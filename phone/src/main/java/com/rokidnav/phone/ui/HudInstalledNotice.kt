package com.rokidnav.phone.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** "HUD installed" after an install done with Hi Rokid on screen; tapping it returns to 885rokidOS. */
object HudInstalledNotice {
    private const val CHANNEL = "hud_install"
    private const val ID = 72

    fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Glasses HUD", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 3,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("HUD installed on your glasses")
            .setContentText("Tap to return to 885rokidOS")
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) } // no-op without notification permission
    }
}
