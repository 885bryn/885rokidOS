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
 * The second pass on the phone (docs/LOCAL_TEST_RESULTS.md, 2026-11-05). T3 dictation only, with Whisper small.en and 4
 * threads (user choice): in the glasses test it made 1 wrong word of 59 (ours 9, Parakeet v3 6), with about 2.6 s of wait
 * after a short instruction but 6–11 s after a 12 s one. Message replies have none: no model beat ours there (117 words:
 * ours 10, Parakeet 13, Whisper 13). The model loads for one voice step and is freed after it (about 1 GB while loaded).
 * Its files are put in `files/stt/<name>/` by hand, as for the streaming model; without them there is no second pass.
 * Both models read the raw audio: they did as well or better without pre-emphasis.
 */
class OfflineSecondPass(private val context: Context) : SecondPass {
    /** The two candidates; the speech tests (VoiceSpike) score both. */
    enum class Model { PARAKEET, WHISPER }

    override fun load(notificationId: String): Rereader? = if (notificationId == T3_CARD_ID) open(Model.WHISPER) else null

    /** Loads [model], or null when its files are not on the phone. Slow: call it off the main thread. */
    fun open(model: Model): Rereader? {
        val stt = context.getExternalFilesDir("stt") ?: return null
        val dictation = model == Model.PARAKEET
        val dir = File(stt, if (dictation) PARAKEET else WHISPER).apply { mkdirs() }
        val parts = if (dictation) listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            else listOf("small.en-encoder.int8.onnx", "small.en-decoder.int8.onnx", "small.en-tokens.txt")
        if (!parts.all { File(dir, it).isFile }) { Log.i(TAG, "second pass off: no model in ${dir.name}"); return null }
        val p = parts.map { File(dir, it).path }
        val started = System.currentTimeMillis()
        val config = if (dictation) OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(encoder = p[0], decoder = p[1], joiner = p[2]),
            tokens = p[3], numThreads = 2, modelType = "nemo_transducer",
        ) else OfflineModelConfig(whisper = OfflineWhisperModelConfig(encoder = p[0], decoder = p[1]), tokens = p[2], numThreads = 4, modelType = "whisper")
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SherpaSpeech.SAMPLE_RATE, featureDim = 80), modelConfig = config))
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
