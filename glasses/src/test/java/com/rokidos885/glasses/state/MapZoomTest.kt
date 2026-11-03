package com.rokidos885.glasses.state

import com.rokidos885.protocol.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapZoomTest {
    private fun kmh(v: Float) = v / 3.6f

    @Test fun cruisingViewGrowsWithSpeed() {
        assertEquals(200f, MapZoom.cruiseMeters(0f), 1f)
        assertEquals(350f, MapZoom.cruiseMeters(kmh(50f)), 1f)
        assertEquals(700f, MapZoom.cruiseMeters(kmh(80f)), 1f)
        assertEquals(1600f, MapZoom.cruiseMeters(kmh(150f)), 1f)
    }

    @Test fun turnPullsTheViewInProgressivelyNotAtTheLastMoment() {
        val v = kmh(50f)                                   // cruise view 350 m
        // Turn 400 m away: not yet framed, cruise view.
        assertEquals(350f, MapZoom.targetMeters(v, 400, Maneuver.TURN_RIGHT, 1), 1f)
        // Turn 200 m away: already tightening (200*1.4+60 = 340 m).
        assertEquals(340f, MapZoom.targetMeters(v, 200, Maneuver.TURN_RIGHT, 1), 1f)
        // Turn 100 m away: 200 m view.
        assertEquals(200f, MapZoom.targetMeters(v, 100, Maneuver.TURN_RIGHT, 1), 1f)
        // At the corner: never closer than the turn minimum.
        assertEquals(MapZoom.TURN_MIN_M, MapZoom.targetMeters(v, 10, Maneuver.TURN_RIGHT, 1), 1f)
    }

    @Test fun highwayExitIsFramedEarlyBecauseTheCruiseViewIsWide() {
        val v = kmh(110f)                                  // cruise view 1.2 km
        assertTrue(MapZoom.targetMeters(v, 800, Maneuver.OFF_RAMP_RIGHT, 1) < 1200f)
    }

    @Test fun straightAndNameChangesDoNotZoom() {
        assertEquals(350f, MapZoom.targetMeters(kmh(50f), 50, Maneuver.STRAIGHT, 1), 1f)
        assertEquals(350f, MapZoom.targetMeters(kmh(50f), 50, Maneuver.NAME_CHANGE, 1), 1f)
    }

    @Test fun userZoomScalesTheAutomaticView() {
        assertEquals(245f, MapZoom.targetMeters(kmh(50f), null, null, 0), 1f)
        assertEquals(525f, MapZoom.targetMeters(kmh(50f), null, null, 2), 1f)
    }

    @Test fun easingMovesSmoothlyAndStartsAtTheTarget() {
        assertEquals(800f, MapZoom.ease(0f, 800f, 0.016f), 0.01f)
        val next = MapZoom.ease(800f, 200f, 0.1f)
        assertTrue(next < 800f && next > 600f)
    }
}
