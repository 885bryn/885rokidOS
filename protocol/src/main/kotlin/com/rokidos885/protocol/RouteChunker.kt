package com.rokidos885.protocol

/** Splits a route polyline into [RouteChunk]s that fit under [MAX_FRAME_BYTES]. */
object RouteChunker {
    // Halved because '\' (valid in encoded polylines) becomes 2 bytes in JSON; 512 covers the envelope.
    private const val CHUNK_CHARS = (MAX_FRAME_BYTES - 512) / 2

    fun split(
        routeId: String,
        encodedPolyline: String,
        totalMeters: Int,
        nextSeq: () -> Long,
    ): List<RouteChunk> {
        val parts = encodedPolyline.chunked(CHUNK_CHARS).ifEmpty { listOf("") }
        return parts.mapIndexed { i, part ->
            RouteChunk(nextSeq(), routeId, i, parts.size, part, totalMeters)
        }
    }
}

/** Packs a street snapshot, in priority order, into [RoadContext] frames that each fit the frame budget. */
object RoadContextChunker {
    private const val FRAME_BUDGET = 6_000

    fun split(routeId: String, contextId: String, roads: List<ContextRoad>, nextSeq: () -> Long): List<RoadContext> {
        val parts = ArrayList<List<ContextRoad>>()
        var current = ArrayList<ContextRoad>()
        for (road in roads) {
            current += road
            if (encodedSize(routeId, contextId, current) > FRAME_BUDGET) {
                current.removeAt(current.lastIndex)
                if (current.isEmpty()) continue // a single road too large for any frame is skipped
                parts += current
                current = arrayListOf(road)
                if (encodedSize(routeId, contextId, current) > FRAME_BUDGET) current = ArrayList()
            }
        }
        if (current.isNotEmpty() || parts.isEmpty()) parts += current
        return parts.mapIndexed { i, part -> RoadContext(nextSeq(), routeId, part, contextId, i, parts.size) }
    }

    private fun encodedSize(routeId: String, contextId: String, roads: List<ContextRoad>) =
        ProtocolCodec.encode(RoadContext(Long.MAX_VALUE, routeId, roads, contextId, 99, 99)).second.toByteArray().size
}

/** Glasses-side reassembly. Feed every [RouteChunk]; returns the full polyline once complete. */
class RouteAssembler {
    private var routeId: String? = null
    private var parts: Array<String?> = emptyArray()

    fun accept(chunk: RouteChunk): String? {
        if (chunk.routeId != routeId || parts.size != chunk.chunkCount) {
            routeId = chunk.routeId
            parts = arrayOfNulls(chunk.chunkCount)
        }
        if (chunk.chunkIndex !in parts.indices) return null
        parts[chunk.chunkIndex] = chunk.encodedPolylinePart
        return if (parts.all { it != null }) parts.joinToString("") else null
    }

    /** Missing indices, for a [ResyncRequest] if a chunk never arrives. */
    fun missing(): List<Int> = parts.indices.filter { parts[it] == null }
}
