package com.rokidnav.phone.session

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rokidnav.phone.RokidNavApp
import com.rokidnav.phone.ui.MainActivity
import com.rokidnav.protocol.NavState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Ongoing location service for guidance while the phone screen is off. */
class NavForegroundService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Navigation",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end=PendingIntent.getService(this,1,Intent(this,NavForegroundService::class.java).setAction(END),PendingIntent.FLAG_IMMUTABLE)
        val show=PendingIntent.getService(this,2,Intent(this,NavForegroundService::class.java).setAction(SHOW),PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("885rokidOS navigation").setContentText("Guidance is running. Keep Hi Rokid connected.")
            .setContentIntent(open).setOngoing(true).setSilent(true).addAction(0,"Show on glasses",show).addAction(0,"End navigation",end).build()
        startForeground(71,notification)
        scope.launch {(application as RokidNavApp).navEngine.navState.collectLatest {
            if(it==NavState.ENDED || it==NavState.ARRIVED)stopSelf()
        }}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==END){(application as RokidNavApp).navEngine.stop();stopSelf()}
        // Reopens the HUD after it was closed on the glasses; a no-op while already connected.
        if(intent?.action==SHOW)(application as RokidNavApp).glassesLink.connect()
        return START_NOT_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    companion object {const val CHANNEL="guidance";const val END="com.rokidnav.END";const val SHOW="com.rokidnav.SHOW_ON_GLASSES"}
}
