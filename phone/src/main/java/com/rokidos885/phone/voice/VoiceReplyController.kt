package com.rokidos885.phone.voice

import com.rokidos885.phone.glasses.GlassesLink
import com.rokidos885.protocol.VOICE_COMMAND_ID
import com.rokidos885.protocol.VoicePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Speech to text for one spoken reply. [SherpaSpeech] is the real one; tests use a fake. */
interface Transcriber {
    /** Null when the speech model is not on the phone. Slow (loads the model): call it off the main thread. */
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
 * One voice reply at a time: open the glasses microphone at once, load the speech model in parallel (audio is held until it
 * is ready, so nothing said right after the tap is lost), show the words as they come, stop when the speaker pauses (or
 * taps, or after [MAX_LISTEN_MS]), let the user confirm, then send. Every step is reported with [report], which may be
 * called from [work]. Nothing is sent without [send]. Message text is never logged here.
 * The id [VOICE_COMMAND_ID] marks a spoken command instead of a reply: after listening it is acted on at once ("open Grok Bot").
 */
class VoiceReplyController(
    private val scope: CoroutineScope,
    private val link: GlassesLink,
    private val transcriber: Transcriber,
    private val sender: ReplySender,
    /** Where the heavy work runs; the real app uses Dispatchers.Default so the main thread stays free. */
    private val work: CoroutineContext = EmptyCoroutineContext,
    /** Debug aid: every microphone chunk, to keep the last recording for analysis. */
    private val onAudio: ((ByteArray) -> Unit)? = null,
    /** The "open Grok Bot" command: null when Grok Bot was opened, otherwise the reason it was not ([GrokBot.open]). */
    private val openGrokBot: (() -> String?)? = null,
    private val report: (VoicePhase, String, String) -> Unit,
) {
    private var job: Job? = null
    @Volatile private var stopNow = false
    @Volatile private var activeId = ""
    @Volatile private var reviewed = ""

    fun start(notificationId: String) {
        cancelJob()
        activeId = notificationId
        stopNow = false
        reviewed = ""
        job = scope.launch(work) { listen(notificationId) }
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

    private suspend fun listen(id: String) = coroutineScope {
        // The model loads while the microphone already runs. A load that throws (no native library: UnsatisfiedLinkError, or a
        // broken model file) counts as no model: thrown here it would end this job and, with no handler, crash the app.
        var loadError = ""
        val loading: Deferred<Transcription?> = async {
            try { transcriber.begin() } catch (e: CancellationException) { throw e } catch (e: Throwable) { loadError = "Speech engine failed: ${e.javaClass.simpleName}"; null }
        }
        fun noModel() = loadError.ifEmpty { "Speech model missing" }
        if (!link.startAudio()) {
            report(VoicePhase.FAILED, id, "Glasses microphone not available")
            loading.cancel(); loading.invokeOnCompletion { runCatching { loading.getCompleted()?.close() } }
            return@coroutineScope
        }
        var speech: Transcription? = null
        var words = ""
        var lastSent = ""
        var live = false
        val held = ArrayDeque<ByteArray>()
        val audioEnded = CompletableDeferred<Unit>()
        try {
            withTimeoutOrNull(MAX_LISTEN_MS) {
                coroutineScope {
                    val collector = launch {
                        link.audio.collect { chunk ->
                            onAudio?.invoke(chunk)
                            if (!live) { live = true; report(VoicePhase.LISTENING, id, "") }
                            if (speech == null) {
                                if (!loading.isCompleted) { held.addLast(chunk); return@collect }
                                speech = loading.await()
                                if (speech == null) throw ModelMissing()
                            }
                            val engine = speech!!
                            while (held.isNotEmpty()) words = engine.accept(held.removeFirst())
                            words = engine.accept(chunk)
                            if (words != lastSent) { lastSent = words; report(VoicePhase.LISTENING, id, words) }
                            if (engine.endpoint()) throw EndOfSpeech()
                        }
                    }
                    val watcher = launch { while (!stopNow) delay(50); throw EndOfSpeech() }
                    // No model on the phone: say so now, not when the first audio arrives.
                    val modelCheck = launch { if (loading.await() == null) { report(VoicePhase.FAILED, id, noModel()); throw ModelMissing() } }
                    try { collector.join() } finally { watcher.cancel(); modelCheck.cancel() }
                }
            }
        } catch (_: EndOfSpeech) {
        } catch (_: ModelMissing) {
            link.stopAudio()
            return@coroutineScope
        } catch (e: CancellationException) {
            link.stopAudio(); runCatching { loading.cancel() }
            throw e
        }
        link.stopAudio()
        audioEnded.complete(Unit)
        val engine = speech ?: runCatching { loading.await() }.getOrNull()
        if (engine == null) { if (words.isBlank()) report(VoicePhase.FAILED, id, noModel()); return@coroutineScope }
        engine.use {
            while (held.isNotEmpty()) words = it.accept(held.removeFirst())
            val finalText = it.finish().ifBlank { words }
            if (finalText.isBlank()) { report(VoicePhase.FAILED, id, "Heard nothing"); return@use }
            // A command has no check step: opening an app sends nothing and is undone with one gesture on the phone.
            if (id == VOICE_COMMAND_ID) { command(finalText); return@use }
            reviewed = finalText
            report(VoicePhase.REVIEW, id, Dictation.format(finalText))
        }
    }

    private fun command(heard: String) {
        val id = VOICE_COMMAND_ID
        if (!VoiceCommands.opensGrokBot(heard)) { report(VoicePhase.FAILED, id, "Heard \"${Dictation.format(heard)}\". Say \"open Grok Bot\""); return }
        val open = openGrokBot
        val problem = if (open == null) "Opening apps is not set up" else runCatching { open() }.getOrElse { "Could not open Grok Bot" }
        report(if (problem == null) VoicePhase.SENT else VoicePhase.FAILED, id, problem ?: "Opening Grok Bot on the phone")
    }

    private class EndOfSpeech : RuntimeException()
    private class ModelMissing : RuntimeException()

    companion object { const val MAX_LISTEN_MS = 15_000L }
}
