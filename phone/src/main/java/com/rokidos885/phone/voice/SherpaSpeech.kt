package com.rokidos885.phone.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Offline speech to text on the phone (sherpa-onnx, NVIDIA streaming FastConformer, English). It takes the glasses'
 * microphone audio (16 kHz, 16-bit mono PCM) as it arrives and writes the words so far. Loading the model takes a
 * second or two, so create it when a reply starts and [close] it afterwards. The model files are put in
 * [modelDir] by hand for now (docs/VOICE.md); there is no download inside the app yet.
 */
class SherpaSpeech(modelDir: File) : AutoCloseable {
    private val recognizer = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").path,
                    decoder = File(modelDir, "decoder.int8.onnx").path,
                    joiner = File(modelDir, "joiner.int8.onnx").path,
                ),
                tokens = File(modelDir, "tokens.txt").path,
                numThreads = 2,
                modelType = "nemo_transducer",
            ),
            // Sentence end: 1.4 s of quiet after speech, 5 s of nothing at the start, or 12 s in all.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 5.0f, 0.0f),
                rule2 = EndpointRule(true, 1.4f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 12.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )

    /** One spoken reply: feed audio, read the words so far, stop when [endpoint] says the speaker finished. */
    inner class Utterance : Transcription {
        private val stream: OnlineStream = recognizer.createStream()
        private var closed = false
        private var previous = 0f

        /** [pcm16] is little-endian 16-bit mono at 16 kHz. Returns the words so far. */
        override fun accept(pcm16: ByteArray): String {
            val samples = SpeechAudio.toFloats(pcm16)
            previous = SpeechAudio.preEmphasize(samples, previous)
            stream.acceptWaveform(samples, SAMPLE_RATE)
            decodeReady()
            return recognizer.getResult(stream).text
        }

        /** True once the speaker has paused long enough after speaking (or the time limit passed). */
        override fun endpoint(): Boolean = recognizer.isEndpoint(stream)

        /** Pads with silence so the last words come out, and returns the final text. */
        override fun finish(): String {
            stream.acceptWaveform(FloatArray(SAMPLE_RATE * 8 / 10), SAMPLE_RATE)
            stream.inputFinished()
            decodeReady()
            return recognizer.getResult(stream).text
        }

        private fun decodeReady() { while (recognizer.isReady(stream)) recognizer.decode(stream) }

        override fun close() { if (!closed) { closed = true; stream.release() } }
    }

    fun utterance() = Utterance()

    override fun close() = recognizer.release()

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MODEL_NAME = "sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8"

        /** The folder with encoder, decoder, joiner and tokens, or null when the model is not on the phone yet. */
        fun modelDir(context: Context): File? {
            val dir = File(context.getExternalFilesDir("stt"), MODEL_NAME)
            val ready = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt").all { File(dir, it).isFile }
            if (!ready) Log.i("885rokidOSVoice", "speech model missing in $dir")
            return dir.takeIf { ready }
        }
    }
}
