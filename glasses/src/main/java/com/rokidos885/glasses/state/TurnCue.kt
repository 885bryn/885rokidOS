package com.rokidos885.glasses.state

import com.rokidos885.protocol.Geo
import com.rokidos885.protocol.LatLng
import com.rokidos885.protocol.Maneuver
import kotlin.math.abs
import kotlin.math.hypot

/**
 * "2nd right · after Francisco St" for an upcoming turn, from the street context alone. Counts the
 * streets that open on the turn's side between the driver and the turn. A wrong count is worse than
 * none, so it returns "" whenever the data looks incomplete or the count is too long to be useful.
 */
object TurnCue {
    private const val MAX_RANGE_M = 400
    private const val MERGE_M = 15.0          // one intersection, even with split carriageways
    private const val SIDE_REACH_M = 12.0     // a street must extend this far to the side to count
    private const val PARALLEL_DOT = 0.9      // |cos| above this: the road we are on, not a cross street

    fun compute(route: List<LatLng>, along: Int, toTurn: Int, maneuver: Maneuver, roads: List<HudRoad>): String {
        val side = when (maneuver) {
            Maneuver.TURN_RIGHT, Maneuver.TURN_SLIGHT_RIGHT, Maneuver.TURN_SHARP_RIGHT -> "right"
            Maneuver.TURN_LEFT, Maneuver.TURN_SLIGHT_LEFT, Maneuver.TURN_SHARP_LEFT -> "left"
            else -> return ""
        }
        if (toTurn !in 30..MAX_RANGE_M || along < 0 || route.size < 2 || roads.isEmpty()) return ""

        // Route stretch from the driver to the turn, in local meters.
        val origin = route[0]
        val pts = route.map { Geo.toLocalMeters(origin, it) }
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
        val start = along.toDouble()
        val turnAt = start + toTurn
        val turnPoint = pointAt(pts, cum, turnAt) ?: return ""

        // Guard: a street leaving the route at the turn must be in the data (not just the road we are on),
        // otherwise the context is too sparse to count.
        val local = roads.map { r -> r.points.map { Geo.toLocalMeters(origin, it) } }
        val (inX, inY) = directionBefore(pts, cum, turnAt) ?: return ""
        val turnStreetKnown = local.any { line ->
            (1 until line.size).any {
                val a = line[it - 1]; val b = line[it]
                val len = hypot(b.first - a.first, b.second - a.second)
                len > 0 && segmentDistance(turnPoint, a, b) < 30 &&
                    abs(((b.first - a.first) * inX + (b.second - a.second) * inY) / len) <= PARALLEL_DOT
            }
        }
        if (!turnStreetKnown) return ""

        data class Opening(val at: Double, val name: String)
        val openings = ArrayList<Opening>()
        for (i in 1 until pts.size) {
            if (cum[i] < start + 15 || cum[i - 1] > turnAt - 20) continue
            val (px, py) = pts[i - 1]; val (qx, qy) = pts[i]
            val len = cum[i] - cum[i - 1]
            if (len <= 0) continue
            val dx = (qx - px) / len; val dy = (qy - py) / len
            for ((r, line) in local.withIndex()) {
                for (j in 1 until line.size) {
                    val (ax, ay) = line[j - 1]; val (bx, by) = line[j]
                    val ex = bx - ax; val ey = by - ay
                    val elen = hypot(ex, ey)
                    if (elen <= 0 || abs((ex * dx + ey * dy) / elen) > PARALLEL_DOT) continue
                    val hit = intersect(px, py, qx, qy, ax, ay, bx, by) ?: continue
                    val at = cum[i - 1] + hit
                    if (at < start + 15 || at > turnAt - 20) continue
                    val jx = px + dx * hit; val jy = py + dy * hit
                    // Does this street reach out on the turn side? cross < 0 is right of travel.
                    val opensOnSide = reachesSide(line, jx, jy, dx, dy, side == "right")
                    if (opensOnSide) openings += Opening(at, roads[r].name)
                }
            }
        }
        val merged = ArrayList<Opening>()
        for (o in openings.sortedBy { it.at }) {
            val last = merged.lastOrNull()
            if (last != null && o.at - last.at < MERGE_M) { if (last.name.isBlank() && o.name.isNotBlank()) merged[merged.lastIndex] = o }
            else merged += o
        }
        val nth = merged.size + 1
        if (nth > 4) return ""
        if (nth == 1) return "Next $side"
        val ordinal = when (nth) { 2 -> "2nd"; 3 -> "3rd"; else -> "4th" }
        val after = merged.last().name
        return if (after.isBlank()) "$ordinal $side" else "$ordinal $side · after $after"
    }

    /** Distance along segment p→q to its crossing with a→b, or null. */
    private fun intersect(px: Double, py: Double, qx: Double, qy: Double, ax: Double, ay: Double, bx: Double, by: Double): Double? {
        val rx = qx - px; val ry = qy - py; val sx = bx - ax; val sy = by - ay
        val denom = rx * sy - ry * sx
        if (abs(denom) < 1e-9) return null
        val t = ((ax - px) * sy - (ay - py) * sx) / denom
        val u = ((ax - px) * ry - (ay - py) * rx) / denom
        // Small tolerance on the cross street so T-junctions that stop at the kerb still count.
        val slack = 8.0 / hypot(sx, sy)
        if (t !in 0.0..1.0 || u < -slack || u > 1 + slack) return null
        return t * hypot(rx, ry)
    }

    /** Whether the street, within 60 m of the junction, extends [SIDE_REACH_M] to the right (or left) of travel. */
    private fun reachesSide(line: List<Pair<Double, Double>>, jx: Double, jy: Double, dx: Double, dy: Double, right: Boolean): Boolean {
        for (j in 1 until line.size) {
            val (ax, ay) = line[j - 1]; val (bx, by) = line[j]
            val len = hypot(bx - ax, by - ay)
            val steps = (len / 5).toInt().coerceIn(1, 400)
            for (k in 0..steps) {
                val x = ax + (bx - ax) * k / steps; val y = ay + (by - ay) * k / steps
                if (hypot(x - jx, y - jy) > 60) continue
                val cross = dx * (y - jy) - dy * (x - jx)   // > 0: left of travel
                if ((if (right) -cross else cross) > SIDE_REACH_M) return true
            }
        }
        return false
    }

    private fun directionBefore(pts: List<Pair<Double, Double>>, cum: DoubleArray, at: Double): Pair<Double, Double>? {
        for (i in 1 until pts.size) if (cum[i] >= at - 1) {
            val len = cum[i] - cum[i - 1]
            if (len <= 0) return null
            return (pts[i].first - pts[i - 1].first) / len to (pts[i].second - pts[i - 1].second) / len
        }
        return null
    }

    private fun segmentDistance(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val ex = b.first - a.first; val ey = b.second - a.second
        val len2 = ex * ex + ey * ey
        val t = if (len2 == 0.0) 0.0 else (((p.first - a.first) * ex + (p.second - a.second) * ey) / len2).coerceIn(0.0, 1.0)
        return hypot(p.first - (a.first + ex * t), p.second - (a.second + ey * t))
    }

    private fun pointAt(pts: List<Pair<Double, Double>>, cum: DoubleArray, at: Double): Pair<Double, Double>? {
        for (i in 1 until pts.size) if (cum[i] >= at) {
            val f = ((at - cum[i - 1]) / (cum[i] - cum[i - 1]).coerceAtLeast(1e-9))
            return pts[i - 1].first + (pts[i].first - pts[i - 1].first) * f to pts[i - 1].second + (pts[i].second - pts[i - 1].second) * f
        }
        return null
    }
}
