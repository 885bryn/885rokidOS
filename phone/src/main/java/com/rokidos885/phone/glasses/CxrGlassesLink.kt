package com.rokidos885.phone.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.session.*
import com.rokid.sprite.aiapp.externalapp.example.ExternalAppClient
import com.rokidos885.phone.BuildConfig
import com.rokidos885.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

/** SDK boundary. Tokens stay in memory and are never included in logs or UI state. */
class CxrGlassesLink(private val context:Context):GlassesLink {
    private val mutableState=MutableStateFlow<LinkState>(LinkState.Idle)
    override val state=mutableState.asStateFlow()
    private val messages=MutableSharedFlow<NavMessage>(extraBufferCapacity=64,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    override val incoming=messages.asSharedFlow()
    private val audioChunks=MutableSharedFlow<ByteArray>(extraBufferCapacity=256,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    override val audio=audioChunks.asSharedFlow()
    private val manager by lazy {CxrSessionManager.getInstance(context.applicationContext)}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val main=Handler(Looper.getMainLooper())
    private var token:String?=null
    private var session:CxrSession?=null
    private var generation=0
    private var hudReplacedFor:String?=null
    private var helloFor=-1

    private fun onMain(action:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())action() else main.post(action)}
    override fun checkEnvironment():EnvironmentStatus=when(val result=manager.checkRokidAppCompatibility(context)) {
        is RokidAppStatus.NotInstalled->EnvironmentStatus.RokidAppMissing(result.downloadUrl)
        is RokidAppStatus.VersionTooLow->EnvironmentStatus.RokidAppTooOld(result.downloadUrl)
        is RokidAppStatus.Compatible->EnvironmentStatus.Ok
    }
    override fun authorize(activity:Activity) {
        if(state.value==LinkState.Authorizing)return
        mutableState.value=LinkState.Authorizing
        try {
            // Device management installs and launches the glasses app; the microphone is for voice replies.
            manager.requestAuthorization(activity,listOf(GlassPermission.DEVICE_MANAGE,GlassPermission.MICROPHONE)){result->onMain {acceptAuth(result)}}
        } catch(_:Exception){mutableState.value=LinkState.Failed("Could not open Rokid authorization. Open Hi Rokid and try again.")}
    }
    fun onAuthorizationResult(resultCode:Int,data:Intent?) {
        if(state.value!=LinkState.Authorizing)return
        // parseAuthorizationResult invokes the registered callback; no auth Intent is logged.
        try {manager.parseAuthorizationResult(resultCode,data)}
        catch(_:Exception){mutableState.value=LinkState.Failed("Rokid authorization could not be read. Try again.")}
    }
    private fun acceptAuth(result:AuthResult) {
        val authorized=result.token
        if(result.isSuccess && !authorized.isNullOrBlank()){
            token=authorized;mutableState.value=LinkState.Authorized
        }else{token=null;mutableState.value=LinkState.Failed("Authorization was not granted (${result.errorCode?.name ?: "cancelled"}).")}
    }
    override fun connect() {
        val authorized=token ?: run {mutableState.value=LinkState.Failed("Authorize glasses first.");return}
        if(state.value==LinkState.Connecting || state.value==LinkState.Ready || state.value==LinkState.UpdatingHud)return
        openSession(authorized)
    }
    private fun openSession(authorized:String) {
        val attempt=++generation
        mutableState.value=LinkState.Connecting
        scope.launch {
            val apk=try{withContext(Dispatchers.IO){GlassesApkInstaller.ensureApkOnDisk(context)}}catch(_:Exception){
                if(attempt==generation)mutableState.value=LinkState.Failed("The bundled glasses app could not be prepared.");return@launch
            }
            if(attempt!=generation)return@launch
            try {
                session?.close()
                val created=manager.create(SessionConfig(sessionType=SessionType.CUSTOM_APP,
                    glassesPackageName=BuildConfig.GLASSES_PACKAGE,glassesActivityName=BuildConfig.GLASSES_ACTIVITY,
                    glassesApkPath=apk.absolutePath,aiInterceptMode=AiInterceptMode.ALLOW_WITH_PAUSE,
                    terminatingGracePeriodMs=HUD_AWAY_GRACE_MS))
                session=created
                var connectionFailure: LinkState.Failed? = null
                var terminating: TerminatingReason? = null
                created.addLifecycleCallback(object:ISessionLifecycleCbk {
                    private fun update(value:LinkState){onMain {if(attempt==generation)mutableState.value=value}}
                    override fun onSessionStarted(){Log.i("885rokidOSLink","session_started");update(LinkState.Ready);onMain {requestHello(attempt)}}
                    override fun onSessionPaused(reason:PausedReason){update(LinkState.Paused(reason.name))}
                    override fun onSessionResumed(){update(LinkState.Ready)}
                    override fun onSessionTerminating(reason:TerminatingReason,remainingMs:Long){
                        Log.i("885rokidOSLink","session_terminating reason=${reason.name}")
                        onMain {terminating=reason}
                        // HUD left the foreground: the session lingers for HUD_AWAY_GRACE_MS and still hears
                        // the HUD's hello when it returns (see onFrame); the phone shows "Show on glasses".
                        update(if(reason==TerminatingReason.GLASSES_APP_EXIT)LinkState.Closed(LinkState.Closed.HUD_CLOSED,false)
                            else LinkState.Paused("Closing"))
                    }
                    override fun onSessionClosed(reason:CloseReason){onMain {
                        Log.i("885rokidOSLink","session_closed reason=${reason.name}")
                        if(attempt==generation){val reauth=reason==CloseReason.CHAIN_TORN || reason==CloseReason.LINK_LOST
                            if(reauth)token=null
                            session=null;mutableState.value=if(reason==CloseReason.CONNECT_FAILED && connectionFailure!=null)
                                connectionFailure!! else if(!reauth && terminating==TerminatingReason.GLASSES_APP_EXIT)
                                LinkState.Closed(LinkState.Closed.HUD_CLOSED,false) else LinkState.Closed(reason.name,reauth)
                        }
                    }}
                    override fun onConnectResult(success:Boolean,error:SessionErrorCode?){onMain {
                        Log.i("885rokidOSLink","connect_result success=$success error=${error?.name ?: "none"}")
                        if(!success && attempt==generation){
                            connectionFailure=LinkState.Failed(if(error==SessionErrorCode.GLASSES_APP_INSTALL_FAILED)
                                "Hi Rokid couldn't send the HUD to the glasses: its Wi-Fi transfer needs Location, which it only has " +
                                "while on screen. Tap Install with Hi Rokid open (takes about 20 s)."
                                else "Glasses connection failed (${error?.name ?: "unknown"}). Check Hi Rokid.",canRetry=true,
                                installBlocked=error==SessionErrorCode.GLASSES_APP_INSTALL_FAILED)
                            mutableState.value=connectionFailure!!
                        }
                    }}
                })
                created.addAudioCallback(object:IAudioCallback {
                    override fun onAudioReceived(data:ByteArray){audioChunks.tryEmit(data)}
                    override fun onAudioError(code:Int,message:String?){Log.w(TAG,"audio_error code=$code")}
                    override fun onAudioStreamStateChanged(active:Boolean){Log.i(TAG,"audio_stream active=$active")}
                })
                created.addCustomCmdCallback(object:ICustomCmdSessionCallback {
                    override fun onCustomCmdResult(key:String,data:ByteArray?){if(attempt==generation)onFrame(key,data,attempt)}
                })
                created.connect(authorized)
            } catch(_:Exception){if(attempt==generation)mutableState.value=LinkState.Failed("Could not start the glasses session. Reopen Hi Rokid and try again.")}
        }
    }
    private fun onFrame(key:String,data:ByteArray?,attempt:Int) {
        if(key!=Channels.GLASSES_TO_PHONE || data==null || data.size>MAX_FRAME_BYTES)return
        val msg=runCatching {
            val caps=Caps.fromBytes(data)
            if(caps.size()!=2)null else ProtocolCodec.decode(caps.at(0).string,caps.at(1).string)
        }.getOrNull() ?: return
        if(msg is GlassesHello)onMain {
            // The HUD came back to the foreground while its session was lingering after it left: resume
            // with a fresh session (the HUD is already in front, so nothing pops up) and resync the trip.
            if(attempt==generation && (state.value as? LinkState.Closed)?.hudClosedOnGlasses==true) {
                Log.i(TAG,"hud_returned");token?.let(::openSession);return@onMain
            }
            checkHudVersion(msg,attempt)
        }
        messages.tryEmit(msg)
    }

    /**
     * The SDK only installs the HUD when none is on the glasses (confirmed in its log: an installed HUD is
     * opened as is), so a new build never reaches the glasses by itself. When the running HUD reports a
     * different version than the one bundled here, uninstall it and open a fresh session: the SDK then
     * finds no HUD and uploads, installs and opens the bundled APK. Tried once per bundled version, so a
     * HUD that still reports something else after reinstalling keeps running instead of looping.
     */
    private fun checkHudVersion(hello:GlassesHello,attempt:Int) {
        Log.i(TAG,"hud_hello installed=${hello.appVersion} bundled=${BuildConfig.BUNDLED_HUD_VERSION}")
        if(attempt!=generation)return
        helloFor=attempt
        if(hello.appVersion!=BuildConfig.BUNDLED_HUD_VERSION)replaceHud(attempt)
    }
    /** A HUD older than [HelloRequest] never answers it; silence therefore also means outdated. */
    private fun requestHello(attempt:Int) {
        if(attempt!=generation)return
        send(HelloRequest())
        main.postDelayed({
            if(attempt==generation && helloFor!=attempt){Log.i(TAG,"hud_hello missing");replaceHud(attempt)}
        },HELLO_TIMEOUT_MS)
    }
    private fun replaceHud(attempt:Int) {
        val bundled=BuildConfig.BUNDLED_HUD_VERSION
        if(attempt!=generation || hudReplacedFor==bundled)return
        hudReplacedFor=bundled
        // v1.1 has no public uninstall; the session's own SDK client has one.
        val client=session?.let(::linkClientOf) ?: run {Log.w(TAG,"hud_outdated, SDK client unreachable");return}
        Log.i(TAG,"hud_outdated bundled=$bundled")
        val update=++generation
        mutableState.value=LinkState.UpdatingHud
        main.postDelayed({if(update==generation)failHudUpdate()},HUD_UNINSTALL_TIMEOUT_MS)
        client.appUninstall(object:IGlassAppCbk {
            override fun onUnInstallAppResult(success:Boolean){onMain {
                if(update!=generation)return@onMain
                Log.i(TAG,"hud_uninstall success=$success")
                if(!success){failHudUpdate();return@onMain}
                session?.close();session=null
                token?.let(::openSession) ?: run {mutableState.value=LinkState.Failed("Authorize glasses again.")}
            }}
            override fun onQueryAppResult(installed:Boolean){}
            override fun onInstallAppResult(success:Boolean){}
            override fun onOpenAppResult(success:Boolean){}
            override fun onStopAppResult(success:Boolean){}
            override fun onGlassAppResume(resumed:Boolean){}
        })
    }
    private fun failHudUpdate() {
        generation++;session?.close();session=null
        mutableState.value=LinkState.Failed("The glasses have an older HUD that couldn't be replaced. Remove it in Hi Rokid → Toolbox → Glasses app management, then connect again.",canRetry=true)
    }
    private fun linkClientOf(session:CxrSession):ExternalAppClient?=runCatching {
        session.javaClass.declaredFields.firstOrNull {it.type==ExternalAppClient::class.java}
            ?.apply {isAccessible=true}?.get(session) as ExternalAppClient?
    }.getOrNull()
    override fun send(message:NavMessage):Boolean {
        if(state.value!=LinkState.Ready)return false
        return runCatching {
            val (magic,body)=ProtocolCodec.encode(message)
            if(body.toByteArray(Charsets.UTF_8).size>MAX_FRAME_BYTES-128)return false
            val caps=Caps().apply {write(magic);write(body)}
            session?.sendCustomCmd(Channels.PHONE_TO_GLASSES,caps,ByteArray(0))?.isSuccess==true
        }.getOrDefault(false)
    }
    override fun startAudio():Boolean {
        if(state.value!=LinkState.Ready)return false
        return runCatching {
            val result=session?.startAudioStream()
            Log.i(TAG,"audio_start success=${result?.isSuccess} code=${result?.code?.name}")
            result?.isSuccess==true
        }.getOrDefault(false)
    }
    override fun stopAudio(){runCatching {session?.stopAudioStream()}}
    override fun disconnect(){generation++;session?.close();session=null;mutableState.value=if(token==null)LinkState.Idle else LinkState.Authorized}

    private companion object {
        const val TAG="885rokidOSLink"
        const val HUD_UNINSTALL_TIMEOUT_MS=30_000L
        /** How long a session lingers after the HUD leaves the foreground, waiting for it to come back.
         *  The SDK accepts 1–30 s (SessionConfig rejects anything else, seen in its bytecode). */
        const val HUD_AWAY_GRACE_MS=30_000L
        const val HELLO_TIMEOUT_MS=8_000L
    }
}
