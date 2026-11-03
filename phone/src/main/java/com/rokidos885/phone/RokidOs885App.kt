package com.rokidos885.phone

import android.app.Application
import com.rokidos885.phone.alerts.CompositeIncidentProvider
import com.rokidos885.phone.alerts.AndroidSabreClient
import com.rokidos885.phone.alerts.WzSabrePoliceProvider
import com.rokidos885.phone.alerts.WzSabreCameraProvider
import com.rokidos885.phone.alerts.WzSabreCameraRepository
import com.rokidos885.phone.glasses.CxrGlassesLink
import com.rokidos885.phone.glasses.GlassesLink
import com.rokidos885.phone.nav.GoogleNavigationEngine
import com.rokidos885.phone.nav.NavigationEngine
import com.rokidos885.phone.session.GlassesLinkService
import com.rokidos885.phone.session.NavSessionCoordinator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.rokidos885.phone.traffic.RoutesApiClient
import com.rokidos885.phone.traffic.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual DI. Swap implementations here (e.g. a fake GlassesLink for UI work without hardware). */
class RokidOs885App : Application() {
    // Serialize coordinator state and SDK calls. Providers move blocking work to Dispatchers.IO.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var glassesLink: GlassesLink
        private set
    lateinit var navEngine: NavigationEngine
        private set
    lateinit var coordinator: NavSessionCoordinator
        private set

    lateinit var routesApi:RoutesApiClient
        private set
    lateinit var settings:com.rokidos885.phone.settings.SettingsRepository
        private set
    lateinit var policeProvider:WzSabrePoliceProvider
        private set
    lateinit var traffic:TrafficMonitor
        private set
    lateinit var media:com.rokidos885.phone.media.MediaMonitor
        private set
    lateinit var replies:com.rokidos885.phone.notify.NotificationReplier
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        glassesLink = CxrGlassesLink(this)
        navEngine = GoogleNavigationEngine(this)
        routesApi = RoutesApiClient(BuildConfig.ROUTES_API_KEY,androidPackage=packageName,androidCert=RoutesApiClient.signingCertificate(this))
        settings=com.rokidos885.phone.settings.SettingsRepository(this)
        traffic=TrafficMonitor(routesApi,navEngine)
        media=com.rokidos885.phone.media.MediaMonitor(this)
        replies=com.rokidos885.phone.notify.NotificationReplier(this)
        policeProvider=WzSabrePoliceProvider(AndroidSabreClient(this), navEngine.position)
        coordinator = NavSessionCoordinator(
            scope = appScope,
            link = glassesLink,
            engine = navEngine,
            traffic = traffic,
            incidents = CompositeIncidentProvider(
                listOf(
                    com.rokidos885.phone.alerts.ConfiguredIncidentProvider(policeProvider,settings.state) {it.police},
                    com.rokidos885.phone.alerts.ConfiguredIncidentProvider(WzSabreCameraProvider(WzSabreCameraRepository(java.io.File(filesDir, "cameras"))::load),settings.state) {it.cameras},
                ),
            ),
            settings=settings,
            roads = com.rokidos885.phone.map.RoadContextProvider(java.io.File(cacheDir, "streets")),
            speaker = com.rokidos885.phone.alerts.AndroidAlertSpeaker(this),
            media = media,
            transcriber = com.rokidos885.phone.voice.SherpaTranscriber(this),
            replySender = com.rokidos885.phone.voice.ReplySender {id,text->replies.reply(id,text)},
            voiceWork = Dispatchers.Default,
            // Debug builds keep the last spoken reply's audio on the phone (private folder) to diagnose recognition.
            voiceAudio = if(BuildConfig.DEBUG)lastVoiceRecorder(java.io.File(filesDir,"voice-last.pcm")) else null,
            openGrokBot = {com.rokidos885.phone.voice.GrokBot.open(this)},
        )
        coordinator.start()
        media.start()
        appScope.launch {glassesLink.state.map(GlassesLinkService::wanted).distinctUntilChanged().collect {
            GlassesLinkService.sync(this@RokidOs885App,glassesLink.state.value)
        }}
    }

    /**
     * Debug builds keep the audio of the last 20 spoken replies (voice-replies/reply-<time>.pcm in the app's private folder) so a
     * misheard reply can be studied offline. A new reply starts a new file when audio has paused for 2 s.
     */
    private fun lastVoiceRecorder(file:java.io.File):(ByteArray)->Unit {
        val dir=java.io.File(file.parentFile,"voice-replies").apply {mkdirs()}
        var lastAt=0L
        var out:java.io.OutputStream?=null
        return {chunk->
            val now=System.currentTimeMillis()
            if(out==null || now-lastAt>2_000){
                runCatching {out?.close()}
                out=java.io.File(dir,"reply-$now.pcm").outputStream()
                dir.listFiles()?.sortedBy {it.name}?.dropLast(20)?.forEach {it.delete()}
            }
            lastAt=now
            runCatching {out?.write(chunk)}
        }
    }

    companion object {
        lateinit var instance: RokidOs885App
            private set
    }
}
