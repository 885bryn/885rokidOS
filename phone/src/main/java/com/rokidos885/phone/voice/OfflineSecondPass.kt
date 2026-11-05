package com.rokidos885.phone.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.rokidos885.protocol.T3_CARD_ID
import java.io.File

/**
 * The second pass on the phone (measured 2026-11-05, docs/LOCAL_TEST_RESULTS.md): Parakeet TDT 0.6B v3 for T3 dictation
 * (about 1 s for 12 s, fixes the known wrong words) and Whisper small.en with 4 threads for message replies (about 1.6 s,
 * 1 wrong word of 46 against 6). Each loads for one voice step and is freed after it (about 1 GB while loaded). The
 * model files are put in `files/stt/<name>/` by hand, as for the streaming model; without them there is no second pass.
 * Both read the raw audio: they did as well or better without pre-emphasis.
 */
class OfflineSecondPass(private val context: Context) : SecondPass {
    override fun load(notificationId: String): Rereader? {
        val stt = context.getExternalFilesDir("stt") ?: return null
        val dictation = notificationId == T3_CARD_ID
        val dir = File(stt, if (dictation) PARAKEET else WHISPER).apply { mkdirs() }
        val parts = if (dictation) listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            else listOf("small.en-encoder.int8.onnx", "small.en-decoder.int8.onnx", "small.en-tokens.txt")
        if (!parts.all { File(dir, it).isFile }) { Log.i(TAG, "second pass off: no model in ${dir.name}"); return null }
        val p = parts.map { File(dir, it).path }
        val started = System.currentTimeMillis()
        val model = if (dictation) OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(encoder = p[0], decoder = p[1], joiner = p[2]),
            tokens = p[3], numThreads = 2, modelType = "nemo_transducer",
        ) else OfflineModelConfig(whisper = OfflineWhisperModelConfig(encoder = p[0], decoder = p[1]), tokens = p[2], numThreads = 4, modelType = "whisper")
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SherpaSpeech.SAMPLE_RATE, featureDim = 80), modelConfig = model))
        Log.i(TAG, "second pass ${dir.name} loaded in ${System.currentTimeMillis() - started} ms")
        return object : Rereader {
            override fun read(pcm16: ByteArray): String {
                val t = System.currentTimeMillis()
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(SpeechAudio.toFloats(pcm16), SherpaSpeech.SAMPLE_RATE)
                    recognizer.decode(stream)
                    // Only the length and time: the text is the user's speech.
                    return recognizer.getResult(stream).text.also {
                        Log.i(TAG, "second pass ${pcm16.size / 32} ms of audio in ${System.currentTimeMillis() - t} ms, ${it.length} chars")
                    }
                } finally { stream.release() }
            }
            override fun close() = recognizer.release()
        }
    }

    private companion object {
        const val TAG = "885rokidOSVoice"
        const val PARAKEET = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
        const val WHISPER = "sherpa-onnx-whisper-small.en"
    }
}
