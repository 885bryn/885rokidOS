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

    /**
     * Debug: a fixed set of phrases for the second-pass test (2026-11-05). [dir] is under files/, [limits] are those of the
     * real feature (so our model decodes as it does there), [seconds] is the recording time of each phrase.
     */
    class TestSet(val name: String, val dir: String, val seconds: Int, val limits: ListenLimits, val phrases: List<String>)

    /**
     * Debug: records [set] like [recordAccuracySet] (the phone shows each phrase, the glasses microphone records it), then
     * [scoreSet]. [card] shows each phrase on the glasses. Recordings and results stay in the app's private folder; nothing goes to logcat but counts and times.
     */
    suspend fun recordAndScore(set: TestSet, say: (String) -> Unit, line: (String) -> Unit, card: (String, String) -> Unit = { _, _ -> }) {
        if (link.state.value != LinkState.Ready) { say("Glasses are not connected."); return }
        val dir = File(context.filesDir, set.dir).apply { deleteRecursively(); mkdirs() }
        File(dir, "phrases.txt").writeText(set.phrases.mapIndexed { i, p -> "%02d\t%s".format(i, p) }.joinToString("\n"))
        for ((i, phrase) in set.phrases.withIndex()) {
            // The glasses microphone sometimes sends only zeros (2026-11-05: 12 of 18 recordings): such a recording is
            // useless for scoring, so the phrase is asked again, up to [SILENT_TRIES] times.
            for (attempt in 1..SILENT_TRIES) {
                say(if (attempt == 1) "Get ready…  ${i + 1}/${set.phrases.size}" else "No sound from the glasses microphone. Again…  ${i + 1}/${set.phrases.size}")
                delay(if (attempt == 1) 1_200 else 2_500)
                say("${i + 1}/${set.phrases.size}  Say now (${set.seconds} s):  \"$phrase\"")
                // The phrase also goes to the glasses as a card: it keeps their display on (a sleeping display sent only
                // zeros from the microphone, 2026-11-05) and the user can read it there.
                card("Say now  ${i + 1}/${set.phrases.size}", phrase)
                val file = File(dir, "%02d.pcm".format(i))
                val ok = file.outputStream().use { out ->
                    coroutineScope {
                        val reader = launch(Dispatchers.IO) { link.audio.collect { out.write(it) } }
                        if (!link.startAudio()) { reader.cancel(); return@coroutineScope false }
                        delay(set.seconds * 1000L)
                        link.stopAudio()
                        delay(400)
                        reader.cancel()
                        true
                    }
                }
                if (!ok) { say("The glasses refused the microphone."); return }
                val peak = loudness(file.readBytes()).first
                Log.i(TAG, "test ${set.dir} %02d attempt $attempt peak=$peak".format(i))
                if (peak >= SPEECH_PEAK) break
                if (attempt == SILENT_TRIES) line("${i + 1}. No sound from the glasses microphone in $SILENT_TRIES tries.")
            }
        }
        scoreSet(set, say, line)
    }

    /**
     * Debug: decodes the recordings of [set] with our streaming model (as the feature does), Parakeet v3 and Whisper small.en,
     * one model at a time, and shows each model's wrong words of all words, its median wait after the end, and the phrases
     * any model got wrong. The second-pass models count only their decode: they load while the user speaks.
     */
    suspend fun scoreSet(set: TestSet, say: (String) -> Unit, line: (String) -> Unit) = withContext(Dispatchers.Default) {
        val dir = File(context.filesDir, set.dir)
        val audio = set.phrases.indices.map { File(dir, "%02d.pcm".format(it)).takeIf(File::isFile)?.readBytes() }
        if (audio.any { it == null }) { say("Record the ${set.name} first."); return@withContext }
        val heard = linkedMapOf<String, List<Pair<String, Long>>>()
        say("Scoring: our model…")
        val modelDir = SherpaSpeech.modelDir(context)
        if (modelDir != null) SherpaSpeech(modelDir, set.limits).use { engine ->
            heard["Ours"] = audio.map { pcm ->
                engine.utterance().use { u ->
                    var at = 0
                    // Fed as it arrives (100 ms chunks); our model decodes while the user speaks, so only finish() is a wait.
                    while (at < pcm!!.size) { val end = minOf(at + 3_200, pcm.size); u.accept(pcm.copyOfRange(at, end)); at = end }
                    val t = System.currentTimeMillis()
                    u.finish() to System.currentTimeMillis() - t
                }
            }
        }
        val second = OfflineSecondPass(context)
        for ((label, model) in listOf("Parakeet" to OfflineSecondPass.Model.PARAKEET, "Whisper" to OfflineSecondPass.Model.WHISPER)) {
            say("Scoring: $label…")
            val reader = runCatching { second.open(model) }.getOrNull() ?: continue
            reader.use { r -> heard[label] = audio.map { pcm -> val t = System.currentTimeMillis(); r.read(pcm!!) to System.currentTimeMillis() - t } }
        }
        val total = set.phrases.sumOf { WordErrors.words(it).size }
        val results = StringBuilder("engine\tphrase\twrong\tms\ttext\n")
        val summary = heard.map { (label, texts) ->
            val wrong = texts.mapIndexed { i, (text, ms) ->
                WordErrors.count(set.phrases[i], text).also { results.append("$label\t$i\t$it\t$ms\t$text\n") }
            }.sum()
            val wait = texts.map { it.second }.sorted()[texts.size / 2]
            Log.i(TAG, "test ${set.dir} $label wrong=$wrong/$total median_ms=$wait")
            "$label $wrong/$total wrong, ${"%.1f".format(wait / 1000.0)} s"
        }
        File(dir, "results.tsv").writeText(results.toString())
        for (i in set.phrases.indices) {
            val got = heard.mapValues { (_, texts) -> texts[i].first }
            if (got.values.all { WordErrors.count(set.phrases[i], it) == 0 }) continue
            line("${i + 1}. \"${set.phrases[i]}\"\n" + got.entries.joinToString("\n") { (label, text) ->
                val n = WordErrors.count(set.phrases[i], text)
                "   $label${if (n == 0) " ✓" else " ($n)"}: ${Dictation.format(text)}"
            })
        }
        say("${set.name}: " + summary.joinToString(" · ") + if (heard.size < 3) "  (a model is missing on the phone)" else "")
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

    companion object {
        private const val TAG = "885rokidOSVoice"
        private const val SILENT_TRIES = 3
        /** Below this peak a recording has no speech: the glasses send 0 or 1 when the microphone is silent. */
        private const val SPEECH_PEAK = 200
        /** Everyday message replies, with the kinds of words our model got wrong. */
        val REPLY_SET = TestSet("reply test", "test-replies", 6, ListenLimits.REPLY, listOf(
            "hey are we still on for dinner tonight", "I'm stuck in traffic be there in twenty minutes",
            "can you pick up some milk on the way home", "sounds good let me know when you leave",
            "thanks for the birthday wishes", "I already sent the photos to your email", "running late start without me",
            "what time does the movie start", "happy to help anytime", "I'll call you back after my meeting",
        ))
        /** Instructions to T3, with the words that went wrong in real dictations (pull, emojis, DeepSeek, thread). */
        val T3_SET = TestSet("T3 test", "test-t3", 10, ListenLimits.DICTATION, listOf(
            "pull latest branch and run the tests", "add emojis to the notification card",
            "ask DeepSeek to review the pull request on GitHub", "start a thread about the soy sauce recipe",
            "make the notification page wider and merge it", "fix the Kotlin build error in the glasses app",
            "commit merge and push to master", "install the phone app over wireless debugging",
        ))
        private val ACCURACY_PHRASES = listOf(
            "good to know", "on my way", "I will call you later", "can we talk tomorrow", "sounds good see you there",
            "no problem thanks", "I am running about ten minutes late", "yes that works for me", "let me check and get back to you",
            "love you too",
        )
    }
}
