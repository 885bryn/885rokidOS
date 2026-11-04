package com.rokidos885.tools

import com.rokidos885.protocol.*
import java.io.File
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

/** Converts a Routes-shaped fixture into deterministic protocol JSONL without network access. */
fun main(args: Array<String>) {
    val input = File(args.getOrElse(0) { "tools/fixtures/demo_routes.json" })
    val output = File(args.getOrElse(1) { "glasses/src/main/assets/demo_trip.jsonl" })
    val route = Json.parseToJsonElement(input.readText()).jsonObject.getValue("routes").jsonArray.first().jsonObject
    val encoded = route.getValue("polyline").jsonObject.getValue("encodedPolyline").jsonPrimitive.content
    val points = PolylineCodec.decode(encoded)
    require(points.size > 1)
    val lengths = points.zipWithNext(Geo::distanceMeters)
    val total = lengths.sum()
    val duration = route.getValue("duration").jsonPrimitive.content.removeSuffix("s").toInt()
    val steps = route.getValue("legs").jsonArray.first().jsonObject.getValue("steps").jsonArray
    val ends = ArrayList<Double>()
    var accumulated = 0.0
    // Synthetic lane guidance on the first turn so the HUD's lane row shows in demo and previews.
    val demoLanes = listOf(
        Lane(listOf(Maneuver.STRAIGHT), false), Lane(listOf(Maneuver.STRAIGHT), false),
        Lane(listOf(Maneuver.STRAIGHT, Maneuver.TURN_RIGHT), true), Lane(listOf(Maneuver.TURN_RIGHT), true))
    val instructions = steps.mapIndexed { index, raw ->
        val step = raw.jsonObject
        accumulated += step.getValue("distanceMeters").jsonPrimitive.double
        ends += accumulated
        val instruction = step.getValue("navigationInstruction").jsonObject
        Step(Maneuver.valueOf(instruction.getValue("maneuver").jsonPrimitive.content),
            step.getValue("roadName").jsonPrimitive.content, lanes = if (index == 0) demoLanes else emptyList())
    }
    var seq = 0L
    val lines = ArrayList<String>()
    fun emit(message: NavMessage, delay: Long = 0) {
        lines += buildJsonObject {
            put("delayMs", delay)
            put("message", Json.parseToJsonElement(ProtocolCodec.encode(message).second))
        }.toString()
    }
    emit(SessionStart(++seq, Units.METRIC, false, "Marina overlook"))
    RouteChunker.split("demo-marina", encoded, total.roundToInt()) { ++seq }.forEach { emit(it) }
    // Synthetic streets for the synthetic recording. Live trips use actual OpenStreetMap geometry.
    emit(RoadContext(++seq,"demo-marina",listOf(
        ContextRoad(PolylineCodec.encode(listOf(LatLng(37.805,-122.437),LatLng(37.805,-122.417))),"Oak Avenue"),
        ContextRoad(PolylineCodec.encode(listOf(LatLng(37.808,-122.437),LatLng(37.808,-122.417))),"Bay Street",true),
        ContextRoad(PolylineCodec.encode(listOf(LatLng(37.799,-122.427),LatLng(37.817,-122.427))),"Scott Street"),
        ContextRoad(PolylineCodec.encode(listOf(LatLng(37.799,-122.423),LatLng(37.817,-122.423))),"Buchanan Street"))))
    emit(NavStateUpdate(++seq, NavState.ACTIVE))
    emit(TrafficSegments(++seq, "demo-marina", listOf(
        TrafficSegment((total * .30).toInt(), (total * .45).toInt(), TrafficLevel.SLOW),
        TrafficSegment((total * .65).toInt(), (total * .78).toInt(), TrafficLevel.JAM))))
    var alertRemaining = total * .48
    var alertSegment = 0
    while (alertSegment < lengths.lastIndex && alertRemaining > lengths[alertSegment]) {
        alertRemaining -= lengths[alertSegment]; alertSegment++
    }
    val alertFraction = alertRemaining / lengths[alertSegment]
    val alertA = points[alertSegment]
    val alertB = points[alertSegment + 1]
    val alertLat = alertA.lat + (alertB.lat - alertA.lat) * alertFraction
    val alertLon = alertA.lng + (alertB.lng - alertA.lng) * alertFraction
    for (second in 0..duration) {
        val along = total * second / duration
        var left = along
        var segment = 0
        while (segment < lengths.lastIndex && left > lengths[segment]) { left -= lengths[segment]; segment++ }
        val fraction = (left / lengths[segment]).coerceIn(0.0, 1.0)
        val a = points[segment]
        val b = points[segment + 1]
        val lat = a.lat + (b.lat - a.lat) * fraction
        val lon = a.lng + (b.lng - a.lng) * fraction
        emit(PositionUpdate(++seq, lat, lon, Geo.bearingDeg(a, b).toFloat(), (total / duration).toFloat(),
            along.roundToInt(), second * 1000L), if (second == 0) 0 else 1000)
        val index = ends.indexOfFirst { it >= along }.let { if (it < 0) instructions.lastIndex else it }
        val distance = (ends[index] - along).roundToInt().coerceAtLeast(0)
        emit(ManeuverUpdate(++seq, instructions[index], distance, (distance / (total / duration)).roundToInt(),
            instructions.getOrNull(index + 1)))
        val remainingSeconds = (duration - second) * 9
        emit(TripProgress(++seq, second * 1000L + remainingSeconds * 1000L,
            (total - along).roundToInt(), remainingSeconds, minOf(240, remainingSeconds / 3)))
        val alertAt = total * .48
        if (second >= duration * .2 && along <= alertAt) {
            emit(Alert(++seq, "demo:police", AlertKind.POLICE, (alertAt - along).roundToInt(),
                alertLat, alertLon, "Police reported", "Demo", 1L))
        } else if (second == (duration * .48).toInt() + 1) emit(AlertCleared(++seq, "demo:police"))
        if (second == (duration * .60).toInt()) emit(DetourOffer(++seq, "demo:detour", 360, 200,
            "via Bay Street", expiresAtMs = second * 1000L + 30_000))
    }
    emit(NavStateUpdate(++seq, NavState.ARRIVED))
    emit(NavStateUpdate(++seq, NavState.IDLE), 10_000)
    output.parentFile.mkdirs()
    output.writeText(lines.joinToString("\n", postfix = "\n"))
    println("Wrote ${lines.size} messages to ${output.path}")
}
