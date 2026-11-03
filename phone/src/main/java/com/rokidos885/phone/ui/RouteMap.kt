package com.rokidos885.phone.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.doOnLayout
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.rokidos885.protocol.PolylineCodec
import com.rokidos885.protocol.LatLng as NavLatLng

/** Google street map for the phone; attribution remains visible inside the SDK view. */
@Composable
fun RouteMap(lines:List<String>,selected:Int,position:NavLatLng?,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val view=remember {MapView(context).apply {onCreate(null)}}
    var map by remember {mutableStateOf<GoogleMap?>(null)}
    var marker by remember {mutableStateOf<Marker?>(null)}
    DisposableEffect(view,owner) {
        val observer=LifecycleEventObserver {_,event->when(event) {
            Lifecycle.Event.ON_START->view.onStart()
            Lifecycle.Event.ON_RESUME->view.onResume()
            Lifecycle.Event.ON_PAUSE->view.onPause()
            Lifecycle.Event.ON_STOP->view.onStop()
            else->Unit
        }}
        owner.lifecycle.addObserver(observer)
        view.getMapAsync {map=it;it.isTrafficEnabled=true}
        onDispose {owner.lifecycle.removeObserver(observer);view.onPause();view.onStop();view.onDestroy()}
    }
    AndroidView(factory={view},modifier=modifier)
    LaunchedEffect(map,lines,selected) {
        val value=map ?: return@LaunchedEffect
        value.clear();marker=null
        val bounds=LatLngBounds.builder();var hasPoints=false
        lines.forEachIndexed {index,line ->
            val points=PolylineCodec.decode(line).map {LatLng(it.lat,it.lng)}
            if(points.isNotEmpty()) {
                value.addPolyline(PolylineOptions().addAll(points).color(if(index==selected)0xFF3DFF8A.toInt() else 0xFF65736A.toInt())
                    .width(if(index==selected)12f else 7f).zIndex(if(index==selected)2f else 1f))
                points.forEach {bounds.include(it);hasPoints=true}
            }
        }
        if(hasPoints)view.doOnLayout {value.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(),48))}
    }
    LaunchedEffect(map,position,lines) {
        val value=map ?: return@LaunchedEffect
        position?.let {
            val point=LatLng(it.lat,it.lng)
            if(marker==null)marker=value.addMarker(MarkerOptions().position(point).title("You")) else marker?.position=point
            value.moveCamera(CameraUpdateFactory.newLatLngZoom(point,16f))
        }
    }
}
