package com.rokidos885.phone.voice

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Opens the Grok Bot app on the phone for the voice command "open Grok Bot". */
object GrokBot {
    /** "Grok Bot" on Google Play (not the Grok chat app, ai.x.grok). Keep equal to AndroidManifest.xml `<queries>`. */
    const val PACKAGE = "ai.x.grok.bot"

    /** Null when Android was asked to open Grok Bot, otherwise the reason it was not (short: the glasses show it). */
    fun open(context: Context): String? {
        // Needs the package in the manifest's <queries> (Android 11+ package visibility), or this is always null.
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return "Grok Bot is not installed on the phone"
        // Android 10+ ignores activity starts from an app in the background (the usual case: phone in a pocket). The user's
        // "Display over other apps" grant lifts that. Without it, only an app on screen may start another one.
        if (!Settings.canDrawOverlays(context) && !onScreen()) return "Allow 885rokidOS to display over other apps"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        return null
    }

    private fun onScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
