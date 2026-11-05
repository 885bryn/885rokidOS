package com.rokidos885.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every message between phone and glasses. Distances are meters, times are seconds, timestamps are
 * epoch millis. Formatting (mi/km, 12/24h) happens on the glasses using [SessionStart.units].
 *
 * Rules: add fields with defaults only; never reorder/rename @SerialName values; document every
 * change in docs/PROTOCOL.md. Breaking changes bump [ProtocolVersion].
 */
@Serializable
sealed interface NavMessage {
    val seq: Long
}

// ---------------------------------------------------------------- phone -> glasses

@Serializable @SerialName("session_start")
data class SessionStart(
    override val seq: Long,
    val units: Units = Units.IMPERIAL,
    val clock24h: Boolean = false,
    val destinationName: String = "",
) : NavMessage

/**
 * Asks the glasses to answer with [GlassesHello]. Sent by the phone link when a session starts: a HUD
 * that was already running is only brought to the front, so it would not say hello by itself. Handled
 * outside the HUD's sequence ordering, so [seq] is 0.
 */
@Serializable @SerialName("hello_request")
data class HelloRequest(override val seq: Long = 0) : NavMessage

/** Route polyline, sent once per route (and again on reroute). Large routes are split by [RouteChunker]. */
@Serializable @SerialName("route_chunk")
data class RouteChunk(
    override val seq: Long,
    val routeId: String,
    val chunkIndex: Int,
    val chunkCount: Int,
    /** Slice of a Google encoded polyline string. Concatenate all chunks in order, then decode. */
    val encodedPolylinePart: String,
    val totalMeters: Int,
) : NavMessage

/**
 * Surrounding streets for the glasses map (see docs/DATA_SOURCES.md, "map context"). One snapshot may
 * span several frames ([RoadContextChunker]): the glasses replace their streets once all [partCount]
 * parts of [contextId] have arrived. Defaults keep single-frame senders valid.
 */
@Serializable @SerialName("road_context")
data class RoadContext(
    override val seq: Long,
    val routeId: String,
    val roads: List<ContextRoad>,
    val contextId: String = "",
    val part: Int = 0,
    val partCount: Int = 1,
) : NavMessage

@Serializable
data class ContextRoad(val encodedPolyline: String, val name: String = "", val major: Boolean = false)

/** ~1 Hz. Glasses interpolate between updates for smooth motion. */
@Serializable @SerialName("pos")
data class PositionUpdate(
    override val seq: Long,
    val lat: Double,
    val lng: Double,
    val bearingDeg: Float,
    val speedMps: Float,
    /** Distance travelled along the current route polyline; lets glasses snap the chevron to the line. */
    val metersAlongRoute: Int = -1,
    val timestampMs: Long,
) : NavMessage

@Serializable @SerialName("maneuver")
data class ManeuverUpdate(
    override val seq: Long,
    val current: Step,
    val distanceToStepMeters: Int,
    val timeToStepSeconds: Int,
    /** The step after [current], shown as "then ..." when close together. */
    val next: Step? = null,
) : NavMessage

@Serializable
data class Step(
    val maneuver: Maneuver,
    val roadName: String = "",
    val instruction: String = "",
    val exitNumber: String = "",
    val roundaboutExit: Int = 0,
    val lanes: List<Lane> = emptyList(),
)

@Serializable
data class Lane(val directions: List<Maneuver>, val recommended: Boolean)

@Serializable @SerialName("progress")
data class TripProgress(
    override val seq: Long,
    val etaEpochMs: Long,
    val remainingMeters: Int,
    val remainingSeconds: Int,
    /** Extra time vs free-flow, from traffic. 0 when unknown. */
    val trafficDelaySeconds: Int = 0,
) : NavMessage

/** Congestion along the current route, by distance from route start. */
@Serializable @SerialName("traffic")
data class TrafficSegments(
    override val seq: Long,
    val routeId: String,
    val segments: List<TrafficSegment>,
) : NavMessage

@Serializable
data class TrafficSegment(val startMeters: Int, val endMeters: Int, val level: TrafficLevel)

@Serializable @SerialName("alert")
data class Alert(
    override val seq: Long,
    val id: String,
    val kind: AlertKind,
    val distanceAheadMeters: Int,
    val lat: Double,
    val lng: Double,
    val text: String = "",
    val source: String = "",
    val reportedAtMs: Long = 0,
) : NavMessage

@Serializable @SerialName("alert_clear")
data class AlertCleared(override val seq: Long, val id: String) : NavMessage

@Serializable @SerialName("detour_offer")
data class DetourOffer(
    override val seq: Long,
    val offerId: String,
    val savesSeconds: Int,
    val extraMeters: Int,
    /** Short label like "via I-280". */
    val via: String,
    val reason: String = "",
    val expiresAtMs: Long,
    val encodedPolyline: String = "",
) : NavMessage

@Serializable @SerialName("detour_resolved")
data class DetourResolved(override val seq: Long, val offerId: String, val accepted: Boolean) : NavMessage

@Serializable @SerialName("nav_state")
data class NavStateUpdate(override val seq: Long, val state: NavState) : NavMessage

/** Phone-side settings pushed to glasses (e.g. after a menu change is applied). */
@Serializable @SerialName("settings")
data class SettingsUpdate(
    override val seq: Long,
    val voiceMuted: Boolean,
    val units: Units,
    val mapZoomLevel: Int,
    /** Deprecated, ignored: the glasses keep the global Rokid brightness. */
    val brightnessPercent: Int = 35,
    /** Demo mode: the map redraws at 30 fps all the time (smooth for demos, more battery). */
    val demoMode: Boolean = false,
) : NavMessage

/**
 * A phone notification the user chose to see on the glasses (see PhoneNotificationListener). Shown
 * briefly as a card over any HUD view; never cached for resync, because an old notification is noise.
 */
@Serializable @SerialName("notification")
data class PhoneNotification(
    override val seq: Long,
    /** Phone-side key; a newer post with the same id replaces the card. */
    val id: String,
    /** App label, e.g. "Messages". */
    val app: String = "",
    val title: String = "",
    val text: String = "",
    val postedAtMs: Long = 0,
    /** The app offers a free-text reply on this notification: a tap on the card can start a voice reply. */
    val canReply: Boolean = false,
) : NavMessage

/**
 * What the phone is playing (Spotify, YouTube Music, ...), from its active media session. Sent when the
 * track, play state or position jumps, never as a stream: the glasses extrapolate [positionMs] while
 * [playing]. [active] = false means nothing is playing any more. Resent on resync with a fresh position.
 */
@Serializable @SerialName("media")
data class MediaState(
    override val seq: Long,
    val active: Boolean = true,
    /** App label, e.g. "Spotify". */
    val app: String = "",
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    /** Position at the moment the phone sent this message. */
    val positionMs: Long = 0,
    /** 0 when unknown (streams). */
    val durationMs: Long = 0,
) : NavMessage

/**
 * Progress of a voice reply to notification [notificationId], phone to glasses. [text] is the words so far
 * (LISTENING), the final words (REVIEW) or the reason (FAILED). [VoicePhase.IDLE] clears the screen.
 */
@Serializable @SerialName("voice")
data class VoiceState(
    override val seq: Long,
    val phase: VoicePhase,
    val notificationId: String = "",
    val text: String = "",
) : NavMessage

/**
 * [VoiceState.notificationId] and [GlassesAction.targetId] of the Grok Bot card, which has no notification under it. The
 * glasses send OPEN_GROK_BOT (tap on the empty idle screen, swipe back on a trip); the phone answers SENT (text = what was
 * done) or FAILED (text = the reason). There is no listening step and no REVIEW step. The name and value are kept so a HUD
 * that is not updated yet still matches.
 */
const val VOICE_COMMAND_ID = "voice-command"

/**
 * A Grok Bot voice chat started from the glasses runs on the phone ([active]) or ended. Meanwhile the glasses keep the
 * display on and show that a tap ends it, so the ending tap never only wakes a sleeping display. Sent on change and on resync.
 */
@Serializable @SerialName("grok_bot")
data class GrokBotState(override val seq: Long, val active: Boolean) : NavMessage

/**
 * [VoiceState.notificationId] and [GlassesAction.targetId] of a dictation to T3 Code on the PC (the T3 page). The usual
 * VOICE_* steps apply, REVIEW included; the phone's send step hands the text to T3 and [T3Status] follows the run.
 */
const val T3_CARD_ID = "t3"

/**
 * The T3 page: the T3 Code [thread] the phone sends to, its [model], the words last sent ([said]) and the run [state].
 * [text] is the reply (REPLY) or the reason (FAILED, NEEDS_PC). A REPLY also comes as [parts] (plain text, a few
 * sentences each) that the phone reads aloud one by one: [part] is the one being read while [reading]. Sent on change
 * and on resync.
 */
@Serializable @SerialName("t3")
data class T3Status(
    override val seq: Long,
    val thread: String = "",
    val model: String = "",
    val state: T3State = T3State.READY,
    val said: String = "",
    val text: String = "",
    val parts: List<String> = emptyList(),
    val part: Int = 0,
    val reading: Boolean = false,
) : NavMessage

// ---------------------------------------------------------------- glasses -> phone

@Serializable @SerialName("hello")
data class GlassesHello(
    override val seq: Long,
    val appVersion: String,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
) : NavMessage

/** Raw input, mostly for logging/calibration. Business logic should use [GlassesAction]. */
@Serializable @SerialName("input")
data class InputEvent(override val seq: Long, val key: String) : NavMessage

@Serializable @SerialName("action")
data class GlassesAction(
    override val seq: Long,
    val action: ActionType,
    /** e.g. offerId for ACCEPT_DETOUR. */
    val targetId: String = "",
) : NavMessage

/** Glasses ask for a full resync (after reconnect, app restart, or a missed route chunk). */
@Serializable @SerialName("resync")
data class ResyncRequest(override val seq: Long, val reason: String = "") : NavMessage

// ---------------------------------------------------------------- shared enums

@Serializable enum class Units { IMPERIAL, METRIC }

@Serializable enum class TrafficLevel { NORMAL, SLOW, JAM }

@Serializable enum class AlertKind {
    POLICE, SPEED_CAMERA, ACCIDENT, HAZARD, ROAD_CLOSED, CONSTRUCTION, TRAFFIC_JAM, WEATHER, OTHER
}

/** STARTING = tapped, the microphone is not live yet (glasses show it at once); LISTENING = speak now. */
@Serializable enum class VoicePhase { IDLE, STARTING, LISTENING, REVIEW, SENT, FAILED }

/** READY = nothing under way; SENT = on its way to T3; WORKING = the agent runs; NEEDS_PC = it waits for an answer at the PC. */
@Serializable enum class T3State { READY, SENT, WORKING, REPLY, FAILED, NEEDS_PC }

@Serializable enum class NavState { IDLE, ROUTING, ACTIVE, REROUTING, GPS_LOST, ARRIVED, ENDED }

@Serializable enum class ActionType {
    ACCEPT_DETOUR, DISMISS_DETOUR, END_NAVIGATION, TOGGLE_VOICE, ZOOM_IN, ZOOM_OUT,
    SHOW_ALTERNATIVES, TOGGLE_UNITS, CYCLE_BRIGHTNESS, CYCLE_ZOOM,
    MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS,
    /** Voice reply to the notification in targetId: start / stop listening now / send the text / listen again / give up. */
    VOICE_START, VOICE_STOP, VOICE_SEND, VOICE_REDO, VOICE_CANCEL,
    /** Start a Grok Bot voice chat on the phone, no speech step; targetId [VOICE_COMMAND_ID]. The phone answers SENT or FAILED. */
    OPEN_GROK_BOT,
}

/** Mirrors the Google Navigation SDK maneuver set closely enough to map 1:1 (see NavInfoMapper). */
@Serializable enum class Maneuver {
    UNKNOWN, DEPART, DESTINATION, DESTINATION_LEFT, DESTINATION_RIGHT,
    STRAIGHT, TURN_LEFT, TURN_RIGHT, TURN_SLIGHT_LEFT, TURN_SLIGHT_RIGHT,
    TURN_SHARP_LEFT, TURN_SHARP_RIGHT, U_TURN_LEFT, U_TURN_RIGHT,
    MERGE_LEFT, MERGE_RIGHT, MERGE_UNSPECIFIED,
    FORK_LEFT, FORK_RIGHT, ON_RAMP_LEFT, ON_RAMP_RIGHT, OFF_RAMP_LEFT, OFF_RAMP_RIGHT,
    ROUNDABOUT_CLOCKWISE, ROUNDABOUT_COUNTERCLOCKWISE, FERRY, NAME_CHANGE,
}
