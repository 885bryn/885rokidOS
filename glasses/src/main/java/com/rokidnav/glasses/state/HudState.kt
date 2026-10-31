package com.rokidnav.glasses.state

import com.rokidnav.protocol.Alert
import com.rokidnav.protocol.DetourOffer
import com.rokidnav.protocol.LatLng
import com.rokidnav.protocol.ManeuverUpdate
import com.rokidnav.protocol.MediaState
import com.rokidnav.protocol.NavState
import com.rokidnav.protocol.PhoneNotification
import com.rokidnav.protocol.PositionUpdate
import com.rokidnav.protocol.TrafficSegment
import com.rokidnav.protocol.TripProgress
import com.rokidnav.protocol.Units

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

    /** What the phone plays, or null. [mediaAt] is the glasses clock when it arrived (see [mediaPositionMs]). */
    val media: MediaState? = null,
    val mediaAt: Long = 0,

    val mode: HudMode = HudMode.Compact,
    val mapZoom: Int = 1,         // 0 = close (~300 m ahead), 1 = default (~800 m), 2 = far (~2 km)
    val voiceMuted: Boolean = false,
    /** From phone settings: redraw the map at 30 fps all the time (demos). */
    val demoMode: Boolean = false,
    val nowMs: Long = 0,
)

/** Playing position now: the position at arrival plus the time since, while playing. */
fun HudState.mediaPositionMs(): Long {
    val m = media ?: return 0
    val position = m.positionMs + if (m.playing) (nowMs - mediaAt).coerceAtLeast(0) else 0
    return if (m.durationMs > 0) position.coerceIn(0, m.durationMs) else position.coerceAtLeast(0)
}

data class HudRoad(val points: List<LatLng>, val name: String, val major: Boolean)

sealed interface HudMode {
    /** Bottom corners leave the road in the upper/central field clear. Tap cycles Compact → Map → Minimal. */
    data object Compact : HudMode
    /** Default: maneuver banner + map + status strip. */
    data object Map : HudMode
    /** Corner instructions and ETA only, map turned off: least light in the eye and least power. */
    data object Minimal : HudMode
}

