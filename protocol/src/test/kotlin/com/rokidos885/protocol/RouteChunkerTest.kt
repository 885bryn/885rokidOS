package com.rokidos885.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteChunkerTest {
    @Test fun splitAndReassemble() {
        val points = (0 until 5_000).map { LatLng(37.0 + it * 1e-4, -122.0 + it * 1e-4) }
        val encoded = PolylineCodec.encode(points)
        var seq = 0L
        val chunks = RouteChunker.split("r1", encoded, 50_000) { ++seq }
        assertTrue(chunks.size > 1)

        val assembler = RouteAssembler()
        chunks.dropLast(1).forEach { assertNull(assembler.accept(it)) }
        val full = assembler.accept(chunks.last())
        assertEquals(encoded, full)
        assertEquals(points.size, PolylineCodec.decode(full!!).size)
    }

    @Test fun framesStayUnderLimit() {
        val encoded = "a".repeat(100_000)
        RouteChunker.split("r1", encoded, 1) { 0 }.forEach {
            val (_, body) = ProtocolCodec.encode(it)
            assertTrue(body.toByteArray().size < MAX_FRAME_BYTES)
        }
    }
}
