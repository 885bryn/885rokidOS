package com.rokidos885.glasses.ui

import android.content.Context
import android.graphics.*
import com.rokidos885.glasses.state.*
import com.rokidos885.protocol.*
import kotlin.math.*

/** Draws in Claude's 480x398 design coordinates; HudView scales to the actual measured display.
 * Geometry, labels and glyph paths are cached on input changes. Drawing creates no application objects.
 */
class HudRenderer(context: Context) {
    private val regular = Typeface.createFromAsset(context.assets, "fonts/BarlowSemiCondensed-Medium.ttf")
    private val bold = Typeface.createFromAsset(context.assets, "fonts/BarlowSemiCondensed-SemiBold.ttf")
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; fontFeatureSettings = "tnum" }
    private val glyphs = ManeuverGlyphs()
    /** Only the stretch of route around the driver ([ROUTE_BEHIND_M]..[ROUTE_AHEAD_M]); stroking a
     *  whole long route every frame was the map's largest per-frame cost. Rebuilt on each position fix. */
    private val routePath = Path()
    private val minorRoads = Path()
    private val majorRoads = Path()
    private var hasRoads = false
    private var roadNames = emptyArray<String>()
    private var roadMajor = BooleanArray(0)
    private var roadEast = FloatArray(0)
    private var roadNorth = FloatArray(0)
    /** Road indices by label priority: turn street, the "after" street, major roads, the rest. */
    private var labelOrder = IntArray(0)
    private var turnRoad = -1
    private var cueRoad = -1
    /** Named major roads grouped by street (normalized name): each street carries at most one label,
     *  on one piece ([groupPiece]) at a fixed point that only moves when it falls behind or out of reach. */
    private var groupNames = emptyArray<String>()
    private var groupMembers = emptyArray<IntArray>()
    private var groupPiece = IntArray(0)
    private var roadGroup = IntArray(0)
    /** Streets whose label was drawn last frame; they keep their place before any new street is added. */
    private var groupShown = BooleanArray(0)
    private var lastLabelAddNanos = 0L
    private val placedLabelX = FloatArray(MAX_LABELS)
    private val placedLabelY = FloatArray(MAX_LABELS)
    private val placedLabelW = FloatArray(MAX_LABELS)
    private var maneuverX = 0f
    private var maneuverY = 0f
    private var hasManeuverPoint = false
    private var cue = ""
    private var trafficPaths = emptyArray<Path>()
    private var trafficLevels = emptyArray<TrafficLevel>()
    private val chevron = Path().apply { moveTo(0f,-24f); lineTo(12f,6f); lineTo(0f,0f); lineTo(-12f,6f); close() }
    private val shield = Path().apply { moveTo(12f,2.5f); lineTo(20f,5.5f); lineTo(20f,11f); cubicTo(20f,16f,16.5f,19.5f,12f,21.5f); cubicTo(7.5f,19.5f,4f,16f,4f,11f); lineTo(4f,5.5f); close() }
    private val alternativeDash = DashPathEffect(floatArrayOf(8f,7f),0f)
    private var s = HudState()
    private var east = FloatArray(0)
    private var north = FloatArray(0)
    private var distances = DoubleArray(0)
    private var originLat = 0.0
    private var originLng = 0.0
    private var previousX = 0f
    private var previousY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var previousBearing = 0f
    private var currentBearing = 0f
    private var receivedNanos = 0L
    private var transitionMs = 1000L
    private var number = ""
    private var unit = ""
    private var road = ""
    private var compactRoad = ""
    private var compactVia = ""
    private var eta = ""
    private var remainingTime = ""
    private var remainingDistance = ""
    private var statusTime = ""
    private var statusDistance = ""
    private var delayLabel = ""
    private var alertDistance = ""
    private var alertAge = ""
    private var alertKind = ""
    private var detourSaving = ""
    private var detourVia = ""
    private var destination = ""
    private var exit = ""
    private var jamLabel = ""
    private var notificationApp = ""
    private var notificationTitle = ""
    private var mediaApp = ""
    private var mediaTitle = emptyArray<String>()
    private var mediaArtist = ""
    private val playTriangle = Path().apply { moveTo(0f,0f); lineTo(0f,24f); lineTo(21f,12f); close() }
    private var t3Thread = ""
    private var t3Model = ""
    private var t3State = ""
    private var t3Said = emptyArray<String>()
    private var t3Text = emptyArray<String>()
    private var t3Reply = emptyArray<String>()
    private var t3More = false
    private var notificationLeft = ""
    private var voiceHeader = ""
    private var voiceLines = emptyArray<String>()
    private var notificationLines = emptyArray<String>()
    private var cardLayout = -1
    private var cardLeft = 0f
    private var cardTop = 0f
    private var cardW = 0f
    private var cardSize = 18f
    private var cardStep = 23f

    fun prepare(next: HudState) {
        val old = s
        s = next
        val geometryChanged = old.route !== next.route
        if (geometryChanged) {
            val points = next.route
            originLat = points.firstOrNull()?.lat ?: 0.0
            originLng = points.firstOrNull()?.lng ?: 0.0
            east = FloatArray(points.size); north = FloatArray(points.size); distances = DoubleArray(points.size)
            for (i in points.indices) {
                east[i] = x(points[i].lng); north[i] = y(points[i].lat)
                if (i > 0) distances[i] = distances[i-1] + Geo.distanceMeters(points[i-1],points[i])
            }
        }
        if (geometryChanged || old.position?.metersAlongRoute != next.position?.metersAlongRoute) routeWindow(next.position?.metersAlongRoute ?: 0)
        if (geometryChanged || old.contextRoads !== next.contextRoads) {
            minorRoads.reset();majorRoads.reset()
            for(road in next.contextRoads) {
                val path=if(road.major)majorRoads else minorRoads
                road.points.forEachIndexed { i,p -> if(i==0) path.moveTo(x(p.lng),y(p.lat)) else path.lineTo(x(p.lng),y(p.lat)) }
            }
            hasRoads=next.contextRoads.isNotEmpty()
            // Keep each street's label point (and whether it was shown) across road-context refreshes.
            val previous=HashMap<String,FloatArray>()
            if(!geometryChanged)for(g in groupNames.indices)if(groupPiece[g]>=0)
                previous[groupNames[g]]=floatArrayOf(roadEast[groupPiece[g]],roadNorth[groupPiece[g]],if(groupShown[g])1f else 0f)
            val groups=LinkedHashMap<String,MutableList<Int>>()
            next.contextRoads.forEachIndexed { i,road -> if(road.major && road.name.isNotBlank()) groups.getOrPut(normalizeName(road.name)){mutableListOf()}.add(i) }
            groupNames=groups.keys.toTypedArray()
            groupMembers=groups.values.map { it.toIntArray() }.toTypedArray()
            groupPiece=IntArray(groupNames.size){-1}
            groupShown=BooleanArray(groupNames.size)
            roadGroup=IntArray(next.contextRoads.size){-1}
            groupMembers.forEachIndexed { g,members -> members.forEach { roadGroup[it]=g } }
            roadNames=Array(next.contextRoads.size){""}
            roadMajor=BooleanArray(next.contextRoads.size) {next.contextRoads[it].major}
            roadEast=FloatArray(next.contextRoads.size)
            roadNorth=FloatArray(next.contextRoads.size)
            turnRoad=-1;cueRoad=-1
            for(g in groupNames.indices) {
                val keep=previous[groupNames[g]] ?: continue
                var best=Float.MAX_VALUE;var pick=-1;var px=0f;var py=0f
                for(i in groupMembers[g]) {
                    val d=anchor(next.contextRoads[i].points,i,keep[0],keep[1])
                    if(d<best){best=d;pick=i;px=roadEast[i];py=roadNorth[i]}
                }
                if(pick>=0 && best<STICKY_REANCHOR_M*STICKY_REANCHOR_M) {
                    groupPiece[g]=pick;roadEast[pick]=px;roadNorth[pick]=py;groupShown[g]=keep[2]>0f
                    roadNames[pick]=ellipsize(next.contextRoads[pick].name,LABEL_MAJOR,150f)
                }
            }
        }
        if (geometryChanged || old.traffic !== next.traffic) {
            trafficPaths = next.traffic.map { segment -> range(segment.startMeters.toDouble(),segment.endMeters.toDouble()) }.toTypedArray()
            trafficLevels = next.traffic.map { it.level }.toTypedArray()
        }
        if (geometryChanged || old.position !== next.position) {
            next.position?.let { current ->
                val previous = next.previousPosition ?: current
                previousX=x(previous.lng); previousY=y(previous.lat); currentX=x(current.lng); currentY=y(current.lat)
                previousBearing=previous.bearingDeg; currentBearing=current.bearingDeg
                transitionMs=(current.timestampMs-previous.timestampMs).coerceIn(250,1500)
                receivedNanos=System.nanoTime()
            }
        }
        if(geometryChanged || old.position !== next.position || old.contextRoads !== next.contextRoads || old.maneuver !== next.maneuver) {
            val along=next.position?.metersAlongRoute ?: -1
            val toStep=next.maneuver?.distanceToStepMeters
            hasManeuverPoint=false
            if(along>=0 && toStep!=null) {
                val target=(along+toStep).toDouble()
                var index=1
                while(index<distances.size && distances[index]<target)index++
                if(index<distances.size) {
                    val f=((target-distances[index-1])/(distances[index]-distances[index-1]).coerceAtLeast(.01)).toFloat()
                    maneuverX=east[index-1]+(east[index]-east[index-1])*f;maneuverY=north[index-1]+(north[index]-north[index-1])*f
                    hasManeuverPoint=true
                }
            }
            cue=next.maneuver?.let { m -> TurnCue.compute(next.route,along,m.distanceToStepMeters,m.current.maneuver,next.contextRoads) }.orEmpty()
            val turnName=normalizeName(next.maneuver?.current?.roadName.orEmpty())
            val cueName=normalizeName(cue.substringAfter(" · after ","").takeIf {cue.contains(" · after ")}.orEmpty())
            // Labels (user request 2026-10-20): only named major streets plus the turn street, and they
            // must not keep swapping. A street keeps its label point until that point falls behind the
            // driver or out of reach; only then does it move to the piece nearest the look-ahead point.
            val angle=Math.toRadians(currentBearing.toDouble())
            val sine=sin(angle).toFloat();val cosine=cos(angle).toFloat()
            val lookAhead=if(aheadMeters>0f)(aheadMeters*0.5f).coerceIn(200f,1500f) else 350f
            val aheadX=currentX+sine*lookAhead;val aheadY=currentY-cosine*lookAhead
            val reach=maxOf(aheadMeters*1.3f,500f)
            val turnX=if(hasManeuverPoint)maneuverX else aheadX;val turnY=if(hasManeuverPoint)maneuverY else aheadY
            // The turn street (any class) is labelled in bold on its piece nearest the turn.
            if(turnRoad>=0 && turnRoad<roadNames.size)roadNames[turnRoad]=""
            turnRoad=-1;cueRoad=-1
            var turnBest=Float.MAX_VALUE;var turnX2=0f;var turnY2=0f
            if(turnName.isNotEmpty())for(index in next.contextRoads.indices) {
                if(normalizeName(next.contextRoads[index].name)!=turnName)continue
                val saveX=roadEast[index];val saveY=roadNorth[index]
                val best=anchor(next.contextRoads[index].points,index,turnX,turnY)
                if(best<turnBest){turnBest=best;turnRoad=index;turnX2=roadEast[index];turnY2=roadNorth[index]}
                roadEast[index]=saveX;roadNorth[index]=saveY
            }
            for(g in groupNames.indices) {
                val p=groupPiece[g]
                if(groupNames[g]==turnName){if(p>=0)roadNames[p]="";groupPiece[g]=-1;groupShown[g]=false;continue}
                if(p>=0) {
                    val dx=roadEast[p]-currentX;val dy=roadNorth[p]-currentY
                    val along=dx*sine-dy*cosine
                    if(along>-40f && dx*dx+dy*dy<reach*reach)continue
                    roadNames[p]=""
                }
                var best=Float.MAX_VALUE;var pick=-1;var px=0f;var py=0f
                for(i in groupMembers[g]) {
                    val d=anchor(next.contextRoads[i].points,i,aheadX,aheadY)
                    if(d<best){best=d;pick=i;px=roadEast[i];py=roadNorth[i]}
                }
                groupPiece[g]=pick
                if(pick>=0){roadEast[pick]=px;roadNorth[pick]=py;roadNames[pick]=ellipsize(next.contextRoads[pick].name,LABEL_MAJOR,150f)}
                if(p!=pick)groupShown[g]=false
            }
            if(turnRoad>=0){roadEast[turnRoad]=turnX2;roadNorth[turnRoad]=turnY2;roadNames[turnRoad]=ellipsize(next.contextRoads[turnRoad].name,LABEL_TURN,170f)}
            for(g in groupNames.indices)if(groupNames[g]==cueName)cueRoad=groupPiece[g]
            // Stable order: the turn street, the "after" street, then streets in a fixed order.
            val order=ArrayList<Int>()
            if(turnRoad>=0)order+=turnRoad
            if(cueRoad>=0 && cueRoad!=turnRoad)order+=cueRoad
            for(g in groupNames.indices){val p=groupPiece[g];if(p>=0 && p!=turnRoad && p!=cueRoad)order+=p}
            labelOrder=order.toIntArray()
        }
        val formatted = Format.distance(next.maneuver?.distanceToStepMeters ?: 0,next.units).split(' ',limit=2)
        number=formatted[0]; unit=formatted.getOrElse(1){""}
        road=ellipsize(next.maneuver?.current?.roadName.orEmpty(),26f,if(next.maneuver?.next != null) 238f else 340f)
        compactRoad=ellipsize(next.maneuver?.current?.roadName.orEmpty(),22f,210f)
        compactVia=ellipsize(next.detour?.via.orEmpty(),18f,210f)
        exit=next.maneuver?.current?.exitNumber?.takeIf { it.isNotBlank() }?.let { "EXIT $it" }.orEmpty()
        next.progress?.let { p ->
            eta=Format.eta(p.etaEpochMs,next.clock24h); remainingTime=Format.duration(p.remainingSeconds)
            remainingDistance=Format.distance(p.remainingMeters,next.units)
            statusTime=ellipsize(remainingTime,22f,102f)
            statusDistance=ellipsize(remainingDistance,22f,100f)
            delayLabel=if(p.trafficDelaySeconds>0) "+${Format.duration(p.trafficDelaySeconds)}" else "No delays"
        }
        next.alerts.firstOrNull()?.let { a ->
            alertDistance=Format.distance(a.distanceAheadMeters,next.units)
            alertKind=when(a.kind) { AlertKind.POLICE->"POLICE"; AlertKind.SPEED_CAMERA->"CAMERA"; AlertKind.ROAD_CLOSED->"CLOSED"; AlertKind.TRAFFIC_JAM->"TRAFFIC"; else->a.kind.name.replace('_',' ') }
            alertAge=if(a.reportedAtMs>0) "Reported ${((next.nowMs-a.reportedAtMs).coerceAtLeast(0)/60_000)} min ago" else a.source
        }
        next.detour?.let { d -> detourSaving="Save ${Format.duration(d.savesSeconds)}"; detourVia=ellipsize("${d.via} · +${Format.distance(d.extraMeters,next.units)}",20f,284f) }
        // Card layout: with no trip it is wide and tall (nothing else to protect); on a trip the corner
        // view keeps it to the right of the turn arrow, and the big map view has the whole width.
        // The Grok Bot card has a voice card but no notification under it; it takes the same place a card would.
        if(next.notification!=null || next.voice!=null) {
            val idle=next.navState==NavState.IDLE || next.navState==NavState.ENDED
            val corner=next.mode!=HudMode.Map
            val layout=(if(idle)1 else 0)+(if(corner)2 else 0)
            val moved=layout!=cardLayout
            if(moved) {
                cardLayout=layout
                // Without a trip the card is a page: the full width (HudView draws idle screens at full size).
                cardLeft=if(idle)PAGE_LEFT else if(corner)240f else 14f
                cardTop=if(corner && idle)370f else if(corner)410f else if(idle)90f else 124f
                cardW=if(idle)PAGE_W else if(corner)222f else 452f
                cardSize=if(corner && !idle)20f else 22f
                cardStep=cardSize+6f
            }
            val n=next.notification
            if(n!=null) {
                if(n!==old.notification || moved) {
                    notificationApp=ellipsize(n.app.uppercase(),17f,cardW-96f)
                    notificationTitle=ellipsize(n.title,26f,cardW-32f)
                    notificationLines=wrap(n.text,cardSize,cardW-32f,if(idle)6 else 4)
                }
                val elapsed=(next.nowMs-next.notificationShownAt).coerceAtLeast(0)
                notificationLeft=((HudStore.notificationMillis(n)-elapsed+999)/1000).coerceAtLeast(0).toString()
            }
            next.voice?.let { v ->
                if(v!==old.voice || n!==old.notification || moved) {
                    val grokCard=v.notificationId==VOICE_COMMAND_ID
                    // The Grok Bot start (tap, no speech); the header fits the narrow trip card too.
                    voiceHeader=ellipsize(if(grokCard)"GROK BOT" else if(v.notificationId==T3_CARD_ID)"TO T3" else "REPLY TO "+n?.title.orEmpty().uppercase(),17f,cardW-130f)
                    // The Grok card's text says what was done ("Opening Grok Bot on the phone").
                    val shown=when(v.phase) { VoicePhase.SENT->v.text.ifBlank{"Sent"}; VoicePhase.STARTING->"Starting…"
                        VoicePhase.LISTENING->v.text.ifBlank{"Speak now…"}; else->v.text }
                    voiceLines=wrap(shown,VOICE_SIZE,cardW-32f,4)
                }
            }
        }
        next.media?.let { m ->
            mediaApp=m.app.uppercase()
            mediaTitle=wrap(m.title,34f,MEDIA_W-8f,2);mediaArtist=ellipsize(m.artist,26f,MEDIA_W)
        }
        if(next.t3!==old.t3 || next.t3Part!=old.t3Part) {
            val t=next.t3
            t3Thread=ellipsize(t?.thread?.ifEmpty {null} ?: "No thread",26f,T3_W)
            t3Model=ellipsize(t?.model.orEmpty(),17f,T3_W)
            t3State=when(t?.state) { null->"WAITING FOR PHONE"; T3State.READY->""; T3State.SENT->"SENT"; T3State.WORKING->"WORKING…"
                T3State.REPLY->"REPLY"; T3State.FAILED->"FAILED"; T3State.NEEDS_PC->"NEEDS PC" }
            t3Said=wrap(t?.said.orEmpty().let {if(it.isEmpty())it else "“$it”"},20f,T3_W,2)
            // The corner layout gives the page the lower half of the view, so it has room for more lines.
            val tall=next.mode!=HudMode.Map
            t3Text=wrap(t?.text.orEmpty().replace(SPACES," "),22f,T3_W,if(tall)6 else 4)
            // A reply in parts: the part on screen, bigger and with more lines, and where it is in the reply.
            val parts=t?.parts.orEmpty()
            t3Reply=if(parts.isEmpty())emptyArray() else wrap(parts[next.t3Part.coerceIn(0,parts.size-1)],24f,T3_W,if(tall)8 else 6)
            if(parts.isNotEmpty())t3State=(if(t?.reading==true)"READING" else "REPLY")+if(parts.size>1)" ${next.t3Part.coerceIn(0,parts.size-1)+1}/${parts.size}" else ""
            t3More=parts.size>1
        }
        destination=ellipsize(next.destinationName,26f,420f)
        val along=next.position?.metersAlongRoute ?: 0
        val jam=next.traffic.firstOrNull { it.level==TrafficLevel.JAM && it.endMeters>along }
        jamLabel=if(jam==null) "" else "in ${Format.distance((jam.startMeters-along).coerceAtLeast(0),next.units)} · ${Format.distance(jam.endMeters-jam.startMeters,next.units)} long"
    }
    /** Sets the road's label anchor to its point nearest (tx,ty); returns that squared distance. */
    private fun anchor(points:List<LatLng>,index:Int,tx:Float,ty:Float):Float {
        var best=Float.MAX_VALUE
        for(j in 1 until points.size) {
            val ax=x(points[j-1].lng);val ay=y(points[j-1].lat)
            val dx=x(points[j].lng)-ax;val dy=y(points[j].lat)-ay
            val fraction=if(dx*dx+dy*dy==0f)0f else (((tx-ax)*dx+(ty-ay)*dy)/(dx*dx+dy*dy)).coerceIn(0f,1f)
            val lx=ax+fraction*dx;val ly=ay+fraction*dy
            val squared=(lx-tx)*(lx-tx)+(ly-ty)*(ly-ty)
            if(squared<best){best=squared;roadEast[index]=lx;roadNorth[index]=ly}
        }
        return best
    }
    /** Google says "Bay St", OSM says "Bay Street": compare on a common short form. */
    private fun normalizeName(name:String):String {
        var n=name.lowercase().trim().replace(SPACES," ")
        for((long,short) in NAME_ABBREVIATIONS) n=n.replace(long,short)
        return n.replace(".","")
    }
    private fun routeWindow(along:Int) {
        routePath.reset()
        if(distances.isEmpty())return
        val from=along-ROUTE_BEHIND_M;val to=along+ROUTE_AHEAD_M
        var lo=0;var hi=distances.size-1
        while(lo<hi){val mid=(lo+hi+1)/2;if(distances[mid]<=from)lo=mid else hi=mid-1}
        routePath.moveTo(east[lo],north[lo])
        var i=lo+1
        while(i<distances.size){routePath.lineTo(east[i],north[i]);if(distances[i]>=to)break;i++}
    }
    private fun x(lng: Double) = (Math.toRadians((lng-originLng+540)%360-180)*6_371_000*cos(Math.toRadians(originLat))).toFloat()
    private fun y(lat: Double) = (-Math.toRadians(lat-originLat)*6_371_000).toFloat()
    private fun range(start:Double,end:Double):Path = Path().apply {
        var started=false
        for(i in 1 until distances.size) {
            val lo=maxOf(start,distances[i-1]); val hi=minOf(end,distances[i]); val len=distances[i]-distances[i-1]
            if(lo>hi || len<=0) continue
            val a=((lo-distances[i-1])/len).toFloat(); val b=((hi-distances[i-1])/len).toFloat()
            val ax=east[i-1]+(east[i]-east[i-1])*a; val ay=north[i-1]+(north[i]-north[i-1])*a
            if(!started) {moveTo(ax,ay);started=true}
            lineTo(east[i-1]+(east[i]-east[i-1])*b,north[i-1]+(north[i]-north[i-1])*b)
        }
    }
    private fun paint(size:Float=0f,alpha:Int=255,b:Boolean=false,black:Boolean=false,stroke:Float=0f):Paint {
        ink.color=if(black) Color.BLACK else GREEN; ink.alpha=alpha; ink.style=if(stroke>0) Paint.Style.STROKE else Paint.Style.FILL
        ink.strokeWidth=stroke; ink.textSize=size; ink.typeface=if(b) bold else regular; ink.pathEffect=null
        return ink
    }
    private fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,alpha:Int=255,b:Boolean=false,black:Boolean=false) { c.drawText(value,x,y,paint(size,alpha,b,black)) }
    private fun right(c:Canvas,value:String,x:Float,y:Float,size:Float,alpha:Int=255,b:Boolean=false,black:Boolean=false) {
        val p=paint(size,alpha,b,black); c.drawText(value,x-p.measureText(value),y,p)
    }
    private fun center(c:Canvas,value:String,x:Float,y:Float,size:Float,alpha:Int=255,b:Boolean=false) {
        val p=paint(size,alpha,b); c.drawText(value,x-p.measureText(value)/2,y,p)
    }
    private fun box(c:Canvas,l:Float,t:Float,r:Float,b:Float,radius:Float,fill:Boolean=false,alpha:Int=255,black:Boolean=false) {
        c.drawRoundRect(l,t,r,b,radius,radius,paint(alpha=alpha,black=black,stroke=if(fill)0f else 2f))
    }
    private fun ellipsize(value:String,size:Float,width:Float):String {
        val p=paint(size); if(p.measureText(value)<=width) return value
        val count=p.breakText(value,true,width-p.measureText("…"),null)
        return value.take(count)+"…"
    }

    fun draw(c:Canvas,nanos:Long){drawExpanded(c,nanos);if(s.voice!=null)voiceCard(c) else notificationCard(c);countdown(c)}
    /** The last seconds before the HUD lets the display sleep: 4, 3, 2, 1 in the top-right corner. */
    private fun countdown(c:Canvas){if(s.sleepIn>0)right(c,s.sleepIn.toString(),462f,44f,34f,204,b=true)}
    private fun drawExpanded(c:Canvas,nanos:Long) {
        if(!s.linkConnected && s.navState!=NavState.IDLE && s.navState!=NavState.ENDED) {
            text(c,"Phone disconnected",18f,340f,24f)
            text(c,"Reconnect in Hi Rokid",18f,376f,20f,153)
            return
        }
        if(s.phoneSilent) {
            text(c,"Phone not sending",18f,340f,24f)
            text(c,"885rokidOS → Show on glasses",18f,376f,20f,153)
            return
        }
        when {
            s.navState==NavState.ARRIVED -> arrived(c)
            s.navState==NavState.IDLE || s.navState==NavState.ENDED -> {
                // Home screen: the session now stays open without a trip, so notifications can arrive here.
                // No trip: the screen is the player when music plays, otherwise black (transparent). A
                // notification card replaces it. Only a lost phone link says anything.
                if(s.t3Visible) t3(c,PAGE_LEFT,70f,T3_H)
                else if(s.notification==null && s.voice==null) {
                    if(s.mediaVisible) media(c,PAGE_LEFT,110f)
                    else if(!s.linkConnected) center(c,"Phone not connected",240f,199f,30f,204)
                    else if(s.homeActive) center(c,ACTIVE,240f,199f,22f,204,b=true)
                    if(s.grokBotActive) center(c,GROK_ON,240f,380f,20f,153)
                }
            }
            else -> {
                banner(c)
                if(s.mode==HudMode.Map) map(c,nanos)
                lanes(c)
                status(c)
                // Alerts never replace trip status: a chip in the map's top-right corner, below the lane box when shown.
                if(s.alerts.isNotEmpty()) alertChip(c,452f,if(lanesVisible())184f else 124f,28f,compact=false)
                if(s.detour!=null) detour(c,s.detour!!)
            }
        }
    }
    /** Corner layout: upper 62% stays transparent; map labels are available in expanded mode. */
    fun drawCompact(c:Canvas,nanos:Long){drawCompactCorners(c,nanos);if(s.voice!=null)voiceCard(c) else notificationCard(c);countdown(c)}
    private fun drawCompactCorners(c:Canvas,nanos:Long) {
        if(s.navState==NavState.IDLE || s.navState==NavState.ENDED) {
            if(s.t3Visible) t3(c,PAGE_LEFT,T3_TALL_TOP,T3_TALL_H)
            else if(s.notification==null && s.voice==null) {
                if(s.mediaVisible) media(c,PAGE_LEFT,408f)
                else if(!s.linkConnected) text(c,"Phone not connected",18f,600f,28f,204)
                else if(s.homeActive) text(c,ACTIVE,18f,600f,22f,204,b=true)
                if(s.grokBotActive) text(c,GROK_ON,18f,if(s.mediaVisible)380f else 600f,20f,153)
            }
            return
        }
        if(s.navState==NavState.ARRIVED) {
            text(c,"You've arrived",18f,560f,30f,b=true)
            text(c,destination,18f,600f,22f,217)
            return
        }
        if(!s.linkConnected) {
            text(c,"Phone disconnected",18f,560f,22f)
            text(c,"Reconnect in Hi Rokid",18f,600f,18f,153)
            return
        }
        if(s.phoneSilent) {
            text(c,"Phone not sending",18f,560f,22f)
            text(c,"885rokidOS → Show on glasses",18f,600f,18f,153)
            return
        }
        // Minimal: the same corner instructions with the map turned off (third view in the tap cycle).
        if(s.mode!=HudMode.Minimal)map(c,nanos,compact=true)
        val d=s.detour
        if(d!=null) {
            text(c,detourSaving,18f,458f,28f,b=true)
            text(c,compactVia,18f,488f,18f,217)
            text(c,"TAP  take  ·  2× TAP  keep",18f,522f,16f,191)
        } else {
            val m=s.maneuver
            if(m!=null) {
                glyphs.draw(c,m.current.maneuver,18f,430f,44f,paint(stroke=6.5f))
                text(c,number,76f,464f,36f,b=true)
                text(c,unit,82f+paint(36f,b=true).measureText(number),464f,20f,191)
                text(c,compactRoad,18f,503f,22f,217)
            }
            val lanesShown=lanesVisible()
            if(!lanesShown && cue.isNotEmpty() && s.navState==NavState.ACTIVE)text(c,ellipsize(cue,15f,220f),18f,528f,15f,166)
            if(lanesShown && m!=null) {
                // Lane row (Google Maps style): your lanes bright, the others dim, thin dividers between.
                val lanes=m.current.lanes;val count=minOf(lanes.size,8)
                val step=minOf(36f,212f/count);val size=minOf(30f,step-4f)
                var laneIndex=0
                while(laneIndex<count) {
                    val lane=lanes[laneIndex];val lx=18f+laneIndex*step;var direction=0
                    while(direction<lane.directions.size) {
                        glyphs.draw(c,lane.directions[direction],lx,508f,size,paint(alpha=laneAlpha(lane,lane.directions[direction]),stroke=7.5f));direction++
                    }
                    if(laneIndex>0)c.drawLine(lx-3f,510f,lx-3f,508f+size,paint(alpha=77,stroke=1.5f))
                    laneIndex++
                }
            }
            if(s.navState==NavState.GPS_LOST || s.navState==NavState.REROUTING)
                text(c,if(s.navState==NavState.GPS_LOST)"GPS lost" else "Rerouting",18f,534f,20f)
        }
        // Alerts sit in the map box's top-left corner and never displace the ETA lines.
        if(s.alerts.isNotEmpty()) alertChip(c,null,422f,22f,compact=true)
        if(s.progress!=null) {
            text(c,eta,18f,570f,24f,b=true)
            text(c,"ETA",24f+paint(24f,b=true).measureText(eta),570f,15f,153)
            text(c,statusTime,18f,602f,20f,217)
            right(c,statusDistance,228f,602f,20f,217)
        }
    }
    private fun banner(c:Canvas) {
        val m=s.maneuver ?: return
        glyphs.draw(c,m.current.maneuver,18f,16f,84f,paint(stroke=6.5f))
        // With a turn cue the three lines move up 9 px so the cue fits above the progress bar.
        val lift=if(cue.isNotEmpty())9f else 0f
        text(c,number,118f,65f-lift,52f,b=true)
        val numberWidth=paint(52f,b=true).measureText(number)
        text(c,unit,124f+numberWidth,65f-lift,22f,191)
        if(exit.isNotEmpty()) { box(c,118f,79f-lift,200f,105f-lift,6f); text(c,exit,124f,99f-lift,18f,b=true); text(c,road,210f,101f-lift,24f) }
        else text(c,road,118f,99f-lift,24f)
        if(cue.isNotEmpty())text(c,cue,118f,108f,16f,179)
        val nextStep=m.next
        if(nextStep!=null && m.distanceToStepMeters>=150) {
            box(c,380f,22f,462f,54f,10f,alpha=179)
            text(c,"then",390f,44f,16f,179)
            glyphs.draw(c,nextStep.maneuver,430f,27f,22f,paint(alpha=179,stroke=9f))
        }
        if(m.distanceToStepMeters<150) {
            val barY=if(cue.isNotEmpty())115f else 110f
            c.drawLine(18f,barY,462f,barY,paint(alpha=89,stroke=4f))
            c.drawLine(18f,barY,18f+444f*(m.distanceToStepMeters/150f).coerceIn(0f,1f),barY,paint(stroke=4f))
        }
        if(s.navState==NavState.GPS_LOST || s.navState==NavState.REROUTING) {
            box(c,290f,78f,466f,110f,6f,fill=true,black=true)
            right(c,if(s.navState==NavState.GPS_LOST) "GPS lost" else "Rerouting",460f,101f,22f)
        }
    }
    /**
     * Lane guidance shows whenever Google includes lanes for the next maneuver (it only does where lanes
     * matter) and that maneuver is within [LANE_SHOW_M]; it was previously held back until 150 m.
     */
    private fun lanesVisible():Boolean {
        val m=s.maneuver ?: return false
        return s.navState==NavState.ACTIVE && m.current.lanes.isNotEmpty() && m.distanceToStepMeters<=LANE_SHOW_M
    }
    /**
     * Your lanes bright, others dim. In a lane allowing several directions (e.g. straight + right) only the
     * arrow that matches the maneuver is bright, like Google Maps, so overlapping arrows stay readable.
     */
    private fun laneAlpha(lane:Lane,direction:Maneuver):Int {
        if(!lane.recommended)return 64
        if(lane.directions.size<2)return 255
        val turn=s.maneuver?.current?.maneuver ?: return 255
        return if(laneSide(direction)==laneSide(turn))255 else 90
    }
    private fun laneSide(m:Maneuver)=when(m) {
        Maneuver.TURN_LEFT,Maneuver.TURN_SLIGHT_LEFT,Maneuver.TURN_SHARP_LEFT,Maneuver.U_TURN_LEFT,Maneuver.MERGE_LEFT,
        Maneuver.FORK_LEFT,Maneuver.ON_RAMP_LEFT,Maneuver.OFF_RAMP_LEFT,Maneuver.DESTINATION_LEFT,Maneuver.ROUNDABOUT_COUNTERCLOCKWISE->-1
        Maneuver.TURN_RIGHT,Maneuver.TURN_SLIGHT_RIGHT,Maneuver.TURN_SHARP_RIGHT,Maneuver.U_TURN_RIGHT,Maneuver.MERGE_RIGHT,
        Maneuver.FORK_RIGHT,Maneuver.ON_RAMP_RIGHT,Maneuver.OFF_RAMP_RIGHT,Maneuver.DESTINATION_RIGHT,Maneuver.ROUNDABOUT_CLOCKWISE->1
        else->0
    }
    private fun lanes(c:Canvas) {
        val lanes=s.maneuver?.current?.lanes ?: return
        if(lanes.isEmpty() || !lanesVisible()) return
        val count=minOf(lanes.size,8); val width=count*34f+20f; val left=240f-width/2
        box(c,left,128f,left+width,178f,10f,fill=true,black=true);box(c,left,128f,left+width,178f,10f)
        var i=0
        while(i<count) {
            val lane=lanes[i]; var j=0
            while(j<lane.directions.size) { glyphs.draw(c,lane.directions[j],left+10+i*34,133f,34f,paint(alpha=laneAlpha(lane,lane.directions[j]),stroke=8f));j++ }
            if(i>0)c.drawLine(left+8+i*34,136f,left+8+i*34,170f,paint(alpha=77,stroke=2f))
            i++
        }
    }
    private fun map(c:Canvas,nanos:Long,compact:Boolean=false) {
        val position=s.position ?: return
        // Continuous auto-zoom (MapZoom): speed sets the cruise view, an upcoming turn pulls it in early.
        // The big map shows EXPANDED_ZOOM_OUT× more road than the corner map, for an overview of the route.
        val target=MapZoom.targetMeters(position.speedMps,s.maneuver?.distanceToStepMeters,s.maneuver?.current?.maneuver,s.mapZoom)*
            (if(compact)1f else EXPANDED_ZOOM_OUT)
        val dt=if(lastFrameNanos==0L)0f else ((nanos-lastFrameNanos)/1e9f).coerceIn(0f,0.5f)
        lastFrameNanos=nanos
        aheadMeters=MapZoom.ease(aheadMeters,target,dt)
        zoomTarget=target
        val ppm=(if(compact)148f else 188f)/aheadMeters
        lastPpm=ppm
        val t=((nanos-receivedNanos)/1_000_000.0/transitionMs).toFloat().coerceIn(0f,1f)
        val px=previousX+(currentX-previousX)*t; val py=previousY+(currentY-previousY)*t
        val heading=previousBearing+((currentBearing-previousBearing+540)%360-180)*t
        val cx=if(compact)360f else if(s.detour==null)240f else 380f
        val anchor=if(compact)580f else 306f
        c.save()
        if(compact)c.clipRect(254f,418f,466f,604f) else c.clipRect(0f,118f,450f,338f)
        c.translate(cx,anchor);c.rotate(-heading);c.scale(ppm,ppm);c.translate(-px,-py)
        // Streets: visible but clearly below the route. Residential 2.5 px at 45%, major 4 px at 65%.
        c.drawPath(minorRoads,paint(alpha=115,stroke=(if(compact)2f else 2.5f)/ppm))
        c.drawPath(majorRoads,paint(alpha=166,stroke=(if(compact)3f else 4f)/ppm))
        // Route: a bright hollow tube with a black middle (user request 2026-10-20), plus a black outer
        // edge that separates it from any street it runs along or crosses. Congestion fills the middle:
        // slow traffic dashed, a jam solid, so a jammed stretch reads as one thick bright line.
        val routeW=if(compact)8f else 12f
        c.drawPath(routePath,paint(black=true,stroke=(routeW+6f)/ppm))
        c.drawPath(routePath,paint(stroke=routeW/ppm))
        c.drawPath(routePath,paint(black=true,stroke=routeW*0.45f/ppm))
        var seg=0
        while(seg<trafficPaths.size) {
            if(trafficLevels[seg]!=TrafficLevel.NORMAL) {
                val p=paint(stroke=routeW*0.5f/ppm)
                if(trafficLevels[seg]==TrafficLevel.SLOW)p.pathEffect=slowDash(ppm)
                c.drawPath(trafficPaths[seg],p)
            }
            seg++
        }
        // Turn arrow just past the turn, pointing the way to go, once the turn is in view.
        val toStep=s.maneuver?.distanceToStepMeters
        if(hasManeuverPoint && toStep!=null && toStep<aheadMeters && turnArrowAt(position.metersAlongRoute.coerceAtLeast(0)+toStep+25.0)) {
            c.save();c.translate(arrowX,arrowY);c.rotate(arrowDeg);c.scale(1/ppm,1/ppm)
            val size=if(compact)9f else 13f
            turnArrow.reset();turnArrow.moveTo(0f,-size);turnArrow.lineTo(size*0.9f,size*0.7f);turnArrow.lineTo(-size*0.9f,size*0.7f);turnArrow.close()
            c.drawPath(turnArrow,paint(black=true,stroke=5f));c.drawPath(turnArrow,paint())
            c.restore()
        }
        if(s.alerts.isNotEmpty()) {
            val a=s.alerts[0];val ax=x(a.lng);val ay=y(a.lat)
            c.drawCircle(ax,ay,16/ppm,paint(black=true));c.drawCircle(ax,ay,16/ppm,paint(stroke=3/ppm))
            c.save();c.translate(ax,ay);c.rotate(heading);c.scale(1/ppm,1/ppm);icon(c,a.kind,-12f,-12f,24f);c.restore()
        }
        c.restore()
        if(compact) {
            labels(c,nanos,px,py,heading,ppm,cx,anchor,258f,462f,426f,600f,COMPACT_LABELS,0.85f)
            c.save();c.translate(cx,anchor);c.scale(.65f,.65f);c.drawPath(chevron,paint());c.restore()
            return
        }
        labels(c,nanos,px,py,heading,ppm,cx,306f,18f,446f,if(lanesVisible())192f else 136f,326f,MAX_LABELS,1f)
        c.save();c.translate(cx,306f);c.drawPath(chevron,paint());c.restore()
        // Remaining-route ribbon, normalized by meters rather than polyline point index.
        box(c,458f,130f,466f,326f,4f,alpha=204)
        val along=position.metersAlongRoute.coerceAtLeast(0);val remaining=(s.routeTotalMeters-along).coerceAtLeast(1)
        var i=0
        while(i<s.traffic.size) {
            val segment=s.traffic[i]
            if(segment.endMeters>along && segment.level!=TrafficLevel.NORMAL) {
                val top=326f-196f*((segment.endMeters-along).toFloat()/remaining).coerceIn(0f,1f)
                val bottom=326f-196f*((segment.startMeters-along).toFloat()/remaining).coerceIn(0f,1f)
                c.drawRect(458f,top,466f,bottom,paint(alpha=if(segment.level==TrafficLevel.SLOW)115 else 255))
            };i++
        }
        c.drawCircle(462f,318f,6f,paint());c.drawCircle(462f,318f,6f,paint(black=true,stroke=2f))
        if(jamLabel.isNotEmpty() && s.detour==null && s.traffic.anyJamInWindow(along,aheadMeters.toInt())) {
            c.drawLine(226f,196f,170f,196f,paint(alpha=179,stroke=2f));right(c,"Heavy traffic",162f,190f,20f,b=true)
            right(c,jamLabel,162f,210f,17f,204)
        }
        if(s.detour!=null) { val p=paint(stroke=4f);p.pathEffect=alternativeDash;c.drawLine(430f,282f,440f,128f,p) }
    }
    /**
     * Upright street names in priority order ([labelOrder]) inside the given screen box: the turn street
     * bold and bright, the "after" street and major roads brighter than the rest. Skips labels that would
     * overlap one already placed or the chevron. A black halo cuts the road lines behind each name.
     */
    private fun labels(c:Canvas,nanos:Long,px:Float,py:Float,heading:Float,ppm:Float,cx:Float,cy:Float,
                       left:Float,right:Float,top:Float,bottom:Float,max:Int,scale:Float) {
        val angle=Math.toRadians(heading.toDouble())
        val cosine=cos(angle).toFloat();val sine=sin(angle).toFloat()
        var placed=0
        val mayAdd=nanos-lastLabelAddNanos>=LABEL_ADD_GAP_NS
        var added=false
        // Pass 0: the turn street and streets already on screen keep their place. Pass 1: one new street.
        for(pass in 0..1) {
        var k=0
        while(k<labelOrder.size && placed<max) {
            val i=labelOrder[k];k++
            val g=roadGroup.getOrElse(i){-1}
            val keep=i==turnRoad || (g>=0 && groupShown[g])
            if(pass==0 && !keep)continue
            if(pass==1 && (keep || added || (!mayAdd && placed>0)))continue
            val turn=i==turnRoad;val after=i==cueRoad
            val size=(if(turn)LABEL_TURN else if(after || roadMajor[i])LABEL_MAJOR else LABEL_MINOR)*scale
            val alpha=if(turn)255 else if(after)166 else if(roadMajor[i])204 else 153
            val w=paint(size,b=turn).measureText(roadNames[i])
            val dx=roadEast[i]-px;val dy=roadNorth[i]-py
            val lx=cx+(dx*cosine+dy*sine)*ppm+8*scale
            val ly=cy+(-dx*sine+dy*cosine)*ppm-8*scale
            if(lx<left || lx+w>right || ly-size<top || ly>bottom){if(g>=0)groupShown[g]=false;continue}
            if(abs(lx+w/2-cx)<w/2+24*scale && abs(ly-cy)<40*scale){if(g>=0)groupShown[g]=false;continue}
            var overlaps=false;var j=0
            while(j<placed){
                if(lx<placedLabelX[j]+placedLabelW[j]+6 && placedLabelX[j]<lx+w+6 && abs(ly-placedLabelY[j])<size+4)overlaps=true
                j++
            }
            if(overlaps){if(g>=0)groupShown[g]=false;continue}
            val halo=paint(size,b=turn,black=true,stroke=4f*scale);c.drawText(roadNames[i],lx,ly,halo)
            c.drawText(roadNames[i],lx,ly,paint(size,alpha,b=turn))
            placedLabelX[placed]=lx;placedLabelY[placed]=ly;placedLabelW[placed]=w;placed++
            if(g>=0 && !groupShown[g]){groupShown[g]=true;if(pass==1){added=true;lastLabelAddNanos=nanos}}
        }
        }
    }
    /** 7 px on / 6 px off on screen; rebuilt only when the scale changes by more than 10%. */
    private fun slowDash(ppm:Float):DashPathEffect {
        if(slowDashEffect==null || kotlin.math.abs(ppm-slowDashPpm)>slowDashPpm*0.1f) {
            slowDashPpm=ppm;slowDashEffect=DashPathEffect(floatArrayOf(7f/ppm,6f/ppm),0f)
        }
        return slowDashEffect!!
    }
    private var slowDashEffect:DashPathEffect?=null
    private var slowDashPpm=0f
    /** Sets [arrowX]/[arrowY]/[arrowDeg] to the route point [meters] along and its heading; false past the end. */
    private fun turnArrowAt(meters:Double):Boolean {
        var i=1
        while(i<distances.size && distances[i]<meters)i++
        if(i>=distances.size)return false
        val len=(distances[i]-distances[i-1]).coerceAtLeast(.01)
        val f=((meters-distances[i-1])/len).toFloat()
        val dx=east[i]-east[i-1];val dy=north[i]-north[i-1]
        arrowX=east[i-1]+dx*f;arrowY=north[i-1]+dy*f
        arrowDeg=Math.toDegrees(kotlin.math.atan2(dx.toDouble(),-dy.toDouble())).toFloat()
        return true
    }
    private var arrowX=0f
    private var arrowY=0f
    private var arrowDeg=0f
    private val turnArrow=Path()
    private var aheadMeters=0f
    private var zoomTarget=0f
    /** True while the map glides between two position fixes; once it reaches the latest fix (no newer
     *  one yet, e.g. stopped or a late fix) there is nothing left to animate. */
    val moving get()=s.position!=null && (s.navState==NavState.ACTIVE || s.navState==NavState.REROUTING) &&
        (currentX!=previousX || currentY!=previousY || currentBearing!=previousBearing) &&
        System.nanoTime()-receivedNanos<transitionMs*1_000_000L
    /** True while the map zoom is still easing toward its target, so the view keeps redrawing. */
    val zoomSettling get()=aheadMeters>0f && kotlin.math.abs(aheadMeters-zoomTarget)>aheadMeters*0.01f
    private var lastFrameNanos=0L
    private var lastPpm=0f
    /**
     * Delay to the next map frame, at least [minMs], or 0 for none. Zoom easing runs at [minMs]. The glide between
     * two fixes draws a frame only per [MIN_STEP_PX] of movement on screen (slide or turn), so a slow or zoomed-out
     * map draws fewer frames; under one step per second it waits for the next fix.
     */
    fun nextFrameMs(minMs:Long):Long {
        if(zoomSettling)return minMs
        if(!moving)return 0
        val slide=kotlin.math.hypot(currentX-previousX,currentY-previousY)*lastPpm
        val turn=kotlin.math.abs((currentBearing-previousBearing+540f)%360f-180f)*(Math.PI.toFloat()/180f)*TURN_RADIUS_PX
        val pxPerSecond=maxOf(slide,turn)/(transitionMs/1000f)
        if(pxPerSecond<MIN_STEP_PX)return 0
        return (1000f*MIN_STEP_PX/pxPerSecond).toLong().coerceAtLeast(minMs)
    }
    private fun List<TrafficSegment>.anyJamInWindow(along:Int,visible:Int):Boolean {
        var i=0;while(i<size){val t=this[i];if(t.level==TrafficLevel.JAM && t.endMeters>along && t.startMeters<along+visible)return true;i++};return false
    }
    private fun status(c:Canvas) {
        if(s.progress==null)return
        text(c,eta,18f,377f,26f,b=true)
        text(c,"ETA",24f+paint(26f,b=true).measureText(eta),377f,13f,153)
        // Fixed slots reserve room for traffic, including long durations and metric distances.
        text(c,statusTime,130f,377f,22f,217)
        text(c,statusDistance,244f,377f,22f,217)
        val delayed=(s.progress?.trafficDelaySeconds ?: 0)>0
        if(delayed) {
            val filled=(s.progress?.trafficDelaySeconds ?: 0)>=300
            box(c,362f,352f,464f,386f,8f,fill=filled)
            c.drawCircle(378f,369f,7f,paint(black=filled,stroke=2f));c.drawLine(378f,365f,378f,369f,paint(black=filled,stroke=2f));c.drawLine(378f,369f,382f,371f,paint(black=filled,stroke=2f))
            right(c,delayLabel,456f,376f,20f,b=true,black=filled)
        } else right(c,"No delays",464f,376f,18f,153)
    }
    private fun icon(c:Canvas,kind:AlertKind,x:Float,y:Float,size:Float,black:Boolean=false) {
        c.save();c.translate(x,y);c.scale(size/24,size/24)
        val p=paint(black=black,stroke=2.2f)
        when(kind) {
            AlertKind.POLICE -> c.drawPath(shield,p)
            AlertKind.SPEED_CAMERA -> {c.drawRoundRect(3f,6f,21f,20f,2f,2f,p);c.drawCircle(12f,13f,4f,p);c.drawLine(7f,3f,17f,3f,p)}
            AlertKind.TRAFFIC_JAM -> {c.drawRoundRect(4f,8f,20f,19f,2f,2f,p);c.drawLine(6f,8f,8f,4f,p);c.drawLine(8f,4f,16f,4f,p);c.drawLine(16f,4f,18f,8f,p)}
            AlertKind.ROAD_CLOSED -> {c.drawCircle(12f,12f,9f,p);c.drawLine(5f,19f,19f,5f,p)}
            else -> {c.drawLine(12f,3f,2f,21f,p);c.drawLine(2f,21f,22f,21f,p);c.drawLine(22f,21f,12f,3f,p);c.drawLine(12f,9f,12f,14f,p);c.drawPoint(12f,18f,p)}
        };c.restore()
    }
    /**
     * Passive alert chip: icon, kind and distance, plus "+N" when more are queued. Filled for the first
     * [ALERT_FRESH_MS] after an alert first appears so a glance catches it, then an outlined, dimmer chip
     * until the phone clears it. [right] anchors the expanded chip; null = compact, anchored at the map's left.
     */
    private fun alertChip(c:Canvas,right:Float?,top:Float,height:Float,compact:Boolean) {
        val a=s.alerts.firstOrNull() ?: return
        val fresh=(s.alertSeenAt[a.id]?.let {s.nowMs-it} ?: 0)<ALERT_FRESH_MS
        val kindSize=if(compact)13f else 16f;val distSize=if(compact)14f else 18f;val iconSize=if(compact)15f else 18f
        val more=if(s.alerts.size>1)" +${s.alerts.size-1}" else ""
        val kindW=paint(kindSize,b=true).measureText(alertKind)
        val distW=paint(distSize,b=true).measureText(alertDistance)
        val moreW=if(more.isEmpty())0f else paint(kindSize).measureText(more)
        val width=8+iconSize+6+kindW+8+distW+moreW+8
        val left=right?.let {it-width} ?: 258f
        box(c,left,top,left+width,top+height,8f,fill=true,black=!fresh)
        if(!fresh)box(c,left,top,left+width,top+height,8f,alpha=191)
        val ink=if(fresh)255 else 204
        icon(c,a.kind,left+8,top+(height-iconSize)/2,iconSize,black=fresh)
        val baseline=top+height/2+distSize*0.36f
        var x=left+8+iconSize+6
        text(c,alertKind,x,baseline,kindSize,ink,b=true,black=fresh);x+=kindW+8
        text(c,alertDistance,x,baseline,distSize,ink,b=true,black=fresh);x+=distW
        if(more.isNotEmpty())text(c,more,x,baseline,kindSize,if(fresh)255 else 153,black=fresh)
    }
    private fun detour(c:Canvas,d:DetourOffer) {
        box(c,14f,124f,334f,312f,14f,fill=true,black=true);box(c,14f,124f,334f,312f,14f)
        text(c,"FASTER ROUTE",30f,151f,15f,179,b=true);text(c,detourSaving,30f,195f,44f,b=true)
        text(c,detourVia,30f,223f,20f,217)
        c.drawLine(30f,242f,316f,242f,paint(alpha=64,stroke=4f))
        c.drawLine(30f,242f,30f+286f*((d.expiresAtMs-s.nowMs)/30_000f).coerceIn(0f,1f),242f,paint(stroke=4f))
        box(c,30f,264f,69f,287f,6f,fill=true);text(c,"TAP",37f,281f,15f,b=true,black=true);text(c,"take it",76f,282f,18f)
        box(c,159f,264f,218f,287f,6f,alpha=191);text(c,"2× TAP",165f,281f,15f,191,b=true);text(c,"keep",227f,282f,18f,191)
    }
    /** Now playing: app and play state, track, artist. No bar or times: nothing moves, so nothing redraws. */
    private fun media(c:Canvas,left:Float,top:Float) {
        val right=left+MEDIA_W
        text(c,mediaApp,left,top+20f,18f,179,b=true)
        // The icon shows the state: two bars while playing, a triangle while paused.
        if(s.media?.playing==true) {
            c.drawLine(right-22f,top+2f,right-22f,top+24f,paint(stroke=6f));c.drawLine(right-8f,top+2f,right-8f,top+24f,paint(stroke=6f))
        } else {
            c.save();c.translate(right-24f,top+1f);c.drawPath(playTriangle,paint());c.restore()
        }
        var y=top+64f
        for(i in mediaTitle.indices){text(c,mediaTitle[i],left,y,34f,b=true);y+=40f}
        y-=40f
        text(c,mediaArtist,left,y+40f,28f,224)
    }
    /**
     * T3 page: thread, model, the words sent, the run state and the reply (4 lines; the phone reads all of it aloud).
     * Always full brightness; the footer says what the next tap does, [height] below [top].
     */
    private fun t3(c:Canvas,left:Float,top:Float,height:Float) {
        if(t3Reply.isNotEmpty()){t3Reply(c,left,top,height);return}
        val a=255
        text(c,"T3",left,top+20f,18f,a*179/255,b=true)
        right(c,t3State,left+T3_W,top+20f,18f,a*179/255,b=true)
        text(c,t3Thread,left,top+52f,26f,a,b=true)
        text(c,t3Model,left,top+76f,17f,a*153/255)
        var y=top+106f
        for(line in t3Said){text(c,line,left,y,20f,a*191/255);y+=26f}
        for(line in t3Text){text(c,line,left,y+4f,22f,a);y+=28f}
        text(c,if(s.awake)"TAP to speak" else "TAP to wake",left,top+height,18f,a*191/255,b=true)
    }
    /** A reply: thread, then the part being read (or picked) in big text; the voice reads the same words. */
    private fun t3Reply(c:Canvas,left:Float,top:Float,height:Float) {
        text(c,"T3",left,top+20f,18f,179,b=true)
        right(c,t3State,left+T3_W,top+20f,18f,179,b=true)
        text(c,t3Thread,left,top+50f,22f,204,b=true)
        var y=top+86f
        for(line in t3Reply){text(c,line,left,y,24f);y+=30f}
        text(c,if(!s.awake)"TAP to wake" else if(t3More)"SWIPE for more · TAP to speak" else "TAP to speak",left,top+height,18f,191,b=true)
    }
    /** Phone notification: app, title, wrapped text. Black fill hides what is under it; a detour wins. */
    private fun notificationCard(c:Canvas) {
        if(s.notification==null || s.detour!=null) return
        val lines=notificationLines.size
        val reply=s.replyAvailable
        val height=(if(lines==0)72f else 94f+(lines-1)*cardStep+16f)+(if(reply)30f else 0f)
        box(c,cardLeft,cardTop,cardLeft+cardW,cardTop+height,12f,fill=true,black=true)
        box(c,cardLeft,cardTop,cardLeft+cardW,cardTop+height,12f)
        text(c,notificationApp,cardLeft+16f,cardTop+26f,17f,179,b=true)
        // Seconds until the card goes away.
        right(c,notificationLeft,cardLeft+cardW-16f,cardTop+30f,26f,b=true)
        text(c,notificationTitle,cardLeft+16f,cardTop+60f,26f,b=true)
        for(i in 0 until lines)text(c,notificationLines[i],cardLeft+16f,cardTop+94f+i*cardStep,cardSize,230)
        // The app has a reply box: say what a tap does.
        if(reply)text(c,"TAP  reply by voice",cardLeft+16f,cardTop+height-12f,18f,191,b=true)
    }
    /**
     * Voice reply: the words as they are heard, then Send / Redo / Cancel. Sits at the bottom of the view (the same width as the
     * card) and grows upward so a long message and three rows still fit.
     */
    private fun voiceCard(c:Canvas) {
        val v=s.voice ?: return
        val review=v.phase==VoicePhase.REVIEW
        val lines=voiceLines.size.coerceAtLeast(1)
        val textEnd=70f+(lines-1)*VOICE_STEP+14f
        val height=when(v.phase) { VoicePhase.REVIEW->textEnd+3*VOICE_ROW+8f; VoicePhase.LISTENING,VoicePhase.STARTING->textEnd+30f; else->textEnd+6f }
        val limit=if((cardLayout and 2)!=0)620f else 384f
        val top=minOf(cardTop,limit-height).coerceAtLeast(8f)
        box(c,cardLeft,top,cardLeft+cardW,top+height,12f,fill=true,black=true)
        box(c,cardLeft,top,cardLeft+cardW,top+height,12f)
        text(c,voiceHeader,cardLeft+16f,top+26f,17f,179,b=true)
        val status=when(v.phase) { VoicePhase.STARTING->"STARTING"; VoicePhase.LISTENING->"LISTENING"; VoicePhase.REVIEW->"CHECK"; VoicePhase.SENT->"DONE"; else->"" }
        right(c,status,cardLeft+cardW-16f,top+26f,17f,179,b=true)
        val dim=if((v.phase==VoicePhase.LISTENING && v.text.isBlank()) || v.phase==VoicePhase.STARTING)153 else 245
        for(i in voiceLines.indices)text(c,voiceLines[i],cardLeft+16f,top+70f+i*VOICE_STEP,VOICE_SIZE,dim,b=v.phase==VoicePhase.SENT)
        // The narrow card has room for "cancel" only; a pause still ends listening by itself.
        if(v.phase==VoicePhase.LISTENING || v.phase==VoicePhase.STARTING)text(c,if(v.phase==VoicePhase.STARTING || cardW<NARROW_CARD)"2× TAP  cancel" else "TAP  done  ·  2× TAP  cancel",cardLeft+16f,top+height-12f,18f,191,b=true)
        if(review) {
            val labels=VOICE_ROWS
            for(i in labels.indices) {
                val rowTop=top+textEnd+i*VOICE_ROW
                val focused=s.voiceFocus==i
                if(focused)box(c,cardLeft+10f,rowTop,cardLeft+cardW-10f,rowTop+VOICE_ROW-6f,9f)
                text(c,labels[i],cardLeft+26f,rowTop+VOICE_ROW-16f,26f,if(focused)255 else 140,b=focused)
            }
        }
    }
    /** Splits [value] at spaces into at most [maxLines] lines that fit [width]; the last one ends in "…" if cut. */
    private fun wrap(value:String,size:Float,width:Float,maxLines:Int):Array<String> {
        val lines=ArrayList<String>(maxLines)
        var rest=value.trim()
        while(rest.isNotEmpty() && lines.size<maxLines) {
            val fit=paint(size).breakText(rest,true,width,null)
            if(fit>=rest.length){lines+=rest;break}
            if(lines.size==maxLines-1){lines+=ellipsize(rest,size,width);break}
            val cut=rest.lastIndexOf(' ',fit).takeIf {it>0} ?: fit
            lines+=rest.substring(0,cut).trimEnd()
            rest=rest.substring(cut).trimStart()
        }
        return lines.toTypedArray()
    }
    private fun arrived(c:Canvas) {
        c.drawCircle(240f,112f,42f,paint(stroke=6f));c.drawLine(221f,114f,234f,127f,paint(stroke=6f));c.drawLine(234f,127f,260f,99f,paint(stroke=6f))
        center(c,"You've arrived",240f,204f,44f,b=true);center(c,destination,240f,247f,26f,217)
        center(c,eta,240f,286f,18f,140);center(c,"Closes in 10 s · 2× TAP to close now",240f,320f,15f,115)
    }
    companion object {
        const val GREEN = 0xFF3DFF8A.toInt()
        private const val EXPANDED_ZOOM_OUT = 2.5f
        /** Route kept in [routePath]: the big map shows at most ~6 km ahead (MapZoom × EXPANDED_ZOOM_OUT)
         *  plus its corners. */
        private const val ROUTE_AHEAD_M = 9_000
        private const val ROUTE_BEHIND_M = 600
        /** At most this many street names at once (big map / corner map); user request 2026-10-20. */
        private const val MAX_LABELS = 4
        private const val COMPACT_LABELS = 2
        /** At most one new street name appears per this interval, so names don't keep swapping. */
        private const val LABEL_ADD_GAP_NS = 2_000_000_000L
        /** On a road-context refresh a street keeps its label point if a piece is still this close (m). */
        private const val STICKY_REANCHOR_M = 60f
        private const val LANE_SHOW_M = 2_000
        // Street-name sizes (expanded; compact draws them at 0.85×). Raised ~20% on 2026-10-18: the
        // waveguide resolution made 14–17 px names hard to read.
        private const val LABEL_TURN = 20f
        private const val LABEL_MAJOR = 18f
        private const val LABEL_MINOR = 17f
        const val ALERT_FRESH_MS = 6_000L
        /** Pages without a trip (T3, player, cards) use nearly the whole width: 8 to 472 of 480. */
        private const val PAGE_LEFT = 8f
        private const val PAGE_W = 464f
        private const val MEDIA_W = PAGE_W
        private const val T3_W = PAGE_W
        /** T3 page heights to its footer: the expanded view (6 reply lines), and the corner view's lower half (8 lines). */
        private const val T3_H = 262f
        private const val T3_TALL_TOP = 300f
        private const val T3_TALL_H = 322f
        /** Smallest map movement worth a frame, in design px. */
        const val MIN_STEP_PX = 2f
        /** A turn moves the map edge about this far from the chevron per radian, in design px. */
        private const val TURN_RADIUS_PX = 150f
        /** Cards narrower than this are the trip's corner card (222 wide). */
        private const val NARROW_CARD = 300f
        /** Shown without a trip while a Grok Bot chat runs (on a trip the display stays on anyway). */
        private const val GROK_ON = "GROK BOT  ·  TAP to end"
        /** Home, nothing on it, awake: the next tap starts Grok Bot. */
        private const val ACTIVE = "ACTIVE  ·  TAP for Grok Bot"
        private const val VOICE_SIZE = 26f
        private const val VOICE_STEP = 32f
        private const val VOICE_ROW = 44f
        private val VOICE_ROWS = arrayOf("Send","Redo","Cancel")
        private val SPACES = Regex("\\s+")
        private val NAME_ABBREVIATIONS = listOf("street" to "st","avenue" to "ave","boulevard" to "blvd","road" to "rd",
            "drive" to "dr","lane" to "ln","place" to "pl","court" to "ct","highway" to "hwy","parkway" to "pkwy",
            "north" to "n","south" to "s","east" to "e","west" to "w").map { (long,short) -> Regex("\\b$long\\b") to short }
    }
}
