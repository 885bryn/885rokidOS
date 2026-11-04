package com.rokidos885.phone.nav

import android.app.Activity
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.libraries.navigation.*
import com.rokidos885.protocol.*
import com.rokidos885.protocol.NavState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Google SDK boundary; all route/location listeners live for the application's navigator. */
class GoogleNavigationEngine(private val context:Context):NavigationEngine {
    private val application=context.applicationContext as Application
    private val _navState=MutableStateFlow(NavState.IDLE)
    override val navState=_navState.asStateFlow()
    private fun <T> latest()=MutableSharedFlow<T>(replay=1,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    private val _position=latest<PositionUpdate>();override val position=_position.asSharedFlow()
    private val _maneuver=latest<ManeuverUpdate>();override val maneuver=_maneuver.asSharedFlow()
    private val _progress=latest<TripProgress>();override val progress=_progress.asSharedFlow()
    private val _activeRoute=MutableStateFlow<ActiveRoute?>(null);override val activeRoute=_activeRoute.asStateFlow()
    private val _destination=MutableStateFlow<Destination?>(null);override val destination=_destination.asStateFlow()
    private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
    private var navigator:Navigator?=null
    private var lastFixMs=0L
    private var routePoints=emptyList<LatLng>()
    private var muted=false
    private var routeGeneration=0
    private val locationListener=object:RoadSnappedLocationProvider.GpsAvailabilityEnhancedLocationListener {
        override fun onRawLocationUpdate(location:Location)=Unit
        override fun onGpsAvailabilityUpdate(available:Boolean) {
            if(_navState.value==NavState.ACTIVE && !available)_navState.value=NavState.GPS_LOST
            else if(_navState.value==NavState.GPS_LOST && available)_navState.value=NavState.ACTIVE
        }
        override fun onLocationChanged(location:Location) {
            if(_navState.value !in listOf(NavState.ACTIVE,NavState.REROUTING,NavState.GPS_LOST))return
            val now=SystemClock.elapsedRealtime()
            if(now-lastFixMs<if(location.speed<.5f)5000 else 1000)return
            lastFixMs=now
            val point=LatLng(location.latitude,location.longitude)
            _position.tryEmit(PositionUpdate(0,point.lat,point.lng,location.bearing,location.speed,
                Geo.metersAlongPolyline(routePoints,point).toInt(),System.currentTimeMillis()))
        }
    }
    override suspend fun initialize(activity:Activity):Boolean {
        if(navigator!=null)return true
        _error.value=null
        return suspendCancellableCoroutine { continuation ->
            quietGoogleNotification()
            NavigationApi.getNavigator(activity,object:NavigationApi.NavigatorListener {
                override fun onError(code:Int) {
                    _error.value=when(code) {
                        NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED->"Accept Google's navigation terms to start guidance."
                        NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING->"Allow precise location to start guidance."
                        NavigationApi.ErrorCode.NOT_AUTHORIZED->"Enable Navigation SDK and check the Android API key and signing certificate."
                        else->"Google navigation could not start. Check network access."
                    }
                    if(continuation.isActive)continuation.resume(false)
                }
                override fun onNavigatorReady(value:Navigator) {
                    if(!continuation.isActive)return
                    navigator=value
                    value.addArrivalListener { if(it.isFinalDestination){navigator?.stopGuidance();_navState.value=NavState.ARRIVED;stopForeground()
                        // Release after this callback returns, not from inside the navigator's own listener.
                        android.os.Handler(android.os.Looper.getMainLooper()).post(::release)} }
                    value.addRouteChangedListener { publishRoute();publishProgress() }
                    value.addReroutingListener {_navState.value=NavState.REROUTING}
                    value.addRemainingTimeOrDistanceChangedListener(1,1){publishProgress()}
                    NavigationApi.getRoadSnappedLocationProvider(application).addLocationListener(locationListener)
                    if(!value.registerServiceForNavUpdates(context.packageName,NavUpdatesService::class.java.name,1)) {
                        _error.value="Google turn instructions could not be registered."
                        NavigationApi.getRoadSnappedLocationProvider(application).removeLocationListener(locationListener)
                        value.cleanup();navigator=null;continuation.resume(false);return
                    }
                    setVoiceMuted(muted)
                    continuation.resume(true)
                }
            })
        }
    }
    override suspend fun start(destination:Destination,option:RouteOption?):Boolean {
        val nav=navigator ?: return false
        if(option!=null && option.routeToken.isBlank()){_error.value="The selected route has no Google route token. Recalculate the route.";return false}
        _error.value=null;_navState.value=NavState.ROUTING
        val attempt=++routeGeneration
        try {
            val wp=Waypoint.builder().setTitle(destination.name).apply {
                if(!destination.placeId.isNullOrBlank())setPlaceIdString(destination.placeId)
                else setLatLng(destination.location.lat,destination.location.lng)
            }.build()
            val future=if(!option?.routeToken.isNullOrBlank())nav.setDestinations(listOf(wp),
                CustomRoutesOptions.builder().setRouteToken(option!!.routeToken).setTravelMode(CustomRoutesOptions.TravelMode.DRIVING).build())
                else nav.setDestination(wp)
            val status=suspendCancellableCoroutine<Navigator.RouteStatus> { c ->
                future.setOnResultListener { if(c.isActive)c.resume(it) }
                c.invokeOnCancellation {future.cancel(true)}
            }
            if(attempt!=routeGeneration)return false
            if(status!=Navigator.RouteStatus.OK){_error.value="Google could not calculate guidance ($status).";_navState.value=if(nav.isGuidanceRunning)NavState.ACTIVE else NavState.IDLE;return false}
            _destination.value=destination
            publishRoute(option)
            ContextCompat.startForegroundService(context,android.content.Intent(context,com.rokidos885.phone.session.NavForegroundService::class.java))
            nav.startGuidance();_navState.value=NavState.ACTIVE;publishProgress()
            return true
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){_error.value="Could not start guidance. Check location access and Google API setup.";_navState.value=if(nav.isGuidanceRunning)NavState.ACTIVE else NavState.IDLE;return false}
    }
    override suspend fun switchRoute(option:RouteOption):Boolean = _destination.value?.let {start(it,option)} ?: false
    private fun publishRoute(option:RouteOption?=null) {
        val nav=navigator ?: return
        val points=nav.routeSegments.orEmpty().flatMap {segment->segment.latLngs.orEmpty().map {LatLng(it.latitude,it.longitude)}}
        val encoded=if(points.size>1)PolylineCodec.encode(points) else option?.encodedPolyline ?: return
        if(_activeRoute.value?.encodedPolyline==encoded)return
        routePoints=PolylineCodec.decode(encoded)
        val meters=routePoints.zipWithNext {a,b->Geo.distanceMeters(a,b)}.sum().toInt()
        _activeRoute.value=ActiveRoute("g-${SystemClock.elapsedRealtimeNanos()}",encoded,if(meters>0)meters else option?.distanceMeters ?: 0)
    }
    private fun publishProgress() {
        val remaining=navigator?.currentTimeAndDistance ?: return
        _progress.tryEmit(TripProgress(0,System.currentTimeMillis()+remaining.seconds*1000L,remaining.meters,remaining.seconds))
    }
    override fun setVoiceMuted(muted:Boolean) {this.muted=muted;navigator?.setAudioGuidance(if(muted)Navigator.AudioGuidance.SILENT else Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)}
    override fun simulate(enabled:Boolean) {navigator?.simulator?.let {if(enabled)it.simulateLocationsAlongExistingRoute() else it.unsetUserLocation()}}
    private fun stopForeground(){context.stopService(android.content.Intent(context,com.rokidos885.phone.session.NavForegroundService::class.java))}
    override fun stop() {
        routeGeneration++;navigator?.stopGuidance();navigator?.clearDestinations();navigator?.simulator?.unsetUserLocation()
        _navState.value=NavState.ENDED;_activeRoute.value=null;_destination.value=null;routePoints=emptyList();stopForeground()
        release()
    }
    /**
     * Ends the Google navigator entirely. The SDK keeps its own foreground service and "navigation"
     * notification alive while a navigator exists (our road-snapped location listener keeps it tracking),
     * so stopping guidance alone left that notification up. The next trip gets a fresh navigator from
     * initialize(), which the UI calls before every start().
     */
    private fun release() {
        val nav=navigator ?: return
        navigator=null
        runCatching {NavigationApi.getRoadSnappedLocationProvider(application)?.removeLocationListener(locationListener)}
        runCatching {nav.unregisterServiceForNavUpdates()}
        runCatching {nav.cleanup()}
        // cleanup() alone once left Google's "Driving with Google Maps" service running after a trip
        // (2026-10-19). Drop its service manager and stop that service, which runs in this app.
        runCatching {NavigationApi.clearForegroundServiceManager()}
        runCatching {context.stopService(android.content.Intent().setClassName(context,GOOGLE_NAV_SERVICE))}
        android.util.Log.i("885rokidOSEngine","navigator released")
    }
    /**
     * Google's navigation service otherwise posts its own turn-by-turn notification and updates it at
     * every step, which popped up on the phone for each direction. Give it one fixed, silent notification
     * on our low-importance "guidance" channel instead. Must run before the navigator is created; release()
     * clears the manager, so each new navigator sets it again.
     */
    private fun quietGoogleNotification() {
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(com.rokidos885.phone.session.NavForegroundService.CHANNEL,
            "Navigation",android.app.NotificationManager.IMPORTANCE_LOW))
        val open=android.app.PendingIntent.getActivity(context,3,android.content.Intent(context,com.rokidos885.phone.ui.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=androidx.core.app.NotificationCompat.Builder(context,com.rokidos885.phone.session.NavForegroundService.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions).setContentTitle("885rokidOS navigation")
            .setContentText("Google guidance is running").setContentIntent(open)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).build()
        runCatching {NavigationApi.initForegroundServiceManagerProvider(application,GOOGLE_NOTIFICATION_ID) {notification}}
            .onFailure {android.util.Log.w("885rokidOSEngine","quiet notification not set: ${it.javaClass.simpleName}")}
    }
    override fun releaseIfIdle() {
        if(_navState.value !in listOf(NavState.ROUTING,NavState.ACTIVE,NavState.REROUTING,NavState.GPS_LOST))release()
    }
    private companion object {
        const val GOOGLE_NOTIFICATION_ID=72
        const val GOOGLE_NAV_SERVICE="com.google.android.libraries.geo.navcore.service.base.NavigationService"
    }
    internal fun onManeuver(update:ManeuverUpdate){if(_navState.value!=NavState.ENDED)_maneuver.tryEmit(update)}
    internal fun onNavStateFromFeed(state:NavState) {
        if(_navState.value in listOf(NavState.IDLE,NavState.ENDED,NavState.ARRIVED))return
        if(state==NavState.ENDED)return // Arrival listener owns the arrived screen; STOPPED also occurs before guidance.
        if(_navState.value!=NavState.GPS_LOST || state==NavState.REROUTING)_navState.value=state
    }
}
