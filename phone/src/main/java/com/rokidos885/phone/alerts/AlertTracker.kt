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
 *
 * Battery: the glasses count an alert's distance down from their own fixes, so [update] returns an alert only when
 * it is new, changed, off from that count by [DRIFT_M] or more (the pin moved), or [REFRESH_MS] old. [current] has
 * every alert with its distance from this fix, for the spoken warnings and for resync.
 */
class AlertTracker(
    private val route: List<LatLng>,
    private val toleranceMeters: () -> Int = { 150 },
) {
    private var active = emptyMap<String, Alert>()
    /** Alerts ahead at the last fix, with their distance then. */
    val current: Collection<Alert> get() = active.values

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
        val along = fix?.metersAlong ?: 0.0
        next.forEach { (id, alert) ->
            // Unchanged alerts are re-sent every REFRESH_MS so the glasses can tell "still there" (e.g.
            // stopped at a light) from a dropped link, after which they drop stale alerts themselves.
            val last = sent[id]
            val counted = last?.let { (it.alert.distanceAheadMeters - (along - it.along)).coerceAtLeast(0.0) }
            if (last == null || last.alert.copy(distanceAheadMeters = 0) != alert.copy(distanceAheadMeters = 0) ||
                abs(counted!! - alert.distanceAheadMeters) >= DRIFT_M || nowMs - last.atMs >= REFRESH_MS) {
                messages += alert.copy(seq = nextSeq()); sent[id] = Sent(alert, along, nowMs)
            }
        }
        sent.keys.retainAll(next.keys)
        active = next
        return messages
    }
    private class Sent(val alert: Alert, val along: Double, val atMs: Long)
    private val sent = HashMap<String, Sent>()

    companion object {
        const val LOOKAHEAD_M = 2_000.0
        const val PASSED_MARGIN_M = 100.0
        const val REFRESH_MS = 30_000L
        /** One displayed step (10 m, 50 ft): a smaller error does not show. */
        const val DRIFT_M = 15.0
        /** Our own fix must be on the route (road-snapped), otherwise distances along it are meaningless. */
        const val ON_ROUTE_M = 60.0
    }
}
