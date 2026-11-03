package com.rokidos885.phone.map

import com.rokidos885.protocol.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Builds tiny tiles by hand (protobuf wire format) to check the decoder without a binary fixture. */
class VectorTileTest {
    private class Pb {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (x and 0x7fL.inv() != 0L) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
        fun packed(field: Int, values: List<Int>) { val p = Pb(); values.forEach { p.varint(it.toLong() and 0xffffffffL) }; bytes(field, p.out.toByteArray()) }
        fun uint(field: Int, v: Int) { tag(field, 0); varint(v.toLong()) }
    }
    private fun zz(v: Int) = (v shl 1) xor (v shr 31)
    private fun cmd(id: Int, count: Int) = (count shl 3) or id

    private fun tile(layerName: String, vararg features: Triple<String, String, List<Pair<Int, Int>>>): ByteArray {
        val layer = Pb()
        layer.uint(15, 2); layer.string(1, layerName)
        val values = ArrayList<String>()
        fun valueIndex(s: String) = values.indexOf(s).takeIf { it >= 0 } ?: values.size.also { values += s }
        for ((name, cls, pts) in features) {
            val f = Pb()
            f.packed(2, listOf(0, valueIndex(name), 1, valueIndex(cls)))
            f.uint(3, 2)
            var x = 0; var y = 0
            val geom = ArrayList<Int>()
            geom += cmd(1, 1); geom += zz(pts[0].first - x); geom += zz(pts[0].second - y); x = pts[0].first; y = pts[0].second
            geom += cmd(2, pts.size - 1)
            for (p in pts.drop(1)) { geom += zz(p.first - x); geom += zz(p.second - y); x = p.first; y = p.second }
            f.packed(4, geom)
            layer.bytes(2, f.out.toByteArray())
        }
        layer.string(3, "name"); layer.string(3, "class")
        for (v in values) { val pv = Pb(); pv.string(1, v); layer.bytes(4, pv.out.toByteArray()) }
        layer.uint(5, 4096)
        val t = Pb(); t.bytes(3, layer.out.toByteArray()); return t.out.toByteArray()
    }

    @Test fun decodesNamedRoadsWithClassAndPosition() {
        val tile = TileId(14, 2620, 6331)
        val data = tile("transportation_name",
            Triple("Chestnut Street", "minor", listOf(0 to 2048, 4096 to 2048)),
            Triple("Van Ness Avenue", "primary", listOf(2048 to 0, 2048 to 4096)),
            Triple("Trail", "path", listOf(0 to 0, 10 to 10)))
        val roads = VectorTile.roads(data, tile)
        assertEquals(listOf("Chestnut Street", "Van Ness Avenue"), roads.map { it.name })
        assertEquals(listOf(false, true), roads.map { it.major })
        // Tile centre of z14 2620/6331 is near Russian Hill, San Francisco.
        val mid = roads[1].points[0]
        assertTrue(abs(mid.lng - (-122.42) ) < 0.02 && abs(mid.lat - 37.80) < 0.02)
    }

    @Test fun ignoresOtherLayers() {
        assertEquals(0, VectorTile.roads(tile("building", Triple("X", "minor", listOf(0 to 0, 5 to 5))), TileId(14, 0, 0)).size)
    }

    @Test fun tileContainingRoundTrips() {
        assertEquals(TileId(14, 2620, 6331), TileId.containing(LatLng(37.803, -122.431), 14))
    }
}
