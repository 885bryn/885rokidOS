package com.rokidos885.phone.traffic

import com.rokidos885.phone.nav.*
import com.rokidos885.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Polls only during guidance. Traffic geometry is projected onto the active route, not substituted. */
class TrafficMonitor(private val routes:RoutesApiClient,private val engine:NavigationEngine,
    private val evaluator:DetourEvaluator=DetourEvaluator(),private val pollIntervalMs:Long=150_000) {
    private val _segments=MutableStateFlow<List<TrafficSegment>>(emptyList())
    val segments:Flow<List<TrafficSegment>> = _segments.asSharedFlow()
    private val _detours=MutableSharedFlow<DetourEvaluator.Decision>(extraBufferCapacity=1)
    val detours:Flow<DetourEvaluator.Decision> = _detours.asSharedFlow()
    private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
    private val refresh=Channel<Unit>(Channel.CONFLATED)
    private var job:Job?=null
    private var latestPosition:PositionUpdate?=null
    private var latestProgress:TripProgress?=null
    private val mutex=Mutex()
    private var planned:RouteOption?=null
    fun seed(option:RouteOption){planned=option;engine.activeRoute.value?.let {_segments.value=projectTraffic(it,option)}}
    fun start(scope:CoroutineScope) {
        if(job!=null)return
        job=scope.launch {
            launch {engine.position.collect {latestPosition=it}}
            launch {engine.progress.collect {latestProgress=it}}
            combine(engine.activeRoute,engine.navState){route,state->route.takeIf {state==NavState.ACTIVE}}
                .collectLatest {route->
                    _segments.emit(if(route==null)emptyList() else planned?.let {projectTraffic(route,it)}.orEmpty())
                    if(route==null)return@collectLatest
                    while(isActive) {
                        val forced=withTimeoutOrNull(pollIntervalMs){refresh.receive();true} ?: false
                        poll(route,forced)
                    }
                }
        }
    }
    fun forceRefresh(){refresh.trySend(Unit)}
    private suspend fun poll(route:ActiveRoute,forced:Boolean)=mutex.withLock {
        val fix=latestPosition ?: return@withLock
        val progress=latestProgress ?: return@withLock
        val destination=engine.destination.value ?: return@withLock
        if(engine.activeRoute.value?.routeId!=route.routeId)return@withLock
        if(!forced && (fix.speedMps<1f || progress.remainingMeters<2000))return@withLock
        val result=routes.computeRoutes(LatLng(fix.lat,fix.lng),if(fix.speedMps>1)fix.bearingDeg.toInt() else null,
            destination.location,destination.placeId)
        if(engine.activeRoute.value?.routeId!=route.routeId)return@withLock
        result.onSuccess { options ->
            _error.value=null
            // The primary returned route might differ. Only use traffic where it matches our geometry.
            options.firstOrNull()?.let { _segments.emit(projectTraffic(route,it)) }
            evaluator.evaluate(progress.remainingSeconds,progress.remainingMeters,options,System.currentTimeMillis())?.let {_detours.emit(it)}
        }.onFailure {_error.value=it.message}
    }
    fun stop(){job?.cancel();job=null}
    fun dismiss(option:RouteOption,nowMs:Long)=evaluator.markDismissed(option,nowMs)
    companion object {
        fun projectTraffic(active:ActiveRoute,option:RouteOption):List<TrafficSegment> {
            val target=PolylineCodec.decode(active.encodedPolyline)
            val points=PolylineCodec.decode(option.encodedPolyline)
            if(target.size<2 || points.size<2)return emptyList()
            return option.speedIntervals.mapNotNull {interval ->
                val start=points.getOrNull(interval.startIndex) ?: return@mapNotNull null
                val end=points.getOrNull(interval.endIndex) ?: return@mapNotNull null
                // Check the whole sampled interval, preventing unrelated roads from acquiring traffic.
                val mid=points[(interval.startIndex+interval.endIndex)/2]
                if(listOf(start,mid,end).any {(Geo.projectOntoPolyline(target,it)?.distanceMeters ?: Double.MAX_VALUE)>45})return@mapNotNull null
                val lo=Geo.metersAlongPolyline(target,start).toInt();val hi=Geo.metersAlongPolyline(target,end).toInt()
                if(hi<=lo)return@mapNotNull null
                TrafficSegment(lo,hi,when(interval.speed){"SLOW"->TrafficLevel.SLOW;"TRAFFIC_JAM"->TrafficLevel.JAM;else->TrafficLevel.NORMAL})
            }
        }
    }
}
