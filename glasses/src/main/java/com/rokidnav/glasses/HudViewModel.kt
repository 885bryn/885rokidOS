package com.rokidnav.glasses

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokidnav.glasses.input.GlassesKey
import com.rokidnav.glasses.link.*
import com.rokidnav.glasses.state.HudStore
import com.rokidnav.protocol.*
import kotlinx.coroutines.launch

class HudViewModel(application:Application) : AndroidViewModel(application) {
    private val seq=SeqCounter()
    private lateinit var link:PhoneLink
    private var width=0
    private var height=0
    val store=HudStore(requestResync={if(::link.isInitialized)link.send(ResyncRequest(seq.next(),"route chunk timeout"))}) { if(::link.isInitialized)link.send(it.copy(seq=seq.next())) }
    init { viewModelScope.launch { store.tickLoop() } }
    fun initialize(demo:Boolean) {
        if(::link.isInitialized)return
        // Confirmed on hardware: CXR remote launch selects this bridge and returns GlassesHello.
        link=if(demo) DemoPhoneLink(viewModelScope) {
            getApplication<Application>().assets.open("demo_trip.jsonl").bufferedReader().use { DemoRecording.parse(it.lineSequence()) }
        } else CxrPhoneLink.get(getApplication<Application>())
        viewModelScope.launch { link.incoming.collect {
            if(it is HelloRequest){store.resetSequence();if(width>0)hello()} else {store.onMessage(it);if(it is PhoneNotification)wakeDisplay(it)}
        } }
        viewModelScope.launch { link.connected.collect { connected -> store.onConnection(connected);if(connected && width>0)hello() } }
        (link as? DemoPhoneLink)?.start()
    }
    /**
     * A card is useless on a sleeping display and its timer already runs, so wake the display for the
     * card's lifetime. VERIFY on hardware: whether Rokid's system allows an app wake lock to do this.
     */
    @Suppress("DEPRECATION")
    private fun wakeDisplay(n:PhoneNotification) {
        val power=getApplication<Application>().getSystemService(android.os.PowerManager::class.java) ?: return
        runCatching {
            power.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,"rokidnav:notification")
                .acquire(HudStore.notificationMillis(n)+1_000)
        }.onFailure {android.util.Log.w("RokidNavHud","wake failed: ${it.javaClass.simpleName}")}
    }
    private fun hello(){link.send(GlassesHello(seq.next(),BuildConfig.VERSION_NAME,width,height))}
    /** Back in the foreground (e.g. reopened from the Rokid home screen): say hello so the phone resumes. */
    fun onForeground(){store.onUserWake();if(::link.isInitialized && width>0)hello()}
    fun onDisplayReady(width:Int,height:Int){this.width=width;this.height=height;if(::link.isInitialized)hello()}
    /** Returns whether the HUD used the key (see [HudStore.onKey]). */
    fun onKey(key:GlassesKey):Boolean{if(::link.isInitialized)link.send(InputEvent(seq.next(),key.name));return store.onKey(key)}
    override fun onCleared(){if(::link.isInitialized)link.close()}
}
