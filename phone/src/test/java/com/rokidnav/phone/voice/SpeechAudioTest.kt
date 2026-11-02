package com.rokidnav.phone.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechAudioTest {
    @Test fun convertsLittleEndianPcmToFloats() {
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte(), 0xFF.toByte(), 0x7F)
        val out = SpeechAudio.toFloats(pcm)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(-0.5f, out[1], 1e-6f)
        assertEquals(32767f / 32768f, out[2], 1e-6f)
    }

    @Test fun preEmphasisSubtractsAFractionOfTheLastSample() {
        val x = floatArrayOf(1f, 1f, 0f, -1f)
        val last = SpeechAudio.preEmphasize(x, 0f, 0.8f)
        assertArrayEquals(floatArrayOf(1f, 0.2f, -0.8f, -1f), x, 1e-6f)
        assertEquals(-1f, last, 0f)
    }

    @Test fun smallChunksGiveTheSameResultAsOneLongPiece() {
        val whole = FloatArray(500) { kotlin.math.sin(it / 7.0).toFloat() }
        val expected = whole.copyOf()
        SpeechAudio.preEmphasize(expected, 0f)
        val chunked = whole.copyOf()
        var previous = 0f
        var at = 0
        while (at < chunked.size) {
            val end = minOf(chunked.size, at + 160)
            val piece = chunked.copyOfRange(at, end)
            previous = SpeechAudio.preEmphasize(piece, previous)
            piece.copyInto(chunked, at)
            at = end
        }
        assertArrayEquals(expected, chunked, 1e-6f)
    }

    @Test fun silenceStaysSilent() {
        val x = FloatArray(100)
        SpeechAudio.preEmphasize(x, 0f)
        assertArrayEquals(FloatArray(100), x, 0f)
    }
}
