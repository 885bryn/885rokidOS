package com.rokidos885.phone.voice

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle

/**
 * Removes the lock screen, then starts Grok Bot. Started by [GrokBot.open] only while the keyguard shows but the device is
 * not locked (Smart Lock / Extend Unlock: no PIN needed). Grok Bot started behind the keyguard stays hidden and never
 * listens (tested 2026-11-02, docs/LOCAL_TEST_RESULTS.md). Shows nothing itself (translucent theme).
 */
class GrokBotUnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                GrokBot.intent(this@GrokBotUnlockActivity)?.let { startActivity(it) }
                // The phone was on the lock screen (in a pocket): lock it again once Grok Bot listens.
                ScreenLockService.lockAfterGrokBotStarts(this@GrokBotUnlockActivity)
                finish()
            }
            override fun onDismissError() = finish()
            override fun onDismissCancelled() = finish()
        })
    }
}
