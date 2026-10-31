package com.rokidnav.glasses.ui

import com.rokidnav.protocol.Units
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Called on input state changes, never from a drawing frame. */
object Format {
    private val clock12 = DateTimeFormatter.ofPattern("h:mm", Locale.US)
    private val clock24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    fun distance(meters: Int, units: Units): String {
        val m = meters.coerceAtLeast(0)
        return if (units == Units.IMPERIAL) {
            val feet = m * 3.28084
            if (feet < 1000) "${(feet / 50).roundToInt() * 50} ft"
            else String.format(Locale.US, if (m < 16_093) "%.1f mi" else "%.0f mi", m / 1609.344)
        } else if (m < 1000) "${(m / 10.0).roundToInt() * 10} m"
        else String.format(Locale.US, if (m < 10_000) "%.1f km" else "%.0f km", m / 1000.0)
    }
    fun duration(seconds: Int): String {
        val minutes = (seconds.coerceAtLeast(0) + 59) / 60
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }
    fun eta(epochMs: Long, clock24h: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        (if (clock24h) clock24 else clock12).format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
