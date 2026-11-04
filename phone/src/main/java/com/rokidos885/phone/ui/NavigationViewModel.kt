package com.rokidos885.phone.ui

import android.Manifest
import android.app.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.google.android.gms.tasks.Task
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.*
import com.google.android.libraries.places.api.net.*
import com.rokidos885.phone.*
import com.rokidos885.phone.nav.*
import com.rokidos885.protocol.LatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlaceSuggestion(val id:String,val title:String,val address:String)

@OptIn(FlowPreview::class)
class NavigationViewModel(application:Application):AndroidViewModel(application) {
    private val app=application as RokidOs885App
    private val prefs=application.getSharedPreferences("recent_places",Context.MODE_PRIVATE)
    val query=MutableStateFlow("")
    val suggestions=MutableStateFlow<List<PlaceSuggestion>>(emptyList())
    val recents=MutableStateFlow<List<PlaceSuggestion>>(emptyList())
    val destination=MutableStateFlow<Destination?>(null)
    val attribution=MutableStateFlow<List<String>>(emptyList())
    val options=MutableStateFlow<List<RouteOption>>(emptyList())
    val selected=MutableStateFlow(0)
    val busy=MutableStateFlow(false)
    val error=MutableStateFlow<String?>(null)
    val currentManeuver=app.navEngine.maneuver.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val progress=app.navEngine.progress.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val position=app.navEngine.position.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    private var request:Job?=null
    private var requestGeneration=0
    private var token=AutocompleteSessionToken.newInstance()
    private val places by lazy {
        if(!Places.isInitialized())Places.initializeWithNewPlacesApiEnabled(application,BuildConfig.MAPS_API_KEY)
        Places.createClient(application)
    }
    init {
        if(BuildConfig.MAPS_API_KEY.isNotBlank())viewModelScope.launch {
            query.debounce(300).distinctUntilChanged().collectLatest {text ->
                if(text.length<2){suggestions.value=emptyList();return@collectLatest}
                try {
                    val result=places.findAutocompletePredictions(FindAutocompletePredictionsRequest.builder()
                        .setQuery(text).setSessionToken(token).build()).awaitResult()
                    suggestions.value=result.autocompletePredictions.take(5).map {
                        PlaceSuggestion(it.placeId,it.getPrimaryText(null).toString(),it.getSecondaryText(null).toString())
                    }
                } catch(e:CancellationException){throw e}
                catch(_:Exception){suggestions.value=emptyList();error.value="Search is unavailable. Check Places API access and your connection."}
            }
        }
        if(BuildConfig.MAPS_API_KEY.isNotBlank())viewModelScope.launch {
            recents.value=prefs.getString("ids","").orEmpty().split('|').filter {it.isNotBlank()}.take(5).mapNotNull {id->
                try {val p=fetch(id,false);PlaceSuggestion(id,p.displayName.orEmpty(),p.formattedAddress.orEmpty())}
                catch(e:CancellationException){throw e}catch(_:Exception){null}
            }
        }
    }
    private suspend fun fetch(id:String,session:Boolean=true):Place=places.fetchPlace(FetchPlaceRequest.builder(id,
        listOf(Place.Field.ID,Place.Field.DISPLAY_NAME,Place.Field.LOCATION,Place.Field.FORMATTED_ADDRESS))
        .apply {if(session)setSessionToken(token)}.build()).awaitResult().place
    fun choose(id:String) {
        val generation=++requestGeneration
        request?.cancel()
        request=viewModelScope.launch {
            busy.value=true;error.value=null;options.value=emptyList()
            try {
                val place=fetch(id);token=AutocompleteSessionToken.newInstance()
                val point=requireNotNull(place.location)
                val target=Destination(place.id,place.displayName.orEmpty(),LatLng(point.latitude,point.longitude))
                destination.value=target;attribution.value=place.attributions.orEmpty();suggestions.value=emptyList()
                val origin=freshLocation()
                val result=app.routesApi.computeRoutes(LatLng(origin.latitude,origin.longitude),null,target.location,target.placeId)
                options.value=result.getOrElse {throw it};selected.value=0
                if(options.value.isEmpty())error.value="No driving routes were found. Choose another destination."
                // Only Place IDs persist; Google names/coordinates are fetched afresh.
                val ids=(listOf(id)+prefs.getString("ids","").orEmpty().split('|')).filter {it.isNotBlank()}.distinct().take(5)
                prefs.edit().putString("ids",ids.joinToString("|")).apply()
            } catch(_:TimeoutCancellationException){error.value="No fresh location fix. Move outdoors and try again."}
            catch(e:CancellationException){throw e}
            catch(e:Exception){error.value=if(e is IllegalStateException)e.message else "Could not prepare this route. Check location access and connection."}
            finally {if(generation==requestGeneration)busy.value=false}
        }
    }
    fun start(activity:Activity,simulate:Boolean) {
        val target=destination.value ?: return
        val option=options.value.getOrNull(selected.value) ?: return
        if(busy.value)return
        viewModelScope.launch {
            busy.value=true;error.value=null
            try {
                if(app.navEngine.initialize(activity) && app.navEngine.start(target,option)){
                    app.traffic.seed(option);app.navEngine.simulate(simulate)
                    options.value=emptyList() // A later trip must request a fresh route token.
                }
                else {
                    error.value=(app.navEngine as? GoogleNavigationEngine)?.error?.value ?: "Guidance could not start. Check Google API setup."
                    app.navEngine.releaseIfIdle() // A failed start must not leave Google's navigation notification up.
                }
            } finally {busy.value=false}
        }
    }
    private suspend fun freshLocation():Location {
        check(ContextCompat.checkSelfPermission(getApplication(),Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){"Allow precise location to calculate a route."}
        val manager=getApplication<Application>().getSystemService(LocationManager::class.java)
        val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).filter {manager.isProviderEnabled(it)}
        check(providers.isNotEmpty()){"Turn on phone location services."}
        val cached=providers.mapNotNull {manager.getLastKnownLocation(it)}.maxByOrNull {it.elapsedRealtimeNanos}
        if(cached!=null && (android.os.SystemClock.elapsedRealtimeNanos()-cached.elapsedRealtimeNanos)<120_000_000_000L)return cached
        return withTimeout(20_000) {
            suspendCancellableCoroutine { continuation ->
                val listener=object:LocationListener {
                    override fun onLocationChanged(location:Location) {
                        manager.removeUpdates(this)
                        if(continuation.isActive)continuation.resume(location)
                    }
                    override fun onProviderDisabled(provider:String)=Unit
                    override fun onProviderEnabled(provider:String)=Unit
                    @Deprecated("Legacy callback") override fun onStatusChanged(provider:String?,status:Int,extras:android.os.Bundle?)=Unit
                }
                providers.forEach {manager.requestSingleUpdate(it,listener,Looper.getMainLooper())}
                continuation.invokeOnCancellation {manager.removeUpdates(listener)}
            }
        }
    }
}
private suspend fun <T> Task<T>.awaitResult():T=suspendCancellableCoroutine {c ->
    addOnSuccessListener {if(c.isActive)c.resume(it)}
    addOnFailureListener {if(c.isActive)c.resumeWithException(it)}
    addOnCanceledListener {c.cancel()}
}
