package com.rokidos885.phone.alerts

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class ExCamTest {
    private fun compressed(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        XZOutputStream(bytes, LZMA2Options()).use { it.write(text.toByteArray()) }
        return bytes.toByteArray()
    }
    @Test fun readsXzAndIgnoresUnknownFieldsWithoutMislabelingOtherCameras() {
        val data = """
            {"_meta":{"name":"Fixture","date":"2026-07-19","future":true}}
            {"lat":47.967098,"lon":16.885742,"flg":1,"dir":[90],"spd":130,"future":42}
            {"lat":24.0,"lon":56.0,"flg":13,"dir":null}
            {"lat":24.0,"lon":57.0,"flg":2}
            {"lat":24.0,"lon":58.0,"flg":65}
            {"lat":24.0,"lon":59.0,"flg":8192}
        """.trimIndent()
        val db = ExCam.read(compressed(data).inputStream())
        assertEquals(ExCam.Version("2026-07-19"), db.version)
        assertEquals("speed, average speed and red light; not dummy (65) or plate-only (8192)", 3, db.cameras.size)
        assertEquals("Red-light camera", db.cameras.last().description)
        assertEquals(listOf(90.0), db.cameras.first().headings)
        assertEquals(db, ExCam.read(compressed(data).inputStream()))
        assertEquals("WzSabre", db.cameras.first().source)
    }
    @Test fun readsOnlineLink() {
        assertEquals("https://wzsabre.rocks/cameras.download", ExCam.link(
            """{"_link":{"dataUrl":"https://wzsabre.rocks/cameras.download","date":"2026-07-19"}}"""
        ).dataUrl)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidCoordinates() {
        ExCam.read(compressed("""{"_meta":{"name":"Fixture","date":"2026-07-19"}}
            |{"lat":91,"lon":0,"flg":1}
        """.trimMargin()).inputStream())
    }
}
