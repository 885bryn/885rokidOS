package com.rokidos885.phone.nav

import com.rokidos885.protocol.LatLng
import com.rokidos885.protocol.ManeuverUpdate
import com.rokidos885.protocol.NavState
import com.rokidos885.protocol.PositionUpdate
import com.rokidos885.protocol.TripProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Turn-by-turn guidance, abstracted from Google so the coordinator can run against a fake/replay.
 * Emits protocol types directly: the phone UI and the glasses consume the same data.
 */
interface NavigationEngine {
    val navState: StateFlow<NavState>
    val position: Flow<PositionUpdate>
    val maneuver: Flow<ManeuverUpdate>
    val progress: Flow<TripProgress>

    /** Fires whenever the active route changes (start, reroute, detour accepted). */
    val destination: StateFlow<Destination?>
    val activeRoute: StateFlow<ActiveRoute?>

    /** Must be called from an Activity: Navigation SDK shows its terms dialog on first run. */
    suspend fun initialize(activity: android.app.Activity): Boolean

    /** Starts guidance on a route the user picked. [option.routeToken] pins the exact route. */
    suspend fun start(destination: Destination, option: RouteOption?): Boolean

    /** Switches to a different route mid-trip (accepted detour). */
    suspend fun switchRoute(option: RouteOption): Boolean

    fun setVoiceMuted(muted: Boolean)

    /** Debug: drive along the route with the SDK simulator. */
    fun simulate(enabled: Boolean)

    fun stop()

    /** Frees navigation resources (and their notification) when no trip is starting or running. */
    fun releaseIfIdle() {}
}

data class Destination(val placeId: String?, val name: String, val location: LatLng)

/** One candidate route from Routes API (route picker + detours). */
data class RouteOption(
    val routeToken: String,
    val encodedPolyline: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    /** Free-flow duration; durationSeconds - staticDurationSeconds = traffic delay. */
    val staticDurationSeconds: Int,
    val description: String,
    val speedIntervals: List<SpeedInterval>,
    val warnings: List<String> = emptyList(),
)

/** From Routes API travelAdvisory.speedReadingIntervals (polyline point indices). */
data class SpeedInterval(val startIndex: Int, val endIndex: Int, val speed: String)

data class ActiveRoute(val routeId: String, val encodedPolyline: String, val totalMeters: Int)
