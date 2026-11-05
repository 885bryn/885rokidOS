package com.rokidos885.phone.voice

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.PowerManager
import android.provider.Settings

/** Starts a Grok Bot voice chat on the phone when the glasses ask for it (idle tap, or swipe back on a trip). */
object GrokBot {
    /** "Grok Bot" on Google Play (not the Grok chat app, ai.x.grok). Keep equal to AndroidManifest.xml `<queries>`. */
    const val PACKAGE = "ai.x.grok.bot"

    /** Null when Android was asked to open Grok Bot, otherwise the reason it was not (short: the glasses show it). */
    fun open(context: Context): String? {
        val intent = intent(context) ?: return "Grok Bot is not installed on the phone"
        // Android 10+ ignores activity starts from an app in the background (the usual case: phone in a pocket). The user's
        // "Display over other apps" grant lifts that. Without it, only an app on screen may start another one.
        if (!Settings.canDrawOverlays(context) && !onScreen()) return "Allow 885rokidOS to display over other apps"
        // Behind the keyguard Grok Bot stays hidden and never listens. With Smart Lock the keyguard can go without a PIN;
        // when the device is really locked, do not open the PIN screen in a pocket.
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        // Only a screen that was off goes off again; a screen that was on stays on (user choice 2026-11-03).
        val screenWasOff = !context.getSystemService(PowerManager::class.java).isInteractive
        if (screenWasOff) ScreenLockService.ensureOn(context)
        if (keyguard.isKeyguardLocked) {
            if (keyguard.isDeviceLocked) return "Unlock the phone for Grok Bot"
            context.startActivity(Intent(context, GrokBotUnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(GrokBotUnlockActivity.EXTRA_LOCK_AFTER, screenWasOff))
            return null
        }
        context.startActivity(intent)
        if (screenWasOff) ScreenLockService.lockAfterGrokBotStarts(context)
        return null
    }

    /**
     * True while a Grok Bot voice chat runs: then [open] closes it (Grok Bot ends the chat on a second ACTION_ASSIST).
     * Grok Bot holds the phone in MODE_IN_COMMUNICATION for the chat and sets MODE_NORMAL when it ends (2026-11-03). Android
     * does not say which app set the mode, so another app's internet call also counts; during such a call the caller uses
     * [callRecordings] instead (NavSessionCoordinator, OPEN_GROK_BOT).
     */
    fun running(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_IN_COMMUNICATION

    /**
     * Active call recordings (source VOICE_COMMUNICATION) of every app; Android hides which app. During a WhatsApp call a
     * Grok Bot chat adds one (silenced) from its start to its end (dumpsys audio, 2026-11-03).
     */
    fun callRecordings(context: Context): Int =
        context.getSystemService(AudioManager::class.java).activeRecordingConfigurations
            .count { it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION }

    /** The Grok Bot start, or null when Grok Bot is not installed. */
    fun intent(context: Context): Intent? {
        // ACTION_ASSIST starts a voice chat at once (Grok Bot 1.19.0, AssistTrampolineActivity); it uses the glasses mic and
        // speaker when their call audio (SCO) is up (tested 2026-11-02, docs/LOCAL_TEST_RESULTS.md). Older versions without
        // it get the home screen. Both need the package in the manifest's <queries> (Android 11+ package visibility).
        val assist = Intent(Intent.ACTION_ASSIST).setPackage(PACKAGE)
        val intent = (if (assist.resolveActivity(context.packageManager) != null) assist else null)
            ?: context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return null
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    private fun onScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
