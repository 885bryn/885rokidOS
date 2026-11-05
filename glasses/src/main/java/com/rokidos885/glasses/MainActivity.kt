package com.rokidos885.glasses

import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.*
import com.rokidos885.glasses.input.*
import com.rokidos885.glasses.state.guiding
import com.rokidos885.glasses.state.holdsDisplay
import com.rokidos885.glasses.ui.HudView
import kotlinx.coroutines.launch

class MainActivity:AppCompatActivity() {
    private lateinit var vm:HudViewModel
    private lateinit var hud:HudView
    private val keyReceiver=KeyReceiver { handleKey(it) }
    private val screenOff=object:android.content.BroadcastReceiver(){
        override fun onReceive(context:android.content.Context?,intent:android.content.Intent?){
            if(intent?.action==android.content.Intent.ACTION_SCREEN_ON)vm.store.onDisplayOn() else vm.store.onDisplayOff()
        }
    }
    private var backGestureAt=0L
    private var backGestureConsumed=true
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // Let the glasses compositor choose its native orientation. Forced landscape rotates the HUD.
        if(BuildConfig.DEBUG && intent.getBooleanExtra("phonePreview",false))
            requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowCompat.getInsetsController(window,window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        vm=ViewModelProvider(this)[HudViewModel::class.java]
        // Demo only when asked for explicitly (phone "Preview on this phone", captures). Opening from the
        // Rokid home screen used to start the demo trip, which looked like a different navigation.
        vm.initialize(intent.getBooleanExtra("demo",false))
        // Awake lasts as long as the system's own screen timeout, so the HUD lets go just when the display would sleep.
        vm.store.awakeMillis=android.provider.Settings.System.getLong(contentResolver,android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
            com.rokidos885.glasses.state.HudStore.AWAKE_MS).coerceAtLeast(MIN_AWAKE_MS)
        if(BuildConfig.DEBUG && intent.getBooleanExtra("demo",false) && intent.getBooleanExtra("previewExpanded",false))vm.store.previewMode(com.rokidos885.glasses.state.HudMode.Map)
        hud=HudView(this)
        val container=FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val previewWidth=if(BuildConfig.DEBUG)intent.getIntExtra("previewWidth",0) else 0
        val previewHeight=if(BuildConfig.DEBUG)intent.getIntExtra("previewHeight",0) else 0
        container.addView(hud,FrameLayout.LayoutParams(if(previewWidth>0)previewWidth else -1,if(previewHeight>0)previewHeight else -1,Gravity.CENTER))
        setContentView(container)
        ContextCompat.registerReceiver(this,keyReceiver,IntentFilter().apply {KeyType.entries.forEach { addAction(it.action) }},ContextCompat.RECEIVER_EXPORTED)
        // VERIFY on hardware: that the glasses send SCREEN_OFF and SCREEN_ON when the display sleeps and wakes.
        ContextCompat.registerReceiver(this,screenOff,IntentFilter(android.content.Intent.ACTION_SCREEN_OFF).apply {addAction(android.content.Intent.ACTION_SCREEN_ON)},
            ContextCompat.RECEIVER_NOT_EXPORTED)
        onBackPressedDispatcher.addCallback(this){handleKey(GlassesKey.BACK)}
        val gestures=GestureDetector(this,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapConfirmed(e:MotionEvent):Boolean {vm.onKey(GlassesKey.TAP);return true}
            override fun onDoubleTap(e:MotionEvent):Boolean {handleKey(GlassesKey.DOUBLE_TAP);return true}
            override fun onFling(e1:MotionEvent?,e2:MotionEvent,velocityX:Float,velocityY:Float):Boolean {
                val start=e1 ?: return false
                if(kotlin.math.abs(e2.x-start.x)<hud.width*.1f)return false
                vm.onKey(if(e2.x<start.x)GlassesKey.SWIPE_FORWARD else GlassesKey.SWIPE_BACK);return true
            }
        })
        hud.setOnTouchListener { _,event -> gestures.onTouchEvent(event) }
        hud.post {vm.onDisplayReady(hud.width,hud.height)}
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.STARTED){vm.store.state.collect {hud.render(it)}}}
        // Not tied to STARTED: the display hold and the wake for T3 news must work while the display sleeps.
        lifecycleScope.launch {vm.store.state.collect {keepScreenOn(it)}}
        // Debug only: show a scene without a phone (adb am start --es previewScene card|listening|review|sent|failed|media|t3|grok).
        // The big message numbers make the store ignore the demo trip's own messages.
        if(BuildConfig.DEBUG)intent.getStringExtra("previewScene")?.let {scene->
            val n=1_000_000_000L
            val store=vm.store
            store.onMessage(com.rokidos885.protocol.NavStateUpdate(n,com.rokidos885.protocol.NavState.IDLE))
            if(scene=="t3") {
                store.onMessage(com.rokidos885.protocol.T3Status(n+1,"Fix the glasses build","claude-opus-5-5",com.rokidos885.protocol.T3State.REPLY,
                    "Run the unit tests and tell me what fails","All 214 tests pass. The build is green, and I changed nothing."))
                store.onKey(GlassesKey.SWIPE_BACK)
            } else if(scene=="grok") {
                store.onConnection(true);store.onMessage(com.rokidos885.protocol.GrokBotState(n+1,true,60))
            } else if(scene=="media") {
                store.onMessage(com.rokidos885.protocol.MediaState(n+1,true,"Spotify","Midnight City, a very long track title that wraps","M83",true,0,0))
                store.onKey(GlassesKey.SWIPE_FORWARD)
            } else {
                store.onMessage(com.rokidos885.protocol.PhoneNotification(n+1,"n1","WhatsApp","Sam Carter","Hey, are we still on for dinner tonight? I booked a table at the new place on Main Street for 7:30, but I can move it to 8 if you need more time after work.",canReply=true))
                val phase=when(scene){"listening"->com.rokidos885.protocol.VoicePhase.LISTENING;"review"->com.rokidos885.protocol.VoicePhase.REVIEW;"sent"->com.rokidos885.protocol.VoicePhase.SENT;"failed"->com.rokidos885.protocol.VoicePhase.FAILED;else->null}
                if(phase!=null)store.onMessage(com.rokidos885.protocol.VoiceState(n+2,phase,"n1",when(phase){
                    com.rokidos885.protocol.VoicePhase.LISTENING->"Yes I am still on for dinner, see you at";com.rokidos885.protocol.VoicePhase.REVIEW->"Yes, I am still on for dinner. Seven thirty is fine, see you there.";
                    com.rokidos885.protocol.VoicePhase.FAILED->"The app refused the reply";else->""}))
            }
        }
        if(BuildConfig.DEBUG && intent.getBooleanExtra("capture",false))hud.postDelayed({
            hud.render(vm.store.state.value,now=true)
            val bitmap=Bitmap.createBitmap(hud.width,hud.height,Bitmap.Config.ARGB_8888)
            hud.draw(Canvas(bitmap))
            java.io.File(filesDir,"hud-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        },intent.getIntExtra("captureDelayMs",8000).toLong())
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean {
        android.util.Log.d("885rokidOSKeys","keyCode=$keyCode")
        val key=GlassesKey.fromKeyCode(keyCode) ?: return super.onKeyDown(keyCode,event)
        vm.onKey(key);return true
    }
    /**
     * Every input path lands here. Double-tap and Back are one "back" gesture: Rokid can report a single
     * double-click both as a broadcast and as a Back key, so a second report within 700 ms repeats the
     * first decision instead of acting twice (e.g. keeping a detour and then exiting). Returns true when
     * the HUD used the key; a back gesture it doesn't need returns to the Rokid home screen.
     */
    private fun handleKey(key:GlassesKey):Boolean {
        if(key!=GlassesKey.DOUBLE_TAP && key!=GlassesKey.BACK){vm.onKey(key);return true}
        val now=SystemClock.uptimeMillis()
        if(now-backGestureAt<700)return backGestureConsumed
        backGestureAt=now
        backGestureConsumed=vm.onKey(key)
        if(!backGestureConsumed)exitToHome()
        return backGestureConsumed
    }
    /**
     * Battery: hold the display on while the phone is guiding (link up, not silent), while something else must stay
     * on screen, and while the HUD counts itself awake (the countdown ends it). With the phone connected the HUD stays
     * open without a trip (it is the home for notifications and other features) but lets the display sleep. Without
     * the phone it closes itself after a while.
     */
    private var screenHeld=false
    /** The user left to the Rokid menu: hold nothing and wake nothing until the HUD is opened again. */
    private var leftHome=false
    private var wokeFor=0
    /**
     * The hold is a screen wake lock without ON_AFTER_RELEASE. FLAG_KEEP_SCREEN_ON is one with it: its release restarted
     * the system's screen timeout, so the display stayed on a full timeout after the countdown (seen 2026-11-05).
     * VERIFY on hardware: that the display sleeps right after the countdown.
     */
    @Suppress("DEPRECATION")
    private val screenLock by lazy {getSystemService(android.os.PowerManager::class.java)
        .newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK,"885rokidOS:hud").apply {setReferenceCounted(false)}}
    /** Turns a sleeping display on (news from T3). The screen lock then keeps it on. */
    @Suppress("DEPRECATION")
    private fun wakeDisplay()=getSystemService(android.os.PowerManager::class.java).newWakeLock(
        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,"885rokidOS:wake").acquire(1_000)
    private var idleSince=0L
    private val idleExit=Runnable {if(!isFinishing){android.util.Log.i("885rokidOSHud","idle_exit");finish()}}
    private fun keepScreenOn(s:com.rokidos885.glasses.state.HudState) {
        val live=s.guiding
        // VERIFY: whether a card wakes a display that is already asleep; this only keeps an awake one on.
        // A Grok Bot chat holds it too: the tap that ends the chat must not only wake the display.
        // Awake holds it too: "ACTIVE" and the T3 page promise that the next tap acts, so the display must not sleep
        // before the countdown ends. Released then, it sleeps at once (the system timeout has run out by then).
        val hold=(s.holdsDisplay || s.awake) && !leftHome
        if(s.wakeSeq!=wokeFor){wokeFor=s.wakeSeq;if(!leftHome)wakeDisplay()}
        if(hold!=screenHeld) {
            screenHeld=hold
            if(hold)screenLock.acquire() else screenLock.release()
        }
        if(demo)return
        if(live || s.linkConnected){idleSince=0L;hud.removeCallbacks(idleExit)}
        else if(idleSince==0L){idleSince=SystemClock.uptimeMillis();hud.postDelayed(idleExit,IDLE_EXIT_MS)}
    }
    private val demo get()=intent.getBooleanExtra("demo",false)
    private companion object {
        const val MIN_AWAKE_MS=10_000L
        const val IDLE_EXIT_MS=5*60_000L
    }
    /** Back to the Rokid home screen. The task stays alive, so an active trip resumes where it was. */
    private fun exitToHome(){leftHome=true;keepScreenOn(vm.store.state.value);if(!moveTaskToBack(true))finish()}
    override fun onStart(){super.onStart();leftHome=false;keepScreenOn(vm.store.state.value);vm.onForeground()}
    override fun onDestroy(){hud.removeCallbacks(idleExit);if(screenHeld)screenLock.release();unregisterReceiver(keyReceiver);unregisterReceiver(screenOff);super.onDestroy()}
}
