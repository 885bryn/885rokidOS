package com.rokidnav.glasses.state

import com.rokidnav.glasses.input.GlassesKey
import com.rokidnav.protocol.*
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
    fun onConnection(connected:Boolean){mutable.update {it.copy(linkConnected=connected)}}
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
            is PhoneNotification->mutable.update {it.copy(notification=m,notificationShownAt=clock())}
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
     * double-tap / Back then exits to the Rokid home screen. Double-tap is only kept where it has a job:
     * keep the current route on a detour offer, close the arrival screen. The HUD has no menu
     * (removed 2026-10-20: the glasses deliver no spare gesture for it); trip and settings live on the phone.
     */
    fun onKey(key:GlassesKey):Boolean {
        val s=state.value
        val d=s.detour
        when {
            d!=null && (key==GlassesKey.TAP || key==GlassesKey.DOUBLE_TAP)->{
                sendAction(GlassesAction(0,if(key==GlassesKey.TAP)ActionType.ACCEPT_DETOUR else ActionType.DISMISS_DETOUR,d.offerId))
                mutable.update {it.copy(detour=null)}
            }
            // A detour offer hides the card, so its taps above win; otherwise a tap only hides the card.
            s.notification!=null && key==GlassesKey.TAP->mutable.update {it.copy(notification=null)}
            // No trip and music playing: the idle screen is the player.
            idleMedia(s) && (key==GlassesKey.TAP || key==GlassesKey.SWIPE_FORWARD || key==GlassesKey.SWIPE_BACK)->mediaKey(key)
            key==GlassesKey.TAP->mode(when(s.mode){HudMode.Compact->HudMode.Map;HudMode.Map->HudMode.Minimal;else->HudMode.Compact})
            key==GlassesKey.SWIPE_FORWARD->{mutable.update {it.copy(mapZoom=(it.mapZoom-1).coerceAtLeast(0))};sendAction(GlassesAction(0,ActionType.ZOOM_IN))}
            key==GlassesKey.SWIPE_BACK->{mutable.update {it.copy(mapZoom=(it.mapZoom+1).coerceAtMost(2))};sendAction(GlassesAction(0,ActionType.ZOOM_OUT))}
            (key==GlassesKey.DOUBLE_TAP || key==GlassesKey.BACK) && s.navState==NavState.ARRIVED->mutable.update {it.copy(navState=NavState.IDLE)}
            key==GlassesKey.DOUBLE_TAP || key==GlassesKey.BACK->return false
        }
        return true
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
    private fun idleMedia(s:HudState)=s.media!=null && s.notification==null && (s.navState==NavState.IDLE || s.navState==NavState.ENDED)
    private fun mode(value:HudMode){mutable.update {it.copy(mode=value)}}
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
            notification=s.notification?.takeIf {now-s.notificationShownAt<notificationMillis(it)},
            navState=if(s.navState==NavState.ARRIVED && now-arrivedAt>=10_000)NavState.IDLE else s.navState)
            // Battery: the clock alone only matters while an alert or detour shows time-based content.
            // Otherwise an unchanged state is not re-emitted, so an idle HUD does not redraw every second.
            if(s.alerts.isEmpty() && s.detour==null && n==s.copy(nowMs=now))s else n}
    }
    suspend fun tickLoop(){while(true){tick();delay(1000)}}
    companion object {
        const val ALERT_STALE_MS=120_000L
        /** The phone sends position at least every 5 s while guiding, even when stopped. */
        const val PHONE_SILENT_MS=12_000L
        /** Longer than the spread between the three input paths of one gesture, shorter than two real ones. */
        const val MEDIA_KEY_GAP_MS=600L
        /** How long a card stays: longer text stays longer, but never past the maximum (driver safety). */
        const val NOTIFICATION_MIN_MS=6_000L
        const val NOTIFICATION_MAX_MS=15_000L
        fun notificationMillis(n:PhoneNotification)=
            (4_000L+60L*(n.title.length+n.text.length)).coerceIn(NOTIFICATION_MIN_MS,NOTIFICATION_MAX_MS)
    }
}
