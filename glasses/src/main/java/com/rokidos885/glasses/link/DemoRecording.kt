package com.rokidos885.glasses.link

import com.rokidos885.protocol.*
import kotlinx.serialization.json.*

/** Independent of Android so the exact shipped recording is testable. */
object DemoRecording {
    data class Frame(val delayMs: Long, val message: NavMessage)
    fun parse(lines: Sequence<String>): List<Frame> = lines.filter { it.isNotBlank() }.map { line ->
        val obj = Json.parseToJsonElement(line).jsonObject
        val delay = obj.getValue("delayMs").jsonPrimitive.long
        require(delay in 0..60_000)
        Frame(delay, requireNotNull(ProtocolCodec.decode(ProtocolVersion.MAGIC, obj.getValue("message").toString())))
    }.toList().also { require(it.isNotEmpty()) }

    fun rebase(message: NavMessage, seq: Long, startMs: Long): NavMessage = when (message) {
        is SessionStart -> message.copy(seq = seq)
        is RouteChunk -> message.copy(seq = seq)
        is PositionUpdate -> message.copy(seq = seq, timestampMs = startMs + message.timestampMs)
        is ManeuverUpdate -> message.copy(seq = seq)
        is TripProgress -> message.copy(seq = seq, etaEpochMs = startMs + message.etaEpochMs)
        is TrafficSegments -> message.copy(seq = seq)
        is Alert -> message.copy(seq = seq, reportedAtMs = if (message.reportedAtMs == 0L) 0 else startMs + message.reportedAtMs)
        is AlertCleared -> message.copy(seq = seq)
        is DetourOffer -> message.copy(seq = seq, expiresAtMs = startMs + message.expiresAtMs)
        is DetourResolved -> message.copy(seq = seq)
        is NavStateUpdate -> message.copy(seq = seq)
        is SettingsUpdate -> message.copy(seq = seq)
        is RoadContext -> message.copy(seq = seq)
        else -> error("Demo requires phone-to-glasses messages")
    }
}
