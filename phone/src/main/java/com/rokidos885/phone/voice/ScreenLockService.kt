package com.rokidos885.phone.voice

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Locks the phone again (like the power button) after the glasses started or closed Grok Bot, so the screen does not stay on
 * in a pocket. A normal app cannot turn the screen off; an accessibility service can ([GLOBAL_ACTION_LOCK_SCREEN]). The user
 * turns it on in Settings, Accessibility; without it nothing happens. Smart Lock stays on after this lock, and the Grok Bot
 * voice chat keeps running with the screen off (tested 2026-11-02, docs/LOCAL_TEST_RESULTS.md). Reads no screen content.
 *
 * Android can turn the service off (seen 2026-11-05 after app reinstalls). With WRITE_SECURE_SETTINGS, granted once by adb
 * (`adb shell pm grant com.rokidos885.phone android.permission.WRITE_SECURE_SETTINGS`), [ensureOn] turns it on again.
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
            // Just turned on again, the service may not be bound yet; it is by the time of the lock (1 s or more).
            if (running == null && !enabled(context)) return
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

        /** True when the service is on in Accessibility settings (bound or about to be). */
        fun enabled(context: Context): Boolean {
            val ours = ComponentName(context, ScreenLockService::class.java)
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').any { ComponentName.unflattenFromString(it) == ours }
        }

        /** True when adb granted WRITE_SECURE_SETTINGS, so [ensureOn] can turn the service on by itself. */
        fun canTurnOn(context: Context) =
            context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        /** Turns the service on again when Android turned it off and [canTurnOn]; keeps the other services. True when on. */
        fun ensureOn(context: Context): Boolean {
            if (enabled(context)) return true
            if (!canTurnOn(context)) return false
            val resolver = context.contentResolver
            val others = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() }
            val ours = ComponentName(context, ScreenLockService::class.java).flattenToString()
            runCatching {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + ours).joinToString(":"))
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }.onFailure { android.util.Log.w("885rokidOSLock", "screen lock not turned on: ${it.javaClass.simpleName}") }
            return enabled(context)
        }
    }
}
