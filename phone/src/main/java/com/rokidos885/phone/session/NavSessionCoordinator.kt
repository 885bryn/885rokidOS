package com.rokidos885.phone.session

import com.rokidos885.phone.alerts.*
import com.rokidos885.phone.glasses.*
import com.rokidos885.phone.nav.*
import com.rokidos885.phone.traffic.*
import com.rokidos885.phone.settings.*
import com.rokidos885.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Serial main-thread coordinator. Cached messages get fresh sequence numbers on reconnect. */
class NavSessionCoordinator(private val scope:CoroutineScope,private val link:GlassesLink,
    private val engine:NavigationEngine,private val traffic:TrafficMonitor,private val incidents:IncidentProvider,
    private val roads:com.rokidos885.phone.map.RoadContextProvider?=null,
    private val settings:SettingsRepository?=null,
    private val speaker:AlertSpeaker?=null,
    private val media:com.rokidos885.phone.media.MediaSource?=null,
    transcriber:com.rokidos885.phone.voice.Transcriber?=null,
    replySender:com.rokidos885.phone.voice.ReplySender?=null,
    voiceWork:kotlin.coroutines.CoroutineContext=kotlin.coroutines.EmptyCoroutineContext,
    voiceAudio:((ByteArray)->Unit)?=null,
    private val openGrokBot:(()->String?)?=null,
    /** True while a Grok Bot chat runs, so the same gesture closes it ([com.rokidos885.phone.voice.GrokBot.running]). */
    private val grokBotRunning:()->Boolean={false},
    /** Active call recordings of every app ([com.rokidos885.phone.voice.GrokBot.callRecordings]); a Grok Bot chat adds one. */
    private val callRecordings:()->Int={0},
    /** A dictation or Grok Bot starts (its id): a T3 reply still being read must stop, or the microphone hears it. */
    private val onVoiceStart:(String)->Unit={},
    // The glasses HUD outlives the phone app and drops any message numbered at or below the last it saw, so a
    // restarted phone must count higher than before: start from the clock (a restart is always later).
    seqStart:Long=System.currentTimeMillis()) {
    private val seq=SeqCounter(seqStart)
    private val announcer=AlertAnnouncer({preferences})
    private val lastSent=linkedMapOf<String,NavMessage>()
    private val activeAlerts=linkedMapOf<String,Alert>()
    private var pending:DetourEvaluator.Decision?=null
    private var offerJob:Job?=null
    private var accepting=false
    private val offer=MutableStateFlow<DetourOffer?>(null)
    val detour=offer.asStateFlow()
    private var preferences=UserSettings()
    // Heavy speech work runs off the main thread; each step hops back to it, in order, before it is sent.
    private val voice=if(transcriber!=null && replySender!=null)com.rokidos885.phone.voice.VoiceReplyController(scope,link,transcriber,replySender,work=voiceWork,onAudio=voiceAudio) {phase,id,text->
        val message=VoiceState(seq.next(),phase,id,text)
        scope.launch {link.send(message)}
    } else null
    private var lastMedia:MediaState?=null
    private var lastMediaAt=0L
    private var grokActive=false
    private var grokWatch:Job?=null
    /** Call recordings already on at a start during another app's call; null when the call mode was free. */
    private var grokBase:Int?=null
    fun sendTestSnapshot(build:(()->Long)->List<NavMessage>) {
        if(link.state.value==LinkState.Ready)build(seq::next).forEach {link.send(it)}
    }
    fun start() {
        scope.launch {engine.destination.filterNotNull().collect {
            sendStateful("session",SessionStart(seq.next(),preferences.units,false,it.name))
        }}
        if(settings!=null)scope.launch {settings.state.collect(::applySettings)}
        scope.launch {engine.maneuver.collect {sendStateful("maneuver",it.copy(seq=seq.next()))}}
        scope.launch {engine.progress.collect {sendStateful("progress",it.copy(seq=seq.next()))}}
        scope.launch {engine.position.collect {sendStateful("position",it.copy(seq=seq.next()))}}
        scope.launch {engine.navState.collect {
            if(it==NavState.ENDED || it==NavState.ARRIVED)resolve(false)
            sendStateful("nav_state",NavStateUpdate(seq.next(),it))
        }}
        scope.launch {engine.activeRoute.filterNotNull().collect {
            lastSent.keys.removeAll {it.startsWith("road_context")};lastSent.remove("traffic");sendRoute()
        }}
        scope.launch {traffic.segments.collect {
            val id=engine.activeRoute.value?.routeId ?: return@collect
            sendStateful("traffic",TrafficSegments(seq.next(),id,it))
        }}
        scope.launch {traffic.detours.collect(::offerDetour)}
        scope.launch {
            activeGuidance().collectLatest {route ->
                val provider=roads ?: return@collectLatest
                if(route==null)return@collectLatest
                var center:LatLng?=null
                val routePoints=PolylineCodec.decode(route.encodedPolyline)
                engine.position.collect {fix ->
                    val point=LatLng(fix.lat,fix.lng)
                    if(center==null || Geo.distanceMeters(center!!,point)>=600) {
                        val started=System.currentTimeMillis()
                        val nearby=provider.roadsNear(point,routeAhead(routePoints,fix.metersAlongRoute,2_000))
                        val parts=RoadContextChunker.split(route.routeId,"c${seq.next()}",nearby,seq::next)
                        android.util.Log.i("885rokidOSMap","road_context roads=${nearby.size} parts=${parts.size} along=${fix.metersAlongRoute} took=${System.currentTimeMillis()-started}ms")
                        lastSent.keys.removeAll {it.startsWith("road_context")}
                        parts.forEach {sendStateful("road_context#${it.part}",it)}
                        center=point.takeIf {nearby.isNotEmpty()};delay(30_000)
                    }
                }
            }
        }
        scope.launch {
            activeGuidance().collectLatest {route ->
                activeAlerts.keys.toList().forEach {sendAlert(AlertCleared(seq.next(),it))}
                if(route==null)return@collectLatest
                val tracker=AlertTracker(PolylineCodec.decode(route.encodedPolyline)) {preferences.alertToleranceMeters}
                coroutineScope {
                    var snapshot=emptyList<Incident>();var position:PositionUpdate?=null
                    fun update(){position?.let {tracker.update(snapshot,it,System.currentTimeMillis(),seq::next).forEach(::sendAlert)}}
                    launch {incidents.incidentsAlong(route.encodedPolyline).collect {snapshot=it;update()}}
                    launch {engine.position.collect {position=it;update()}}
                    launch {while(isActive){delay(1000);update()}}
                }
            }
        }
        scope.launch {link.incoming.collect {when(it){is GlassesHello,is ResyncRequest->resync();is GlassesAction->action(it);else->Unit}}}
        scope.launch {link.state.collect {if(it==LinkState.Ready){applySettings(settings?.state?.value ?: preferences);resync()}}}
        // 885rokidOS is more than navigation (notifications, later music and calls), so the session stays
        // open with or without a trip. It opens right after authorization, when the phone app comes to the
        // front (onAppOpened) and when a trip starts. A user Disconnect is respected until one of those.
        scope.launch {
            var previous=link.state.value
            link.state.collect {if(it==LinkState.Authorized && previous==LinkState.Authorizing)link.connect();previous=it}
        }
        scope.launch {engine.navState.map {it==NavState.ACTIVE}.distinctUntilChanged().collect {
            if(it)reconnectIfAllowed()
        }}
        if(media!=null)scope.launch {media.info.collect(::sendMedia)}
        traffic.start(scope)
    }
    /** The phone app came to the front: reopen the glasses session when the token allows it. */
    fun onAppOpened()=reconnectIfAllowed()
    private fun reconnectIfAllowed() {
        val s=link.state.value
        if(s==LinkState.Authorized || s is LinkState.Closed && !s.needsReauth)link.connect()
    }
    private fun activeGuidance()=combine(engine.activeRoute,engine.navState){route,state->
        route.takeIf {state in listOf(NavState.ACTIVE,NavState.REROUTING,NavState.GPS_LOST)}}.distinctUntilChanged()
    private fun sendStateful(key:String,message:NavMessage){lastSent[key]=message;link.send(message)}
    /** The next [meters] of route from [along] (from the start when position on it is unknown). */
    internal fun routeAhead(points:List<LatLng>,alongOrUnknown:Int,meters:Int):List<LatLng> {
        if(points.size<2)return points
        val along=alongOrUnknown.coerceAtLeast(0)
        val out=ArrayList<LatLng>();var travelled=0.0
        for(i in 1 until points.size){
            val step=Geo.distanceMeters(points[i-1],points[i])
            if(travelled+step>=along){if(out.isEmpty())out+=points[i-1];out+=points[i]}
            travelled+=step
            if(travelled>=along+meters)break
        }
        return out.ifEmpty {listOf(points.last())}
    }
    private fun sendAlert(m:NavMessage) {
        // Voice is phone-side, so warnings still play while the glasses are paused or disconnected.
        if(m is Alert)announcer.consider(m)?.let {speaker?.speak(it)}
        val id=when(m){is Alert->{activeAlerts[m.id]=m;m.id};is AlertCleared->{activeAlerts.remove(m.id);m.id};else->return}
        sendStateful("alert:$id",m)
    }
    private fun sendRoute(){engine.activeRoute.value?.let {route->
        RouteChunker.split(route.routeId,route.encodedPolyline,route.totalMeters,seq::next).forEach {link.send(it)}
    }}
    /** Not in [lastSent]: a resync must move the position forward, or the glasses' progress bar jumps back. */
    private fun sendMedia(info:com.rokidos885.phone.media.MediaInfo?) {
        val message=if(info==null)MediaState(seq.next(),active=false)
            else MediaState(seq.next(),true,info.app,info.title.take(120),info.artist.take(120),info.playing,info.positionMs,info.durationMs)
        lastMedia=message;lastMediaAt=System.currentTimeMillis()
        link.send(message)
    }
    private fun resync() {
        lastMedia?.let {m->
            val elapsed=if(m.playing)System.currentTimeMillis()-lastMediaAt else 0L
            link.send(m.copy(seq=seq.next(),positionMs=(m.positionMs+elapsed).let {p->if(m.durationMs>0)p.coerceAtMost(m.durationMs) else p}))
        }
        lastSent["session"]?.let {link.send(fresh(it))};sendRoute()
        lastSent.filterKeys {it!="session"}.values.forEach {link.send(fresh(it))}
        offer.value?.takeIf {it.expiresAtMs>System.currentTimeMillis()}?.let {link.send(it.copy(seq=seq.next()))}
        // Sent either way: a HUD that kept "active" from before a phone restart clears it.
        link.send(GrokBotState(seq.next(),grokActive))
    }
    /**
     * Follows the Grok Bot chat a glasses gesture started or closed, polling [grokBotRunning] each second (Android tells
     * other apps of a mode change only from API 31). Tells the glasses on each change. Stops when the chat ends, or after
     * [GROK_START_MS] when none started.
     */
    private fun watchGrokBot() {
        grokWatch?.cancel()
        grokWatch=scope.launch {
            var waited=0L
            var seen=false
            while(true) {
                val running=grokRunning()
                if(running!=grokActive){grokActive=running;link.send(GrokBotState(seq.next(),running))}
                if(running)seen=true
                if(!running && (seen || waited>=GROK_START_MS)){grokBase=null;break}
                delay(GROK_POLL_MS);waited+=GROK_POLL_MS
            }
        }
    }
    /** The call mode alone, or during another app's call, a call recording more than at the start (Grok Bot records one). */
    private fun grokRunning():Boolean {
        val busy=runCatching {grokBotRunning()}.getOrDefault(false)
        val base=grokBase ?: return busy
        return busy && runCatching {callRecordings()}.getOrDefault(0)>base
    }
    private fun fresh(m:NavMessage):NavMessage=when(m) {
        is SessionStart->m.copy(seq=seq.next());is ManeuverUpdate->m.copy(seq=seq.next())
        is TripProgress->m.copy(seq=seq.next());is PositionUpdate->m.copy(seq=seq.next())
        is RoadContext->m.copy(seq=seq.next());is SettingsUpdate->m.copy(seq=seq.next())
        is TrafficSegments->m.copy(seq=seq.next());is NavStateUpdate->m.copy(seq=seq.next())
        is Alert->m.copy(seq=seq.next());is AlertCleared->m.copy(seq=seq.next());is T3Status->m.copy(seq=seq.next());else->m
    }
    internal fun offerDetour(decision:DetourEvaluator.Decision) {
        if(pending!=null)return
        pending=decision
        val message=DetourOffer(seq.next(),"d${seq.next()}",decision.savesSeconds,decision.extraMeters,
            decision.option.description,expiresAtMs=System.currentTimeMillis()+30_000,encodedPolyline=decision.option.encodedPolyline)
        offer.value=message;link.send(message)
        offerJob=scope.launch {delay(30_000);resolve(false)}
    }
    private fun resolve(accepted:Boolean) {
        val message=offer.value ?: return
        if(!accepted)pending?.let {traffic.dismiss(it.option,System.currentTimeMillis())}
        offerJob?.cancel();offerJob=null;pending=null;offer.value=null;accepting=false
        link.send(DetourResolved(seq.next(),message.offerId,accepted))
    }
    /** Not cached for resync: a notification only matters when it arrives. Dropped unless the link is Ready. */
    fun forwardNotification(id:String,app:String,title:String,text:String,postedAtMs:Long,canReply:Boolean=false):Boolean=
        link.send(PhoneNotification(seq.next(),id,app,title,text,postedAtMs,canReply))
    /** The glasses' T3 page; the last one is resent on resync. */
    fun sendT3(status:T3Status)=sendStateful("t3",status.copy(seq=seq.next()))
    /** For the log only. */
    val linkStateName:String get()=link.state.value::class.simpleName.orEmpty()
    fun respondToDetour(accept:Boolean){offer.value?.let {action(GlassesAction(0,if(accept)ActionType.ACCEPT_DETOUR else ActionType.DISMISS_DETOUR,it.offerId))}}
    private fun applySettings(value:UserSettings) {
        preferences=value;engine.setVoiceMuted(value.voiceMuted)
        sendStateful("settings",SettingsUpdate(seq.next(),value.voiceMuted,value.units,value.zoom,demoMode=value.demoMode))
    }
    private fun changeSettings(transform:(UserSettings)->UserSettings) {
        if(settings!=null)settings.update(transform) else applySettings(transform(preferences))
    }
    private fun action(a:GlassesAction) {
        when(a.action) {
            ActionType.ACCEPT_DETOUR->{
                val o=offer.value?.takeIf {it.offerId==a.targetId} ?: return
                val decision=pending ?: return
                if(accepting)return
                if(o.expiresAtMs<=System.currentTimeMillis()){resolve(false);return}
                accepting=true;offerJob?.cancel()
                scope.launch {val success=engine.switchRoute(decision.option);if(success)traffic.seed(decision.option);if(offer.value?.offerId==a.targetId)resolve(success)}
            }
            ActionType.DISMISS_DETOUR->if(offer.value?.offerId==a.targetId && !accepting)resolve(false)
            ActionType.END_NAVIGATION->engine.stop()
            ActionType.TOGGLE_VOICE->changeSettings {it.copy(voiceMuted=!it.voiceMuted)}
            ActionType.TOGGLE_UNITS->changeSettings {it.copy(units=if(it.units==Units.IMPERIAL)Units.METRIC else Units.IMPERIAL)}
            ActionType.ZOOM_IN->changeSettings {it.copy(zoom=(it.zoom-1).coerceAtLeast(0))}
            ActionType.ZOOM_OUT->changeSettings {it.copy(zoom=(it.zoom+1).coerceAtMost(2))}
            ActionType.CYCLE_ZOOM->changeSettings {it.copy(zoom=(it.zoom+1)%3)}
            // The glasses keep their own Rokid brightness; older HUDs may still send this.
            ActionType.CYCLE_BRIGHTNESS->Unit
            ActionType.SHOW_ALTERNATIVES->traffic.forceRefresh()
            ActionType.VOICE_START,ActionType.VOICE_REDO->{onVoiceStart(a.targetId);voice?.start(a.targetId) ?: link.send(VoiceState(seq.next(),VoicePhase.FAILED,a.targetId,"Voice replies are not set up"))}
            ActionType.VOICE_STOP->voice?.stop()
            ActionType.VOICE_SEND->voice?.send(a.targetId)
            ActionType.VOICE_CANCEL->voice?.cancel(a.targetId)
            ActionType.OPEN_GROK_BOT->{
                onVoiceStart(a.targetId)
                val open=openGrokBot
                // Checked before the start: the same start closes a running chat. Android does not say which app holds the
                // call mode, so a mode that is on with no chat of ours is another app's call (WhatsApp, 2026-11-03). Then the
                // chat is the call recording Grok Bot adds to the ones on now.
                val busy=runCatching {grokBotRunning()}.getOrDefault(false)
                if(busy && !grokActive)grokBase=runCatching {callRecordings()}.getOrDefault(0)
                val closing=busy && grokActive
                val problem=if(open==null)"Opening apps is not set up" else runCatching {open()}.getOrElse {"Could not open Grok Bot"}
                val done=if(closing)"Closing Grok Bot" else "Opening Grok Bot on the phone"
                link.send(VoiceState(seq.next(),if(problem==null)VoicePhase.SENT else VoicePhase.FAILED,VOICE_COMMAND_ID,problem ?: done))
                if(problem==null)watchGrokBot()
            }
            ActionType.MEDIA_PLAY_PAUSE->media?.playPause()
            ActionType.MEDIA_NEXT->media?.next()
            ActionType.MEDIA_PREVIOUS->media?.previous()
        }
    }
    private companion object {
        const val GROK_POLL_MS=1_000L
        /** Grok Bot set its chat mode about 1 s after the start (2026-11-03); more is a start that failed. */
        const val GROK_START_MS=10_000L
    }
}
