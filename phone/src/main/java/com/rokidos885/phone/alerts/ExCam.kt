package com.rokidos885.phone.alerts

import com.rokidos885.protocol.AlertKind
import com.rokidos885.protocol.Geo
import com.rokidos885.protocol.LatLng
import com.rokidos885.protocol.PolylineCodec
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.XZInputStream

/** ExCam: https://highwayradar.com/excam. Unknown JSON fields are deliberately ignored. */
object ExCam {
    private const val PLATE_READING = 8192
    data class Version(val date: String, val revision: Int = 0)
    data class Link(val version: Version, val dataUrl: String)
    data class Database(val version: Version, val cameras: List<Incident>)

    fun link(json: String): Link {
        val item = Json.parseToJsonElement(json).jsonObject.getValue("_link").jsonObject
        return Link(version(item), item.getValue("dataUrl").jsonPrimitive.content)
    }

    fun read(input: InputStream): Database = input.use { source -> readCompressed(source) }

    private fun readCompressed(input: InputStream): Database = XZInputStream(input, 65536).bufferedReader().use { reader ->
        val meta = Json.parseToJsonElement(requireNotNull(reader.readLine())).jsonObject
            .getValue("_meta").jsonObject
        require(meta.getValue("name").jsonPrimitive.content.isNotBlank())
        val version = version(meta)
        val cameras = ArrayList<Incident>()
        reader.lineSequence().filter { it.isNotBlank() }.forEachIndexed { index, line ->
            require(index < 1_000_000) { "ExCam database too large" }
            require(line.length <= 16_384) { "ExCam record too large" }
            val item = Json.parseToJsonElement(line).jsonObject
            val lat = item.getValue("lat").jsonPrimitive.double
            val lon = item.getValue("lon").jsonPrimitive.double
            require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0)
            val flags = item.getValue("flg").jsonPrimitive.int
            // Every enforcement camera (speed, red light, average speed, lane, ...). Dummy cameras (64)
            // and plate-reading-only cameras (8192) are excluded: they enforce nothing on the road.
            if (flags and 64 == 0 && flags and PLATE_READING.inv() != 0) {
                val directions = (item["dir"] as? JsonArray)?.map { it.jsonPrimitive.double }.orEmpty()
                require(directions.all { it.isFinite() && it in 0.0..359.0 })
                cameras += Incident(
                    id = "wzsabre:camera:$lat:$lon:$flags:${directions.sorted().joinToString(",")}",
                    kind = AlertKind.SPEED_CAMERA,
                    location = LatLng(lat, lon),
                    description = if (flags and 1 == 0 && flags and 2 != 0) "Red-light camera" else "Speed camera",
                    reportedAtMs = 0, // A dataset date is not an incident report time.
                    source = "WzSabre",
                    headings = directions,
                )
            }
            require(cameras.size <= 1_000_000) { "ExCam database too large" }
        }
        Database(version, cameras.distinctBy { it.id })
    }

    private fun version(item: JsonObject): Version {
        val date = item.getValue("date").jsonPrimitive.content
        LocalDate.parse(date)
        val revision = item["revision"]?.jsonPrimitive?.int ?: 0
        require(revision >= 0)
        return Version(date, revision)
    }
}

/** Last valid database survives network errors and invalid updates. No location leaves the phone. */
class WzSabreCameraRepository(
    private val directory: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var cached: ExCam.Database? = null
    private var checkedAtMs: Long? = null

    @Synchronized
    fun load(): List<Incident> {
        directory.mkdirs()
        val file = File(directory, "wzsabre.excam")
        if (cached == null && file.exists()) cached = runCatching { ExCam.read(file.inputStream()) }.getOrNull()
        val now = nowMs()
        if (checkedAtMs?.let { now - it in 0 until 86_400_000 } == true) return cached?.cameras.orEmpty()
        checkedAtMs = now
        try {
            val link = ExCam.link(getText("https://wzsabre.rocks/cameras"))
            // This provider uses only the source chosen by the user, including redirects.
            require(link.dataUrl == "https://wzsabre.rocks/cameras.download")
            if (cached?.version != link.version) {
                val temporary = File(directory, "wzsabre.download")
                try {
                    client.newCall(Request.Builder().url(link.dataUrl).build()).execute().use { response ->
                        require(response.isSuccessful)
                        require(response.request.url.host == "wzsabre.rocks" && response.request.url.isHttps)
                        val body = requireNotNull(response.body)
                        body.byteStream().use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var count = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= 32L * 1024 * 1024) { "Camera download too large" }
                                output.write(buffer, 0, read)
                            }
                        } }
                    }
                    val database = ExCam.read(temporary.inputStream())
                    require(database.version == link.version)
                    java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    cached = database
                } finally {
                    temporary.delete()
                }
            }
        } catch (_: java.io.IOException) {
            // Keep the last known-good database, including when offline.
        } catch (_: IllegalArgumentException) {
            // Reject malformed metadata, camera data, and unexpected download destinations.
        } catch (_: IllegalStateException) {
            // JSON shape mismatch; keep the previous database.
        } catch (_: NoSuchElementException) {
            // Required ExCam field missing.
        }
        return cached?.cameras.orEmpty()
    }

    private fun getText(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            require(response.isSuccessful)
            require(response.request.url.host == "wzsabre.rocks" && response.request.url.isHttps)
            val body = requireNotNull(response.body)
            body.charStream().use { reader ->
                val buffer = CharArray(16_385)
                var size = 0
                while (size < buffer.size) {
                    val count = reader.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                require(size <= 16_384)
                String(buffer, 0, size)
            }
        }
}

class WzSabreCameraProvider(private val load: () -> List<Incident>) : IncidentProvider {
    override val name = "WzSabre cameras"
    override fun incidentsAlong(routePolyline: String): Flow<List<Incident>> = flow {
        val route = PolylineCodec.decode(routePolyline)
        if (route.isEmpty()) { emit(emptyList()); return@flow }
        val south = route.minOf { it.lat } - 0.001
        val north = route.maxOf { it.lat } + 0.001
        val west = route.minOf { it.lng }
        val east = route.maxOf { it.lng }
        val longitudeMargin = 0.001 / kotlin.math.cos(Math.toRadians(maxOf(kotlin.math.abs(south), kotlin.math.abs(north)).coerceAtMost(89.9)))
        emit(load().filter { camera ->
            camera.location.lat in south..north &&
                (east - west > 180 || camera.location.lng in (west - longitudeMargin)..(east + longitudeMargin)) &&
                Geo.projectOntoPolyline(route, camera.location)?.distanceMeters?.let { it <= 60 } == true
        })
    }.flowOn(Dispatchers.IO)
}
