package com.rokidos885.phone.voice

import android.content.Context

/** Loads the speech model for each reply and frees it afterwards, so it uses memory only while someone speaks. */
class SherpaTranscriber(private val context: Context) : Transcriber {
    override fun begin(limits: ListenLimits): Transcription? {
        val dir = SherpaSpeech.modelDir(context) ?: return null
        val engine = SherpaSpeech(dir, limits)
        val utterance = engine.utterance()
        return object : Transcription {
            override fun accept(pcm16: ByteArray) = utterance.accept(pcm16)
            override fun endpoint() = utterance.endpoint()
            override fun finish() = utterance.finish()
            override fun close() { utterance.close(); engine.close() }
        }
    }
}
