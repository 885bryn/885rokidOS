package com.rokidos885.phone.alerts

import com.rokidos885.phone.settings.UserSettings
import com.rokidos885.protocol.Alert
import com.rokidos885.protocol.AlertKind
import com.rokidos885.protocol.Geo
import com.rokidos885.protocol.LatLng
import com.rokidos885.protocol.Units
import java.util.Locale

/**
 * Decides which alerts get a spoken warning. Pure logic (no Android), fed every alert update.
 *
 * A report is spoken once, the first time it is within the user's warning distance ahead. Several
 * people reporting the same trap produce separate reports a few metres apart, so a report of the same
 * kind within [DUPLICATE_RADIUS_M] of one spoken in the last [DUPLICATE_WINDOW_MS] stays silent.
 */
class AlertAnnouncer(private val settings: () -> UserSettings, private val nowMs: () -> Long = System::currentTimeMillis) {
    private data class Spoken(val kind: AlertKind, val at: LatLng, val timeMs: Long)

    private val considered = LinkedHashSet<String>()
    private val recent = ArrayList<Spoken>()

    /** Returns the sentence to speak for this alert update, or null. */
    fun consider(alert: Alert): String? {
        val s = settings()
        if (!s.alertVoice) return null
        if (alert.kind == AlertKind.SPEED_CAMERA && !s.alertVoiceCameras) return null
        if (alert.id in considered || alert.distanceAheadMeters > s.alertVoiceMeters) return null
        considered += alert.id
        if (considered.size > MAX_REMEMBERED) considered.remove(considered.first())
        val now = nowMs()
        recent.removeAll { now - it.timeMs > DUPLICATE_WINDOW_MS }
        val at = LatLng(alert.lat, alert.lng)
        if (recent.any { it.kind == alert.kind && Geo.distanceMeters(it.at, at) < DUPLICATE_RADIUS_M }) return null
        recent += Spoken(alert.kind, at, now)
        return phrase(alert.kind, alert.distanceAheadMeters, s.units)
    }

    companion object {
        const val DUPLICATE_RADIUS_M = 250.0
        const val DUPLICATE_WINDOW_MS = 15 * 60_000L
        private const val MAX_REMEMBERED = 500

        fun phrase(kind: AlertKind, meters: Int, units: Units): String {
            val what = when (kind) {
                AlertKind.POLICE -> "Police reported"
                AlertKind.SPEED_CAMERA -> "Speed camera"
                AlertKind.ACCIDENT -> "Crash reported"
                AlertKind.HAZARD -> "Hazard reported"
                AlertKind.ROAD_CLOSED -> "Road closed"
                AlertKind.CONSTRUCTION -> "Construction"
                AlertKind.TRAFFIC_JAM -> "Traffic jam"
                AlertKind.WEATHER -> "Weather warning"
                AlertKind.OTHER -> "Road alert"
            }
            return if (meters < 100) "$what just ahead." else "$what ahead in ${spokenDistance(meters, units)}."
        }

        /** Distances written the way they should be spoken ("half a mile", "400 feet", "1.5 kilometres"). */
        fun spokenDistance(meters: Int, units: Units): String {
            if (units == Units.METRIC) {
                if (meters < 1000) return "${(meters + 25) / 50 * 50} metres"
                return trimmed(meters / 1000.0) + if (meters in 950..1049) " kilometre" else " kilometres"
            }
            if (meters < 305) return "${((meters * 3.28084 + 25) / 50).toInt() * 50} feet"
            val miles = meters / 1609.344
            return when {
                miles < 0.375 -> "a quarter mile"
                miles < 0.625 -> "half a mile"
                miles < 0.875 -> "three quarters of a mile"
                miles < 1.05 -> "1 mile"
                else -> trimmed(miles) + " miles"
            }
        }

        private fun trimmed(v: Double) = String.format(Locale.US, "%.1f", v).removeSuffix(".0")
    }
}

/** Speaks alert sentences; implemented with Android text-to-speech in [AndroidAlertSpeaker]. */
interface AlertSpeaker {
    fun speak(text: String)
    fun shutdown()
}
