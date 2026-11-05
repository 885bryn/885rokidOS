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
 * Offline speech to text on the phone (sherpa-onnx, NVIDIA streaming FastConformer, English, 1040 ms chunks). It takes the glasses'
 * microphone audio (16 kHz, 16-bit mono PCM) as it arrives and writes the words so far. Loading the model takes a
 * second or two, so create it when a reply starts and [close] it afterwards. The model files are put in
 * [modelDir] by hand for now (docs/VOICE.md); there is no download inside the app yet.
 */
class SherpaSpeech(modelDir: File, limits: ListenLimits = ListenLimits.REPLY) : AutoCloseable {
    /**
     * The hotwords file, or null for none. Written next to the model (the app owns that folder). A failed write only loses
     * the word list: the model then decodes as it does for a reply.
     */
    private val hotwordsFile: File? = limits.hotwords.takeIf { it.isNotEmpty() }?.let { words ->
        runCatching {
            val vocab = File(modelDir, BPE_VOCAB)
            if (!vocab.isFile) vocab.writeText(SpeechVocabulary.bpeVocab(File(modelDir, "tokens.txt").readText()))
            File(modelDir, HOTWORDS).apply { writeText(SpeechVocabulary.hotwords(words)) }
        }.onFailure { Log.w("885rokidOSVoice", "hotwords off: ${it.javaClass.simpleName}") }.getOrNull()
    }

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
                // The tokens are BPE pieces; sherpa-onnx splits the hotwords with this vocab.
                modelingUnit = if (hotwordsFile != null) "bpe" else "",
                bpeVocab = if (hotwordsFile != null) File(modelDir, BPE_VOCAB).path else "",
            ),
            // Sentence end: [ListenLimits.pauseSeconds] of quiet after speech, 5 s of nothing at the start, or the time
            // limit less 3 s in all (12 s for a reply), so the utterance ends cleanly before the controller's timeout.
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 5.0f, 0.0f),
                rule2 = EndpointRule(true, limits.pauseSeconds, 0.0f),
                rule3 = EndpointRule(false, 0.0f, limits.maxMs / 1000f - 3f),
            ),
            enableEndpoint = true,
            // Beam search alone gave the same words as greedy on the glasses recordings (2026-11-04) and costs more time,
            // but hotwords need it: they only steer the beam (docs/VOICE.md, 2026-11-05).
            decodingMethod = if (hotwordsFile != null) "modified_beam_search" else "greedy_search",
            maxActivePaths = if (hotwordsFile != null) SpeechVocabulary.PATHS else 4,
            hotwordsFile = hotwordsFile?.path ?: "",
            hotwordsScore = SpeechVocabulary.SCORE,
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

        /**
         * Pads with silence so the last words come out, and returns the final text. The model decodes in 1040 ms chunks,
         * so the pad must be longer than one chunk: 0.8 s could leave the last word (said just before a tap) undecoded.
         */
        override fun finish(): String {
            stream.acceptWaveform(FloatArray(SAMPLE_RATE * TAIL_PAD_MS / 1000), SAMPLE_RATE)
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
        /** Silence after the last audio: two 1040 ms chunks. */
        const val TAIL_PAD_MS = 2_100
        private const val BPE_VOCAB = "bpe.vocab"
        private const val HOTWORDS = "hotwords.txt"
        /**
         * Best first. The 1040 ms model made a third fewer word errors than the 80 ms one on the glasses recordings and
         * decodes faster; it only shows words about a second later (docs/LOCAL_TEST_RESULTS.md, 2026-11-04). Same settings.
         */
        private val MODEL_NAMES = listOf(
            "sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-1040ms-int8",
            "sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-80ms-int8",
        )

        /** The folder with encoder, decoder, joiner and tokens, or null when no model is on the phone yet. */
        fun modelDir(context: Context): File? {
            val dirs = MODEL_NAMES.map { File(context.getExternalFilesDir("stt"), it) }
            // The app must own the folder: one made by `adb shell mkdir` belongs to shell and the app cannot read it.
            dirs.first().mkdirs()
            val dir = dirs.firstOrNull { d -> listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt").all { File(d, it).isFile } }
            if (dir == null) Log.i("885rokidOSVoice", "speech model missing in ${dirs.first()}") else Log.i("885rokidOSVoice", "speech model ${dir.name}")
            return dir
        }
    }
}
