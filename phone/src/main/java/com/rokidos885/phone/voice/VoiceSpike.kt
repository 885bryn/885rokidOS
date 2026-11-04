package com.rokidos885.phone.voice

import android.content.Context
import android.util.Log
import com.rokidos885.phone.glasses.GlassesLink
import com.rokidos885.phone.glasses.LinkState
import kotlinx.coroutines.*
import java.io.File

/**
 * SPIKE (debug button on the Glasses tab): records [seconds] of the glasses' microphone, reports its loudness, and
 * transcribes it on the phone with [SherpaSpeech]. The recording stays in the app's private folder. The spoken test
 * phrase is logged on purpose here, never in the real feature.
 */
class VoiceSpike(private val context: Context, private val link: GlassesLink) {
    suspend fun run(seconds: Int, say: (String) -> Unit) {
        if (link.state.value != LinkState.Ready) { say("Glasses are not connected."); return }
        val file = File(context.filesDir, "voice-spike.pcm")
        var chunks = 0
        var bytes = 0L
        say("Speak now for $seconds s…")
        file.outputStream().use { out ->
            coroutineScope {
                val reader = launch(Dispatchers.IO) {
                    link.audio.collect { chunk -> chunks++; bytes += chunk.size; out.write(chunk) }
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
        Log.i(TAG, "audio chunks=$chunks bytes=$bytes")
        if (bytes == 0L) { say("No audio arrived from the glasses."); return }
        transcribe(file, say)
    }

    /**
     * Debug: ten typical short replies. The screen shows each phrase, the glasses microphone records [secondsEach] s, and the audio
     * is saved to files/accuracy/NN.pcm with the phrase in files/accuracy/phrases.txt, so models can be compared offline.
     */
    suspend fun recordAccuracySet(secondsEach: Int, say: (String) -> Unit) {
        if (link.state.value != LinkState.Ready) { say("Glasses are not connected."); return }
        val dir = File(context.filesDir, "accuracy").apply { deleteRecursively(); mkdirs() }
        File(dir, "phrases.txt").writeText(ACCURACY_PHRASES.mapIndexed { i, p -> "%02d\t%s".format(i, p) }.joinToString("\n"))
        for ((i, phrase) in ACCURACY_PHRASES.withIndex()) {
            say("${i + 1}/${ACCURACY_PHRASES.size}  Say: \"$phrase\"")
            val file = File(dir, "%02d.pcm".format(i))
            file.outputStream().use { out ->
                coroutineScope {
                    val reader = launch(Dispatchers.IO) { link.audio.collect { out.write(it) } }
                    if (!link.startAudio()) { say("The glasses refused the microphone."); reader.cancel(); return@coroutineScope }
                    delay(secondsEach * 1000L)
                    link.stopAudio()
                    delay(400)
                    reader.cancel()
                }
            }
            delay(600)
        }
        say("Done: ${ACCURACY_PHRASES.size} phrases saved. Tell Claude.")
    }

    /** Debug: transcribe the saved recording again (adb am broadcast to VoiceReplayReceiver). */
    suspend fun replay(say: (String) -> Unit) {
        val file = File(context.filesDir, "voice-spike.pcm")
        if (!file.exists()) { say("No recording yet."); return }
        transcribe(file, say)
    }

    private suspend fun transcribe(file: File, say: (String) -> Unit) = withContext(Dispatchers.Default) {
        val data = file.readBytes()
        val (peak, rms) = loudness(data)
        Log.i(TAG, "loudness peak=$peak rms=$rms")
        if (peak < 50) { say("The glasses microphone sent silence (peak $peak). Did it hear you?"); return@withContext }
        val modelDir = SherpaSpeech.modelDir(context)
        if (modelDir == null) { say("The speech model is not on the phone yet (docs/VOICE.md)."); return@withContext }
        val started = System.currentTimeMillis()
        SherpaSpeech(modelDir).use { engine ->
            val loaded = System.currentTimeMillis()
            engine.utterance().use { utterance ->
                var at = 0
                while (at < data.size) {
                    val end = minOf(data.size, at + 3_200)
                    utterance.accept(data.copyOfRange(at, end)); at = end
                }
                val text = utterance.finish()
                val done = System.currentTimeMillis()
                Log.i(TAG, "recognized=\"$text\" load=${loaded - started}ms decode=${done - loaded}ms audio=${data.size / 32}ms")
                say(if (text.isBlank()) "Heard nothing it could write down." else "Heard: ${Dictation.format(text)}  (${done - loaded} ms)")
            }
        }
    }

    /** Peak and RMS of 16-bit little-endian PCM; 0 = digital silence, 32767 = full scale. */
    private fun loudness(data: ByteArray): Pair<Int, Int> {
        var peak = 0; var sum = 0.0; var n = 0; var i = 0
        while (i + 1 < data.size) {
            val v = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort().toInt()
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
            sum += v.toDouble() * v; n++; i += 2
        }
        return peak to (if (n == 0) 0 else kotlin.math.sqrt(sum / n).toInt())
    }

    private companion object {
        const val TAG = "885rokidOSVoice"
        val ACCURACY_PHRASES = listOf(
            "good to know", "on my way", "I will call you later", "can we talk tomorrow", "sounds good see you there",
            "no problem thanks", "I am running about ten minutes late", "yes that works for me", "let me check and get back to you",
            "love you too",
        )
    }
}
