package com.rokidos885.phone.voice

import android.app.Activity
import com.rokidos885.phone.glasses.EnvironmentStatus
import com.rokidos885.phone.glasses.GlassesLink
import com.rokidos885.phone.glasses.LinkState
import com.rokidos885.protocol.NavMessage
import com.rokidos885.protocol.VoicePhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceReplyControllerTest {
    /** [firstWords] arrive inside startAudio, as the real stream may send audio before the call returns. */
    private class FakeLink(var micAvailable: Boolean = true, val firstWords: String? = null) : GlassesLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Ready)
        override val incoming = MutableSharedFlow<NavMessage>()
        override val audio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        var started = 0
        var stopped = 0
        override fun checkEnvironment() = EnvironmentStatus.Ok
        override fun authorize(activity: Activity) {}
        override fun connect() {}
        override fun send(message: NavMessage) = true
        override fun disconnect() {}
        override fun startAudio(): Boolean {
            if (micAvailable) { started++; firstWords?.let { audio.tryEmit(it.toByteArray()) } }
            return micAvailable
        }
        override fun stopAudio() { stopped++ }
    }

    /** Each audio chunk is one ASCII word; the "words so far" are the chunks joined. [endAfter] chunks = endpoint. */
    private class FakeTranscriber(private val endAfter: Int = Int.MAX_VALUE, private val present: Boolean = true) : Transcriber {
        override fun begin(limits: ListenLimits): Transcription? = if (!present) null else object : Transcription {
            private val words = mutableListOf<String>()
            override fun accept(pcm16: ByteArray): String { words += String(pcm16); return words.joinToString(" ") }
            override fun endpoint() = words.size >= endAfter
            override fun finish() = words.joinToString(" ")
            override fun close() {}
        }
    }

    private data class Report(val phase: VoicePhase, val id: String, val text: String)

    private class Rig(val link: FakeLink, val controller: VoiceReplyController, val reports: MutableList<Report>, val sent: MutableList<Pair<String, String>>)

    private fun kotlinx.coroutines.test.TestScope.rig(transcriber: Transcriber, link: FakeLink = FakeLink(), sendResult: Boolean = true,
                                                      secondPass: SecondPass? = null): Rig {
        val reports = mutableListOf<Report>()
        val sent = mutableListOf<Pair<String, String>>()
        val controller = VoiceReplyController(backgroundScope, link, transcriber, { id, text -> sent += id to text; sendResult },
            secondPass = secondPass) { p, i, t -> reports += Report(p, i, t) }
        return Rig(link, controller, reports, sent)
    }

    @Test fun listensShowsWordsAsTheyComeStopsOnAPauseThenSendsOnlyAfterConfirmation() = runTest {
        val r = rig(FakeTranscriber(endAfter = 3))
        r.controller.start("n1"); runCurrent()
        assertEquals("the microphone opens at once", 1, r.link.started)
        assertTrue("LISTENING waits for the first audio", r.reports.isEmpty())
        r.link.audio.tryEmit("hello".toByteArray()); runCurrent()
        assertEquals(VoicePhase.LISTENING, r.reports.first().phase)
        r.link.audio.tryEmit("there".toByteArray()); runCurrent()
        assertEquals("hello there", r.reports.last().text)
        r.link.audio.tryEmit("friend".toByteArray()); runCurrent()
        val review = r.reports.last()
        assertEquals(VoicePhase.REVIEW, review.phase)
        assertEquals("Hello there friend", review.text)
        assertTrue("nothing is sent before the user confirms", r.sent.isEmpty())
        assertEquals("the microphone stream is closed after listening", 1, r.link.stopped)
        r.controller.send("n1"); runCurrent()
        assertEquals(listOf("n1" to "Hello there friend"), r.sent)
        assertEquals(VoicePhase.SENT, r.reports.last().phase)
    }

    /** Gives [text] for the whole recording and remembers what it read and whether it was freed. */
    private class FakeRereader(private val text: String) : Rereader {
        var heard = ""
        var closed = false
        override fun read(pcm16: ByteArray): String { heard = String(pcm16); return text }
        override fun close() { closed = true }
    }

    @Test fun theSecondPassReplacesOurTextOnTheCheckScreenAndIsSent() = runTest {
        val reader = FakeRereader("Hello there, friend.")
        var loadedFor = ""
        val r = rig(FakeTranscriber(endAfter = 3), secondPass = { id -> loadedFor = id; reader })
        r.controller.start("n1"); runCurrent()
        for (w in listOf("hello", "their", "fiend")) { r.link.audio.tryEmit(w.toByteArray()); runCurrent() }
        val reviews = r.reports.filter { it.phase == VoicePhase.REVIEW }.map { it.text }
        assertEquals("ours shows first, then the second model's", listOf("Hello their fiend", "Hello there friend"), reviews)
        assertEquals("n1", loadedFor)
        assertEquals("it reads the whole recording", "hellotheirfiend", reader.heard)
        assertTrue("the second model is freed after one read", reader.closed)
        r.controller.send("n1"); runCurrent()
        assertEquals(listOf("n1" to "Hello there friend"), r.sent)
    }

    @Test fun anEmptyOrMuchShorterSecondPassKeepsOurText() {
        assertEquals("see you at seven", VoiceReplyController.better("see you at seven", "[BLANK_AUDIO]"))
        assertEquals("see you at seven tonight ok", VoiceReplyController.better("see you at seven tonight ok", "you"))
        assertEquals("See you at 7.", VoiceReplyController.better("see you at seven", " See you at 7. "))
    }

    @Test fun withoutTheSecondModelOurTextStays() = runTest {
        val r = rig(FakeTranscriber(endAfter = 1), secondPass = { null })
        r.controller.start("n1"); runCurrent()
        r.link.audio.tryEmit("hi".toByteArray()); runCurrent()
        assertEquals(listOf("Hi"), r.reports.filter { it.phase == VoicePhase.REVIEW }.map { it.text })
    }

    @Test fun aTapOnTheGlassesStopsListeningEarly() = runTest {
        val r = rig(FakeTranscriber())
        r.controller.start("n1"); runCurrent()
        r.link.audio.tryEmit("hi".toByteArray()); runCurrent()
        r.controller.stop(); advanceTimeBy(200); runCurrent()
        assertEquals(VoicePhase.REVIEW, r.reports.last().phase)
        assertEquals("Hi", r.reports.last().text)
    }

    @Test fun cancelClearsTheScreenAndNothingIsSent() = runTest {
        val r = rig(FakeTranscriber(endAfter = 1))
        r.controller.start("n1"); runCurrent()
        r.link.audio.tryEmit("hi".toByteArray()); runCurrent()
        r.controller.cancel("n1")
        assertEquals(VoicePhase.IDLE, r.reports.last().phase)
        r.controller.send("n1"); runCurrent()
        assertTrue(r.sent.isEmpty())
    }

    @Test fun silenceFailsAndTheMissingModelOrMicrophoneAreReported() = runTest {
        val silent = rig(FakeTranscriber())
        silent.controller.start("n1"); runCurrent()
        silent.controller.stop(); advanceTimeBy(200); runCurrent()
        assertEquals(VoicePhase.FAILED, silent.reports.last().phase)
        assertEquals("Heard nothing", silent.reports.last().text)

        val noModel = rig(FakeTranscriber(present = false))
        noModel.controller.start("n2"); runCurrent()
        assertEquals("Speech model missing", noModel.reports.last().text)

        val noMic = rig(FakeTranscriber(), FakeLink(micAvailable = false))
        noMic.controller.start("n3"); runCurrent()
        assertEquals(VoicePhase.FAILED, noMic.reports.last().phase)
    }

    @Test fun aSpeechEngineThatFailsToLoadIsReportedAndDoesNotCrash() = runTest {
        val broken = object : Transcriber { override fun begin(limits: ListenLimits): Transcription? = throw UnsatisfiedLinkError("no libsherpa-onnx-jni.so") }
        val r = rig(broken)
        r.controller.start("n1"); runCurrent()
        r.link.audio.tryEmit("hi".toByteArray()); runCurrent()
        assertEquals(VoicePhase.FAILED, r.reports.last().phase)
        assertEquals("Speech engine failed: UnsatisfiedLinkError", r.reports.last().text)
        assertEquals("the microphone is closed again", 1, r.link.stopped)
    }

    @Test fun anAppThatRefusesTheReplyIsReportedAsFailed() = runTest {
        val r = rig(FakeTranscriber(endAfter = 1), sendResult = false)
        r.controller.start("n1"); runCurrent()
        r.link.audio.tryEmit("hi".toByteArray()); runCurrent()
        r.controller.send("n1"); runCurrent()
        assertEquals(VoicePhase.FAILED, r.reports.last().phase)
    }

    @Test fun listeningStopsByItselfAfterTheTimeLimit() = runTest {
        val r = rig(FakeTranscriber())
        r.controller.start("n1"); runCurrent()
        r.link.audio.tryEmit("hi".toByteArray()); runCurrent()
        advanceTimeBy(ListenLimits.REPLY.maxMs + 100); runCurrent()
        assertEquals(VoicePhase.REVIEW, r.reports.last().phase)
    }

    @Test fun audioThatArrivesAsTheMicrophoneOpensIsNotLost() = runTest {
        val r = rig(FakeTranscriber(), FakeLink(firstWords = "soy"))
        r.controller.start("n1"); runCurrent()
        r.link.audio.tryEmit("sauce".toByteArray()); runCurrent()
        r.controller.stop(); advanceTimeBy(200); runCurrent()
        assertEquals("Soy sauce", r.reports.last().text)
    }

    @Test fun t3DictationWaitsLongerForAPauseAndListensLongerThanAReply() = runTest {
        val asked = mutableListOf<ListenLimits>()
        val recording = object : Transcriber {
            val inner = FakeTranscriber()
            override fun begin(limits: ListenLimits): Transcription? { asked += limits; return inner.begin(limits) }
        }
        val r = rig(recording)
        r.controller.start(com.rokidos885.protocol.T3_CARD_ID); runCurrent()
        r.link.audio.tryEmit("fix".toByteArray()); runCurrent()
        assertEquals(listOf(ListenLimits.DICTATION), asked)
        assertTrue("a pause in the middle of a sentence does not end dictation", ListenLimits.DICTATION.pauseSeconds > ListenLimits.REPLY.pauseSeconds)
        advanceTimeBy(ListenLimits.REPLY.maxMs + 100); runCurrent()
        assertEquals("still listening after a reply's time limit", VoicePhase.LISTENING, r.reports.last().phase)
        advanceTimeBy(ListenLimits.DICTATION.maxMs); runCurrent()
        assertEquals(VoicePhase.REVIEW, r.reports.last().phase)

        r.controller.start("n1"); runCurrent()
        r.link.audio.tryEmit("ok".toByteArray()); runCurrent()
        assertEquals("a message reply keeps the short limits", ListenLimits.REPLY, asked.last())
    }

    /** The model is not ready for the first chunks (as with the real 2 s load): they are held, not lost. */
    private class SlowTranscriber(val readyAfterChunks: Int) : Transcriber {
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        override fun begin(limits: ListenLimits): Transcription? {
            while (!ready.get()) Thread.sleep(1)
            return object : Transcription {
                private val words = mutableListOf<String>()
                override fun accept(pcm16: ByteArray): String { words += String(pcm16); return words.joinToString(" ") }
                override fun endpoint() = false
                override fun finish() = words.joinToString(" ")
                override fun close() {}
            }
        }
    }

    @Test fun wordsSpokenWhileTheModelLoadsAreHeldAndNotLost() {
        // Real threads here: the model load blocks, as it does on the phone.
        val link = FakeLink()
        val reports = java.util.concurrent.CopyOnWriteArrayList<Report>()
        val slow = SlowTranscriber(2)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val controller = VoiceReplyController(scope, link, slow, { _, _ -> true }, work = kotlinx.coroutines.Dispatchers.Default) { p, i, t -> reports += Report(p, i, t) }
        controller.start("n1")
        // The microphone opens at once, before the model is ready.
        val deadline = System.currentTimeMillis() + 3_000
        while (link.started == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(1, link.started)
        Thread.sleep(100)
        link.audio.tryEmit("but".toByteArray()); link.audio.tryEmit("you".toByteArray())
        Thread.sleep(100)
        assertTrue("LISTENING shows as soon as audio flows", reports.any { it.phase == VoicePhase.LISTENING })
        slow.ready.set(true)
        Thread.sleep(300)
        link.audio.tryEmit("like".toByteArray()); link.audio.tryEmit("it".toByteArray())
        Thread.sleep(200)
        controller.stop()
        val end = System.currentTimeMillis() + 3_000
        while (reports.none { it.phase == VoicePhase.REVIEW } && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals("But you like it", reports.last { it.phase == VoicePhase.REVIEW }.text)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
