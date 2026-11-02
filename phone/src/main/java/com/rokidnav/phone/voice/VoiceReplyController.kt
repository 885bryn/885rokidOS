package com.rokidnav.phone.voice

import com.rokidnav.phone.glasses.GlassesLink
import com.rokidnav.protocol.VoicePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Speech to text for one spoken reply. [SherpaSpeech] is the real one; tests use a fake. */
interface Transcriber {
    /** Null when the speech model is not on the phone. */
    fun begin(): Transcription?
}

interface Transcription : AutoCloseable {
    /** Feeds 16 kHz 16-bit mono PCM; returns the words so far. */
    fun accept(pcm16: ByteArray): String
    /** True once the speaker has paused long enough after speaking. */
    fun endpoint(): Boolean
    fun finish(): String
}

/** Sends text to a notification's reply box. */
fun interface ReplySender { fun reply(notificationId: String, text: String): Boolean }

/**
 * One voice reply at a time: listen to the glasses' microphone, show the words as they come, stop when the speaker
 * pauses (or taps, or after [MAX_LISTEN_MS]), let the user confirm, then send. Every step is reported with
 * [report]. Nothing is sent without [send]. Message text is never logged here.
 */
class VoiceReplyController(
    private val scope: CoroutineScope,
    private val link: GlassesLink,
    private val transcriber: Transcriber,
    private val sender: ReplySender,
    private val report: (VoicePhase, String, String) -> Unit,
) {
    private var job: Job? = null
    private var stopNow = false
    private var activeId = ""
    private var reviewed = ""

    fun start(notificationId: String) {
        cancelJob()
        activeId = notificationId
        stopNow = false
        reviewed = ""
        job = scope.launch {
            val speech = transcriber.begin()
            if (speech == null) { report(VoicePhase.FAILED, notificationId, "Speech model missing"); return@launch }
            speech.use { listen(notificationId, it) }
        }
    }

    /** The user tapped to stop listening early. */
    fun stop() { stopNow = true }

    fun send(notificationId: String) {
        if (notificationId != activeId || reviewed.isBlank()) return
        val text = Dictation.format(reviewed)
        val ok = runCatching { sender.reply(notificationId, text) }.getOrDefault(false)
        report(if (ok) VoicePhase.SENT else VoicePhase.FAILED, notificationId, if (ok) "" else "The app refused the reply")
        reviewed = ""
    }

    fun cancel(notificationId: String) {
        cancelJob(); link.stopAudio(); reviewed = ""
        report(VoicePhase.IDLE, notificationId, "")
    }

    private fun cancelJob() { job?.cancel(); job = null }

    private suspend fun listen(id: String, speech: Transcription) {
        if (!link.startAudio()) { report(VoicePhase.FAILED, id, "Glasses microphone not available"); return }
        report(VoicePhase.LISTENING, id, "")
        var words = ""
        var lastSent = ""
        try {
            withTimeoutOrNull(MAX_LISTEN_MS) {
                // Collect until the speaker finishes: a pause after speech, or a tap on the glasses.
                kotlinx.coroutines.coroutineScope {
                    val collector = launch {
                        link.audio.collect { chunk ->
                            words = speech.accept(chunk)
                            if (words != lastSent) { lastSent = words; report(VoicePhase.LISTENING, id, words) }
                            if (speech.endpoint()) throw EndOfSpeech()
                        }
                    }
                    val watcher = launch { while (!stopNow) delay(50); throw EndOfSpeech() }
                    try { collector.join() } finally { watcher.cancel() }
                }
            }
        } catch (_: EndOfSpeech) {
        } catch (e: CancellationException) {
            throw e
        } finally {
            link.stopAudio()
        }
        val finalText = speech.finish().ifBlank { words }
        if (finalText.isBlank()) { report(VoicePhase.FAILED, id, "Heard nothing"); return }
        reviewed = finalText
        report(VoicePhase.REVIEW, id, Dictation.format(finalText))
    }

    private class EndOfSpeech : RuntimeException()

    companion object { const val MAX_LISTEN_MS = 15_000L }
}
