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
        val (peak, rms) = loudness(file)
        Log.i(TAG, "loudness peak=$peak rms=$rms")
        say(if (peak < 50) "The glasses microphone sent silence (peak $peak). Did it hear you?" else "Loudness: peak $peak, rms $rms.")
        if (peak < 50) return
        val text = recognize(file, 16_000, 1, 25_000)
        Log.i(TAG, "recognized=${text ?: "(nothing)"}")
        say(if (text.isNullOrBlank()) "The recognizer heard nothing it could use." else "Heard: $text")
    }

    /** Peak and RMS of 16-bit little-endian PCM, 0 = digital silence, 32767 = full scale. */
    private fun loudness(file: File): Pair<Int, Int> {
        val data = file.readBytes()
        var peak = 0; var sum = 0.0; var n = 0
        var i = 0
        while (i + 1 < data.size) {
            val v = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort().toInt()
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
            sum += v.toDouble() * v; n++; i += 2
        }
        return peak to (if (n == 0) 0 else kotlin.math.sqrt(sum / n).toInt())
    }

    /**
     * Runs the recognizer on the saved recording with each guess of its format and logs which one produces text.
     * Needs no new speech. Debug only (see VoiceReplayReceiver).
     */
    suspend fun replay(say: (String) -> Unit) {
        val file = File(context.filesDir, "voice-spike.pcm")
        if (!file.exists()) { say("No recording yet."); return }
        // A pipe fed at real-time speed looks like a live microphone; a file ends at once.
        for (pipe in booleanArrayOf(true, false)) for (rate in intArrayOf(16_000, 8_000)) {
            val text = recognize(file, rate, 1, 12_000, pipe)
            say("replay pipe=$pipe rate=$rate -> ${text ?: "(nothing)"}")
            // The recognizer service needs a moment before it takes the next request ("busy" / "server disconnected").
            delay(2_000)
        }
    }

    /** Feeds the saved 16-bit PCM to the on-device recognizer as the given format. */
    private suspend fun recognize(file: File, rate: Int, channels: Int, timeoutMs: Long, pipe: Boolean = false): String? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33) { Log.i(TAG, "needs Android 13 for a supplied audio source"); return@withContext null }
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        Log.i(TAG, "onDeviceRecognitionAvailable=$onDevice")
        val source: ParcelFileDescriptor
        if (pipe) {
            val ends = ParcelFileDescriptor.createPipe()
            source = ends[0]
            val sink = ParcelFileDescriptor.AutoCloseOutputStream(ends[1])
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    file.inputStream().use { input ->
                        val chunk = ByteArray(640 * channels * rate / 16_000)
                        while (true) { val n = input.read(chunk); if (n < 0) break; sink.write(chunk, 0, n); delay(20) }
                    }
                    sink.close()
                }
            }
        } else source = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        val result = withTimeoutOrNull(timeoutMs) {
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
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channels)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                recognizer.startListening(intent)
                cont.invokeOnCancellation { recognizer.cancel() }
            }
        }
        recognizer.destroy()
        result
    }

    private companion object { const val TAG = "RokidNavVoice" }
}
