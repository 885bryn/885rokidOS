package com.rokidos885.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadContextChunkerTest {
    private fun road(i: Int) = ContextRoad(PolylineCodec.encode((0 until 20).map { LatLng(37.0 + i * 1e-3 + it * 1e-4, -122.0 + it * 1e-4) }), "Street $i", i % 5 == 0)

    @Test fun splitsIntoFramesUnderBudgetKeepingOrder() {
        val roads = (0 until 300).map(::road)
        var seq = 0L
        val parts = RoadContextChunker.split("r1", "c1", roads, { ++seq })
        assertTrue(parts.size > 1)
        parts.forEachIndexed { i, part ->
            assertEquals(i, part.part); assertEquals(parts.size, part.partCount); assertEquals("c1", part.contextId)
            assertTrue(ProtocolCodec.encode(part).second.toByteArray().size <= 6_000)
        }
        assertEquals(roads, parts.flatMap { it.roads })
    }

    @Test fun emptySnapshotIsOneEmptyFrame() {
        val parts = RoadContextChunker.split("r1", "c1", emptyList(), { 1 })
        assertEquals(1, parts.size); assertEquals(0, parts[0].roads.size)
    }
}
