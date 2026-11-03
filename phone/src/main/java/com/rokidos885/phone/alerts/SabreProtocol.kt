package com.rokidos885.phone.alerts

import com.rokidos885.protocol.AlertKind
import com.rokidos885.protocol.LatLng
import kotlinx.serialization.json.*

/** Read-only host implementation of https://sabre.app/tech (protocol major version 1). */
object SabreProtocol {
    const val PACKAGE = "app.sabre.wzsabre"
    data class Handshake(val requestAction: String, val shutdownAction: String?)
    data class Batch(val requestId: String, val count: Int, val index: Int, val incidents: List<Incident>)

    fun handshakeRequest(responseAction: String) = buildJsonObject {
        put("protocol_version", 1)
        put("response_action", responseAction)
    }.toString()

    fun handshakeResponse(data: String): Handshake {
        val item = Json.parseToJsonElement(data).jsonObject
        fun action(key: String): String? = item[key]?.jsonPrimitive?.contentOrNull?.also {
            // Action names are advertised by the plugin; the explicit component pins the recipient.
            require(it.isNotBlank() && it.length <= 512)
        }
        return Handshake(requireNotNull(action("request_action")), action("shutdown_action"))
    }

    fun fetchRequest(id: String, center: LatLng, responseAction: String) = buildJsonObject {
        put("request_id", id)
        put("lat", center.lat)
        put("lon", center.lng)
        put("radius_m", 5000.0)
        put("response_action", responseAction)
    }.toString()

    fun batch(data: String): Batch {
        val root = Json.parseToJsonElement(data).jsonObject
        require(root["error_message"] == null || root["error_message"] == JsonNull) { "SABRE fetch failed" }
        val response = root.getValue("response").jsonObject
        val count = response.getValue("n_batches").jsonPrimitive.int
        val index = response.getValue("batch_id").jsonPrimitive.int
        require(count in 1..64 && index in 0 until count)
        val alerts = response.getValue("alerts").jsonArray
        require(alerts.size <= 1000)
        return Batch(root.getValue("request_id").jsonPrimitive.content, count, index,
            alerts.mapNotNull { runCatching { incident(it.jsonObject) }.getOrNull() })
    }

    private fun incident(item: JsonObject): Incident? {
        // WzSabre 2.2 sends Waze's own names (subtype, else type): POLICE, POLICE_VISIBLE, POLICE_HIDING,
        // POLICE_WITH_MOBILE_CAMERA, CAMERA, ... rather than only the SABRE spec's FIXED_/MOBILE_CAMERA.
        // Matching just the spec names dropped every Waze camera and every plain POLICE report.
        val type = item.getValue("type").jsonPrimitive.content
        val kind = when {
            "CAMERA" in type -> AlertKind.SPEED_CAMERA
            type == "POLICE" || type.startsWith("POLICE_") -> AlertKind.POLICE
            else -> return null
        }
        val lat = item.getValue("lat").jsonPrimitive.double
        val lon = item.getValue("lon").jsonPrimitive.double
        require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0)
        val reported = item.getValue("report_ts").jsonPrimitive.long
        val confirmed = item["confirm_ts"]?.jsonPrimitive?.longOrNull ?: reported
        require(reported in 0..Long.MAX_VALUE / 1000 - 1200)
        require(confirmed in 0..Long.MAX_VALUE / 1000 - 1200)
        val heading = item["heading_deg"]?.jsonPrimitive?.doubleOrNull
            ?.takeIf { it.isFinite() && it in 0.0..<360.0 }
        val source = item.getValue("alert_source").jsonPrimitive.content
        val id = item.getValue("alert_id").jsonPrimitive.content
        require(source.length in 1..128 && id.length in 1..256)
        return Incident(
            id = "wzsabre:report:${source.length}:$source:$id",
            kind = kind,
            location = LatLng(lat, lon),
            description = if (kind == AlertKind.POLICE) "Police reported" else "Camera reported",
            reportedAtMs = reported * 1000,
            source = "WzSabre",
            headings = listOfNotNull(heading),
            expiresAtMs = (maxOf(reported, confirmed) + 1200) * 1000,
        )
    }
}

/** Keep partial responses private until all batches of the current request have arrived. */
class SabreBatches(private val requestId: String) {
    private val batches = mutableMapOf<Int, SabreProtocol.Batch>()
    private var expected: Int? = null
    fun accept(batch: SabreProtocol.Batch): List<Incident>? {
        if (batch.requestId != requestId) return null
        require(expected == null || expected == batch.count)
        expected = batch.count
        batches[batch.index] = batch
        return if (batches.size == expected) batches.toSortedMap().values
            .flatMap { it.incidents }.distinctBy { it.id } else null
    }
}
