package com.rokidos885.glasses.state

import com.rokidos885.protocol.Alert
import com.rokidos885.protocol.DetourOffer
import com.rokidos885.protocol.LatLng
import com.rokidos885.protocol.ManeuverUpdate
import com.rokidos885.protocol.MediaState
import com.rokidos885.protocol.NavState
import com.rokidos885.protocol.PhoneNotification
import com.rokidos885.protocol.PositionUpdate
import com.rokidos885.protocol.T3Status
import com.rokidos885.protocol.TrafficSegment
import com.rokidos885.protocol.TripProgress
import com.rokidos885.protocol.Units
import com.rokidos885.protocol.VoiceState

/** Immutable snapshot the HudView draws. Everything the screen shows must come from here. */
data class HudState(
    val linkConnected: Boolean = false,
    /** Mid-trip and nothing from the phone for [HudStore.PHONE_SILENT_MS]: the phone's session ended
     *  (e.g. the HUD was backgrounded), so guidance on screen would be frozen. */
    val phoneSilent: Boolean = false,
    val navState: NavState = NavState.IDLE,
    val units: Units = Units.METRIC,
    val clock24h: Boolean = false,
    val destinationName: String = "",

    val route: List<LatLng> = emptyList(),
    val routeTotalMeters: Int = 0,
    val traffic: List<TrafficSegment> = emptyList(),
    val contextRoads: List<HudRoad> = emptyList(),
    val position: PositionUpdate? = null,
    val previousPosition: PositionUpdate? = null,
    val maneuver: ManeuverUpdate? = null,
    val progress: TripProgress? = null,

    /** Active alerts sorted nearest-first; the HUD shows only the first. */
    val alerts: List<Alert> = emptyList(),
    /** When each alert id first appeared (clock ms): the chip is highlighted briefly, then goes quiet. */
    val alertSeenAt: Map<String, Long> = emptyMap(),
    val detour: DetourOffer? = null,
    /** Latest phone notification, shown as a card for [HudStore.notificationMillis]; tap hides it. */
    val notification: PhoneNotification? = null,
    val notificationShownAt: Long = 0,
    /** A voice reply in progress for [notification]; while it is set the card does not expire. */
    val voice: VoiceState? = null,
    val voiceAt: Long = 0,
    /** Focused row of the review screen: 0 Send, 1 Redo, 2 Cancel. */
    val voiceFocus: Int = 0,
    /** A Grok Bot chat runs on the phone: the display stays on and shows that a tap ends it. */
    val grokBotActive: Boolean = false,

    /** What the phone plays, or null. Only track, artist and play state matter: nothing here moves with time. */
    val media: MediaState? = null,
    /** The player is on screen only when the user asked for it (woke the glasses or touched the pad), never because
     *  music is playing or a notification just ended. It hides itself after [HudStore.MEDIA_SHOW_MS]. */
    val mediaShown: Boolean = false,
    val mediaShownAt: Long = 0,

    /** The T3 page, left of home (swipe back from home). It stays the page across display sleep, until a swipe forward. */
    val t3Page: Boolean = false,
    /** Thread, model and run state from the phone, or null before the phone sent any. */
    val t3: T3Status? = null,
    /** The reply part on screen: the one the phone reads, or the one a swipe picked. */
    val t3Part: Int = 0,
    /** Goes up when news from T3 must wake the display (MainActivity wakes it). */
    val wakeSeq: Int = 0,

    val mode: HudMode = HudMode.Compact,
    val mapZoom: Int = 1,         // 0 = close (~300 m ahead), 1 = default (~800 m), 2 = far (~2 km)
    val voiceMuted: Boolean = false,
    /** From phone settings: redraw the map at 30 fps all the time (demos). */
    val demoMode: Boolean = false,
    val nowMs: Long = 0,

    /** The display is awake and the HUD knows it: a touch acts. Asleep, the touch that wakes the display only wakes it. */
    val awake: Boolean = true,
    /** 4, 3, 2, 1: seconds until the HUD lets the display sleep; 0 when no countdown runs. */
    val sleepIn: Int = 0,
)

/** The phone is guiding: the display stays on for the whole trip. */
val HudState.guiding: Boolean get() = navState in GUIDING && linkConnected && !phoneSilent
private val GUIDING = setOf(NavState.ACTIVE, NavState.REROUTING, NavState.GPS_LOST, NavState.ARRIVED)

/** Something on screen that must stay on: a trip, a card, a voice step or a Grok Bot chat. The awake time starts after it. */
val HudState.holdsDisplay: Boolean get() = guiding || notification != null || voice != null || grokBotActive ||
    (t3Visible && t3?.reading == true && linkConnected)

/** Home with nothing on it, awake: "ACTIVE" says the next tap starts Grok Bot. */
val HudState.homeActive: Boolean get() = awake && linkConnected && !t3Page && !mediaVisible && notification == null && voice == null &&
    !grokBotActive && (navState == NavState.IDLE || navState == NavState.ENDED)

val HudState.mediaVisible: Boolean get() = media != null && mediaShown

/** The T3 page is on screen: no trip, and no card or voice step over it. */
val HudState.t3Visible: Boolean get() = t3Page && notification == null && voice == null && (navState == NavState.IDLE || navState == NavState.ENDED)

/** A tap on the card starts a voice reply: the app offers a reply box and no trip is running (trip cards stay glances). */
val HudState.replyAvailable: Boolean get() = notification?.canReply == true && (navState == NavState.IDLE || navState == NavState.ENDED)

data class HudRoad(val points: List<LatLng>, val name: String, val major: Boolean)

sealed interface HudMode {
    /** Bottom corners leave the road in the upper/central field clear. Tap cycles Compact → Map → Minimal. */
    data object Compact : HudMode
    /** Default: maneuver banner + map + status strip. */
    data object Map : HudMode
    /** Corner instructions and ETA only, map turned off: least light in the eye and least power. */
    data object Minimal : HudMode
}

