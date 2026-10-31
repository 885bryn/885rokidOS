package com.rokidnav.phone.session

import android.app.Activity
import com.rokidnav.phone.alerts.*
import com.rokidnav.phone.glasses.*
import com.rokidnav.phone.nav.*
import com.rokidnav.phone.traffic.*
import com.rokidnav.protocol.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavSessionCoordinatorTest {
    private class Engine : NavigationEngine {
        override val navState = MutableStateFlow(NavState.ACTIVE)
        override val position = MutableSharedFlow<PositionUpdate>(replay = 1)
        override val maneuver = emptyFlow<ManeuverUpdate>()
        override val progress = emptyFlow<TripProgress>()
        override val destination = MutableStateFlow<Destination?>(null)
        override val activeRoute = MutableStateFlow<ActiveRoute?>(ActiveRoute("r1",
            PolylineCodec.encode(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.05))), 5560))
        override suspend fun initialize(activity: Activity) = true
        override suspend fun start(destination: Destination, option: RouteOption?) = true
        var switched:RouteOption?=null
        var switchSucceeds=true
        override suspend fun switchRoute(option: RouteOption):Boolean {switched=option;return switchSucceeds}
        override fun setVoiceMuted(muted: Boolean) {}
        override fun simulate(enabled: Boolean) {}
        override fun stop() { activeRoute.value = null; navState.value = NavState.ENDED }
    }
    private class Link : GlassesLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Ready)
        override val incoming = MutableSharedFlow<NavMessage>()
        val sent = mutableListOf<NavMessage>()
        override fun checkEnvironment() = EnvironmentStatus.Ok
        override fun authorize(activity: Activity) {}
        var connects = 0
        var disconnects = 0
        override fun connect() { connects++; state.value = LinkState.Ready }
        override fun send(message: NavMessage): Boolean {
            if (state.value != LinkState.Ready) return false
            val (magic, json) = ProtocolCodec.encode(message)
            sent += requireNotNull(ProtocolCodec.decode(magic, json))
            return true
        }
        override fun disconnect() { disconnects++; state.value = LinkState.Authorized }
    }
    @Test fun keepsTheGlassesSessionOpenWithoutATrip() = runTest {
        val engine = Engine()
        val link = Link()
        val coordinator = NavSessionCoordinator(backgroundScope, link, engine, TrafficMonitor(RoutesApiClient(""), engine), noIncidents)
        coordinator.start()
        runCurrent()
        engine.stop(); runCurrent()
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertEquals("no trip does not close the session", 0, link.disconnects)
        // A user Disconnect stays until the app is opened again or a trip starts.
        link.disconnect(); runCurrent()
        assertEquals(LinkState.Authorized, link.state.value)
        coordinator.onAppOpened(); runCurrent()
        assertEquals(1, link.connects)
        link.state.value = LinkState.Closed(LinkState.Closed.HUD_CLOSED, needsReauth = false); runCurrent()
        engine.navState.value = NavState.ACTIVE; runCurrent()
        assertEquals(2, link.connects)
        assertEquals(LinkState.Ready, link.state.value)
    }
    @Test fun connectsRightAfterAuthorizationButNotAfterReauthClose() = runTest {
        val engine = Engine().apply { navState.value = NavState.IDLE }
        val link = Link().apply { state.value = LinkState.Authorizing }
        val coordinator = NavSessionCoordinator(backgroundScope, link, engine, TrafficMonitor(RoutesApiClient(""), engine), noIncidents)
        coordinator.start(); runCurrent()
        link.state.value = LinkState.Authorized; runCurrent()
        assertEquals(1, link.connects)
        link.state.value = LinkState.Closed("CHAIN_TORN", needsReauth = true); runCurrent()
        coordinator.onAppOpened(); runCurrent()
        assertEquals(1, link.connects)
    }
    private class FakeMedia : com.rokidnav.phone.media.MediaSource {
        val flow = MutableStateFlow<com.rokidnav.phone.media.MediaInfo?>(null)
        override val info = flow
        val calls = mutableListOf<String>()
        override fun playPause() { calls += "playPause" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
    }
    @Test fun sendsMediaStateAndResyncsItAndRoutesGlassesControls() = runTest {
        val engine = Engine()
        val link = Link()
        val media = FakeMedia()
        val coordinator = NavSessionCoordinator(backgroundScope, link, engine, TrafficMonitor(RoutesApiClient(""), engine), noIncidents, media = media)
        coordinator.start(); runCurrent()
        media.flow.value = com.rokidnav.phone.media.MediaInfo("Spotify", "Song", "Artist", true, 10_000, 200_000); runCurrent()
        assertEquals("Song", link.sent.filterIsInstance<MediaState>().last().title)
        // Reconnect: the same track comes again, with the playing time added.
        link.sent.clear()
        link.state.value = LinkState.Paused("bluetooth"); runCurrent()
        link.state.value = LinkState.Ready; runCurrent()
        val again = link.sent.filterIsInstance<MediaState>().single()
        assertEquals("Song", again.title)
        assertTrue(again.positionMs >= 10_000)
        media.flow.value = null; runCurrent()
        assertEquals(false, link.sent.filterIsInstance<MediaState>().last().active)
        link.incoming.emit(GlassesAction(1, ActionType.MEDIA_PLAY_PAUSE))
        link.incoming.emit(GlassesAction(2, ActionType.MEDIA_NEXT))
        link.incoming.emit(GlassesAction(3, ActionType.MEDIA_PREVIOUS))
        runCurrent()
        assertEquals(listOf("playPause", "next", "previous"), media.calls)
    }
    private val noIncidents = object : IncidentProvider {
        override val name = "none"
        override fun incidentsAlong(routePolyline: String) = flowOf(emptyList<Incident>())
    }
    @Test fun sendsCountdownClearsWhileDisconnectedAndResyncsClearOnReconnect() = runTest {
        val engine = Engine()
        val link = Link()
        val provider = object : IncidentProvider {
            override val name = "fake"
            override fun incidentsAlong(routePolyline: String) = flowOf(listOf(
                Incident("p1", AlertKind.POLICE, LatLng(0.0, 0.01), "Police reported", 0, "WzSabre")))
        }
        NavSessionCoordinator(backgroundScope, link, engine,
            TrafficMonitor(RoutesApiClient(""), engine), provider).start()
        runCurrent()
        fun fix(lon: Double) = PositionUpdate(0, 0.0, lon, 90f, 10f, timestampMs = System.currentTimeMillis())
        engine.position.emit(fix(0.0)); runCurrent()
        assertEquals(1112, link.sent.filterIsInstance<Alert>().last().distanceAheadMeters)
        engine.position.emit(fix(0.005)); runCurrent()
        assertEquals(556, link.sent.filterIsInstance<Alert>().last().distanceAheadMeters)
        link.state.value = LinkState.Paused("bluetooth"); runCurrent()
        engine.position.emit(fix(0.02)); runCurrent()
        assertTrue(link.sent.filterIsInstance<AlertCleared>().isEmpty())
        link.sent.clear()
        link.state.value = LinkState.Ready; runCurrent()
        assertEquals("p1", link.sent.filterIsInstance<AlertCleared>().single().id)
        assertTrue(link.sent.none { it is Alert })
        engine.position.emit(fix(0.0)); runCurrent()
        link.sent.clear()
        engine.stop(); runCurrent()
        assertEquals("p1", link.sent.filterIsInstance<AlertCleared>().single().id)
    }
    @Test fun speaksEachNewReportOnceWhenInRangeEvenWithTheGlassesDisconnected() = runTest {
        val engine = Engine()
        val link = Link()
        val spoken = mutableListOf<String>()
        val speaker = object : AlertSpeaker { override fun speak(text: String) { spoken += text }; override fun shutdown() {} }
        val provider = object : IncidentProvider {
            override val name = "fake"
            override fun incidentsAlong(routePolyline: String) = flowOf(listOf(
                Incident("p1", AlertKind.POLICE, LatLng(0.0, 0.015), "Police reported", 0, "WzSabre")))
        }
        NavSessionCoordinator(backgroundScope, link, engine,
            TrafficMonitor(RoutesApiClient(""), engine), provider, speaker = speaker).start()
        runCurrent()
        fun fix(lon: Double) = PositionUpdate(0, 0.0, lon, 90f, 10f, timestampMs = System.currentTimeMillis())
        engine.position.emit(fix(0.0)); runCurrent()          // 1.67 km ahead: beyond the 1 km default
        assertTrue(spoken.isEmpty())
        link.state.value = LinkState.Paused("bluetooth"); runCurrent()
        engine.position.emit(fix(0.007)); runCurrent()        // 890 m: in range, glasses paused
        assertEquals(listOf("Police reported ahead in 900 metres."), spoken)
        engine.position.emit(fix(0.012)); runCurrent()        // still active: not repeated
        assertEquals(1, spoken.size)
    }
    @Test fun detourExpiryRejectsOldActionsAndAcceptsOnlyCurrentOffer() = runTest {
        val engine=Engine();val link=Link()
        val provider=object:IncidentProvider {
            override val name="none"
            override fun incidentsAlong(routePolyline:String)=flowOf(emptyList<Incident>())
        }
        val coordinator=NavSessionCoordinator(backgroundScope,link,engine,TrafficMonitor(RoutesApiClient(""),engine),provider)
        coordinator.start();runCurrent()
        val option=RouteOption("token","??",1000,300,250,"via A",emptyList())
        coordinator.offerDetour(DetourEvaluator.Decision(option,300,100));runCurrent()
        val old=coordinator.detour.value!!
        advanceTimeBy(30_001);runCurrent()
        assertNull(coordinator.detour.value)
        assertEquals(false,link.sent.filterIsInstance<DetourResolved>().last().accepted)
        link.incoming.emit(GlassesAction(1,ActionType.ACCEPT_DETOUR,old.offerId));runCurrent()
        assertNull(engine.switched)
        coordinator.offerDetour(DetourEvaluator.Decision(option,300,100));runCurrent()
        val current=coordinator.detour.value!!
        link.incoming.emit(GlassesAction(2,ActionType.DISMISS_DETOUR,old.offerId));runCurrent()
        assertNotNull(coordinator.detour.value)
        link.incoming.emit(GlassesAction(3,ActionType.ACCEPT_DETOUR,current.offerId));runCurrent()
        assertEquals(option,engine.switched)
        assertEquals(true,link.sent.filterIsInstance<DetourResolved>().last().accepted)
        assertNull(coordinator.detour.value)
    }
    @Test fun failedRouteSwitchResolvesAsRejectedAndResyncSequenceAlwaysIncreases() = runTest {
        val engine=Engine().apply {switchSucceeds=false};val link=Link()
        val provider=object:IncidentProvider {override val name="none";override fun incidentsAlong(routePolyline:String)=flowOf(emptyList<Incident>())}
        val coordinator=NavSessionCoordinator(backgroundScope,link,engine,TrafficMonitor(RoutesApiClient(""),engine),provider)
        coordinator.start();runCurrent()
        coordinator.offerDetour(DetourEvaluator.Decision(RouteOption("token","??",1000,300,250,"A",emptyList()),300,100));runCurrent()
        link.incoming.emit(GlassesAction(1,ActionType.ACCEPT_DETOUR,coordinator.detour.value!!.offerId));runCurrent()
        assertEquals(false,link.sent.filterIsInstance<DetourResolved>().last().accepted)
        link.state.value=LinkState.Paused("assistant");runCurrent()
        link.state.value=LinkState.Ready;runCurrent()
        val sequences=link.sent.map {it.seq}
        assertTrue(sequences.zipWithNext().all {(a,b)->b>a})
    }
}
