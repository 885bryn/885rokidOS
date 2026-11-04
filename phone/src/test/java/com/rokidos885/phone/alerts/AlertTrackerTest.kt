package com.rokidos885.phone.alerts

import com.rokidos885.protocol.*
import org.junit.Assert.*
import org.junit.Test

class AlertTrackerTest {
    // Route runs east along the equator; 0.001° ≈ 111 m.
    private val route = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.05))
    private val incident = Incident("p1", AlertKind.POLICE, LatLng(0.0, 0.01), "Police reported", 1000, "WzSabre")
    private var seq = 0L
    private fun position(lon: Double) = PositionUpdate(0, 0.0, lon, 90f, 10f, timestampMs = 2000)

    @Test fun countsDownHoldsAtZeroJustPastThePinThenClears() {
        val tracker = AlertTracker(route)
        val first = tracker.update(listOf(incident), position(0.0), 2000) { ++seq }.single() as Alert
        assertEquals(1112, first.distanceAheadMeters)
        assertEquals("WzSabre", first.source)
        val next = tracker.update(listOf(incident), position(0.005), 2000) { ++seq }.single() as Alert
        assertEquals(556, next.distanceAheadMeters)
        assertTrue(tracker.update(listOf(incident), position(0.005), 2000) { ++seq }.isEmpty())
        // 55 m past the pin: pins are approximate, so it stays, at 0.
        assertEquals(0, (tracker.update(listOf(incident), position(0.0105), 2000) { ++seq }.single() as Alert).distanceAheadMeters)
        // 111 m past: cleared, and not replayed.
        assertEquals("p1", (tracker.update(listOf(incident), position(0.011), 2000) { ++seq }.single() as AlertCleared).id)
        assertTrue(tracker.update(listOf(incident), position(0.011), 2000) { ++seq }.isEmpty())
    }

    @Test fun rejectsFarSideRoadOppositeDirectionExpiredFutureAndDistantReports() {
        val tracker = AlertTracker(route)
        val rejected = listOf(
            incident.copy(location = LatLng(0.002, 0.01)),   // 222 m off the route
            incident.copy(headings = listOf(270.0)),
            incident.copy(expiresAtMs = 1500),
            incident.copy(reportedAtMs = 100_000),
            incident.copy(location = LatLng(0.0, 0.04)),     // 4.4 km ahead
        )
        assertTrue(tracker.update(rejected, position(0.0), 2000) { ++seq }.isEmpty())
        assertTrue(tracker.update(listOf(incident.copy(headings = listOf(90.0))), position(0.0), 2000) { ++seq }.single() is Alert)
        assertTrue(tracker.update(emptyList(), position(0.0), 2000) { ++seq }.single() is AlertCleared)
    }

    @Test fun toleranceSettingDecidesHowFarOffTheRouteAReportMayBe() {
        val offRoad = incident.copy(location = LatLng(0.001, 0.01))  // 111 m off: typical sloppy pin
        assertTrue(AlertTracker(route) { 60 }.update(listOf(offRoad), position(0.0), 2000) { ++seq }.isEmpty())
        assertTrue(AlertTracker(route) { 150 }.update(listOf(offRoad), position(0.0), 2000) { ++seq }.single() is Alert)
        var tolerance = 300
        val tracker = AlertTracker(route) { tolerance }
        val wider = incident.copy(location = LatLng(0.0025, 0.01))   // 278 m off
        assertTrue(tracker.update(listOf(wider), position(0.0), 2000) { ++seq }.single() is Alert)
        tolerance = 150   // changed mid-trip: takes effect on the next fix
        assertTrue(tracker.update(listOf(wider), position(0.0), 2000) { ++seq }.single() is AlertCleared)
    }

    @Test fun unchangedAlertIsRefreshedSoTheGlassesKnowItIsStillThere() {
        val tracker = AlertTracker(route)
        assertTrue(tracker.update(listOf(incident), position(0.0), 0) { ++seq }.single() is Alert)
        assertTrue(tracker.update(listOf(incident), position(0.0), 10_000) { ++seq }.isEmpty())
        assertTrue(tracker.update(listOf(incident), position(0.0), AlertTracker.REFRESH_MS) { ++seq }.single() is Alert)
    }
}
