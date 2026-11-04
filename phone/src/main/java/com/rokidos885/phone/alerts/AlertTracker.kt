package com.rokidos885.phone.alerts

import com.rokidos885.protocol.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns incidents into on-route alerts, once per fix (never per HUD frame).
 *
 * Reports are approximate, so a report counts when it lies within [toleranceMeters] of the route line
 * (user setting) and, when it carries a direction, faces our direction of travel within 60°; that keeps
 * the wider window from catching the opposite carriageway. An alert stays at "0 m" until we are
 * [PASSED_MARGIN_M] past its pin, because pins are often a little off along the road too.
 */
class AlertTracker(
    private val route: List<LatLng>,
    private val toleranceMeters: () -> Int = { 150 },
) {
    private var active = emptyMap<String, Alert>()

    fun update(incidents: List<Incident>, position: PositionUpdate, nowMs: Long, nextSeq: () -> Long): List<NavMessage> {
        val tolerance = toleranceMeters().toDouble()
        val fix = Geo.projectOntoPolyline(route, LatLng(position.lat, position.lng))
        val ahead = if (fix == null || fix.distanceMeters > ON_ROUTE_M) emptyList() else incidents.mapNotNull { incident ->
            if (incident.expiresAtMs?.let { it <= nowMs } == true || incident.reportedAtMs > nowMs + 60_000) return@mapNotNull null
            val point = Geo.projectOntoPolyline(route, incident.location) ?: return@mapNotNull null
            if (point.distanceMeters > tolerance) return@mapNotNull null
            if (incident.headings.isNotEmpty() && incident.headings.none {
                abs((it - point.bearingDeg + 540) % 360 - 180) <= 60
            }) return@mapNotNull null
            val meters = point.metersAlong - fix.metersAlong
            if (meters < -PASSED_MARGIN_M || meters > LOOKAHEAD_M) return@mapNotNull null
            Alert(0, incident.id, incident.kind, meters.coerceAtLeast(0.0).roundToInt(), incident.location.lat,
                incident.location.lng, incident.description, incident.source, incident.reportedAtMs)
        }
        val next = ahead.associateBy { it.id }
        val messages = ArrayList<NavMessage>()
        (active.keys - next.keys).forEach { messages += AlertCleared(nextSeq(), it) }
        next.forEach { (id, alert) ->
            // Unchanged alerts are re-sent every REFRESH_MS so the glasses can tell "still there" (e.g.
            // stopped at a light) from a dropped link, after which they drop stale alerts themselves.
            if (active[id] != alert || nowMs - (sentAt[id] ?: 0) >= REFRESH_MS) {
                messages += alert.copy(seq = nextSeq()); sentAt[id] = nowMs
            }
        }
        sentAt.keys.retainAll(next.keys)
        active = next
        return messages
    }
    private val sentAt = HashMap<String, Long>()

    companion object {
        const val LOOKAHEAD_M = 2_000.0
        const val PASSED_MARGIN_M = 100.0
        const val REFRESH_MS = 30_000L
        /** Our own fix must be on the route (road-snapped), otherwise distances along it are meaningless. */
        const val ON_ROUTE_M = 60.0
    }
}
