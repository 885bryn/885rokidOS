package com.rokidnav.glasses.ui

import com.rokidnav.protocol.Units
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun distancesUseTheSelectedUnitsAndGlanceablePrecision() {
        assertEquals("400 ft",Format.distance(122,Units.IMPERIAL))
        assertEquals("1.0 mi",Format.distance(1609,Units.IMPERIAL))
        assertEquals("150 m",Format.distance(150,Units.METRIC))
        assertEquals("1.2 km",Format.distance(1200,Units.METRIC))
    }
    @Test fun durationsRoundUpAndClocksUseTheSelectedFormat() {
        assertEquals("2 min",Format.duration(61))
        assertEquals("1 h 1 min",Format.duration(3660))
        val time=Instant.parse("2026-10-18T13:07:00Z").toEpochMilli()
        assertEquals("1:07",Format.eta(time,false,ZoneId.of("UTC")))
        assertEquals("13:07",Format.eta(time,true,ZoneId.of("UTC")))
    }
}
