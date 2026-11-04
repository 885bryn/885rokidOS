package com.rokidos885.protocol

import org.junit.Assert.*
import org.junit.Test

class GeoTest {
    private val route = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.01), LatLng(0.01, 0.01))
    @Test fun projectsOntoNearestSegmentAndAccumulatesDistance() {
        val p = Geo.projectOntoPolyline(route, LatLng(0.005, 0.0101))!!
        assertEquals(1667.9, p.metersAlong, 1.0)
        assertEquals(11.1, p.distanceMeters, 0.2)
        assertEquals(0.0, p.bearingDeg, 0.1)
    }
    @Test fun clampsToRouteEndpoints() {
        assertEquals(0.0, Geo.metersAlongPolyline(route, LatLng(0.0, -0.01)), 0.01)
        assertEquals(2223.9, Geo.metersAlongPolyline(route, LatLng(0.02, 0.01)), 1.0)
    }
    @Test fun handlesEmptySingleAndRepeatedPoints() {
        assertNull(Geo.projectOntoPolyline(emptyList(), LatLng(0.0, 0.0)))
        assertEquals(0.0, Geo.metersAlongPolyline(listOf(route[0]), route[1]), 0.0)
        assertEquals(556.0, Geo.metersAlongPolyline(listOf(route[0], route[0], route[1]), LatLng(0.0, 0.005)), 1.0)
    }
    @Test fun crossesDateLineAndHandlesAntipodes() {
        val points = listOf(LatLng(0.0, 179.99), LatLng(0.0, -179.99))
        assertEquals(1112.0, Geo.metersAlongPolyline(points, LatLng(0.0, 180.0)), 1.0)
        assertTrue(Geo.distanceMeters(LatLng(0.0, 0.0), LatLng(0.0, 180.0)).isFinite())
    }
}
