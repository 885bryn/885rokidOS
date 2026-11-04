package com.rokidos885.phone.alerts

import com.rokidos885.phone.settings.UserSettings
import com.rokidos885.protocol.Alert
import com.rokidos885.protocol.AlertKind
import com.rokidos885.protocol.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertAnnouncerTest {
    private var settings = UserSettings(units = Units.IMPERIAL)
    private var now = 0L
    private val announcer = AlertAnnouncer({ settings }, { now })
    private fun alert(id: String, meters: Int, kind: AlertKind = AlertKind.POLICE, lng: Double = 0.01) =
        Alert(0, id, kind, meters, 0.0, lng)

    @Test fun speaksOnceWhenTheReportComesWithinTheWarningDistance() {
        assertNull(announcer.consider(alert("p1", 1800)))                    // beyond 1 km default
        assertEquals("Police reported ahead in half a mile.", announcer.consider(alert("p1", 900)))
        assertNull(announcer.consider(alert("p1", 600)))                     // already spoken
    }

    @Test fun duplicateReportsOfTheSameTrapStaySilent() {
        assertEquals("Police reported ahead in half a mile.", announcer.consider(alert("p1", 900)))
        assertNull(announcer.consider(alert("p2", 880, lng = 0.0101)))       // 11 m from p1
        assertEquals("Speed camera ahead in half a mile.", announcer.consider(alert("c1", 870, AlertKind.SPEED_CAMERA, 0.0101)))
        now += AlertAnnouncer.DUPLICATE_WINDOW_MS + 1
        assertEquals("Police reported ahead in half a mile.", announcer.consider(alert("p3", 900, lng = 0.0102)))
    }

    @Test fun honoursOffAndCameraSwitches() {
        settings = settings.copy(alertVoiceCameras = false)
        assertNull(announcer.consider(alert("c1", 500, AlertKind.SPEED_CAMERA)))
        settings = settings.copy(alertVoice = false)
        assertNull(announcer.consider(alert("p1", 500)))
    }

    @Test fun warningDistanceIsConfigurable() {
        settings = settings.copy(alertVoiceMeters = 2000)
        assertEquals("Police reported ahead in 1.1 miles.", announcer.consider(alert("p1", 1800)))
    }

    @Test fun metricIsTheDefault() {
        val metric = AlertAnnouncer({ UserSettings() }, { 0L })
        assertEquals("Police reported ahead in 900 metres.", metric.consider(alert("p1", 890)))
    }

    @Test fun phrasesReadNaturally() {
        assertEquals("Police reported just ahead.", AlertAnnouncer.phrase(AlertKind.POLICE, 40, Units.IMPERIAL))
        assertEquals("Speed camera ahead in 400 feet.", AlertAnnouncer.phrase(AlertKind.SPEED_CAMERA, 120, Units.IMPERIAL))
        assertEquals("a quarter mile", AlertAnnouncer.spokenDistance(400, Units.IMPERIAL))
        assertEquals("1 mile", AlertAnnouncer.spokenDistance(1609, Units.IMPERIAL))
        assertEquals("350 metres", AlertAnnouncer.spokenDistance(340, Units.METRIC))
        assertEquals("1.5 kilometres", AlertAnnouncer.spokenDistance(1500, Units.METRIC))
        assertEquals("1 kilometre", AlertAnnouncer.spokenDistance(1000, Units.METRIC))
    }
}
