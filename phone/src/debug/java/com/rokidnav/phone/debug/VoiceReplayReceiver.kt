package com.rokidnav.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokidnav.phone.RokidNavApp
import com.rokidnav.phone.voice.VoiceSpike
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Debug only: `adb shell am broadcast -n com.rokidnav.phone/com.rokidnav.phone.debug.VoiceReplayReceiver`. */
class VoiceReplayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                VoiceSpike(context.applicationContext, RokidNavApp.instance.glassesLink).replay { Log.i("RokidNavVoice", it) }
            } finally {
                pending.finish()
            }
        }
    }
}
