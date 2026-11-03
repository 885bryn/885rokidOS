package com.rokidos885.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolCodecTest {
    @Test fun roundTripsEveryMessageType() {
        val messages: List<NavMessage> = listOf(
            SessionStart(1, Units.METRIC, true, "Home"),
            ManeuverUpdate(2, Step(Maneuver.TURN_RIGHT, "Main St"), 300, 25, Step(Maneuver.TURN_LEFT)),
            TripProgress(3, 1_700_000_000_000, 15_000, 1_100, 240),
            Alert(4, "a1", AlertKind.POLICE, 600, 37.0, -122.0, "Police reported"),
            DetourOffer(5, "d1", 360, 800, "via I-280", expiresAtMs = 1L),
            GlassesAction(6, ActionType.ACCEPT_DETOUR, "d1"),
            RouteChunk(7, "r1", 0, 1, "??", 100),
            RoadContext(8, "r1", listOf(ContextRoad("??", "Main St", true))),
            RoadContext(20, "r1", listOf(ContextRoad("??", "Bay St")), "c7", 1, 3),
            PositionUpdate(9, 37.0, -122.0, 90f, 12f, 300, 1000L),
            TrafficSegments(10, "r1", listOf(TrafficSegment(0, 100, TrafficLevel.JAM))),
            AlertCleared(11, "a1"),
            DetourResolved(12, "d1", true),
            NavStateUpdate(13, NavState.ACTIVE),
            SettingsUpdate(14, true, Units.IMPERIAL, 2, 60, demoMode = true),
            GlassesAction(20,ActionType.CYCLE_BRIGHTNESS),
            GlassesAction(21,ActionType.CYCLE_ZOOM),
            GlassesHello(15, "0.1", 480, 398),
            HelloRequest(),
            InputEvent(16, "TAP"),
            ResyncRequest(17, "reconnect"),
            ManeuverUpdate(18, Step(Maneuver.ROUNDABOUT_CLOCKWISE, roundaboutExit = 3,
                lanes = listOf(Lane(listOf(Maneuver.TURN_LEFT, Maneuver.STRAIGHT), true))), 30, 2),
            Alert(19, "cam1", AlertKind.SPEED_CAMERA, 700, 37.0, -122.0, "Speed camera", "WzSabre", 1700000000000),
            PhoneNotification(22, "n1", "Messages", "Sam", "On my way", 1700000000000),
            MediaState(23, true, "Spotify", "Song", "Artist", true, 61_000, 215_000),
            MediaState(24, active = false),
            GlassesAction(25, ActionType.MEDIA_PLAY_PAUSE),
            PhoneNotification(26, "n2", "WhatsApp", "Sam", "Hi", 1700000000000, canReply = true),
            VoiceState(27, VoicePhase.REVIEW, "n2", "I will be there soon."),
            GlassesAction(28, ActionType.VOICE_SEND, "n2"),
        )
        for (m in messages) {
            val (magic, body) = ProtocolCodec.encode(m)
            assertEquals(m, ProtocolCodec.decode(magic, body))
        }
    }

    @Test fun rejectsForeignFrames() {
        assertNull(ProtocolCodec.decode("message", "hello"))
        assertNull(ProtocolCodec.decode(ProtocolVersion.MAGIC, "{not json"))
    }

    @Test fun ignoresUnknownFieldsFromNewerSender() {
        val body = """{"t":"nav_state","seq":9,"state":"ACTIVE","futureField":42}"""
        assertEquals(NavStateUpdate(9, NavState.ACTIVE), ProtocolCodec.decode(ProtocolVersion.MAGIC, body))
    }
    @Test fun oldSettingsDefaultBrightness() {
        val old="""{"t":"settings","seq":1,"voiceMuted":false,"units":"IMPERIAL","mapZoomLevel":1}"""
        assertEquals(35,(ProtocolCodec.decode(ProtocolVersion.MAGIC,old) as SettingsUpdate).brightnessPercent)
        assertEquals(false,(ProtocolCodec.decode(ProtocolVersion.MAGIC,old) as SettingsUpdate).demoMode)
    }
    @Test fun oldAlertsHaveUnknownReportTime() {
        val old="""{"t":"alert","seq":1,"id":"p1","kind":"POLICE","distanceAheadMeters":100,"lat":0,"lng":0}"""
        assertEquals(0L,(ProtocolCodec.decode(ProtocolVersion.MAGIC,old) as Alert).reportedAtMs)
    }
    @Test fun oldNotificationsCannotReply() {
        val old="""{"t":"notification","seq":1,"id":"n1","title":"Sam"}"""
        assertEquals(false,(ProtocolCodec.decode(ProtocolVersion.MAGIC,old) as PhoneNotification).canReply)
    }
    @Test fun minimalNotificationDecodes() {
        val body="""{"t":"notification","seq":1,"id":"n1"}"""
        assertEquals(PhoneNotification(1,"n1"),ProtocolCodec.decode(ProtocolVersion.MAGIC,body))
    }
}
