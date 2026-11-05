package com.rokidos885.phone.ui

import com.rokidos885.phone.settings.UserSettings
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.rokidos885.phone.*
import com.rokidos885.phone.glasses.LinkState
import com.rokidos885.protocol.*
import kotlinx.coroutines.launch

@Composable
fun NavigationScreen(activity:ComponentActivity,app:RokidOs885App) {
    val vm=remember {ViewModelProvider(activity)[NavigationViewModel::class.java]}
    val preferences by app.settings.state.collectAsState()
    val query by vm.query.collectAsState();val suggestions by vm.suggestions.collectAsState()
    val recents by vm.recents.collectAsState();val destination by vm.destination.collectAsState()
    val options by vm.options.collectAsState();val selected by vm.selected.collectAsState()
    val busy by vm.busy.collectAsState();val error by vm.error.collectAsState()
    val state by app.navEngine.navState.collectAsState();val route by app.navEngine.activeRoute.collectAsState()
    val maneuver by vm.currentManeuver.collectAsState();val progress by vm.progress.collectAsState()
    val position by vm.position.collectAsState();val link by app.glassesLink.state.collectAsState()
    val detour by app.coordinator.detour.collectAsState();val trafficError by app.traffic.error.collectAsState()
    val attributes by vm.attribution.collectAsState()
    var simulation by remember {mutableStateOf(false)}
    var permissionMessage by remember {mutableStateOf<String?>(null)}
    var pendingPlace by remember {mutableStateOf<String?>(null)}
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {grants ->
        val id=pendingPlace;pendingPlace=null
        if(grants[Manifest.permission.ACCESS_FINE_LOCATION]==true){permissionMessage=null;if(id!=null)vm.choose(id)}
        else permissionMessage="Allow precise location in Android settings to calculate routes."
    }
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {vm.start(activity,simulation)}
    fun choose(id:String) {
        if(ContextCompat.checkSelfPermission(activity,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)vm.choose(id)
        else {pendingPlace=id;permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
    }
    val guiding=state in listOf(NavState.ACTIVE,NavState.REROUTING,NavState.GPS_LOST,NavState.ROUTING)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Where to?",style=MaterialTheme.typography.headlineMedium)
        if(BuildConfig.MAPS_API_KEY.isBlank() || BuildConfig.ROUTES_API_KEY.isBlank()) {
            Text("Google API setup is required for search, maps and live guidance.")
            Text("Add the keys to local.properties and rebuild. You can still connect the glasses and use the HUD preview.",color=Color.LightGray)
        } else if(!guiding) {
            OutlinedTextField(query,{vm.query.value=it},Modifier.fillMaxWidth(),label={Text("Search destinations")},singleLine=true,enabled=!busy)
            if(suggestions.isNotEmpty()) {
                suggestions.forEach {place -> TextButton(onClick={choose(place.id)},enabled=!busy) {Column {Text(place.title);Text(place.address,style=MaterialTheme.typography.bodySmall)}}}
                Text("Google Maps",fontSize=12.sp,color=Color.White)
            } else if(query.isBlank() && recents.isNotEmpty()) {
                Text("Recent destinations",style=MaterialTheme.typography.titleMedium)
                recents.forEach {place -> TextButton(onClick={choose(place.id)},enabled=!busy){Text(place.title)}}
                Text("Google Maps",fontSize=12.sp,color=Color.White)
            }
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        (error ?: permissionMessage)?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(guiding || state==NavState.ARRIVED) {
            Text(if(state==NavState.ARRIVED)"You've arrived" else state.name.replace('_',' '),style=MaterialTheme.typography.titleLarge)
            Text(app.navEngine.destination.collectAsState().value?.name ?: destination?.name.orEmpty())
            maneuver?.let {Text("${phoneDistance(it.distanceToStepMeters,preferences.units)} · ${it.current.roadName}",style=MaterialTheme.typography.headlineSmall)}
            progress?.let {Text("${(it.remainingSeconds+59)/60} min left · ${phoneDistance(it.remainingMeters,preferences.units)}")}
            if(link!=LinkState.Ready) {
                val closed=link as? LinkState.Closed
                Text(if(closed?.hudClosedOnGlasses==true)"The HUD was closed on the glasses. Phone guidance continues."
                    else "Glasses link unavailable. Keep Hi Rokid running; phone guidance continues.",color=MaterialTheme.colorScheme.error)
                // Only the phone can restart the session; reconnecting reopens the HUD and resyncs the trip.
                if((closed!=null && !closed.needsReauth) || (link as? LinkState.Failed)?.canRetry==true)
                    Button(onClick={app.glassesLink.connect()}){Text("Show on glasses")}
            }
            detour?.let {offer ->
                HorizontalDivider();Text("Faster route: save ${(offer.savesSeconds+59)/60} min via ${offer.via}")
                Row {Button(onClick={app.coordinator.respondToDetour(true)}){Text("Take route")};TextButton(onClick={app.coordinator.respondToDetour(false)}){Text("Keep route")}}
            }
            Row {OutlinedButton(onClick=app.traffic::forceRefresh){Text("Check traffic")};TextButton(onClick=app.navEngine::stop){Text("End navigation")}}
            trafficError?.let {Text(it,color=Color.LightGray)}
        } else if(destination!=null && options.isNotEmpty()) {
            Text(destination!!.name,style=MaterialTheme.typography.titleLarge)
            options.forEachIndexed {index,option ->
                OutlinedButton(onClick={vm.selected.value=index},Modifier.fillMaxWidth(),enabled=!busy) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${if(index==selected)"✓ " else ""}${option.description}")
                        Text("${(option.durationSeconds+59)/60} min · ${phoneDistance(option.distanceMeters,preferences.units)} · +${((option.durationSeconds-option.staticDurationSeconds).coerceAtLeast(0)+59)/60} min traffic")
                    }
                }
            }
            Text("Google Maps",fontSize=12.sp,color=Color.White)
            options.getOrNull(selected)?.warnings?.forEach {Text(it,color=Color.LightGray)}
            if(BuildConfig.DEBUG)Row {Checkbox(simulation,{simulation=it});Text("Simulate this drive")}
            Button(onClick={
                if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(activity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else vm.start(activity,simulation)
            },enabled=!busy){Text("Go")}
        }
        val lines=if(guiding)route?.encodedPolyline?.let(::listOf).orEmpty() else options.map {it.encodedPolyline}
        if(BuildConfig.MAPS_API_KEY.isNotBlank() && lines.isNotEmpty())
            RouteMap(lines,if(guiding)0 else selected,if(guiding)position?.let {LatLng(it.lat,it.lng)} else null,Modifier.fillMaxWidth().height(300.dp))
        if(attributes.isNotEmpty())AndroidView(factory={TextView(it).apply {setTextColor(android.graphics.Color.LTGRAY);movementMethod=LinkMovementMethod.getInstance()}},update={it.text=Html.fromHtml(attributes.joinToString("<br>"),Html.FROM_HTML_MODE_LEGACY)})
    }
}

@Composable
fun SettingsScreen(app:RokidOs885App) {
    val settings by app.settings.state.collectAsState()
    val policeStatus by app.policeProvider.status.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Display & alerts",style=MaterialTheme.typography.headlineMedium)
        Text("The glasses use the bottom corners. Tap the glasses to change the view. Trip details and settings are here on the phone.")
        Row {Switch(!settings.voiceMuted,{value->app.settings.update {it.copy(voiceMuted=!value)}});Text("Voice guidance")}
        OutlinedButton(onClick={app.settings.update {it.copy(units=if(it.units==Units.IMPERIAL)Units.METRIC else Units.IMPERIAL)}}){Text("Units: ${settings.units}")}
        OutlinedButton(onClick={app.settings.update {it.copy(zoom=(it.zoom+1)%3)}}){Text("Map zoom: ${when(settings.zoom){0->"Close";2->"Far";else->"Default"}}")}
        Row {Switch(settings.police,{value->app.settings.update {it.copy(police=value)}});Text("WzSabre police reports")}
        Row {Switch(settings.cameras,{value->app.settings.update {it.copy(cameras=value)}});Text("WzSabre speed cameras")}
        Row {Switch(settings.demoMode,{value->app.settings.update {it.copy(demoMode=value)}});Text("Demo mode: smooth 30 fps map on the glasses (uses more battery)")}
        // Re-read on every composition: the user grants access in system Settings and comes back.
        val notificationAccess=androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
        OutlinedButton(onClick={app.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}) {
            Text(if(notificationAccess)"Phone notifications on glasses: on (change)" else "Show phone notifications on glasses: allow access")
        }
        Text("Turn off notifications in Hi Rokid, or the glasses show them twice.",color=Color.LightGray)
        Text("Alerts on the glasses clear themselves once passed; no tapping needed.",color=Color.LightGray)
        Row {Switch(settings.alertVoice,{value->app.settings.update {it.copy(alertVoice=value)}});Text("Voice warning for each new report")}
        if(settings.alertVoice) {
            Row {Switch(settings.alertVoiceCameras,{value->app.settings.update {it.copy(alertVoiceCameras=value)}});Text("Also speak speed cameras")}
            OutlinedButton(onClick={app.settings.update {s->
                val list=UserSettings.VOICE_DISTANCES;s.copy(alertVoiceMeters=list[(list.indexOf(s.alertVoiceMeters)+1)%list.size])}}) {
                Text("Warn when ${phoneDistance(settings.alertVoiceMeters,settings.units)} ahead")
            }
        }
        OutlinedButton(onClick={app.settings.update {s->
            val list=UserSettings.TOLERANCES;s.copy(alertToleranceMeters=list[(list.indexOf(s.alertToleranceMeters)+1)%list.size])}}) {
            Text("Report accuracy: ${UserSettings.toleranceLabel(settings.alertToleranceMeters)} (within ${phoneDistance(settings.alertToleranceMeters,settings.units)} of route)")
        }
        val installed=remember {runCatching {app.packageManager.getPackageInfo("app.sabre.wzsabre",0);true}.getOrDefault(false)}
        Text(if(installed)"WzSabre is installed. It needs no setup; keep it exempt from battery optimization." else "Install WzSabre from wzsabre.rocks for police reports.",color=Color.LightGray)
        Text(if(settings.police)policeStatus else "Police reports disabled",color=Color.LightGray)
        Text("Camera data uses WzSabre's ExCam database. Police report availability depends on the plugin and its report sources.",color=Color.LightGray)
        ScreenLockSettings(app)
        T3Settings(app)
    }
}

/** Whether the phone locks again after the glasses start Grok Bot from a pocket (ScreenLockService). */
@Composable
private fun ScreenLockSettings(app:RokidOs885App) {
    // Re-read on every composition, and turn it on again when allowed: Android can turn the service off.
    val on=com.rokidos885.phone.voice.ScreenLockService.ensureOn(app)
    val auto=com.rokidos885.phone.voice.ScreenLockService.canTurnOn(app)
    Text("Phone screen lock",style=MaterialTheme.typography.headlineMedium)
    Text(if(on)"Screen lock: on. The phone locks again after the glasses start Grok Bot."
        else "Screen lock: off. After the glasses start Grok Bot, the phone screen stays on.",color=if(on)Color.LightGray else Color(0xFFFFB4A9))
    if(!on)OutlinedButton(onClick={app.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}){Text("Turn on: Accessibility, 885rokidOS screen lock")}
    Text(if(auto)"It turns itself on again if Android turns it off."
        else "To make it turn itself on again, run once on a computer: adb shell pm grant com.rokidos885.phone android.permission.WRITE_SECURE_SETTINGS",
        color=Color.LightGray)
}

/** T3 Code on the PC for the glasses' T3 page: address (Tailscale HTTPS), sign-in, one thread. */
@Composable
private fun T3Settings(app:RokidOs885App) {
    val client=app.t3.client
    val scope=rememberCoroutineScope()
    var origin by remember {mutableStateOf(client.origin)}
    var note by remember {mutableStateOf(if(client.signedIn)"Signed in" else "Not signed in")}
    var thread by remember {mutableStateOf(client.threadTitle)}
    var threads by remember {mutableStateOf(emptyList<Pair<String,String>>())}
    var busy by remember {mutableStateOf(false)}
    fun work(block:()->Unit,done:String) {
        busy=true
        scope.launch {
            note=runCatching {kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){block()};done}
                .getOrElse {(it as? com.rokidos885.phone.code.T3Error)?.message ?: "T3 not reachable"}
            busy=false;app.t3.refresh()
        }
    }
    Text("T3 Code",style=MaterialTheme.typography.headlineMedium)
    Text("Swipe back on the empty glasses screen for the T3 page. Tap to wake it, tap again to speak to the thread.",color=Color.LightGray)
    OutlinedTextField(origin,{origin=it},label={Text("T3 address (Tailscale HTTPS)")},singleLine=true)
    Button(enabled=!busy && origin.isNotBlank(),onClick={
        client.origin=origin
        work({client.signIn {url->app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}},"Signed in")
    }){Text("T3: sign in")}
    Text("Approve 885rokidOS on the T3 page that opens, then come back here.",color=Color.LightGray)
    OutlinedButton(enabled=!busy && client.signedIn,onClick={work({threads=client.listThreads()},"Pick a thread")}){
        Text("T3 thread: ${thread.ifEmpty {"none"}} (change)")
    }
    threads.forEach {(id,title)->
        TextButton(onClick={client.pickThread(id,title);thread=title;threads=emptyList();app.t3.refresh()}){Text(title.ifEmpty {id})}
    }
    Text(note,color=MaterialTheme.colorScheme.primary)
}

private fun phoneDistance(meters:Int,units:Units):String = if(units==Units.IMPERIAL) {
    if(meters<305)"${(meters*3.28084/50).toInt()*50} ft" else String.format(java.util.Locale.US,"%.1f mi",meters/1609.344)
} else if(meters<1000)"$meters m" else String.format(java.util.Locale.US,"%.1f km",meters/1000.0)
