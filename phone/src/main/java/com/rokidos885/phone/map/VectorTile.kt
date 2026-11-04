package com.rokidos885.phone.map

import com.rokidos885.protocol.LatLng
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** A road line decoded from a tile: geometry, display name, and whether it is a major road. */
data class TileRoad(val points: List<LatLng>, val name: String, val major: Boolean)

/** Slippy-map tile address (Web Mercator). */
data class TileId(val z: Int, val x: Int, val y: Int) {
    companion object {
        fun containing(p: LatLng, z: Int): TileId {
            val n = 1 shl z
            val x = floor((p.lng + 180) / 360 * n).toInt().coerceIn(0, n - 1)
            val latR = Math.toRadians(p.lat.coerceIn(-85.0, 85.0))
            val y = floor((1 - ln(tan(latR) + 1 / kotlin.math.cos(latR)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
            return TileId(z, x, y)
        }
    }
}

/**
 * Minimal Mapbox Vector Tile (protobuf) reader, enough to pull road lines out of an OpenMapTiles-schema
 * tile such as OpenFreeMap's. Reads the `transportation_name` layer: named road lines with a `class`.
 * Spec: https://github.com/mapbox/vector-tile-spec/tree/master/2.1
 */
object VectorTile {
    private val MAJOR = setOf("motorway", "trunk", "primary", "secondary", "tertiary")
    private val DRIVABLE = MAJOR + setOf("minor", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link")

    fun roads(data: ByteArray, tile: TileId, layerName: String = "transportation_name"): List<TileRoad> {
        val out = ArrayList<TileRoad>()
        val tileReader = Reader(data, 0, data.size)
        while (tileReader.more()) {
            val (field, wire) = tileReader.tag()
            if (field == 3 && wire == 2) {
                val (start, end) = tileReader.span()
                layer(data, start, end, tile, layerName, out)
            } else tileReader.skip(wire)
        }
        return out
    }

    private fun layer(data: ByteArray, start: Int, end: Int, tile: TileId, wanted: String, out: MutableList<TileRoad>) {
        val r = Reader(data, start, end)
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<String>()
        val features = ArrayList<Pair<Int, Int>>()
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> name = r.string()
                field == 2 && wire == 2 -> features += r.span()
                field == 3 && wire == 2 -> keys += r.string()
                field == 4 && wire == 2 -> { val (s, e) = r.span(); values += value(data, s, e) }
                field == 5 && wire == 0 -> extent = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        if (name != wanted) return
        val iName = keys.indexOf("name")
        val iClass = keys.indexOf("class")
        for ((s, e) in features) {
            val f = Reader(data, s, e)
            var type = 0
            var tags: IntArray? = null
            var geometry: IntArray? = null
            while (f.more()) {
                val (field, wire) = f.tag()
                when {
                    field == 2 && wire == 2 -> tags = f.packed()
                    field == 3 && wire == 0 -> type = f.varint().toInt()
                    field == 4 && wire == 2 -> geometry = f.packed()
                    else -> f.skip(wire)
                }
            }
            if (type != 2 || tags == null || geometry == null) continue
            var roadName = ""; var roadClass = ""
            var t = 0
            while (t + 1 < tags.size) {
                val k = tags[t]; val v = tags[t + 1]
                if (k == iName && v in values.indices) roadName = values[v]
                if (k == iClass && v in values.indices) roadClass = values[v]
                t += 2
            }
            if (roadClass !in DRIVABLE) continue
            for (line in lines(geometry, tile, extent)) if (line.size >= 2) out += TileRoad(line, roadName, roadClass in MAJOR)
        }
    }

    /** Decodes MoveTo/LineTo commands into lines of LatLng. */
    private fun lines(g: IntArray, tile: TileId, extent: Int): List<List<LatLng>> {
        val result = ArrayList<List<LatLng>>()
        var current = ArrayList<LatLng>()
        var x = 0; var y = 0; var i = 0
        while (i < g.size) {
            val cmd = g[i] and 7; val count = g[i] ushr 3; i++
            when (cmd) {
                1, 2 -> repeat(count) {
                    if (i + 1 >= g.size) return result.also { if (current.size >= 2) it += current }
                    x += zigzag(g[i]); y += zigzag(g[i + 1]); i += 2
                    if (cmd == 1) { if (current.size >= 2) result += current; current = ArrayList() }
                    current += toLatLng(tile, x.toDouble() / extent, y.toDouble() / extent)
                }
                7 -> Unit
                else -> return result
            }
        }
        if (current.size >= 2) result += current
        return result
    }

    private fun toLatLng(tile: TileId, fx: Double, fy: Double): LatLng {
        val n = (1 shl tile.z).toDouble()
        val lng = (tile.x + fx) / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * (tile.y + fy) / n))))
        return LatLng(lat, lng)
    }

    private fun zigzag(v: Int) = (v ushr 1) xor -(v and 1)

    /** Value message: only the string form matters for names and classes. */
    private fun value(data: ByteArray, start: Int, end: Int): String {
        val r = Reader(data, start, end)
        var s = ""
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == 1 && wire == 2) s = r.string() else r.skip(wire)
        }
        return s
    }

    private class Reader(val data: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0; var result = 0L
            while (true) {
                require(pos < end) { "truncated varint" }
                val b = data[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }
        fun tag(): Pair<Int, Int> { val t = varint().toInt(); return (t ushr 3) to (t and 7) }
        fun span(): Pair<Int, Int> {
            val len = varint().toInt(); val s = pos
            require(len >= 0 && s + len <= end) { "bad length" }
            pos += len; return s to pos
        }
        fun string(): String { val (s, e) = span(); return String(data, s, e - s, Charsets.UTF_8) }
        fun packed(): IntArray {
            val (s, e) = span(); val r = Reader(data, s, e); val out = ArrayList<Int>()
            while (r.more()) out += r.varint().toInt()
            return out.toIntArray()
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> span()
                5 -> pos += 4
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
            require(pos <= end) { "truncated field" }
        }
    }

}
