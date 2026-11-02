package com.rokidnav.phone.voice

/** Turns the glasses' PCM into what the speech model reads. Pure Kotlin so it is unit-tested. */
object SpeechAudio {
    /**
     * Pre-emphasis: y[n] = x[n] - 0.8 * x[n-1]. The glasses' microphone sends almost only low sounds (96% of the energy is under 1 kHz), so
     * consonants like "th", "s" and "k" are faint and the model confuses words ("thanks" / "things", "good to know" / "curtail").
     * Lifting the high frequencies cut the wrong words from 20% to about 2-6% on ten recorded replies (docs/VOICE.md).
     */
    const val PRE_EMPHASIS = 0.8f

    /** Little-endian 16-bit mono PCM to floats in -1..1. */
    fun toFloats(pcm16: ByteArray): FloatArray {
        val out = FloatArray(pcm16.size / 2)
        for (i in out.indices) {
            val v = ((pcm16[2 * i + 1].toInt() shl 8) or (pcm16[2 * i].toInt() and 0xff)).toShort()
            out[i] = v / 32768f
        }
        return out
    }

    /**
     * Filters [samples] in place and returns the last unfiltered sample. Pass that value back as [previous] for the next chunk,
     * so a stream in small pieces gives the same result as one long piece.
     */
    fun preEmphasize(samples: FloatArray, previous: Float, coefficient: Float = PRE_EMPHASIS): Float {
        var before = previous
        for (i in samples.indices) {
            val current = samples[i]
            samples[i] = current - coefficient * before
            before = current
        }
        return before
    }
}
