package com.rokidos885.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PolylineCodecTest {
    // Example from Google's "Encoded Polyline Algorithm Format" documentation.
    private val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
    private val points = listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453))

    @Test fun decodesGoogleReference() {
        val decoded = PolylineCodec.decode(encoded)
        assertEquals(points.size, decoded.size)
        decoded.zip(points).forEach { (a, b) ->
            assertEquals(b.lat, a.lat, 1e-5)
            assertEquals(b.lng, a.lng, 1e-5)
        }
    }

    @Test fun encodesGoogleReference() {
        assertEquals(encoded, PolylineCodec.encode(points))
    }
}
