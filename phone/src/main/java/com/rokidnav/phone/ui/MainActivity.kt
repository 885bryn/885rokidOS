package com.rokidnav.phone.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.rokidnav.phone.BuildConfig
import com.rokidnav.phone.RokidNavApp
import com.rokidnav.phone.glasses.*
import com.rokidnav.protocol.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity:ComponentActivity() {
    private val app get()=RokidNavApp.instance
    private val events=mutableStateListOf<String>()
    private val exportHud=registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) {uri ->
        if(uri!=null)lifecycleScope.launch {
            val success=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {runCatching {
                assets.open("glasses.apk").use {input->requireNotNull(contentResolver.openOutputStream(uri)).use {input.copyTo(it)}}
            }.isSuccess}
            events.add(0,if(success)"HUD installer saved. Open Hi Rokid → Toolbox → Glasses app management." else "Could not save the HUD installer.")
        }
    }
    private val recordAudio=registerForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if(granted)startVoiceTest() else events.add(0,"Voice test needs the microphone permission.")
    }
    private fun voiceTest() {
        if(androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)startVoiceTest()
        else recordAudio.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    /** The status line sits right under the button; the old list at the bottom of the page was out of view. */
    private val voiceStatus=mutableStateOf("")
    private val voiceBusy=mutableStateOf(false)
    private fun accuracySet() {
        if(voiceBusy.value)return
        val permission=androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)
        if(permission!=android.content.pm.PackageManager.PERMISSION_GRANTED){recordAudio.launch(android.Manifest.permission.RECORD_AUDIO);return}
        voiceBusy.value=true
        lifecycleScope.launch {
            try {com.rokidnav.phone.voice.VoiceSpike(applicationContext,app.glassesLink).recordAccuracySet(5) {voiceStatus.value=it;events.add(0,it)}}
            finally {voiceBusy.value=false}
        }
    }
    private fun startVoiceTest(){
        if(voiceBusy.value)return // a second start while one runs makes the glasses refuse the stream
        voiceBusy.value=true
        lifecycleScope.launch {
            try {com.rokidnav.phone.voice.VoiceSpike(applicationContext,app.glassesLink).run(6) {voiceStatus.value=it;events.add(0,it)}}
            finally {voiceBusy.value=false}
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {app.glassesLink.incoming.collect {message->
            val label=when(message){is GlassesHello->"HUD connected · ${message.displayWidthPx} × ${message.displayHeightPx} · v${message.appVersion}";is InputEvent->"Touchpad: ${message.key}";is GlassesAction->"Action: ${message.action}";else->"Received ${message::class.simpleName}"}
            events.add(0,label);while(events.size>8)events.removeAt(events.lastIndex)
        }}
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF3DFF8A),background=Color.Black,surface=Color.Black,onPrimary=Color.Black)) {
                var page by rememberSaveable {mutableIntStateOf(if(BuildConfig.MAPS_API_KEY.isBlank())1 else 0)}
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background,
                    contentColor=MaterialTheme.colorScheme.onBackground) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    NavigationBar {
                        listOf("Navigate","Glasses","Settings").forEachIndexed {index,label ->
                            NavigationBarItem(selected=page==index,onClick={page=index},icon={},label={Text(label)})
                        }
                    }
                    Box(Modifier.weight(1f)) {
                    when(page) {
                    0->NavigationScreen(this@MainActivity,app)
                    2->SettingsScreen(app)
                    else->SetupScreen(app.glassesLink,events,
                    authorize={app.glassesLink.authorize(this@MainActivity)},connect=app.glassesLink::connect,installWithRokid={installWithHiRokidOpen()},
                    openRokid={openRokid()},test={sendHudTest()},voiceTest={voiceTest()},accuracySet={accuracySet()},voiceStatus=voiceStatus.value,voiceBusy=voiceBusy.value,export={exportHud.launch("RokidNav-HUD.apk")},preview={startActivity(Intent().setClassName(BuildConfig.GLASSES_PACKAGE,BuildConfig.GLASSES_ACTIVITY).putExtra("demo",true).putExtra("phonePreview",true))})
                    }
                    }
                }
                }
            }
        }
    }
    /** Opening the app brings the glasses session back (no-op before authorization or while open). */
    override fun onStart(){super.onStart();app.coordinator.onAppOpened()}
    /** Leaving or closing the app with no trip running frees Google's navigator and its notification. */
    override fun onStop(){super.onStop();app.navEngine.releaseIfIdle()}
    @Deprecated("Rokid SDK uses activity authorization results")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);(app.glassesLink as? CxrGlassesLink)?.onAuthorizationResult(resultCode,data)}
    /**
     * Installs or updates the HUD with Hi Rokid on screen, so its Wi-Fi transfer gets Location under the
     * "while using the app" setting. A notification brings the user back once the HUD is running.
     */
    private fun installWithHiRokidOpen() {
        openRokid()
        app.glassesLink.connect()
        app.appScope.launch {
            val result=app.glassesLink.state.first {it==LinkState.Ready || it is LinkState.Failed || it is LinkState.Closed}
            if(result==LinkState.Ready)HudInstalledNotice.show(this@MainActivity)
        }
    }
    private fun openRokid() {
        val launch=packageManager.getLaunchIntentForPackage("com.rokid.sprite.global.aiapp") ?: packageManager.getLaunchIntentForPackage("com.rokid.sprite.aiapp")
        if(launch!=null)startActivity(launch)
    }
    private fun sendHudTest() {
        val link=app.glassesLink
        if(link.state.value!=LinkState.Ready)return
        val now=System.currentTimeMillis()
        app.coordinator.sendTestSnapshot { next -> buildList {
            val points=listOf(LatLng(37.802,-122.431),LatLng(37.808,-122.431),LatLng(37.808,-122.423))
            add(SessionStart(next(),app.settings.state.value.units,false,"Marina overlook"))
            addAll(RouteChunker.split("test-$now",PolylineCodec.encode(points),1400,next))
            add(NavStateUpdate(next(),NavState.ACTIVE))
            add(PositionUpdate(next(),37.803,-122.431,0f,0f,111,now))
            add(ManeuverUpdate(next(),Step(Maneuver.TURN_RIGHT,"Bay Street"),480,30,Step(Maneuver.TURN_LEFT,"Marina Boulevard")))
            add(TripProgress(next(),now+1_080_000,14800,1080,240))
        } }
        events.add(0,"Sent test HUD. Check your glasses.")
        lifecycleScope.launch {
            val nearby=com.rokidnav.phone.map.RoadContextProvider(java.io.File(cacheDir,"streets"))
                .roadsNear(LatLng(37.803,-122.431))
            app.coordinator.sendTestSnapshot {next->RoadContextChunker.split("test-$now","test-$now",nearby,next)}
            events.add(0,if(nearby.isEmpty())"Street data is unavailable; the route preview is still active." else "Sent nearby streets from OpenStreetMap.")
        }
    }
}

@Composable
private fun SetupScreen(link:GlassesLink,events:List<String>,authorize:()->Unit,connect:()->Unit,installWithRokid:()->Unit,openRokid:()->Unit,test:()->Unit,voiceTest:()->Unit,accuracySet:()->Unit,voiceStatus:String,voiceBusy:Boolean,export:()->Unit,preview:()->Unit) {
    val state by link.state.collectAsState()
    var environment by remember {mutableStateOf<EnvironmentStatus?>(null)}
    var checkError by remember {mutableStateOf(false)}
    val context=LocalContext.current
    LaunchedEffect(state){try{environment=link.checkEnvironment();checkError=false}catch(_:Exception){checkError=true}}
    val status=when(val s=state){LinkState.Idle->"Not authorized";LinkState.Authorizing->"Waiting for Rokid approval";LinkState.Authorized->"Authorized · ready to connect";LinkState.Connecting->"Installing and opening the glasses HUD";LinkState.UpdatingHud->"Updating the HUD on your glasses (about 30 s)";LinkState.Ready->"Glasses connected";is LinkState.Paused->"Glasses paused: ${s.reason}";is LinkState.Closed->if(s.hudClosedOnGlasses)"HUD closed on the glasses · tap Connect to show it again" else "Glasses disconnected: ${s.reason}";is LinkState.Failed->s.message}
    Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("885rokidOS",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Medium)
        Text("Connect your glasses",style=MaterialTheme.typography.titleLarge)
        Text("Open Hi Rokid and connect your glasses, then authorize this app.",color=Color.LightGray)
        HorizontalDivider(color=Color(0xFF25382D))
        Text(when {checkError->"Could not check Hi Rokid. Open it and try again.";environment is EnvironmentStatus.Ok->"Hi Rokid is installed and compatible";environment is EnvironmentStatus.RokidAppTooOld->"Update Hi Rokid before connecting";environment is EnvironmentStatus.RokidAppMissing->"Install Hi Rokid to continue";else->"Checking Hi Rokid…"})
        OutlinedButton(onClick=openRokid){Text("Open Hi Rokid")}
        OutlinedButton(onClick=export){Text("Save glasses installer")}
        Text(status,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodyLarge)
        val busy=state==LinkState.Authorizing || state==LinkState.Connecting || state==LinkState.UpdatingHud
        if(state==LinkState.Authorized || state is LinkState.Closed && !(state as LinkState.Closed).needsReauth || state is LinkState.Failed && (state as LinkState.Failed).canRetry) {
            if((state as? LinkState.Failed)?.installBlocked==true)Button(onClick=installWithRokid,enabled=!busy){Text("Install with Hi Rokid open")}
            Button(onClick=connect,enabled=!busy){Text("Connect glasses")}
        } else if(state!=LinkState.Ready && state !is LinkState.Paused) {
            Button(onClick=authorize,enabled=!busy && environment is EnvironmentStatus.Ok){Text(if(state==LinkState.Authorizing)"Approve on the Rokid screen" else "Authorize glasses")}
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state==LinkState.Ready){OutlinedButton(onClick=test){Text("Send HUD test")};if(BuildConfig.DEBUG){OutlinedButton(onClick=voiceTest,enabled=!voiceBusy){Text(if(voiceBusy)"Listening… speak now" else "Voice test (speak for 6 s)")};OutlinedButton(onClick=accuracySet,enabled=!voiceBusy){Text("Accuracy test: 10 phrases (about 1 min)")};if(voiceStatus.isNotEmpty())Text(voiceStatus,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodyLarge)};TextButton(onClick=link::disconnect){Text("Disconnect")}}
        if(BuildConfig.DEBUG){
            HorizontalDivider(color=Color(0xFF25382D))
            Text("HUD preview",style=MaterialTheme.typography.titleMedium)
            Text("The green HUD uses a demo route. Tap to change the view; double tap exits.",color=Color.LightGray)
            OutlinedButton(onClick={runCatching(preview)}){Text("Preview on this phone")}
        }
        if(BuildConfig.MAPS_API_KEY.isBlank())Text("Google navigation needs an API key before live routing can be enabled.",color=Color.Gray)
        TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright")))}){Text("Street data © OpenStreetMap contributors · tiles © OpenMapTiles, OpenFreeMap")}
        if(events.isNotEmpty()){
            Text("Connection activity",style=MaterialTheme.typography.titleMedium)
            events.forEach {Text(it,style=MaterialTheme.typography.bodyMedium,color=Color.LightGray)}
        }
    }
}
