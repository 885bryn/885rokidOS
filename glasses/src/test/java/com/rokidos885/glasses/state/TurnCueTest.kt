package com.rokidos885.glasses.state

import com.rokidos885.protocol.LatLng
import com.rokidos885.protocol.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos

class TurnCueTest {
    private val baseLat = 37.80
    private val baseLng = -122.42
    private fun p(x: Double, y: Double) =
        LatLng(baseLat + y / 111_320.0, baseLng + x / (111_320.0 * cos(Math.toRadians(baseLat))))
    private fun road(name: String, vararg xy: Pair<Double, Double>) = HudRoad(xy.map { p(it.first, it.second) }, name, false)

    // Driving north on Polk (x = 0) from y = 0, turning right onto Bay St at y = 300.
    private val route = listOf(p(0.0, 0.0), p(0.0, 300.0), p(200.0, 300.0))
    private val polk = road("Polk St", 0.0 to -50.0, 0.0 to 400.0)
    private val chestnut = road("Chestnut St", -150.0 to 100.0, 150.0 to 100.0)   // opens both sides
    private val lombardWest = road("Lombard St", -150.0 to 200.0, 0.0 to 200.0)   // T-junction, left only
    private val bay = road("Bay St", -150.0 to 300.0, 250.0 to 300.0)

    @Test fun countsOnlyStreetsOpeningOnTheTurnSide() {
        val cue = TurnCue.compute(route, 0, 300, Maneuver.TURN_RIGHT, listOf(polk, chestnut, lombardWest, bay))
        assertEquals("2nd right · after Chestnut St", cue)
    }

    @Test fun leftTurnCountsBothOpenings() {
        val cue = TurnCue.compute(route, 0, 300, Maneuver.TURN_LEFT, listOf(polk, chestnut, lombardWest, bay))
        assertEquals("3rd left · after Lombard St", cue)
    }

    @Test fun nextTurnWhenNothingInBetween() {
        assertEquals("Next right", TurnCue.compute(route, 150, 150, Maneuver.TURN_RIGHT, listOf(polk, chestnut, lombardWest, bay)))
    }

    @Test fun silentWhenTurnStreetMissingFromData() {
        assertEquals("", TurnCue.compute(route, 0, 300, Maneuver.TURN_RIGHT, listOf(polk, chestnut)))
    }

    @Test fun silentForNonTurnsAndFarTurns() {
        val roads = listOf(polk, chestnut, bay)
        assertEquals("", TurnCue.compute(route, 0, 300, Maneuver.STRAIGHT, roads))
        assertEquals("", TurnCue.compute(route, 0, 900, Maneuver.TURN_RIGHT, roads))
    }
}
