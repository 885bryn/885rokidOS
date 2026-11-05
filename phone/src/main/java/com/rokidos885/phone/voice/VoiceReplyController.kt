package com.rokidos885.phone.voice

import com.rokidos885.phone.glasses.GlassesLink
import com.rokidos885.protocol.T3_CARD_ID
import com.rokidos885.protocol.VoicePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
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

/**
 * When listening ends by itself: after [pauseSeconds] of quiet that follow speech, or after [maxMs] in all. A tap ends it
 * at any time. [hotwords] are words the model should favour (empty: plain greedy decoding; see [SpeechVocabulary]).
 */
data class ListenLimits(val pauseSeconds: Float, val maxMs: Long, val hotwords: List<String> = emptyList()) {
    companion object {
        /** A short reply to a message: it ends at the first real pause. */
        val REPLY = ListenLimits(1.4f, 15_000L)
        /**
         * T3 dictation: people stop to think in the middle of a sentence, and 1.4 s cut them off (user report
         * 2026-11-05; 5 s is the user's choice for now). Instructions are also longer than a message reply.
         */
        val DICTATION = ListenLimits(5.0f, 60_000L, SpeechVocabulary.T3_WORDS)
        fun forId(notificationId: String) = if (notificationId == T3_CARD_ID) DICTATION else REPLY
    }
}

/** Speech to text for one spoken reply. [SherpaSpeech] is the real one; tests use a fake. */
interface Transcriber {
    /** Null when the speech model is not on the phone. Slow (loads the model): call it off the main thread. */
    fun begin(limits: ListenLimits): Transcription?
}

interface Transcription : AutoCloseable {
    /** Feeds 16 kHz 16-bit mono PCM; returns the words so far. */
    fun accept(pcm16: ByteArray): String
    /** True once the speaker has paused long enough after speaking. */
    fun endpoint(): Boolean
    fun finish(): String
}

/**
 * A slower, more accurate model that reads the whole recording again once the speaker stopped (docs/VOICE.md, "Second pass").
 * [load] runs while the user speaks; null when its model is not on the phone.
 */
fun interface SecondPass { fun load(notificationId: String): Rereader? }

interface Rereader : AutoCloseable {
    /** The text of the whole recording (16 kHz 16-bit mono PCM). */
    fun read(pcm16: ByteArray): String
}

/** Sends text to a notification's reply box. */
fun interface ReplySender { fun reply(notificationId: String, text: String): Boolean }

/**
 * One voice reply at a time: open the glasses microphone at once, load the speech model in parallel (audio is held until it
 * is ready, so nothing said right after the tap is lost), show the words as they come, stop when the speaker pauses (or
 * taps, or after the time limit; see [ListenLimits.forId]), let the user confirm, then send. Every step is reported with [report], which may be
 * called from [work]. Nothing is sent without [send]. Message text is never logged here.
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
    /** Reads the recording again after the speaker stopped; its text replaces ours on the check screen ([better]). */
    private val secondPass: SecondPass? = null,
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
        val limits = ListenLimits.forId(id)
        val loading: Deferred<Transcription?> = async {
            try { transcriber.begin(limits) } catch (e: CancellationException) { throw e } catch (e: Throwable) { loadError = "Speech engine failed: ${e.javaClass.simpleName}"; null }
        }
        fun noModel() = loadError.ifEmpty { "Speech model missing" }
        // The second model loads while the user speaks, so its wait after the end is only its decode.
        val rereading: Deferred<Rereader?>? = secondPass?.let { pass ->
            async { try { pass.load(id) } catch (e: CancellationException) { throw e } catch (_: Throwable) { null } }
        }
        val recording = java.io.ByteArrayOutputStream()
        // Listen before the microphone opens: link.audio keeps nothing for a late listener, so the first words would be lost.
        // The queue also keeps the chunks that arrive while the last ones are decoded, for the end of speech.
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val tap = launch(start = CoroutineStart.UNDISPATCHED) { link.audio.collect { incoming.trySend(it) } }
        if (!link.startAudio()) {
            tap.cancel()
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
            withTimeoutOrNull(limits.maxMs) {
                coroutineScope {
                    val collector = launch {
                        for (chunk in incoming) {
                            onAudio?.invoke(chunk); recording.write(chunk)
                            if (!live) { live = true; report(VoicePhase.LISTENING, id, "") }
                            if (speech == null) {
                                if (!loading.isCompleted) { held.addLast(chunk); continue }
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
            tap.cancel(); link.stopAudio(); rereading?.let(::drop)
            return@coroutineScope
        } catch (e: CancellationException) {
            tap.cancel(); link.stopAudio(); runCatching { loading.cancel() }; rereading?.let(::drop)
            throw e
        }
        link.stopAudio()
        tap.cancel()
        // Words said just before a tap may still wait in the queue: decode them too.
        while (true) { val chunk = incoming.tryReceive().getOrNull() ?: break; onAudio?.invoke(chunk); recording.write(chunk); held.addLast(chunk) }
        audioEnded.complete(Unit)
        val engine = speech ?: runCatching { loading.await() }.getOrNull()
        if (engine == null) { if (words.isBlank()) report(VoicePhase.FAILED, id, noModel()); rereading?.let(::drop); return@coroutineScope }
        var ours = ""
        engine.use {
            while (held.isNotEmpty()) words = it.accept(held.removeFirst())
            ours = it.finish().ifBlank { words }
        }
        if (ours.isBlank()) { report(VoicePhase.FAILED, id, "Heard nothing"); rereading?.let(::drop); return@coroutineScope }
        // Our text shows at once; the second model's text replaces it when ready, unless the user already sent or left.
        reviewed = ours
        report(VoicePhase.REVIEW, id, Dictation.format(ours))
        val reader = rereading?.await() ?: return@coroutineScope
        val theirs = reader.use { r -> runCatching { r.read(recording.toByteArray()) }.getOrDefault("") }
        val text = better(ours, theirs)
        if (text != ours && activeId == id && reviewed == ours) {
            reviewed = text
            report(VoicePhase.REVIEW, id, Dictation.format(text))
        }
    }

    /** Frees a second model that loads or has loaded, without waiting for it. */
    private fun drop(rereading: Deferred<Rereader?>) {
        rereading.cancel()
        rereading.invokeOnCompletion { runCatching { rereading.getCompleted()?.close() } }
    }

    companion object {
        /**
         * The second model's text, unless it is empty or much shorter than ours (it may drop a short or quiet recording, and
         * Whisper writes "[BLANK_AUDIO]" or "(music)" for noise).
         */
        fun better(ours: String, theirs: String): String {
            val clean = theirs.replace(NOISE_TAG, " ").trim().replace(Regex("\\s+"), " ")
            val count = { t: String -> t.split(' ').count { it.isNotBlank() } }
            return if (clean.isEmpty() || count(clean) * 2 < count(ours)) ours else clean
        }
        private val NOISE_TAG = Regex("\\[[^]]*]|\\([^)]*\\)")
    }

    private class EndOfSpeech : RuntimeException()
    private class ModelMissing : RuntimeException()
}
