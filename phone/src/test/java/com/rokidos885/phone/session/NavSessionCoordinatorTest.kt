package com.rokidos885.phone.session

import android.app.Activity
import com.rokidos885.phone.alerts.*
import com.rokidos885.phone.glasses.*
import com.rokidos885.phone.nav.*
import com.rokidos885.phone.traffic.*
import com.rokidos885.protocol.*
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
        override val audio = emptyFlow<ByteArray>()
        override fun startAudio() = false
        override fun stopAudio() {}
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
    private class FakeMedia : com.rokidos885.phone.media.MediaSource {
        val flow = MutableStateFlow<com.rokidos885.phone.media.MediaInfo?>(null)
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
        media.flow.value = com.rokidos885.phone.media.MediaInfo("Spotify", "Song", "Artist", true, 10_000, 200_000); runCurrent()
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
    @Test fun openGrokBotActionOpensItAndAnswersWithTheResult() = runTest {
        val engine = Engine()
        val link = Link()
        var problem: String? = null
        var opened = 0
        var chat = false
        val coordinator = NavSessionCoordinator(backgroundScope, link, engine, TrafficMonitor(RoutesApiClient(""), engine), noIncidents,
            openGrokBot = { opened++; chat = !chat; problem }, grokBotRunning = { chat })
        coordinator.start(); runCurrent()
        link.incoming.emit(GlassesAction(1, ActionType.OPEN_GROK_BOT, VOICE_COMMAND_ID)); runCurrent()
        assertEquals(1, opened)
        assertEquals(VoiceState(0, VoicePhase.SENT, VOICE_COMMAND_ID, "Opening Grok Bot on the phone"), link.sent.filterIsInstance<VoiceState>().last().copy(seq = 0))
        // The same gesture during a chat closes it, and the glasses say so (the state is read before the start).
        link.incoming.emit(GlassesAction(2, ActionType.OPEN_GROK_BOT, VOICE_COMMAND_ID)); runCurrent()
        assertEquals(VoiceState(0, VoicePhase.SENT, VOICE_COMMAND_ID, "Closing Grok Bot"), link.sent.filterIsInstance<VoiceState>().last().copy(seq = 0))
        chat = false
        problem = "Unlock the phone for Grok Bot"
        link.incoming.emit(GlassesAction(2, ActionType.OPEN_GROK_BOT, VOICE_COMMAND_ID)); runCurrent()
        assertEquals(VoiceState(0, VoicePhase.FAILED, VOICE_COMMAND_ID, "Unlock the phone for Grok Bot"), link.sent.filterIsInstance<VoiceState>().last().copy(seq = 0))
        // No opener (tests, a phone without the setup): a reason, not a crash.
        val bare = Link()
        val plain = NavSessionCoordinator(backgroundScope, bare, engine, TrafficMonitor(RoutesApiClient(""), engine), noIncidents)
        plain.start(); runCurrent()
        bare.incoming.emit(GlassesAction(3, ActionType.OPEN_GROK_BOT, VOICE_COMMAND_ID)); runCurrent()
        assertEquals(VoicePhase.FAILED, bare.sent.filterIsInstance<VoiceState>().last().phase)
    }
    @Test fun tellsTheGlassesWhileAGrokBotChatRuns() = runTest {
        val engine = Engine()
        val link = Link()
        var chat = false
        val coordinator = NavSessionCoordinator(backgroundScope, link, engine, TrafficMonitor(RoutesApiClient(""), engine), noIncidents,
            openGrokBot = { null }, grokBotRunning = { chat })
        coordinator.start(); runCurrent()
        // The connect resync says "no chat"; only the changes after the tap count here.
        link.sent.clear()
        fun states() = link.sent.filterIsInstance<GrokBotState>().map { it.active }
        link.incoming.emit(GlassesAction(1, ActionType.OPEN_GROK_BOT, VOICE_COMMAND_ID)); runCurrent()
        assertEquals(emptyList<Boolean>(), states())
        // Grok Bot takes about a second to start its chat.
        chat = true; advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf(true), states())
        // A reconnect says it again.
        link.sent.clear(); link.incoming.emit(ResyncRequest(2)); runCurrent()
        assertEquals(listOf(true), states())
        // The chat ends (on the phone or by a tap): the glasses hear it once, then the polling stops.
        link.sent.clear(); chat = false; advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf(false), states())
        chat = true; advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(false), states())
    }
    @Test fun duringAnotherAppsCallTheChatIsTheExtraCallRecording() = runTest {
        val engine = Engine()
        val link = Link()
        var opened = 0
        // A WhatsApp call holds the call mode and one call recording; Android does not say which app holds them.
        var recordings = 1
        val coordinator = NavSessionCoordinator(backgroundScope, link, engine, TrafficMonitor(RoutesApiClient(""), engine), noIncidents,
            openGrokBot = { opened++; null }, grokBotRunning = { true }, callRecordings = { recordings })
        coordinator.start(); runCurrent(); link.sent.clear()
        fun states() = link.sent.filterIsInstance<GrokBotState>().map { it.active }
        fun said() = link.sent.filterIsInstance<VoiceState>().last().text
        link.incoming.emit(GlassesAction(1, ActionType.OPEN_GROK_BOT, VOICE_COMMAND_ID)); runCurrent()
        assertEquals("Opening Grok Bot on the phone", said())
        assertEquals("the call alone is not a chat", emptyList<Boolean>(), states())
        // Grok Bot starts its chat: one more call recording.
        recordings = 2; advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf(true), states())
        // The tap that ends it says so; the extra recording goes and the glasses hear it.
        link.incoming.emit(GlassesAction(2, ActionType.OPEN_GROK_BOT, VOICE_COMMAND_ID)); runCurrent()
        assertEquals("Closing Grok Bot", said())
        recordings = 1; advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf(true, false), states())
        assertEquals(2, opened)
        // The next tap, still in the call, opens again; a Grok Bot that never starts shows no chat.
        link.incoming.emit(GlassesAction(3, ActionType.OPEN_GROK_BOT, VOICE_COMMAND_ID)); advanceTimeBy(30_000); runCurrent()
        assertEquals("Opening Grok Bot on the phone", said())
        assertEquals(listOf(true, false), states())
        // A reconnect clears a HUD that still shows a chat from before.
        link.sent.clear(); link.incoming.emit(ResyncRequest(4)); runCurrent()
        assertEquals(listOf(false), states())
    }
    @Test fun messageNumbersStartFromTheGivenCounterStartSoARestartedPhoneCountsHigher() = runTest {
        val engine = Engine()
        val link = Link()
        // The real start is the clock; a restart is later than the last run, so its numbers are higher.
        val coordinator = NavSessionCoordinator(backgroundScope, link, engine, TrafficMonitor(RoutesApiClient(""), engine), noIncidents, seqStart = 5_000_000_000L)
        coordinator.start(); runCurrent()
        coordinator.forwardNotification("n1", "App", "Title", "Text", 0)
        assertTrue(link.sent.isNotEmpty())
        assertTrue(link.sent.all { it.seq > 5_000_000_000L })
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
        // Closer: the glasses count down themselves, so no new alert message.
        engine.position.emit(fix(0.005)); runCurrent()
        assertEquals(1, link.sent.filterIsInstance<Alert>().size)
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
