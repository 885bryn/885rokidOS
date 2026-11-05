package com.rokidos885.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.rokidos885.phone.voice.ListenLimits
import com.rokidos885.phone.voice.SherpaSpeech
import com.rokidos885.phone.voice.SpeechAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug only. Measures speech engines on the phone over the saved recordings (`files/voice-replies`, `files/accuracy`):
 * `adb shell am broadcast -n com.rokidos885.phone/com.rokidos885.phone.debug.SttBenchReceiver`.
 *
 * 1. The app's streaming model as T3 dictation uses it ([ListenLimits.DICTATION]: word list, beam search).
 * 2. Parakeet TDT 0.6B v3 int8, not streaming, on the raw audio: the second-pass candidate (docs/VOICE.md, 2026-11-05).
 *    Put its four files in `files/stt/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/` (adb push, as for the other model).
 *
 * Times and memory go to logcat (tag 885rokidOSBench). The texts go only to `files/stt-bench.tsv` in the app's private folder,
 * never to logcat: they are the user's own speech. Read it with `adb exec-out run-as com.rokidos885.phone cat files/stt-bench.tsv`.
 */
class SttBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Runs longer than a broadcast may last, so it does not hold the broadcast open.
        CoroutineScope(Dispatchers.Default).launch {
            try { run(app) } catch (e: Throwable) { log("failed: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private fun run(context: Context) {
        val files = listOf("voice-replies", "accuracy")
            .flatMap { File(context.filesDir, it).listFiles { f -> f.name.endsWith(".pcm") }.orEmpty().sortedBy { it.name } }
        if (files.isEmpty()) { log("no recordings in files/voice-replies or files/accuracy"); return }
        val out = File(context.filesDir, "stt-bench.tsv")
        out.writeText("engine\tfile\taudio_s\tdecode_ms\ttext\n")
        log("start: ${files.size} recordings, ${memory()}")

        val streamingDir = SherpaSpeech.modelDir(context)
        if (streamingDir == null) log("streaming model missing") else {
            val t0 = System.currentTimeMillis()
            SherpaSpeech(streamingDir, ListenLimits.DICTATION).use { engine ->
                log("streaming ${streamingDir.name} (T3 word list) loaded in ${System.currentTimeMillis() - t0} ms, ${memory()}")
                var total = 0L
                for (f in files) {
                    val pcm = f.readBytes()
                    val t = System.currentTimeMillis()
                    val text = engine.utterance().use { u ->
                        var at = 0
                        while (at < pcm.size) { val end = minOf(at + 640, pcm.size); u.accept(pcm.copyOfRange(at, end)); at = end }
                        u.finish()
                    }
                    val ms = System.currentTimeMillis() - t; total += ms
                    out.appendText("streaming\t${f.parentFile?.name}/${f.name}\t${seconds(pcm)}\t$ms\t${text.trim()}\n")
                }
                log("streaming decode total $total ms for ${"%.1f".format(files.sumOf { it.length() } / 32000.0)} s of audio, ${memory()}")
            }
        }

        val parakeet = File(context.getExternalFilesDir("stt"), PARAKEET).apply { mkdirs() }
        val parts = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        if (!parts.all { File(parakeet, it).isFile }) { log("Parakeet missing in $parakeet"); log("done"); return }
        val t0 = System.currentTimeMillis()
        val rec = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SherpaSpeech.SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(parakeet, parts[0]).path, decoder = File(parakeet, parts[1]).path, joiner = File(parakeet, parts[2]).path,
                    ),
                    tokens = File(parakeet, parts[3]).path,
                    numThreads = 2,
                    modelType = "nemo_transducer",
                ),
            ),
        )
        try {
            log("parakeet loaded in ${System.currentTimeMillis() - t0} ms, ${memory()}")
            var total = 0L
            for (f in files) {
                val pcm = f.readBytes()
                val t = System.currentTimeMillis()
                // Raw audio: on the PC, Parakeet did as well or better without pre-emphasis.
                val stream = rec.createStream()
                stream.acceptWaveform(SpeechAudio.toFloats(pcm), SherpaSpeech.SAMPLE_RATE)
                rec.decode(stream)
                val text = rec.getResult(stream).text
                stream.release()
                val ms = System.currentTimeMillis() - t; total += ms
                out.appendText("parakeet\t${f.parentFile?.name}/${f.name}\t${seconds(pcm)}\t$ms\t${text.trim()}\n")
            }
            log("parakeet decode total $total ms, ${memory()}")
        } finally {
            rec.release()
        }
        log("done")
    }

    private fun seconds(pcm: ByteArray) = "%.1f".format(pcm.size / 32000.0)

    /** Resident and peak memory of the app process. */
    private fun memory(): String = runCatching {
        val status = File("/proc/self/status").readLines()
        fun kb(key: String) = status.first { it.startsWith(key) }.filter { it.isDigit() }.toLong() / 1024
        "RSS ${kb("VmRSS")} MB, peak ${kb("VmHWM")} MB"
    }.getOrDefault("memory unknown")

    private fun log(text: String) { Log.i("885rokidOSBench", text) }

    private companion object { const val PARAKEET = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8" }
}
