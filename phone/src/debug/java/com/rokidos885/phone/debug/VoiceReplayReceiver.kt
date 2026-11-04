package com.rokidos885.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokidos885.phone.RokidOs885App
import com.rokidos885.phone.voice.VoiceSpike
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Debug only: `adb shell am broadcast -n com.rokidos885.phone/com.rokidos885.phone.debug.VoiceReplayReceiver`. */
class VoiceReplayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                VoiceSpike(context.applicationContext, RokidOs885App.instance.glassesLink).replay { Log.i("885rokidOSVoice", it) }
            } finally {
                pending.finish()
            }
        }
    }
}
