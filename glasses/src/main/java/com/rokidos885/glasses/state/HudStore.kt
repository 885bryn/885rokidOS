package com.rokidos885.glasses.state

import com.rokidos885.glasses.input.GlassesKey
import com.rokidos885.protocol.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/** Single writer: messages/keys -> immutable HudState. Renderers only consume snapshots. */
class HudStore(private val clock:()->Long=System::currentTimeMillis,private val requestResync:()->Unit={},private val sendAction:(GlassesAction)->Unit) {
    private val mutable=MutableStateFlow(HudState())
    val state:StateFlow<HudState> = mutable.asStateFlow()
    private var assembler=RouteAssembler()
    private var lastSeq=-1L
    private var routePendingAt=0L
    private var routeId:String?=null
    private var contextId=""
    private val contextParts=HashMap<Int,List<ContextRoad>>()
    private val dismissed=HashSet<String>()
    private val alertUpdatedAt=HashMap<String,Long>()
    private var lastMessageAt=0L
    private var lastMediaKey:GlassesKey?=null
    private var lastMediaKeyAt=0L
    private var arrivedAt=0L
    private fun resultOver(s:HudState,now:Long)=s.voice!=null && (s.voice.phase==VoicePhase.SENT || s.voice.phase==VoicePhase.FAILED) && now-s.voiceAt>=REPLY_RESULT_MS
    /** A new phone session began (HelloRequest): the phone may have restarted and count from a lower number. */
    fun resetSequence(){lastSeq=-1L}
    // Without the link the phone cannot say the chat ended, so do not keep the display on for it (resync says it again).
    fun onConnection(connected:Boolean){mutable.update {it.copy(linkConnected=connected,grokBotActive=it.grokBotActive && connected)}}
    fun onMessage(m:NavMessage) {
        lastMessageAt=clock()
        if(state.value.phoneSilent)mutable.update {it.copy(phoneSilent=false)}
        if(m !is SessionStart && m.seq<=lastSeq)return
        lastSeq=m.seq
        when(m) {
            is SessionStart->{
                assembler=RouteAssembler();routeId=null;routePendingAt=0;dismissed.clear();alertUpdatedAt.clear();contextId="";contextParts.clear()
                mutable.update {it.copy(units=m.units,clock24h=m.clock24h,destinationName=m.destinationName,
                    route=emptyList(),routeTotalMeters=0,traffic=emptyList(),contextRoads=emptyList(),
                    position=null,previousPosition=null,maneuver=null,progress=null,alerts=emptyList(),alertSeenAt=emptyMap(),detour=null,navState=NavState.ROUTING)}
            }
            is RouteChunk->{routePendingAt=clock();assembler.accept(m)?.let { line->
                routePendingAt=0
                val changed=routeId!=m.routeId;routeId=m.routeId
                mutable.update {it.copy(route=PolylineCodec.decode(line),routeTotalMeters=m.totalMeters,
                    contextRoads=if(changed)emptyList() else it.contextRoads,traffic=if(changed)emptyList() else it.traffic)}
            }}
            is PositionUpdate->mutable.update {it.copy(previousPosition=it.position,position=m)}
            is ManeuverUpdate->mutable.update {it.copy(maneuver=m)}
            is TripProgress->mutable.update {it.copy(progress=m)}
            is TrafficSegments->if(m.routeId==routeId)mutable.update {it.copy(traffic=m.segments)}
            is RoadContext->if(m.routeId==routeId)acceptRoads(m)
            // Alerts are passive: they never take over the screen or need a tap; the phone clears them once
            // passed, and tick() drops any the phone stops updating.
            is Alert->if(m.id !in dismissed){
                alertUpdatedAt[m.id]=clock()
                mutable.update {s->s.copy(
                    alerts=(s.alerts.filterNot {it.id==m.id}+m).sortedBy {it.distanceAheadMeters},
                    alertSeenAt=if(m.id in s.alertSeenAt)s.alertSeenAt else s.alertSeenAt+(m.id to clock()))}
            }
            is AlertCleared->{dismissed.remove(m.id);alertUpdatedAt.remove(m.id);mutable.update {s->s.copy(
                alerts=s.alerts.filterNot {it.id==m.id},alertSeenAt=s.alertSeenAt-m.id)}}
            is DetourOffer->mutable.update {it.copy(detour=m)}
            is DetourResolved->mutable.update {if(it.detour?.offerId==m.offerId)it.copy(detour=null) else it}
            is NavStateUpdate->{
                if(m.state==NavState.ARRIVED)arrivedAt=clock()
                if(m.state==NavState.IDLE || m.state==NavState.ENDED)dismissed.clear()
                val tripOver=m.state in listOf(NavState.IDLE,NavState.ENDED,NavState.ARRIVED)
                if(tripOver)alertUpdatedAt.clear()
                mutable.update {it.copy(navState=m.state,alerts=if(tripOver)emptyList() else it.alerts,
                    alertSeenAt=if(tripOver)emptyMap() else it.alertSeenAt)}
            }
            is SettingsUpdate->mutable.update {it.copy(voiceMuted=m.voiceMuted,units=m.units,mapZoom=m.mapZoomLevel.coerceIn(0,2),demoMode=m.demoMode)}
            // Position-only changes (a seek, a resync) change nothing on screen, so they do not redraw.
            is MediaState->mutable.update {s->val next=m.takeIf {it.active}
                if(sameOnScreen(s.media,next))s else s.copy(media=next)}
            // A notification and the player never share the screen, and the player does not come back after the card.
            // A card never replaces a reply that is being spoken.
            is PhoneNotification->mutable.update {if(it.voice!=null)it else it.copy(notification=m,notificationShownAt=clock(),nowMs=clock(),mediaShown=false)}
            is VoiceState->mutable.update {s->
                if(m.phase==VoicePhase.IDLE)s.copy(voice=null,voiceFocus=0)
                else s.copy(voice=m,voiceAt=clock(),nowMs=clock(),voiceFocus=if(s.voice?.phase==m.phase)s.voiceFocus else 0)
            }
            is GrokBotState->mutable.update {it.copy(grokBotActive=m.active)}
            else->Unit
        }
    }
    /** Streets replace the map only once every part of a snapshot is in, so the map never half-empties. */
    private fun acceptRoads(m:RoadContext) {
        if(m.partCount<=1){showRoads(m.roads);return}
        if(m.contextId!=contextId){contextId=m.contextId;contextParts.clear()}
        if(m.part !in 0 until m.partCount)return
        contextParts[m.part]=m.roads
        if(contextParts.size==m.partCount)showRoads((0 until m.partCount).flatMap {contextParts[it].orEmpty()})
    }
    private fun showRoads(roads:List<ContextRoad>)=mutable.update {
        it.copy(contextRoads=roads.map {road->HudRoad(PolylineCodec.decode(road.encodedPolyline),road.name,road.major)})
    }
    /**
     * Applies a key. Returns false when the HUD has no use for it, so the caller hands it to the system:
     * double-tap / Back then exits to the Rokid home screen. Double-tap is "back one step" wherever there is a
     * step to go back from: keep the current route on a detour offer, close a card, cancel a voice reply, close
     * the arrival screen. The HUD has no menu (removed 2026-10-20: the glasses deliver no spare gesture for it);
     * trip and settings live on the phone. Map view and zoom belong to a trip, so without one tap and swipe
     * never change them unseen.
     */
    fun onKey(key:GlassesKey):Boolean {
        val s=state.value
        val d=s.detour
        val back=key==GlassesKey.DOUBLE_TAP || key==GlassesKey.BACK
        when {
            d!=null && (key==GlassesKey.TAP || key==GlassesKey.DOUBLE_TAP)->{
                sendAction(GlassesAction(0,if(key==GlassesKey.TAP)ActionType.ACCEPT_DETOUR else ActionType.DISMISS_DETOUR,d.offerId))
                mutable.update {it.copy(detour=null)}
            }
            // A detour offer hides the card, so its taps above win; otherwise a tap only hides the card.
            s.voice!=null->voiceKey(s,s.voice,key)
            // A card whose app has a reply box: tap starts a voice reply (shown at once; the phone confirms).
            s.replyAvailable && key==GlassesKey.TAP->startVoice(s.notification!!.id)
            // Any gesture closes a card: tap, a swipe either way, or double tap. It is consumed here, so the same swipe
            // does not also step the trip zoom, start Grok Bot or bring up the player, and a second delivery from another
            // input path is dropped. A double tap does not also leave the app.
            s.notification!=null->{
                lastMediaKey=key;lastMediaKeyAt=clock()
                mutable.update {it.copy(notification=null)}
            }
            // No trip and the player on screen: tap plays or pauses, swipe skips.
            idleMedia(s) && s.mediaShown && !back->mediaKey(key)
            // No trip and nothing on screen: tap starts Grok Bot on the phone. The tap that closed a card or a voice
            // result, arriving again by another input path, is not one. The touch that wakes the display is: one tap works
            // whether or not the display slept, so nobody has to guess (user request 2026-11-03).
            idle(s) && key==GlassesKey.TAP && s.linkConnected->{
                val now=clock()
                if(!(lastMediaKey==key && now-lastMediaKeyAt<MEDIA_KEY_GAP_MS))startVoice(VOICE_COMMAND_ID,action=ActionType.OPEN_GROK_BOT)
            }
            // Music but the player hidden: a swipe brings it up (it must be seen before it is controlled); a swipe that
            // closed a card, arriving again by another input path, is not one (see repeated).
            idleMedia(s) && !back->if(!repeated(key))mutable.update {it.copy(mediaShown=true,mediaShownAt=clock())}
            // No trip, nothing to act on.
            idle(s) && !back->Unit
            key==GlassesKey.TAP->mode(when(s.mode){HudMode.Compact->HudMode.Map;HudMode.Map->HudMode.Minimal;else->HudMode.Compact})
            // On a trip, swipe forward steps the zoom (default, far, close, as the phone's "Map zoom" button) and swipe back
            // starts Grok Bot: tap is the view and double tap leaves, so one swipe direction was the only free gesture.
            // A detour offer keeps the screen for itself. Each swipe counts once, however many input paths report it.
            key==GlassesKey.SWIPE_FORWARD->if(!repeated(key)){mutable.update {it.copy(mapZoom=(it.mapZoom+1)%3)};sendAction(GlassesAction(0,ActionType.CYCLE_ZOOM))}
            key==GlassesKey.SWIPE_BACK->if(d==null && s.linkConnected && !repeated(key))startVoice(VOICE_COMMAND_ID,key,ActionType.OPEN_GROK_BOT)
            (key==GlassesKey.DOUBLE_TAP || key==GlassesKey.BACK) && s.navState==NavState.ARRIVED->mutable.update {it.copy(navState=NavState.IDLE)}
            key==GlassesKey.DOUBLE_TAP || key==GlassesKey.BACK->return false
        }
        return true
    }
    /** Starts a voice reply to card [id], or the Grok Bot card ([VOICE_COMMAND_ID], [action] OPEN_GROK_BOT); shown at once, the phone confirms. */
    private fun startVoice(id:String,key:GlassesKey=GlassesKey.TAP,action:ActionType=ActionType.VOICE_START) {
        // The same gesture may arrive again by another input path; the voice key guard drops it (see mediaKey).
        lastMediaKey=key;lastMediaKeyAt=clock()
        sendAction(GlassesAction(0,action,id))
        mutable.update {it.copy(voice=VoiceState(0,VoicePhase.STARTING,id),voiceAt=clock(),mediaShown=false)}
    }
    /** True when [key] is the last accepted gesture arriving again by another input path; otherwise records it (see mediaKey). */
    private fun repeated(key:GlassesKey):Boolean {
        val now=clock()
        if(key==lastMediaKey && now-lastMediaKeyAt<MEDIA_KEY_GAP_MS)return true
        lastMediaKey=key;lastMediaKeyAt=now
        return false
    }
    private fun sameOnScreen(a:MediaState?,b:MediaState?)=if(a==null || b==null)a==b else
        a.app==b.app && a.title==b.title && a.artist==b.artist && a.playing==b.playing
    /**
     * One physical gesture reaches the HUD by up to three paths (system broadcast, key event, touch), so the
     * same media key inside [MEDIA_KEY_GAP_MS] of the last accepted one is the same gesture and is dropped.
     */
    private fun mediaKey(key:GlassesKey) {
        val now=clock()
        if(key==lastMediaKey && now-lastMediaKeyAt<MEDIA_KEY_GAP_MS)return
        lastMediaKey=key;lastMediaKeyAt=now
        mutable.update {it.copy(mediaShownAt=now)}
        when(key) {
            GlassesKey.TAP->{
                sendAction(GlassesAction(0,ActionType.MEDIA_PLAY_PAUSE))
                // Optimistic: the icon changes now; the phone's answer corrects it.
                mutable.update {it.copy(media=it.media?.copy(playing=!it.media.playing))}
            }
            GlassesKey.SWIPE_FORWARD->sendAction(GlassesAction(0,ActionType.MEDIA_NEXT))
            else->sendAction(GlassesAction(0,ActionType.MEDIA_PREVIOUS))
        }
    }
    /** Keys during a voice reply. Everything is consumed so a swipe never skips a song or the double tap never exits. */
    private fun voiceKey(s:HudState,v:VoiceState,key:GlassesKey) {
        val id=v.notificationId
        fun cancel(){sendAction(GlassesAction(0,ActionType.VOICE_CANCEL,id));mutable.update {it.copy(voice=null,voiceFocus=0,notification=null)}}
        fun close(){mutable.update {it.copy(voice=null,voiceFocus=0,notification=null)}}
        // One physical gesture can arrive by several input paths (see mediaKey).
        val now=clock()
        if(key==lastMediaKey && now-lastMediaKeyAt<MEDIA_KEY_GAP_MS)return
        lastMediaKey=key;lastMediaKeyAt=now
        // The Grok Bot card (no speech step) is up for 2 s after each tap: its own gesture (tap, or swipe back on a trip)
        // starts or closes Grok Bot again, as on the empty screen, instead of only closing the card.
        if(id==VOICE_COMMAND_ID && v.phase in GROK_CARD && (key==GlassesKey.TAP || key==GlassesKey.SWIPE_BACK)) {
            startVoice(id,key,ActionType.OPEN_GROK_BOT)
            return
        }
        when(v.phase) {
            VoicePhase.STARTING->if(key==GlassesKey.DOUBLE_TAP || key==GlassesKey.BACK)cancel()
            VoicePhase.LISTENING->when(key) {
                GlassesKey.TAP->sendAction(GlassesAction(0,ActionType.VOICE_STOP,id))
                GlassesKey.DOUBLE_TAP,GlassesKey.BACK->cancel()
                else->Unit
            }
            VoicePhase.REVIEW->when(key) {
                GlassesKey.SWIPE_FORWARD->mutable.update {it.copy(voiceFocus=(it.voiceFocus+1)%3)}
                GlassesKey.SWIPE_BACK->mutable.update {it.copy(voiceFocus=(it.voiceFocus+2)%3)}
                GlassesKey.TAP->when(s.voiceFocus) {
                    0->sendAction(GlassesAction(0,ActionType.VOICE_SEND,id))
                    1->{sendAction(GlassesAction(0,ActionType.VOICE_REDO,id));mutable.update {it.copy(voice=VoiceState(0,VoicePhase.STARTING,id),voiceFocus=0)}}
                    else->cancel()
                }
                GlassesKey.DOUBLE_TAP,GlassesKey.BACK->cancel()
            }
            VoicePhase.SENT,VoicePhase.FAILED->if(key==GlassesKey.TAP || key==GlassesKey.DOUBLE_TAP || key==GlassesKey.BACK)close()
            VoicePhase.IDLE->Unit
        }
    }
    private fun idle(s:HudState)=s.navState==NavState.IDLE || s.navState==NavState.ENDED
    private fun idleMedia(s:HudState)=s.media!=null && s.notification==null && idle(s)
    private fun mode(value:HudMode){mutable.update {it.copy(mode=value)}}
    /** Debug previews only: pick the view before the demo trip starts (a tap no longer does that without a trip). */
    fun previewMode(value:HudMode)=mode(value)
    fun tick(now:Long=clock()) {
        if(routePendingAt>0 && now-routePendingAt>=5000){routePendingAt=0;requestResync()}
        // The phone refreshes active alerts with every position fix; a silent one is stale (link drop).
        val stale=alertUpdatedAt.filterValues {now-it>=ALERT_STALE_MS}.keys
        stale.forEach(alertUpdatedAt::remove)
        val silent=lastMessageAt>0 && now-lastMessageAt>=PHONE_SILENT_MS
        mutable.update {s->val n=s.copy(nowMs=now,detour=s.detour?.takeIf {it.expiresAtMs>now},
            phoneSilent=silent && s.navState in listOf(NavState.ACTIVE,NavState.REROUTING,NavState.GPS_LOST),
            alerts=if(stale.isEmpty())s.alerts else s.alerts.filterNot {it.id in stale},
            alertSeenAt=if(stale.isEmpty())s.alertSeenAt else s.alertSeenAt-stale,
            // A card with a reply under way stays; a finished reply (sent or failed) leaves after REPLY_RESULT_MS.
            notification=s.notification?.takeIf {(s.voice!=null && !resultOver(s,now)) || (s.voice==null && now-s.notificationShownAt<notificationMillis(it))},
            voice=s.voice?.takeIf {!resultOver(s,now)},
            mediaShown=s.mediaShown && now-s.mediaShownAt<MEDIA_SHOW_MS,
            navState=if(s.navState==NavState.ARRIVED && now-arrivedAt>=10_000)NavState.IDLE else s.navState)
            // Battery: the clock alone only matters while an alert or detour shows time-based content.
            // Otherwise an unchanged state is not re-emitted, so an idle HUD does not redraw every second.
            // A card on screen counts down in seconds, so it redraws every tick; otherwise nothing to redraw.
            if(s.alerts.isEmpty() && s.detour==null && s.notification==null && n==s.copy(nowMs=now))s else n}
    }
    suspend fun tickLoop(){while(true){tick();delay(1000)}}
    companion object {
        /** Phases of the Grok Bot card: waiting for the phone, or its answer. */
        private val GROK_CARD=setOf(VoicePhase.STARTING,VoicePhase.SENT,VoicePhase.FAILED)
        const val ALERT_STALE_MS=120_000L
        /** The phone sends position at least every 5 s while guiding, even when stopped. */
        const val PHONE_SILENT_MS=12_000L
        /** How long "Sent" or the failure reason stays before the card closes. */
        const val REPLY_RESULT_MS=2_000L
        /** How long the player stays after the last touch. */
        const val MEDIA_SHOW_MS=10_000L
        /** Longer than the spread between the three input paths of one gesture, shorter than two real ones. */
        const val MEDIA_KEY_GAP_MS=600L
        /** How long a card stays: longer text stays longer, but never past the maximum (driver safety). */
        const val NOTIFICATION_MIN_MS=6_000L
        const val NOTIFICATION_MAX_MS=15_000L
        fun notificationMillis(n:PhoneNotification)=
            (4_000L+60L*(n.title.length+n.text.length)).coerceIn(NOTIFICATION_MIN_MS,NOTIFICATION_MAX_MS)
    }
}
