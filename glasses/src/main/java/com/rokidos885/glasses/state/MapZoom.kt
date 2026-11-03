package com.rokidos885.glasses.state

import com.rokidos885.protocol.Maneuver

/**
 * How many metres of road ahead the map shows (chevron to top edge). Pure, so it is unit tested.
 *
 * - Speed sets the cruising view: ~200 m in town, ~700 m at 80 km/h, up to ~1.6 km on fast highways.
 * - An upcoming turn pulls the view in progressively from the moment it enters the map, keeping the turn
 *   about two-thirds of the way up, down to [TURN_MIN_M] at the corner. After the turn the next step is
 *   far away again, so the view eases back out.
 * - The user's zoom setting scales the result (Close 0.7×, Default 1×, Far 1.5×).
 * The renderer eases toward this target so changes never jump.
 */
object MapZoom {
    const val TURN_MIN_M = 150f
    private const val MIN_M = 120f
    private const val MAX_M = 2500f

    /** (km/h, metres ahead) knots; linear between them. */
    private val SPEED_KNOTS = arrayOf(0f to 200f, 30f to 220f, 50f to 350f, 80f to 700f, 110f to 1200f, 130f to 1600f)

    private val NOT_A_TURN = setOf(Maneuver.STRAIGHT, Maneuver.NAME_CHANGE, Maneuver.DEPART, Maneuver.UNKNOWN)

    fun cruiseMeters(speedMps: Float): Float {
        val kmh = (speedMps * 3.6f).coerceAtLeast(0f)
        for (i in 1 until SPEED_KNOTS.size) {
            val (v0, m0) = SPEED_KNOTS[i - 1]; val (v1, m1) = SPEED_KNOTS[i]
            if (kmh <= v1) return m0 + (m1 - m0) * ((kmh - v0) / (v1 - v0))
        }
        return SPEED_KNOTS.last().second
    }

    fun targetMeters(speedMps: Float, distanceToStepMeters: Int?, maneuver: Maneuver?, userZoom: Int): Float {
        var ahead = cruiseMeters(speedMps)
        if (distanceToStepMeters != null && maneuver != null && maneuver !in NOT_A_TURN) {
            // Turn in (or about to enter) view: frame it at ~70% of the map height.
            val framed = distanceToStepMeters * 1.4f + 60f
            if (framed < ahead) ahead = framed.coerceAtLeast(TURN_MIN_M)
        }
        val scale = when (userZoom) { 0 -> 0.7f; 2 -> 1.5f; else -> 1f }
        return (ahead * scale).coerceIn(MIN_M, MAX_M)
    }

    /** One easing step toward [target]; ~63% of the gap closes every [TIME_CONSTANT_S]. */
    fun ease(current: Float, target: Float, dtSeconds: Float): Float {
        if (current <= 0f) return target
        val k = (dtSeconds / TIME_CONSTANT_S).coerceIn(0f, 1f)
        return current + (target - current) * k
    }
    private const val TIME_CONSTANT_S = 0.6f
}
