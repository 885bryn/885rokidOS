package com.rokidos885.phone.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rokidos885.phone.notify.PhoneNotificationListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Follows the phone's active media session (Spotify, YouTube Music, ...). Android only lets an app list
 * sessions when it holds Notification access, so this works on the same permission as notifications:
 * [start] is called at app start and again by [PhoneNotificationListener] once access is granted.
 */
class MediaMonitor(private val context: Context) : MediaSource {
    private val mutable = MutableStateFlow<MediaInfo?>(null)
    override val info: Flow<MediaInfo?> = mutable.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val manager by lazy { context.getSystemService(MediaSessionManager::class.java) }
    private val component by lazy { ComponentName(context, PhoneNotificationListener::class.java) }
    private var started = false
    private var controller: MediaController? = null
    private var lastPlaying: Boolean? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { choose(it.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    /** Safe to call any time; does nothing until Notification access is granted. */
    fun start() {
        if (started) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            started = true
            refresh()
        } catch (_: SecurityException) {
            Log.i(TAG, "no notification access yet")
        }
    }

    private fun refresh() = choose(runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList()))

    /** Prefers a session that is playing; the list is already ordered by Android's priority. */
    private fun choose(sessions: List<MediaController>) {
        val pick = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        if (pick?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = pick
            pick?.registerCallback(callback, main)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val state = c?.playbackState
        if (c == null || title.isBlank() || state == null || state.state == PlaybackState.STATE_STOPPED || state.state == PlaybackState.STATE_NONE) {
            mutable.value = null
            return
        }
        val playing = state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING
        if (playing != lastPlaying) { lastPlaying = playing; Log.i(TAG, "playing=$playing pkg=${c.packageName} state=${state.state}") }
        val position = if (state.state == PlaybackState.STATE_PLAYING)
            state.position + ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        else state.position
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        mutable.value = MediaInfo(
            app = appLabel(c.packageName),
            title = title,
            artist = (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            playing = playing,
            positionMs = position.coerceAtLeast(0),
            durationMs = duration.coerceAtLeast(0),
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    override fun playPause() {
        val c = controller ?: return
        Log.i(TAG, "action play_pause")
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }
    override fun next() { Log.i(TAG, "action next"); controller?.transportControls?.skipToNext() }
    override fun previous() { Log.i(TAG, "action previous"); controller?.transportControls?.skipToPrevious() }

    private companion object { const val TAG = "885rokidOSMedia" }
}
