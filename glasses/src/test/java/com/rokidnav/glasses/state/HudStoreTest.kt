package com.rokidnav.glasses.state

import com.rokidnav.glasses.input.GlassesKey
import com.rokidnav.protocol.ActionType
import com.rokidnav.protocol.DetourOffer
import com.rokidnav.protocol.GlassesAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudStoreTest {
    private val sent = mutableListOf<GlassesAction>()
    private val store = HudStore { sent += it }

    @Test fun multiPartStreetSnapshotAppliesOnlyWhenComplete() {
        val line=com.rokidnav.protocol.PolylineCodec.encode(listOf(com.rokidnav.protocol.LatLng(37.0,-122.0),com.rokidnav.protocol.LatLng(37.001,-122.0)))
        store.onMessage(com.rokidnav.protocol.RouteChunk(1,"r",0,1,line,100))
        val a=com.rokidnav.protocol.ContextRoad(line,"A");val b=com.rokidnav.protocol.ContextRoad(line,"B",true)
        store.onMessage(com.rokidnav.protocol.RoadContext(2,"r",listOf(a),"c1",0,2))
        assertEquals(0,store.state.value.contextRoads.size)
        store.onMessage(com.rokidnav.protocol.RoadContext(3,"r",listOf(b),"c1",1,2))
        assertEquals(listOf("A","B"),store.state.value.contextRoads.map {it.name})
        assertEquals(true,store.state.value.contextRoads[1].major)
        // A new snapshot replaces the old one only when it, too, is complete.
        store.onMessage(com.rokidnav.protocol.RoadContext(4,"r",listOf(b),"c2",0,2))
        assertEquals(listOf("A","B"),store.state.value.contextRoads.map {it.name})
    }

    @Test fun tapAcceptsPendingDetour() {
        store.onMessage(DetourOffer(1, "d1", 300, 0, "via A", expiresAtMs = Long.MAX_VALUE))
        store.onKey(GlassesKey.TAP)
        assertEquals(ActionType.ACCEPT_DETOUR, sent.single().action)
        assertEquals("d1", sent.single().targetId)
        assertNull(store.state.value.detour)
    }

    @Test fun tapCyclesCornerMapBigMapAndInstructionsOnlyWhenNoDetour() {
        store.onKey(GlassesKey.TAP)
        assertEquals(HudMode.Map, store.state.value.mode)
        store.onKey(GlassesKey.TAP)
        assertEquals(HudMode.Minimal, store.state.value.mode)
        store.onKey(GlassesKey.TAP)
        assertEquals(HudMode.Compact, store.state.value.mode)
    }

    @Test fun alertCountsDownClearsAndEndsWithTrip() {
        val alert = com.rokidnav.protocol.Alert(1, "p1", com.rokidnav.protocol.AlertKind.POLICE,
            1000, 0.0, 0.01, "Police reported", "WzSabre")
        store.onMessage(alert)
        store.onMessage(alert.copy(seq = 2, distanceAheadMeters = 500))
        assertEquals(500, store.state.value.alerts.single().distanceAheadMeters)
        store.onMessage(com.rokidnav.protocol.AlertCleared(3, "p1"))
        assertEquals(emptyList<com.rokidnav.protocol.Alert>(), store.state.value.alerts)
        store.onMessage(alert.copy(seq = 4))
        store.onMessage(com.rokidnav.protocol.NavStateUpdate(5, com.rokidnav.protocol.NavState.ENDED))
        assertEquals(emptyList<com.rokidnav.protocol.Alert>(), store.state.value.alerts)
    }

    @Test fun frozenGuidanceIsReplacedWhenThePhoneStopsSending() {
        var now=1_000L
        val hud=HudStore(clock={now}){}
        hud.onMessage(com.rokidnav.protocol.NavStateUpdate(1,com.rokidnav.protocol.NavState.IDLE))
        now+=HudStore.PHONE_SILENT_MS+1;hud.tick()
        assertEquals(false,hud.state.value.phoneSilent)                  // idle: nothing to freeze
        hud.onMessage(com.rokidnav.protocol.NavStateUpdate(2,com.rokidnav.protocol.NavState.ACTIVE))
        now+=HudStore.PHONE_SILENT_MS-1;hud.tick()
        assertEquals(false,hud.state.value.phoneSilent)
        now+=1;hud.tick()                                                 // 12 s of silence mid-trip
        assertEquals(true,hud.state.value.phoneSilent)
        hud.onMessage(com.rokidnav.protocol.SessionStart(1))              // phone reconnected and resyncs
        assertEquals(false,hud.state.value.phoneSilent)
    }

    @Test fun doubleTapAndBackGoToTheSystemUnlessTheHudNeedsThem() {
        // Plain driving view, even with an alert showing: hand it to the system (exit to Rokid home).
        store.onMessage(com.rokidnav.protocol.Alert(1,"p1",com.rokidnav.protocol.AlertKind.POLICE,1000,0.0,0.0))
        assertEquals(false,store.onKey(GlassesKey.DOUBLE_TAP))
        assertEquals(false,store.onKey(GlassesKey.BACK))
        assertEquals(1,store.state.value.alerts.size)                      // alerts are not dismissed by it
        // Detour offer: double-tap keeps the current route.
        store.onMessage(DetourOffer(2,"d1",300,0,"via A",expiresAtMs=Long.MAX_VALUE))
        assertEquals(true,store.onKey(GlassesKey.DOUBLE_TAP))
        assertEquals(ActionType.DISMISS_DETOUR,sent.last().action)
        // Arrival screen: double-tap closes it.
        store.onMessage(com.rokidnav.protocol.NavStateUpdate(3,com.rokidnav.protocol.NavState.ARRIVED))
        assertEquals(true,store.onKey(GlassesKey.DOUBLE_TAP))
        assertEquals(com.rokidnav.protocol.NavState.IDLE,store.state.value.navState)
        // Other keys are always the HUD's.
        assertEquals(true,store.onKey(GlassesKey.TAP))
    }

    @Test fun alertsNeedNoInteractionTheyClearWhenPassedOrWhenThePhoneGoesQuiet() {
        var now=1_000L
        val hud=HudStore(clock={now}){}
        val alert=com.rokidnav.protocol.Alert(1,"p1",com.rokidnav.protocol.AlertKind.POLICE,1000,0.0,0.0)
        hud.onMessage(alert)
        assertEquals(1_000L,hud.state.value.alertSeenAt["p1"])
        now+=60_000;hud.onMessage(alert.copy(seq=2,distanceAheadMeters=400))     // refreshed: first-seen time kept
        assertEquals(1_000L,hud.state.value.alertSeenAt["p1"])
        now+=HudStore.ALERT_STALE_MS-1;hud.tick()
        assertEquals(1,hud.state.value.alerts.size)
        now+=1;hud.tick()                                                          // no update for 2 min: stale
        assertEquals(0,hud.state.value.alerts.size)
        assertNull(hud.state.value.alertSeenAt["p1"])
        hud.onMessage(alert.copy(seq=3,id="p2"))
        hud.onMessage(com.rokidnav.protocol.AlertCleared(4,"p2"))                  // passed: phone clears it
        assertEquals(0,hud.state.value.alerts.size)
    }

    @Test fun staleMessagesCannotReplaceNewerTurnsAndSessionResetsSequence() {
        store.onMessage(com.rokidnav.protocol.ManeuverUpdate(20,com.rokidnav.protocol.Step(com.rokidnav.protocol.Maneuver.TURN_RIGHT),300,20))
        store.onMessage(com.rokidnav.protocol.ManeuverUpdate(19,com.rokidnav.protocol.Step(com.rokidnav.protocol.Maneuver.TURN_LEFT),500,40))
        assertEquals(com.rokidnav.protocol.Maneuver.TURN_RIGHT,store.state.value.maneuver!!.current.maneuver)
        store.onMessage(com.rokidnav.protocol.SessionStart(1,destinationName="New trip"))
        assertNull(store.state.value.maneuver)
        store.onMessage(com.rokidnav.protocol.ManeuverUpdate(2,com.rokidnav.protocol.Step(com.rokidnav.protocol.Maneuver.TURN_LEFT),200,10))
        assertEquals(com.rokidnav.protocol.Maneuver.TURN_LEFT,store.state.value.maneuver!!.current.maneuver)
    }
    @Test fun incompleteRouteRequestsOneResync() {
        var now=1000L;var resyncs=0
        val hud=HudStore(clock={now},requestResync={resyncs++}){}
        hud.onMessage(com.rokidnav.protocol.RouteChunk(1,"r",0,2,"??",100))
        now+=5000;hud.tick();hud.tick()
        assertEquals(1,resyncs)
    }

    @Test fun notificationCardHidesOnTapOrAfterTimeout() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(com.rokidnav.protocol.PhoneNotification(1,"n1","Messages","Sam","On my way"))
        assertEquals("Sam",hud.state.value.notification?.title)
        hud.onKey(GlassesKey.TAP)
        assertNull(hud.state.value.notification)
        assertEquals(HudMode.Compact,hud.state.value.mode)
        hud.onMessage(com.rokidnav.protocol.PhoneNotification(2,"n2","Mail","Bill","Due"))
        now+=HudStore.NOTIFICATION_MIN_MS;hud.tick()
        assertNull(hud.state.value.notification)
    }

    @Test fun longerNotificationsStayLongerUpToTheMaximum() {
        val short=com.rokidnav.protocol.PhoneNotification(1,"a","App","Sam","On my way")
        val long=short.copy(text="x".repeat(120))
        val huge=short.copy(text="x".repeat(5_000))
        assertEquals(HudStore.NOTIFICATION_MIN_MS,HudStore.notificationMillis(short))
        assertEquals(true,HudStore.notificationMillis(long)>HudStore.NOTIFICATION_MIN_MS)
        assertEquals(HudStore.NOTIFICATION_MAX_MS,HudStore.notificationMillis(huge))
    }

    private fun media(seq:Long,playing:Boolean=true,position:Long=10_000)=
        com.rokidnav.protocol.MediaState(seq,true,"Spotify","Song","Artist",playing,position,200_000)

    @Test fun idleMediaScreenTapsPlayPauseAndSwipesSkip() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(media(1))
        hud.onKey(GlassesKey.TAP);hud.onKey(GlassesKey.SWIPE_FORWARD);hud.onKey(GlassesKey.SWIPE_BACK)
        assertEquals(listOf(ActionType.MEDIA_PLAY_PAUSE,ActionType.MEDIA_NEXT,ActionType.MEDIA_PREVIOUS),sent.map {it.action})
        // The icon follows the tap at once; the view mode does not change.
        assertEquals(false,hud.state.value.media?.playing)
        assertEquals(HudMode.Compact,hud.state.value.mode)
    }

    @Test fun mediaKeysDoNothingOnATripOrWithoutMusic() {
        val hud=HudStore {sent+=it}
        hud.onKey(GlassesKey.SWIPE_FORWARD)
        hud.onMessage(media(1));hud.onMessage(com.rokidnav.protocol.NavStateUpdate(2,com.rokidnav.protocol.NavState.ACTIVE))
        hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.ZOOM_IN),sent.map {it.action})
        assertEquals(HudMode.Map,hud.state.value.mode)
        hud.onMessage(com.rokidnav.protocol.MediaState(3,active=false))
        assertNull(hud.state.value.media)
    }

    @Test fun mediaOnlyRedrawsWhenWhatIsOnScreenChanges() {
        var now=10_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(media(1,position=60_000))
        val first=hud.state.value
        // A seek or a resync changes only the position: same state object, so no redraw.
        hud.onMessage(media(2,position=150_000))
        now+=60_000;hud.tick()
        assertEquals(true,hud.state.value.media===first.media)
        // Pausing, or a new track, does change the screen.
        hud.onMessage(media(3,playing=false))
        assertEquals(false,hud.state.value.media?.playing)
        hud.onMessage(media(4,playing=false).copy(title="Other song"))
        assertEquals("Other song",hud.state.value.media?.title)
    }

    @Test fun oneGestureReportedByThreeInputPathsSendsOneMediaAction() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(media(1))
        // Broadcast, key event and touch for one swipe, within a few ms of each other.
        hud.onKey(GlassesKey.SWIPE_FORWARD);now+=15;hud.onKey(GlassesKey.SWIPE_FORWARD);now+=40;hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(listOf(ActionType.MEDIA_NEXT),sent.map {it.action})
        // A second, deliberate swipe later skips again; another key is never held back by it.
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.SWIPE_FORWARD)
        hud.onKey(GlassesKey.SWIPE_BACK)
        assertEquals(listOf(ActionType.MEDIA_NEXT,ActionType.MEDIA_NEXT,ActionType.MEDIA_PREVIOUS),sent.map {it.action})
        // Tap is debounced the same way, so a doubled tap cannot pause and resume at once.
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.TAP);hud.onKey(GlassesKey.TAP)
        assertEquals(1,sent.count {it.action==ActionType.MEDIA_PLAY_PAUSE})
        assertEquals(false,hud.state.value.media?.playing)
    }

    @Test fun aVisibleNotificationRedrawsEverySecondForItsCountdown() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(com.rokidnav.protocol.PhoneNotification(1,"n1","Messages","Sam","On my way"))
        assertEquals(1_000L,hud.state.value.nowMs)
        now+=1_000;hud.tick()
        assertEquals(2_000L,hud.state.value.nowMs)
        now+=1_000;hud.tick()
        assertEquals(3_000L,hud.state.value.nowMs)
        // With no card (and nothing else timed) an idle HUD does not redraw.
        now+=HudStore.NOTIFICATION_MAX_MS;hud.tick()
        assertNull(hud.state.value.notification)
        val quiet=hud.state.value
        now+=1_000;hud.tick()
        assertEquals(true,hud.state===null || hud.state.value===quiet)
    }
}
