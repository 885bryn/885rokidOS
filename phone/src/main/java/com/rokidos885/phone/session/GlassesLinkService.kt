package com.rokidos885.phone.session

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rokidos885.phone.RokidOs885App
import com.rokidos885.phone.glasses.LinkState
import com.rokidos885.phone.ui.MainActivity

/**
 * Keeps the phone app (and so the glasses session) alive with the screen off and no trip, so
 * notifications and the other non-navigation features keep working. [NavForegroundService] adds
 * location on top of this while guiding. RokidOs885App starts and stops it from the link state.
 */
class GlassesLinkService:Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Glasses connection",NotificationManager.IMPORTANCE_MIN))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val off=PendingIntent.getService(this,1,Intent(this,GlassesLinkService::class.java).setAction(DISCONNECT),PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("885rokidOS is on your glasses").setContentText("Notifications are sent to the glasses.")
            .setContentIntent(open).setOngoing(true).setSilent(true).addAction(0,"Disconnect",off).build()
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID,notification)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        // Stopping follows from the link state (RokidOs885App), so the service and the link never disagree.
        if(intent?.action==DISCONNECT)(application as RokidOs885App).glassesLink.disconnect()
        return START_NOT_STICKY
    }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        private const val CHANNEL="glasses_link";private const val ID=72;private const val DISCONNECT="com.rokidos885.DISCONNECT_GLASSES"
        /** States in which the session exists or is being opened; the service runs only in these. */
        fun wanted(state:LinkState)=state==LinkState.Connecting || state==LinkState.UpdatingHud ||
            state==LinkState.Ready || state is LinkState.Paused
        fun sync(context:Context,state:LinkState) {
            val intent=Intent(context,GlassesLinkService::class.java)
            // Android 12+ refuses a foreground start from the background; the link then simply lives
            // as long as the process does, as before this service existed.
            if(wanted(state))runCatching {ContextCompat.startForegroundService(context,intent)}
            else context.stopService(intent)
        }
    }
}
