package com.rokidos885.phone.media

import kotlinx.coroutines.flow.Flow

/** What the phone is playing right now. [positionMs] is the position at the moment of the snapshot. */
data class MediaInfo(
    val app: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * The phone's active media session as the rest of the app sees it. Only [MediaMonitor] touches Android's
 * media-session classes, so the coordinator can be tested with a fake.
 */
interface MediaSource {
    /** Emits on every change of track, play state or position jump; null = nothing playing. */
    val info: Flow<MediaInfo?>
    fun playPause()
    fun next()
    fun previous()
}
