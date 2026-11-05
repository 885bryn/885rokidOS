package com.rokidos885.phone.code

import com.rokidos885.protocol.T3State
import com.rokidos885.protocol.T3Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The glasses' T3 page on the phone side: one thread, picked in phone Settings. [send] hands dictated text to T3, then polls
 * the thread every [POLL_MS] until the run ends (at most [MAX_RUN_MS]) and [push]es each change. The glasses show 4 lines,
 * so [speak] reads the whole reply aloud (and says when T3 waits on the PC or the run failed). [push] runs on [scope]'s
 * thread; network calls run on IO. Never log the prompt or the reply.
 */
class T3Mini(private val scope: CoroutineScope, val client: T3Client, private val speak: (String) -> Unit = {},
             private val push: (T3Status) -> Unit) {
    private var job: Job? = null
    private var thread = client.threadTitle
    private var model = ""

    /** Thread title and model, or what to set up first. */
    fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch {
            push(setupProblem()?.let { failed("", it) } ?: try {
                val t = withContext(Dispatchers.IO) { client.readThread(client.threadId) }
                thread = t.title; model = t.model
                status(T3State.READY)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { failed("", reason(e)) })
        }
    }

    /** The voice reply's send step (main thread): true when the text is on its way. The run's progress follows by [push]. */
    fun send(text: String): Boolean {
        setupProblem()?.let { push(failed(text, it)); return false }
        val id = client.threadId
        job?.cancel()
        job = scope.launch {
            push(status(T3State.SENT, text))
            try {
                val before = withContext(Dispatchers.IO) { client.readThread(id).items }
                withContext(Dispatchers.IO) { client.send(id, text + T3Speech.VOICE_NOTE) }
                val started = System.currentTimeMillis()
                var shown = T3State.SENT
                var sawRun = false
                while (System.currentTimeMillis() - started < MAX_RUN_MS) {
                    delay(POLL_MS)
                    val t = withContext(Dispatchers.IO) { client.readThread(id) }
                    thread = t.title; model = t.model
                    // A user message and a reply add at least two items: the run ended even if no poll saw it running.
                    if (!t.running && t.pending == 0 && (sawRun || t.items >= before + 2)) {
                        val reply = withContext(Dispatchers.IO) { client.lastReply(id, t.items) }
                        if (t.status == "completed") {
                            push(status(T3State.REPLY, text, reply))
                            speak(T3Speech.spoken(reply).ifEmpty { "T3 finished without a reply." })
                        } else fail(text, "Run ${t.status}")
                        return@launch
                    }
                    if (t.running) sawRun = true
                    val now = if (t.pending > 0) T3State.NEEDS_PC else if (t.running) T3State.WORKING else shown
                    if (now != shown) {
                        shown = now
                        if (now == T3State.NEEDS_PC) { push(status(now, text, "Answer T3 on the PC")); speak("T3 needs your answer on the PC.") }
                        else push(status(now, text))
                    }
                }
                fail(text, "No answer after 10 min")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(text, reason(e)) }
        }
        return true
    }

    private fun setupProblem() = when {
        !client.signedIn -> "Sign in to T3 in phone Settings"
        client.threadId.isEmpty() -> "Pick a T3 thread in phone Settings"
        else -> null
    }

    private fun status(state: T3State, said: String = "", text: String = "") = T3Status(0, thread, model, state, said, text.take(MAX_TEXT))
    private fun failed(said: String, why: String) = status(T3State.FAILED, said, why)
    private fun fail(said: String, why: String) { push(failed(said, why)); speak("T3 failed. $why.") }
    private fun reason(e: Exception) = (e as? T3Error)?.message ?: "T3 not reachable"

    private companion object {
        const val POLL_MS = 3_000L
        const val MAX_RUN_MS = 10 * 60_000L
        /** The glasses show 4 lines; more only costs link time. */
        const val MAX_TEXT = 400
    }
}
