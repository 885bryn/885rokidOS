package com.rokidos885.glasses.state

import com.rokidos885.glasses.input.GlassesKey
import com.rokidos885.protocol.ActionType
import com.rokidos885.protocol.DetourOffer
import com.rokidos885.protocol.GlassesAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudStoreTest {
    private val sent = mutableListOf<GlassesAction>()
    private val store = HudStore { sent += it }

    @Test fun multiPartStreetSnapshotAppliesOnlyWhenComplete() {
        val line=com.rokidos885.protocol.PolylineCodec.encode(listOf(com.rokidos885.protocol.LatLng(37.0,-122.0),com.rokidos885.protocol.LatLng(37.001,-122.0)))
        store.onMessage(com.rokidos885.protocol.RouteChunk(1,"r",0,1,line,100))
        val a=com.rokidos885.protocol.ContextRoad(line,"A");val b=com.rokidos885.protocol.ContextRoad(line,"B",true)
        store.onMessage(com.rokidos885.protocol.RoadContext(2,"r",listOf(a),"c1",0,2))
        assertEquals(0,store.state.value.contextRoads.size)
        store.onMessage(com.rokidos885.protocol.RoadContext(3,"r",listOf(b),"c1",1,2))
        assertEquals(listOf("A","B"),store.state.value.contextRoads.map {it.name})
        assertEquals(true,store.state.value.contextRoads[1].major)
        // A new snapshot replaces the old one only when it, too, is complete.
        store.onMessage(com.rokidos885.protocol.RoadContext(4,"r",listOf(b),"c2",0,2))
        assertEquals(listOf("A","B"),store.state.value.contextRoads.map {it.name})
    }

    @Test fun tapAcceptsPendingDetour() {
        store.onMessage(DetourOffer(1, "d1", 300, 0, "via A", expiresAtMs = Long.MAX_VALUE))
        store.onKey(GlassesKey.TAP)
        assertEquals(ActionType.ACCEPT_DETOUR, sent.single().action)
        assertEquals("d1", sent.single().targetId)
        assertNull(store.state.value.detour)
    }

    @Test fun swipeForwardCyclesCornerMapBigMapAndInstructionsOnATrip() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(com.rokidos885.protocol.NavStateUpdate(1,com.rokidos885.protocol.NavState.ACTIVE))
        hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(HudMode.Map, hud.state.value.mode)
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(HudMode.Minimal, hud.state.value.mode)
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(HudMode.Compact, hud.state.value.mode)
    }

    @Test fun alertCountsDownClearsAndEndsWithTrip() {
        val alert = com.rokidos885.protocol.Alert(1, "p1", com.rokidos885.protocol.AlertKind.POLICE,
            1000, 0.0, 0.01, "Police reported", "WzSabre")
        store.onMessage(alert)
        store.onMessage(alert.copy(seq = 2, distanceAheadMeters = 500))
        assertEquals(500, store.state.value.alerts.single().distanceAheadMeters)
        store.onMessage(com.rokidos885.protocol.AlertCleared(3, "p1"))
        assertEquals(emptyList<com.rokidos885.protocol.Alert>(), store.state.value.alerts)
        store.onMessage(alert.copy(seq = 4))
        store.onMessage(com.rokidos885.protocol.NavStateUpdate(5, com.rokidos885.protocol.NavState.ENDED))
        assertEquals(emptyList<com.rokidos885.protocol.Alert>(), store.state.value.alerts)
    }

    @Test fun frozenGuidanceIsReplacedWhenThePhoneStopsSending() {
        var now=1_000L
        val hud=HudStore(clock={now}){}
        hud.onMessage(com.rokidos885.protocol.NavStateUpdate(1,com.rokidos885.protocol.NavState.IDLE))
        now+=HudStore.PHONE_SILENT_MS+1;hud.tick()
        assertEquals(false,hud.state.value.phoneSilent)                  // idle: nothing to freeze
        hud.onMessage(com.rokidos885.protocol.NavStateUpdate(2,com.rokidos885.protocol.NavState.ACTIVE))
        now+=HudStore.PHONE_SILENT_MS-1;hud.tick()
        assertEquals(false,hud.state.value.phoneSilent)
        now+=1;hud.tick()                                                 // 12 s of silence mid-trip
        assertEquals(true,hud.state.value.phoneSilent)
        hud.onMessage(com.rokidos885.protocol.SessionStart(1))              // phone reconnected and resyncs
        assertEquals(false,hud.state.value.phoneSilent)
    }

    @Test fun doubleTapAndBackGoToTheSystemUnlessTheHudNeedsThem() {
        // Plain driving view, even with an alert showing: hand it to the system (exit to Rokid home).
        store.onMessage(com.rokidos885.protocol.Alert(1,"p1",com.rokidos885.protocol.AlertKind.POLICE,1000,0.0,0.0))
        assertEquals(false,store.onKey(GlassesKey.DOUBLE_TAP))
        assertEquals(false,store.onKey(GlassesKey.BACK))
        assertEquals(1,store.state.value.alerts.size)                      // alerts are not dismissed by it
        // Detour offer: double-tap keeps the current route.
        store.onMessage(DetourOffer(2,"d1",300,0,"via A",expiresAtMs=Long.MAX_VALUE))
        assertEquals(true,store.onKey(GlassesKey.DOUBLE_TAP))
        assertEquals(ActionType.DISMISS_DETOUR,sent.last().action)
        // Arrival screen: double-tap closes it.
        store.onMessage(com.rokidos885.protocol.NavStateUpdate(3,com.rokidos885.protocol.NavState.ARRIVED))
        assertEquals(true,store.onKey(GlassesKey.DOUBLE_TAP))
        assertEquals(com.rokidos885.protocol.NavState.IDLE,store.state.value.navState)
        // Other keys are always the HUD's.
        assertEquals(true,store.onKey(GlassesKey.TAP))
    }

    @Test fun alertsNeedNoInteractionTheyClearWhenPassedOrWhenThePhoneGoesQuiet() {
        var now=1_000L
        val hud=HudStore(clock={now}){}
        val alert=com.rokidos885.protocol.Alert(1,"p1",com.rokidos885.protocol.AlertKind.POLICE,1000,0.0,0.0)
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
        hud.onMessage(com.rokidos885.protocol.AlertCleared(4,"p2"))                  // passed: phone clears it
        assertEquals(0,hud.state.value.alerts.size)
    }

    @Test fun staleMessagesCannotReplaceNewerTurnsAndSessionResetsSequence() {
        store.onMessage(com.rokidos885.protocol.ManeuverUpdate(20,com.rokidos885.protocol.Step(com.rokidos885.protocol.Maneuver.TURN_RIGHT),300,20))
        store.onMessage(com.rokidos885.protocol.ManeuverUpdate(19,com.rokidos885.protocol.Step(com.rokidos885.protocol.Maneuver.TURN_LEFT),500,40))
        assertEquals(com.rokidos885.protocol.Maneuver.TURN_RIGHT,store.state.value.maneuver!!.current.maneuver)
        store.onMessage(com.rokidos885.protocol.SessionStart(1,destinationName="New trip"))
        assertNull(store.state.value.maneuver)
        store.onMessage(com.rokidos885.protocol.ManeuverUpdate(2,com.rokidos885.protocol.Step(com.rokidos885.protocol.Maneuver.TURN_LEFT),200,10))
        assertEquals(com.rokidos885.protocol.Maneuver.TURN_LEFT,store.state.value.maneuver!!.current.maneuver)
    }
    @Test fun incompleteRouteRequestsOneResync() {
        var now=1000L;var resyncs=0
        val hud=HudStore(clock={now},requestResync={resyncs++}){}
        hud.onMessage(com.rokidos885.protocol.RouteChunk(1,"r",0,2,"??",100))
        now+=5000;hud.tick();hud.tick()
        assertEquals(1,resyncs)
    }

    @Test fun notificationCardHidesOnTapOrAfterTimeout() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(com.rokidos885.protocol.PhoneNotification(1,"n1","Messages","Sam","On my way"))
        assertEquals("Sam",hud.state.value.notification?.title)
        hud.onKey(GlassesKey.TAP)
        assertNull(hud.state.value.notification)
        assertEquals(HudMode.Compact,hud.state.value.mode)
        hud.onMessage(com.rokidos885.protocol.PhoneNotification(2,"n2","Mail","Bill","Due"))
        now+=HudStore.NOTIFICATION_MIN_MS;hud.tick()
        assertNull(hud.state.value.notification)
    }

    @Test fun doubleTapClosesACardAndDoesNotLeaveTheApp() {
        val hud=HudStore {sent+=it}
        hud.onMessage(replyCard())
        assertEquals(true,hud.onKey(GlassesKey.DOUBLE_TAP))
        assertNull(hud.state.value.notification)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
        // With the card gone, the next double tap leaves to the Rokid menu as before.
        assertEquals(false,hud.onKey(GlassesKey.DOUBLE_TAP))
    }

    @Test fun withoutATripTapAndSwipeNeverChangeTheHiddenMapViewOrZoom() {
        val hud=HudStore {sent+=it}
        hud.onKey(GlassesKey.TAP);hud.onKey(GlassesKey.SWIPE_FORWARD);hud.onKey(GlassesKey.SWIPE_BACK)
        assertEquals(HudMode.Compact,hud.state.value.mode)
        assertEquals(1,hud.state.value.mapZoom)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
    }

    @Test fun longerNotificationsStayLongerUpToTheMaximum() {
        val short=com.rokidos885.protocol.PhoneNotification(1,"a","App","Sam","On my way")
        val long=short.copy(text="x".repeat(120))
        val huge=short.copy(text="x".repeat(5_000))
        assertEquals(HudStore.NOTIFICATION_MIN_MS,HudStore.notificationMillis(short))
        assertEquals(true,HudStore.notificationMillis(long)>HudStore.NOTIFICATION_MIN_MS)
        assertEquals(HudStore.NOTIFICATION_MAX_MS,HudStore.notificationMillis(huge))
    }

    private fun media(seq:Long,playing:Boolean=true,position:Long=10_000)=
        com.rokidos885.protocol.MediaState(seq,true,"Spotify","Song","Artist",playing,position,200_000)

    @Test fun idleMediaScreenTapsPlayPauseAndSwipesSkip() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(media(1));hud.onKey(GlassesKey.SWIPE_FORWARD)
        hud.onKey(GlassesKey.TAP);hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(listOf(ActionType.MEDIA_PLAY_PAUSE,ActionType.MEDIA_NEXT),sent.map {it.action})
        // The icon follows the tap at once; the view mode does not change.
        assertEquals(false,hud.state.value.media?.playing)
        assertEquals(HudMode.Compact,hud.state.value.mode)
        // Swipe back skips back; the player stays.
        hud.onKey(GlassesKey.SWIPE_BACK)
        assertEquals(true,hud.state.value.mediaVisible)
        assertEquals(ActionType.MEDIA_PREVIOUS,sent.last().action)
    }

    @Test fun mediaKeysDoNothingOnATripOrWithoutMusic() {
        val hud=HudStore {sent+=it}
        hud.onMessage(media(1));hud.onMessage(com.rokidos885.protocol.NavStateUpdate(2,com.rokidos885.protocol.NavState.ACTIVE))
        hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
        assertEquals(HudMode.Map,hud.state.value.mode)
        hud.onMessage(com.rokidos885.protocol.MediaState(3,active=false))
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
        hud.onMessage(media(1));hud.onKey(GlassesKey.SWIPE_FORWARD);now+=HudStore.MEDIA_KEY_GAP_MS
        // Broadcast, key event and touch for one swipe, within a few ms of each other.
        hud.onKey(GlassesKey.SWIPE_FORWARD);now+=15;hud.onKey(GlassesKey.SWIPE_FORWARD);now+=40;hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(listOf(ActionType.MEDIA_NEXT),sent.map {it.action})
        // A second, deliberate swipe later skips again; another key is never held back by it.
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.SWIPE_FORWARD)
        hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.MEDIA_NEXT,ActionType.MEDIA_NEXT,ActionType.MEDIA_PLAY_PAUSE),sent.map {it.action})
        // Tap is debounced the same way, so a doubled tap cannot pause and resume at once.
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.TAP);hud.onKey(GlassesKey.TAP)
        assertEquals(2,sent.count {it.action==ActionType.MEDIA_PLAY_PAUSE})
        assertEquals(true,hud.state.value.media?.playing)
    }

    private fun t3(seq:Long,state:com.rokidos885.protocol.T3State=com.rokidos885.protocol.T3State.READY)=
        com.rokidos885.protocol.T3Status(seq,"Fix the build","claude-opus-5-5",state)

    @Test fun theCarouselHasTheT3PageLeftOfHomeAndThePlayerRight() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onConnection(true);hud.onMessage(media(1));hud.onMessage(t3(2))
        // Home: swipe back opens the T3 page; a second report of the same swipe does nothing more.
        hud.onKey(GlassesKey.SWIPE_BACK);hud.onKey(GlassesKey.SWIPE_BACK)
        assertEquals(true,hud.state.value.t3Visible)
        assertEquals(false,hud.state.value.mediaVisible)
        // The T3 page outlasts the player's 10 s; swipe forward returns home, and the next one shows the player.
        now+=HudStore.MEDIA_SHOW_MS+1_000;hud.tick()
        assertEquals(true,hud.state.value.t3Visible)
        hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(false,hud.state.value.t3Page)
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(true,hud.state.value.mediaVisible)
        // In the player a swipe moves through tracks; double tap goes home and does not leave the app.
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.SWIPE_BACK)
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(listOf(ActionType.MEDIA_PREVIOUS,ActionType.MEDIA_NEXT),sent.map {it.action})
        now+=HudStore.MEDIA_KEY_GAP_MS
        assertEquals(true,hud.onKey(GlassesKey.DOUBLE_TAP))
        assertEquals(false,hud.state.value.mediaVisible)
        // Double tap on the T3 page goes home too; at home it leaves the app.
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.SWIPE_BACK)
        now+=HudStore.MEDIA_KEY_GAP_MS
        assertEquals(true,hud.onKey(GlassesKey.DOUBLE_TAP))
        assertEquals(false,hud.state.value.t3Page)
        now+=HudStore.MEDIA_KEY_GAP_MS
        assertEquals(false,hud.onKey(GlassesKey.DOUBLE_TAP))
    }

    @Test fun theTouchThatWakesTheDisplayOnlyWakesItOnEveryScreen() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onConnection(true);hud.onMessage(t3(1))
        // Home, awake: ACTIVE says the next tap starts Grok Bot.
        assertEquals(true,hud.state.value.homeActive)
        hud.onDisplayOff()
        assertEquals(false,hud.state.value.homeActive)
        // The waking tap sends nothing; its second report by another input path is dropped too.
        hud.onKey(GlassesKey.TAP);now+=100;hud.onKey(GlassesKey.TAP)
        assertEquals(emptyList<GlassesAction>(),sent)
        assertEquals(true,hud.state.value.homeActive)
        now+=HudStore.WAKE_GAP_MS;hud.onKey(GlassesKey.TAP)
        assertEquals(ActionType.OPEN_GROK_BOT,sent.single().action)
        sent.clear()
        // The T3 page: the waking tap only wakes, the next tap starts dictation to T3 with the usual voice steps.
        hud.onMessage(com.rokidos885.protocol.VoiceState(2,com.rokidos885.protocol.VoicePhase.IDLE,com.rokidos885.protocol.VOICE_COMMAND_ID))
        now+=HudStore.WAKE_GAP_MS;hud.onKey(GlassesKey.SWIPE_BACK)
        hud.onDisplayOff()
        assertEquals(true,hud.state.value.t3Page)
        now+=HudStore.WAKE_GAP_MS;hud.onKey(GlassesKey.TAP)
        assertEquals(emptyList<GlassesAction>(),sent)
        now+=HudStore.WAKE_GAP_MS;hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(GlassesAction(0,ActionType.VOICE_START,com.rokidos885.protocol.T3_CARD_ID)),sent)
        assertEquals(com.rokidos885.protocol.VoicePhase.STARTING,hud.state.value.voice?.phase)
    }

    @Test fun awakeCountsDownFromFourThenTheHudLetsTheDisplaySleep() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.awakeMillis=15_000
        hud.onConnection(true);hud.onMessage(t3(1))
        hud.onKey(GlassesKey.SWIPE_BACK)
        now+=10_000;hud.tick()
        assertEquals(0,hud.state.value.sleepIn)
        // News from T3 starts the awake time again.
        hud.onMessage(t3(2,com.rokidos885.protocol.T3State.WORKING))
        now+=11_000;hud.tick()
        assertEquals(4,hud.state.value.sleepIn)
        now+=1_000;hud.tick();assertEquals(3,hud.state.value.sleepIn)
        now+=2_000;hud.tick();assertEquals(1,hud.state.value.sleepIn)
        now+=1_000;hud.tick()
        assertEquals(false,hud.state.value.awake)
        assertEquals(0,hud.state.value.sleepIn)
        // The page stays across sleep, at full brightness: only its footer changes ("TAP to wake").
        assertEquals(true,hud.state.value.t3Visible)
        // A trip holds the display on, so it never counts down while guiding.
        now+=HudStore.WAKE_GAP_MS;hud.onKey(GlassesKey.TAP)
        for(seq in 3L..14L){hud.onMessage(com.rokidos885.protocol.NavStateUpdate(seq,com.rokidos885.protocol.NavState.ACTIVE));now+=5_000;hud.tick()}
        assertEquals(true,hud.state.value.awake)
        assertEquals(0,hud.state.value.sleepIn)
    }

    @Test fun onATripTapStartsGrokBotAndSwipeForwardStepsTheView() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onConnection(true)
        hud.onMessage(com.rokidos885.protocol.NavStateUpdate(1,com.rokidos885.protocol.NavState.ACTIVE))
        hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(HudMode.Map,hud.state.value.mode)
        // Swipe back is free: no zoom, no Grok Bot, no T3 page.
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.SWIPE_BACK)
        assertEquals(emptyList<GlassesAction>(),sent)
        assertEquals(false,hud.state.value.t3Page)
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.TAP)
        assertEquals(ActionType.OPEN_GROK_BOT,sent.single().action)
        // On the Grok Bot card only a tap starts or ends Grok Bot again; a swipe never does.
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.SWIPE_BACK)
        assertEquals(1,sent.size)
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.TAP)
        assertEquals(2,sent.count {it.action==ActionType.OPEN_GROK_BOT})
    }

    @Test fun aVisibleNotificationRedrawsEverySecondForItsCountdown() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(com.rokidos885.protocol.PhoneNotification(1,"n1","Messages","Sam","On my way"))
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
        assertEquals(true,hud.state.value===quiet)
    }

    @Test fun aRestartedPhoneCountingFromOneIsHeardAfterANewSessionStarts() {
        val hud=HudStore {sent+=it}
        hud.onMessage(com.rokidos885.protocol.PhoneNotification(500,"old","App","Old","Before the restart"))
        // The phone app restarts and counts from 1 again: its messages look old and are dropped...
        hud.onMessage(com.rokidos885.protocol.PhoneNotification(2,"n1","Messages","Sam","After"))
        assertEquals("Old",hud.state.value.notification?.title)
        // ...until its new session says hello, which restarts the numbering.
        hud.resetSequence()
        hud.onMessage(com.rokidos885.protocol.PhoneNotification(2,"n1","Messages","Sam","After"))
        assertEquals("Sam",hud.state.value.notification?.title)
    }

    @Test fun thePlayerNeverAppearsOnItsOwnAndNotificationsAndMediaStaySeparate() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(media(1))
        assertEquals(false,hud.state.value.mediaVisible)
        // A card arrives while the player is up: the player goes away and does not return after the card.
        hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(true,hud.state.value.mediaVisible)
        hud.onMessage(com.rokidos885.protocol.PhoneNotification(2,"n1","Messages","Sam","Hi"))
        assertEquals(false,hud.state.value.mediaVisible)
        now+=HudStore.NOTIFICATION_MAX_MS;hud.tick()
        assertNull(hud.state.value.notification)
        assertEquals(false,hud.state.value.mediaVisible)
    }

    @Test fun theFirstSwipeOnlyShowsThePlayerAndItHidesAfterTenSeconds() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onConnection(true);hud.onMessage(media(1))
        hud.onKey(GlassesKey.SWIPE_FORWARD);hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(true,hud.state.value.mediaVisible)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.MEDIA_PLAY_PAUSE),sent.map {it.action})
        now+=HudStore.MEDIA_SHOW_MS-1_000;hud.tick()
        assertEquals(true,hud.state.value.mediaVisible)
        now+=2_000;hud.tick()
        assertEquals(false,hud.state.value.mediaVisible)
    }

    @Test fun tapOnTheEmptyIdleScreenStartsGrokBotEvenWithMusicPlaying() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        // No phone link: nothing would answer, so the tap does nothing.
        hud.onKey(GlassesKey.TAP)
        assertNull(hud.state.value.voice)
        hud.onConnection(true);hud.onMessage(media(1))
        // Music plays, the player is hidden: a tap (reported twice) starts Grok Bot once, not the player.
        hud.onKey(GlassesKey.TAP);now+=15;hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.OPEN_GROK_BOT),sent.map {it.action})
        assertEquals(com.rokidos885.protocol.VOICE_COMMAND_ID,sent.single().targetId)
        assertEquals(com.rokidos885.protocol.VoicePhase.STARTING,hud.state.value.voice?.phase)
        assertEquals(false,hud.state.value.mediaVisible)
        // No speech step: the phone's answer shows and goes away.
        hud.onMessage(com.rokidos885.protocol.VoiceState(3,com.rokidos885.protocol.VoicePhase.SENT,com.rokidos885.protocol.VOICE_COMMAND_ID,"Opening Grok Bot on the phone"))
        assertEquals(com.rokidos885.protocol.VoicePhase.SENT,hud.state.value.voice?.phase)
        now+=HudStore.REPLY_RESULT_MS;hud.tick()
        assertNull(hud.state.value.voice)
        // On a trip the map is home: a tap starts Grok Bot there too, and the view does not change.
        sent.clear();hud.onMessage(com.rokidos885.protocol.NavStateUpdate(4,com.rokidos885.protocol.NavState.ACTIVE))
        now+=1_000;hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.OPEN_GROK_BOT),sent.map {it.action})
        assertEquals(HudMode.Compact,hud.state.value.mode)
    }

    @Test fun aRepeatedTapNeverStartsACommand() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onConnection(true)
        // The tap that closes a card arrives again by another input path.
        hud.onMessage(replyCard(canReply=false));hud.onKey(GlassesKey.TAP);now+=40;hud.onKey(GlassesKey.TAP)
        // The tap that closes a reply's voice result does too.
        hud.onMessage(com.rokidos885.protocol.VoiceState(cardSeq++,com.rokidos885.protocol.VoicePhase.FAILED,"n1","The app refused the reply"))
        now+=1_000;hud.onKey(GlassesKey.TAP);now+=40;hud.onKey(GlassesKey.TAP)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
        // The next real tap does.
        now+=1_000;hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.OPEN_GROK_BOT),sent.map {it.action})
    }

    @Test fun aRunningGrokBotChatIsKnownUntilItEndsOrTheLinkDrops() {
        val hud=HudStore({1_000L}) {sent+=it}
        hud.onConnection(true)
        hud.onMessage(com.rokidos885.protocol.GrokBotState(1,true))
        assertEquals(true,hud.state.value.grokBotActive)
        // The tap on the empty screen still goes to the phone, which closes the chat.
        hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.OPEN_GROK_BOT),sent.map {it.action})
        hud.onMessage(com.rokidos885.protocol.GrokBotState(2,false))
        assertEquals(false,hud.state.value.grokBotActive)
        // Without the link nobody can say it ended: the display is not held for it.
        hud.onMessage(com.rokidos885.protocol.GrokBotState(3,true));hud.onConnection(false)
        assertEquals(false,hud.state.value.grokBotActive)
    }

    @Test fun aTapOnTheGrokBotCardStartsOrClosesGrokBotAgain() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onConnection(true)
        hud.onKey(GlassesKey.TAP)
        // Within the 2 s the answer shows, the next tap (reported twice) goes to the phone again, once.
        hud.onMessage(com.rokidos885.protocol.VoiceState(1,com.rokidos885.protocol.VoicePhase.SENT,com.rokidos885.protocol.VOICE_COMMAND_ID,"Opening Grok Bot on the phone"))
        now+=1_000;hud.onKey(GlassesKey.TAP);now+=15;hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.OPEN_GROK_BOT,ActionType.OPEN_GROK_BOT),sent.map {it.action})
        assertEquals(com.rokidos885.protocol.VoicePhase.STARTING,hud.state.value.voice?.phase)
        // Double tap still only closes the card.
        hud.onMessage(com.rokidos885.protocol.VoiceState(2,com.rokidos885.protocol.VoicePhase.SENT,com.rokidos885.protocol.VOICE_COMMAND_ID,"Closing Grok Bot"))
        now+=1_000;hud.onKey(GlassesKey.DOUBLE_TAP)
        assertNull(hud.state.value.voice)
        assertEquals(2,sent.size)
    }

    @Test fun withMusicAndThePlayerHiddenTheFirstTapStartsGrokBot() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onConnection(true);hud.onMessage(media(1))
        hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(true,hud.state.value.mediaVisible)
        now+=HudStore.MEDIA_SHOW_MS;hud.tick()
        hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.OPEN_GROK_BOT),sent.map {it.action})
        assertEquals(com.rokidos885.protocol.VOICE_COMMAND_ID,sent.single().targetId)
        assertEquals(false,hud.state.value.mediaVisible)
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.DOUBLE_TAP)
        now+=HudStore.MEDIA_KEY_GAP_MS;hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(true,hud.state.value.mediaVisible)
        assertEquals(listOf(ActionType.OPEN_GROK_BOT,ActionType.VOICE_CANCEL),sent.map {it.action})
    }

    @Test fun onATripEachGestureCountsOnceAndADetourKeepsTheScreen() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onConnection(true);hud.onMessage(com.rokidos885.protocol.NavStateUpdate(1,com.rokidos885.protocol.NavState.ACTIVE))
        // Swipe forward, reported by three input paths, steps the view once; zoom stays with the map and the phone.
        hud.onKey(GlassesKey.SWIPE_FORWARD);now+=15;hud.onKey(GlassesKey.SWIPE_FORWARD);now+=40;hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(HudMode.Map,hud.state.value.mode)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
        // Tap, reported twice, starts Grok Bot once; double tap closes its card and stays in the app.
        now+=1_000;hud.onKey(GlassesKey.TAP);now+=15;hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.OPEN_GROK_BOT),sent.map {it.action})
        assertEquals(com.rokidos885.protocol.VOICE_COMMAND_ID,sent.single().targetId)
        now+=1_000;assertEquals(true,hud.onKey(GlassesKey.DOUBLE_TAP))
        assertEquals(ActionType.VOICE_CANCEL,sent.last().action)
        // With nothing on screen, double tap leaves the app.
        now+=1_000;assertEquals(false,hud.onKey(GlassesKey.DOUBLE_TAP))
        // A detour offer: tap takes it; swipe back does nothing.
        hud.onMessage(DetourOffer(2,"d1",300,0,"via A",expiresAtMs=Long.MAX_VALUE))
        sent.clear();now+=1_000;hud.onKey(GlassesKey.SWIPE_BACK)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
        now+=1_000;hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.ACCEPT_DETOUR),sent.map {it.action})
    }

    @Test fun doubleTapCancelsACommandAndStaysInTheApp() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onConnection(true);hud.onKey(GlassesKey.TAP)
        now+=1_000
        assertEquals(true,hud.onKey(GlassesKey.DOUBLE_TAP))
        assertEquals(ActionType.VOICE_CANCEL,sent.last().action)
        assertNull(hud.state.value.voice)
    }

    private var cardSeq=100L
    private fun replyCard(id:String="n1",canReply:Boolean=true)=com.rokidos885.protocol.PhoneNotification(cardSeq++,id,"WhatsApp","Sam","Where are you?",canReply=canReply)

    @Test fun tapOnAReplyableCardStartsAVoiceReplyAndOtherCardsJustClose() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(replyCard("n1",canReply=false))
        hud.onKey(GlassesKey.TAP)
        assertNull(hud.state.value.notification)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})

        now+=1_000
        hud.onMessage(com.rokidos885.protocol.PhoneNotification(cardSeq++,"n2","WhatsApp","Sam","Hi",canReply=true))
        hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.VOICE_START),sent.map {it.action})
        assertEquals("n2",sent.single().targetId)
        assertEquals(com.rokidos885.protocol.VoicePhase.STARTING,hud.state.value.voice?.phase)
        // A trip makes cards glances: no reply, the tap only closes it.
        val trip=HudStore({now}) {sent+=it}
        trip.onMessage(com.rokidos885.protocol.NavStateUpdate(1,com.rokidos885.protocol.NavState.ACTIVE))
        trip.onMessage(replyCard("n3"))
        sent.clear();trip.onKey(GlassesKey.TAP)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
    }

    @Test fun aSwipeClosesANotificationCardWithoutStartingAReply() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(replyCard("n1",canReply=true))
        hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertNull(hud.state.value.notification)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})       // not a reply, not Grok Bot
        now+=1_000
        hud.onMessage(replyCard("n2",canReply=true))
        hud.onKey(GlassesKey.SWIPE_BACK)
        assertNull(hud.state.value.notification)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
    }

    @Test fun aSwipeOnATripClosesTheCardInsteadOfSteppingTheZoomOrStartingGrokBot() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onConnection(true)
        hud.onMessage(com.rokidos885.protocol.NavStateUpdate(1,com.rokidos885.protocol.NavState.ACTIVE))
        hud.onMessage(replyCard("n1",canReply=true))
        val zoom=hud.state.value.mapZoom
        // The same swipe arrives by two input paths: it closes the card once and never steps the zoom.
        hud.onKey(GlassesKey.SWIPE_FORWARD);now+=15;hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertNull(hud.state.value.notification)
        assertEquals(zoom,hud.state.value.mapZoom)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
        now+=1_000
        hud.onMessage(replyCard("n2",canReply=true))
        hud.onKey(GlassesKey.SWIPE_BACK);now+=15;hud.onKey(GlassesKey.SWIPE_BACK)
        assertNull(hud.state.value.notification)
        assertNull(hud.state.value.voice)                               // no Grok Bot card either
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
    }

    @Test fun aSwipeThatClosedACardDoesNotRevealThePlayerWhenItArrivesAgain() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(media(1))
        hud.onMessage(replyCard("n1",canReply=false))
        hud.onKey(GlassesKey.SWIPE_FORWARD);now+=15;hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertNull(hud.state.value.notification)
        assertEquals(false,hud.state.value.mediaVisible)
    }

    @Test fun reviewScreenSwipesBetweenSendRedoCancelAndTapActsOnTheFocusedRow() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(replyCard());hud.onKey(GlassesKey.TAP);sent.clear()
        hud.onMessage(com.rokidos885.protocol.VoiceState(cardSeq++,com.rokidos885.protocol.VoicePhase.REVIEW,"n1","I am on my way."))
        assertEquals(0,hud.state.value.voiceFocus)
        // Send is first: tap sends.
        now+=1_000;hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.VOICE_SEND),sent.map {it.action})
        // Move to Redo (a swipe reported twice counts once), tap: listen again.
        now+=1_000;hud.onKey(GlassesKey.SWIPE_FORWARD);hud.onKey(GlassesKey.SWIPE_FORWARD)
        assertEquals(1,hud.state.value.voiceFocus)
        now+=1_000;hud.onKey(GlassesKey.TAP)
        assertEquals(ActionType.VOICE_REDO,sent.last().action)
        assertEquals(com.rokidos885.protocol.VoicePhase.STARTING,hud.state.value.voice?.phase)
        // Back in review, swipe back wraps to Cancel; tap cancels and closes the card.
        hud.onMessage(com.rokidos885.protocol.VoiceState(cardSeq++,com.rokidos885.protocol.VoicePhase.REVIEW,"n1","Again."))
        now+=1_000;hud.onKey(GlassesKey.SWIPE_BACK)
        assertEquals(2,hud.state.value.voiceFocus)
        now+=1_000;hud.onKey(GlassesKey.TAP)
        assertEquals(ActionType.VOICE_CANCEL,sent.last().action)
        assertNull(hud.state.value.voice)
        assertNull(hud.state.value.notification)
    }

    @Test fun listeningTapStopsDoubleTapCancelsAndDoubleTapIsNotLeftToTheSystem() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(replyCard());hud.onKey(GlassesKey.TAP);sent.clear()
        // Until the microphone is live a tap does nothing; then it stops listening.
        now+=1_000;hud.onKey(GlassesKey.TAP)
        assertEquals(emptyList<ActionType>(),sent.map {it.action})
        hud.onMessage(com.rokidos885.protocol.VoiceState(cardSeq++,com.rokidos885.protocol.VoicePhase.LISTENING,"n1"))
        now+=1_000;hud.onKey(GlassesKey.TAP)
        assertEquals(listOf(ActionType.VOICE_STOP),sent.map {it.action})
        now+=1_000
        assertEquals(true,hud.onKey(GlassesKey.DOUBLE_TAP))
        assertEquals(ActionType.VOICE_CANCEL,sent.last().action)
        assertNull(hud.state.value.voice)
    }

    @Test fun theCardStaysWhileAReplyIsUnderWayAndClosesAfterTheResult() {
        var now=1_000L
        val hud=HudStore({now}) {sent+=it}
        hud.onMessage(replyCard());hud.onKey(GlassesKey.TAP)
        now+=HudStore.NOTIFICATION_MAX_MS*3;hud.tick()
        assertEquals("n1",hud.state.value.notification?.id)
        hud.onMessage(com.rokidos885.protocol.VoiceState(cardSeq++,com.rokidos885.protocol.VoicePhase.SENT,"n1"))
        now+=HudStore.REPLY_RESULT_MS-100;hud.tick()
        assertEquals("n1",hud.state.value.notification?.id)
        now+=200;hud.tick()
        assertNull(hud.state.value.voice)
        assertNull(hud.state.value.notification)
        // A card that arrives mid-reply does not displace it.
        hud.onMessage(replyCard("n2"));hud.onKey(GlassesKey.TAP)
        hud.onMessage(replyCard("n3"))
        assertEquals("n2",hud.state.value.notification?.id)
    }
}
