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
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
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
 * 3. Whisper small.en int8, the same way: the best on short replies on the PC. Its three `small.en-*` int8 files go in
 *    `files/stt/sherpa-onnx-whisper-small.en/`. A model that is missing is skipped.
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

        val stt = context.getExternalFilesDir("stt")
        val parakeet = File(stt, PARAKEET).apply { mkdirs() }
        offline("parakeet", parakeet, listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), files, out) { p ->
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = p[0], decoder = p[1], joiner = p[2]),
                tokens = p[3], numThreads = 2, modelType = "nemo_transducer",
            )
        }
        val whisper = File(stt, WHISPER).apply { mkdirs() }
        offline("whisper", whisper, listOf("small.en-encoder.int8.onnx", "small.en-decoder.int8.onnx", "small.en-tokens.txt"), files, out) { p ->
            OfflineModelConfig(whisper = OfflineWhisperModelConfig(encoder = p[0], decoder = p[1]), tokens = p[2], numThreads = 2, modelType = "whisper")
        }
        log("done")
    }

    /** A model that reads the whole recording ([parts] in [dir], or skipped), on the raw audio as on the PC. */
    private fun offline(name: String, dir: File, parts: List<String>, files: List<File>, out: File, model: (List<String>) -> OfflineModelConfig) {
        if (!parts.all { File(dir, it).isFile }) { log("$name missing in $dir"); return }
        val t0 = System.currentTimeMillis()
        val rec = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SherpaSpeech.SAMPLE_RATE, featureDim = 80),
            modelConfig = model(parts.map { File(dir, it).path }),
        ))
        try {
            log("$name loaded in ${System.currentTimeMillis() - t0} ms, ${memory()}")
            var total = 0L
            for (f in files) {
                val pcm = f.readBytes()
                val t = System.currentTimeMillis()
                val stream = rec.createStream()
                stream.acceptWaveform(SpeechAudio.toFloats(pcm), SherpaSpeech.SAMPLE_RATE)
                rec.decode(stream)
                val text = rec.getResult(stream).text
                stream.release()
                val ms = System.currentTimeMillis() - t; total += ms
                out.appendText("$name\t${f.parentFile?.name}/${f.name}\t${seconds(pcm)}\t$ms\t${text.trim()}\n")
            }
            log("$name decode total $total ms, ${memory()}")
        } finally {
            rec.release()
        }
    }

    private fun seconds(pcm: ByteArray) = "%.1f".format(pcm.size / 32000.0)

    /** Resident and peak memory of the app process. */
    private fun memory(): String = runCatching {
        val status = File("/proc/self/status").readLines()
        fun kb(key: String) = status.first { it.startsWith(key) }.filter { it.isDigit() }.toLong() / 1024
        "RSS ${kb("VmRSS")} MB, peak ${kb("VmHWM")} MB"
    }.getOrDefault("memory unknown")

    private fun log(text: String) { Log.i("885rokidOSBench", text) }

    private companion object {
        const val PARAKEET = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
        const val WHISPER = "sherpa-onnx-whisper-small.en"
    }
}
