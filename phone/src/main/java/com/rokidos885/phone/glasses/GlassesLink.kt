package com.rokidos885.phone.glasses

import android.app.Activity
import com.rokidos885.protocol.NavMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the rest of the phone app knows about the glasses. Only [CxrGlassesLink] touches the
 * Rokid SDK, so the navigation code can be built and tested with a fake.
 */
interface GlassesLink {
    val state: StateFlow<LinkState>

    /** Messages from the glasses app (actions, input, hello, resync). */
    val incoming: Flow<NavMessage>

    /** Checks Rokid AI App install/version. Safe to call any time. */
    fun checkEnvironment(): EnvironmentStatus

    /** Starts Rokid authorization (opens Rokid AI App). Must be called from an Activity. */
    fun authorize(activity: Activity)

    /** Creates a CUSTOM_APP session and launches the glasses app. Requires a token from [authorize]. */
    fun connect()

    /** Returns false (and drops the message) unless state is [LinkState.Ready]. */
    fun send(message: NavMessage): Boolean

    /** Raw chunks from the glasses' microphone while [startAudio] is on. The format is VERIFY (see VoiceSpike). */
    val audio: Flow<ByteArray>

    /** Starts the microphone stream. False (and nothing happens) unless state is [LinkState.Ready]. */
    fun startAudio(): Boolean
    fun stopAudio()

    fun disconnect()
}

sealed interface EnvironmentStatus {
    data object Ok : EnvironmentStatus
    data class RokidAppMissing(val downloadUrl: String?) : EnvironmentStatus
    data class RokidAppTooOld(val downloadUrl: String?) : EnvironmentStatus
}

sealed interface LinkState {
    data object Idle : LinkState
    data object Authorizing : LinkState
    data object Authorized : LinkState
    data object Connecting : LinkState
    /** The glasses run an older HUD than the phone bundles; it is being removed and reinstalled. */
    data object UpdatingHud : LinkState
    /** Session started and glasses app is interactive. Only state in which [GlassesLink.send] works. */
    data object Ready : LinkState
    /** Temporarily unavailable (Rokid AI assistant took over, or Bluetooth dropped). Token still valid. */
    data class Paused(val reason: String) : LinkState
    /** Session ended. [needsReauth] = token invalid (CHAIN_TORN / LINK_LOST). */
    data class Closed(val reason: String, val needsReauth: Boolean) : LinkState {
        /** The HUD left the foreground on the glasses (e.g. double-click to Rokid home), so the SDK ended
         *  the session. Reopen it from the phone with [GlassesLink.connect] ("Show on glasses"). */
        val hudClosedOnGlasses get() = reason == HUD_CLOSED
        companion object { const val HUD_CLOSED = "GLASSES_APP_EXIT" }
    }
    /** [installBlocked]: Hi Rokid could not send the HUD (its Wi-Fi transfer needs Location, which
     *  "while using the app" only grants while Hi Rokid is on screen). Retry with Hi Rokid in front. */
    data class Failed(val message: String,val canRetry:Boolean=false,val installBlocked:Boolean=false) : LinkState
}
