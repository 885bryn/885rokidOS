package com.rokidnav.phone.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.rokidnav.phone.glasses.GlassesLink
import com.rokidnav.phone.glasses.LinkState
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

/**
 * SPIKE (debug button on the Glasses tab): records [seconds] of the glasses' microphone, logs what the stream
 * looks like, then feeds the audio to Android's on-device speech recognizer. It answers three questions:
 * does the stream work, what format is it, and does the recognizer understand it. The recording stays in the
 * app's private folder; the spoken test phrase is logged on purpose here, never in the real feature.
 */
class VoiceSpike(private val context: Context, private val link: GlassesLink) {
    suspend fun run(seconds: Int, say: (String) -> Unit) {
        if (link.state.value != LinkState.Ready) { say("Glasses are not connected."); return }
        val file = File(context.filesDir, "voice-spike.pcm")
        var chunks = 0
        var bytes = 0L
        var head = ByteArray(0)
        say("Speak now for $seconds s…")
        file.outputStream().use { out ->
            coroutineScope {
                val reader = launch(Dispatchers.IO) {
                    link.audio.collect { chunk ->
                        if (chunks == 0) head = chunk.copyOf(minOf(chunk.size, 16))
                        chunks++; bytes += chunk.size; out.write(chunk)
                    }
                }
                if (!link.startAudio()) {
                    say("The glasses refused the microphone stream. Authorize again so Hi Rokid can ask for the microphone.")
                    reader.cancel(); return@coroutineScope
                }
                delay(seconds * 1000L)
                link.stopAudio()
                delay(400)
                reader.cancel()
            }
        }
        val perSecond = bytes / seconds
        val hex = head.joinToString(" ") { "%02x".format(it) }
        Log.i(TAG, "audio chunks=$chunks bytes=$bytes perSecond=$perSecond firstChunkHead=$hex")
        say("Audio: $chunks chunks, $bytes bytes ($perSecond B/s). 32000 B/s = 16 kHz 16-bit mono.")
        if (bytes == 0L) return
        val text = recognize(file)
        Log.i(TAG, "recognized=${text ?: "(nothing)"}")
        say(if (text.isNullOrBlank()) "The recognizer heard nothing it could use." else "Heard: $text")
    }

    /** Assumes 16 kHz 16-bit mono PCM until the stream's real format is known. */
    private suspend fun recognize(file: File): String? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33) { Log.i(TAG, "needs Android 13 for a supplied audio source"); return@withContext null }
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        Log.i(TAG, "onDeviceRecognitionAvailable=$onDevice")
        val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        val result = withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine<String?> { cont ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) { cont.resume(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()) }
                    override fun onError(error: Int) { Log.i(TAG, "recognizer error=$error"); cont.resume(null) }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                recognizer.startListening(intent)
                cont.invokeOnCancellation { recognizer.cancel() }
            }
        }
        recognizer.destroy()
        result
    }

    private companion object { const val TAG = "RokidNavVoice" }
}
