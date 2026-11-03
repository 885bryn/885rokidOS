package com.rokidos885.phone.voice

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Locks the phone again (like the power button) after the glasses started or closed Grok Bot, so the screen does not stay on
 * in a pocket. A normal app cannot turn the screen off; an accessibility service can ([GLOBAL_ACTION_LOCK_SCREEN]). The user
 * turns it on in Settings, Accessibility; without it nothing happens. Smart Lock stays on after this lock, and the Grok Bot
 * voice chat keeps running with the screen off (tested 2026-11-02, docs/LOCAL_TEST_RESULTS.md). Reads no screen content.
 */
class ScreenLockService : AccessibilityService() {
    override fun onServiceConnected() { running = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { running = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        @Volatile private var running: ScreenLockService? = null
        private val main = Handler(Looper.getMainLooper())
        private const val POLL_MS = 250L
        /** Grok Bot opened its microphone about 0.3 s and the glasses call audio about 1 s after the start (2026-11-02). */
        private const val MAX_WAIT_MS = 8_000L
        private const val AFTER_MIC_MS = 1_000L

        /**
         * Locks once Grok Bot records (any app's recording: Android hides which one), plus [AFTER_MIC_MS]; or after
         * [MAX_WAIT_MS] when nothing records (a second tap that ended the chat). A glasses start means a pocket, so lock anyway.
         */
        fun lockAfterGrokBotStarts(context: Context) {
            if (running == null) return
            val audio = context.applicationContext.getSystemService(AudioManager::class.java)
            val start = System.currentTimeMillis()
            main.removeCallbacksAndMessages(null)
            fun check() {
                when {
                    audio.activeRecordingConfigurations.isNotEmpty() -> main.postDelayed({ running?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }, AFTER_MIC_MS)
                    System.currentTimeMillis() - start >= MAX_WAIT_MS -> running?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    else -> main.postDelayed(::check, POLL_MS)
                }
            }
            main.postDelayed(::check, POLL_MS)
        }
    }
}
