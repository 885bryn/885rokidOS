package com.rokidos885.phone.alerts

import java.io.ByteArrayOutputStream
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class CameraRepositoryTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun cachesDailyAndRetainsLastGoodFileAfterInvalidUpdatesAndRestartOffline() {
        val bytes = ByteArrayOutputStream()
        XZOutputStream(bytes, LZMA2Options()).use { it.write("""
            {"_meta":{"name":"Fixture","date":"2026-07-19"}}
            {"lat":1,"lon":2,"flg":1}
        """.trimIndent().toByteArray()) }
        var version = "2026-07-19"
        var bad = false
        var offline = false
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            if (offline) throw IOException("offline fixture")
            val data = if (chain.request().url.encodedPath == "/cameras") {
                """{"_link":{"date":"$version","dataUrl":"https://wzsabre.rocks/cameras.download"}}""".toByteArray()
            } else if (bad) byteArrayOf(0, 1, 2) else bytes.toByteArray()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(data.toResponseBody()).build()
        }.build()
        var now = 1L
        val repository = WzSabreCameraRepository(directory.root, http) { now }
        val cameras = repository.load()
        assertEquals(1, cameras.size)
        assertEquals(2, requests)
        assertEquals(cameras, repository.load())
        assertEquals(2, requests)
        now += 86_400_000
        assertEquals(cameras, repository.load())
        assertEquals(3, requests) // Same version: metadata only, no download.
        now += 86_400_000
        version = "2026-07-20"
        bad = true
        assertEquals(cameras, repository.load())
        offline = true
        assertEquals(cameras, WzSabreCameraRepository(directory.root, http) { now }.load())
        assertFalse(java.io.File(directory.root, "wzsabre.download").exists())
    }
}
