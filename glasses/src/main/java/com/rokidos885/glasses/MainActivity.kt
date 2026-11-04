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
import com.rokidos885.glasses.ui.HudView
import kotlinx.coroutines.launch

class MainActivity:AppCompatActivity() {
    private lateinit var vm:HudViewModel
    private lateinit var hud:HudView
    private val keyReceiver=KeyReceiver { handleKey(it) }
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
        if(BuildConfig.DEBUG && intent.getBooleanExtra("demo",false) && intent.getBooleanExtra("previewExpanded",false))vm.store.previewMode(com.rokidos885.glasses.state.HudMode.Map)
        hud=HudView(this)
        val container=FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val previewWidth=if(BuildConfig.DEBUG)intent.getIntExtra("previewWidth",0) else 0
        val previewHeight=if(BuildConfig.DEBUG)intent.getIntExtra("previewHeight",0) else 0
        container.addView(hud,FrameLayout.LayoutParams(if(previewWidth>0)previewWidth else -1,if(previewHeight>0)previewHeight else -1,Gravity.CENTER))
        setContentView(container)
        ContextCompat.registerReceiver(this,keyReceiver,IntentFilter().apply {KeyType.entries.forEach { addAction(it.action) }},ContextCompat.RECEIVER_EXPORTED)
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
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.STARTED){vm.store.state.collect {hud.render(it);keepScreenOn(it)}}}
        // Debug only: show a scene without a phone (adb am start --es previewScene card|listening|review|sent|failed|media).
        // The big message numbers make the store ignore the demo trip's own messages.
        if(BuildConfig.DEBUG)intent.getStringExtra("previewScene")?.let {scene->
            val n=1_000_000_000L
            val store=vm.store
            store.onMessage(com.rokidos885.protocol.NavStateUpdate(n,com.rokidos885.protocol.NavState.IDLE))
            if(scene=="media") {
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
            hud.render(vm.store.state.value)
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
     * Battery: hold the display on only while the phone is actually guiding (link up, not silent).
     * With the phone connected the HUD stays open without a trip (it is the home for notifications and
     * other features) but lets the display sleep. Without the phone it closes itself after a while.
     */
    private var screenHeld=false
    private var idleSince=0L
    private val idleExit=Runnable {if(!isFinishing){android.util.Log.i("885rokidOSHud","idle_exit");finish()}}
    private fun keepScreenOn(s:com.rokidos885.glasses.state.HudState) {
        val live=s.navState in HOLD_SCREEN && s.linkConnected && !s.phoneSilent
        // VERIFY: whether a card wakes a display that is already asleep; this only keeps an awake one on.
        // A Grok Bot chat holds it too: the tap that ends the chat must not only wake the display.
        val hold=live || s.notification!=null || s.voice!=null || s.grokBotActive
        if(hold!=screenHeld) {
            screenHeld=hold
            if(hold)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if(demo)return
        if(live || s.linkConnected){idleSince=0L;hud.removeCallbacks(idleExit)}
        else if(idleSince==0L){idleSince=SystemClock.uptimeMillis();hud.postDelayed(idleExit,IDLE_EXIT_MS)}
    }
    private val demo get()=intent.getBooleanExtra("demo",false)
    private companion object {
        val HOLD_SCREEN=setOf(com.rokidos885.protocol.NavState.ACTIVE,com.rokidos885.protocol.NavState.REROUTING,
            com.rokidos885.protocol.NavState.GPS_LOST,com.rokidos885.protocol.NavState.ARRIVED)
        const val IDLE_EXIT_MS=5*60_000L
    }
    /** Back to the Rokid home screen. The task stays alive, so an active trip resumes where it was. */
    private fun exitToHome(){if(!moveTaskToBack(true))finish()}
    override fun onStart(){super.onStart();vm.onForeground()}
    override fun onDestroy(){hud.removeCallbacks(idleExit);unregisterReceiver(keyReceiver);super.onDestroy()}
}
