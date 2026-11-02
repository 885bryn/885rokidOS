package com.rokidnav.phone

import android.app.Application
import com.rokidnav.phone.alerts.CompositeIncidentProvider
import com.rokidnav.phone.alerts.AndroidSabreClient
import com.rokidnav.phone.alerts.WzSabrePoliceProvider
import com.rokidnav.phone.alerts.WzSabreCameraProvider
import com.rokidnav.phone.alerts.WzSabreCameraRepository
import com.rokidnav.phone.glasses.CxrGlassesLink
import com.rokidnav.phone.glasses.GlassesLink
import com.rokidnav.phone.nav.GoogleNavigationEngine
import com.rokidnav.phone.nav.NavigationEngine
import com.rokidnav.phone.session.GlassesLinkService
import com.rokidnav.phone.session.NavSessionCoordinator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.rokidnav.phone.traffic.RoutesApiClient
import com.rokidnav.phone.traffic.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual DI. Swap implementations here (e.g. a fake GlassesLink for UI work without hardware). */
class RokidNavApp : Application() {
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
    lateinit var settings:com.rokidnav.phone.settings.SettingsRepository
        private set
    lateinit var policeProvider:WzSabrePoliceProvider
        private set
    lateinit var traffic:TrafficMonitor
        private set
    lateinit var media:com.rokidnav.phone.media.MediaMonitor
        private set
    lateinit var replies:com.rokidnav.phone.notify.NotificationReplier
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        glassesLink = CxrGlassesLink(this)
        navEngine = GoogleNavigationEngine(this)
        routesApi = RoutesApiClient(BuildConfig.ROUTES_API_KEY,androidPackage=packageName,androidCert=RoutesApiClient.signingCertificate(this))
        settings=com.rokidnav.phone.settings.SettingsRepository(this)
        traffic=TrafficMonitor(routesApi,navEngine)
        media=com.rokidnav.phone.media.MediaMonitor(this)
        replies=com.rokidnav.phone.notify.NotificationReplier(this)
        policeProvider=WzSabrePoliceProvider(AndroidSabreClient(this), navEngine.position)
        coordinator = NavSessionCoordinator(
            scope = appScope,
            link = glassesLink,
            engine = navEngine,
            traffic = traffic,
            incidents = CompositeIncidentProvider(
                listOf(
                    com.rokidnav.phone.alerts.ConfiguredIncidentProvider(policeProvider,settings.state) {it.police},
                    com.rokidnav.phone.alerts.ConfiguredIncidentProvider(WzSabreCameraProvider(WzSabreCameraRepository(java.io.File(filesDir, "cameras"))::load),settings.state) {it.cameras},
                ),
            ),
            settings=settings,
            roads = com.rokidnav.phone.map.RoadContextProvider(java.io.File(cacheDir, "streets")),
            speaker = com.rokidnav.phone.alerts.AndroidAlertSpeaker(this),
            media = media,
        )
        coordinator.start()
        media.start()
        appScope.launch {glassesLink.state.map(GlassesLinkService::wanted).distinctUntilChanged().collect {
            GlassesLinkService.sync(this@RokidNavApp,glassesLink.state.value)
        }}
    }

    companion object {
        lateinit var instance: RokidNavApp
            private set
    }
}
