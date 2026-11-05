package com.rokidos885.phone.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Android text-to-speech for alert warnings. Uses the navigation-guidance audio usage, so it plays on the
 * same output as Google's turn voice (the glasses' speakers when Hi Rokid routes Bluetooth audio there),
 * and asks other audio to duck rather than pause. Sentences queue behind each other.
 */
class AndroidAlertSpeaker(context: Context, private val logText: Boolean = true) : AlertSpeaker {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).build()
    private val pending = ArrayList<String>()
    private var ready = false
    private var speaking = 0
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) { Log.w(TAG, "text-to-speech unavailable ($status)"); return@TextToSpeech }
        configure()
    }

    private fun configure() {
        tts.setAudioAttributes(attributes)
        if (tts.isLanguageAvailable(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE) tts.language = Locale.getDefault()
        else tts.language = Locale.US
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finished()
            @Deprecated("Required override") override fun onError(id: String?) = finished()
        })
        synchronized(this) { ready = true; pending.forEach(::say); pending.clear() }
    }

    @Synchronized private fun finished() {
        speaking = (speaking - 1).coerceAtLeast(0)
        if (speaking == 0) audio.abandonAudioFocusRequest(focus)
    }

    @Synchronized override fun speak(text: String) {
        if (!ready) { if (pending.size < 3) pending += text; return }
        say(text)
    }

    private fun say(text: String) {
        if (speaking == 0) audio.requestAudioFocus(focus)
        speaking++
        // T3 replies are private: their speaker logs only the length.
        if (logText) Log.i(TAG, "alert_voice \"$text\"") else Log.i(TAG, "voice ${text.length} chars")
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString()) != TextToSpeech.SUCCESS) finished()
    }

    /** Stops what is being said and what waits (a dictation is about to listen). */
    @Synchronized fun stop() { pending.clear(); if (ready) tts.stop(); if (speaking > 0) { speaking = 0; audio.abandonAudioFocusRequest(focus) } }

    override fun shutdown() { tts.stop(); tts.shutdown(); audio.abandonAudioFocusRequest(focus) }

    private companion object { const val TAG = "885rokidOSAlerts" }
}
