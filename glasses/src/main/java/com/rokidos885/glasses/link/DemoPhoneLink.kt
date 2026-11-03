package com.rokidos885.glasses.link

import com.rokidos885.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Starts after subscribers attach; loops the recording with fresh timestamps. */
class DemoPhoneLink(private val scope: CoroutineScope, private val load: () -> List<DemoRecording.Frame>) : PhoneLink {
    override val connected: StateFlow<Boolean> = MutableStateFlow(true)
    private val messages = MutableSharedFlow<NavMessage>(extraBufferCapacity = 128)
    override val incoming = messages.asSharedFlow()
    private var job: Job? = null
    private val seq = SeqCounter()
    private var offerId: String? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            messages.subscriptionCount.first { it > 0 }
            val frames = withContext(Dispatchers.IO) { load() }
            while (isActive) {
                val start = System.currentTimeMillis()
                for (frame in frames) {
                    delay(frame.delayMs)
                    val message = DemoRecording.rebase(frame.message, seq.next(), start)
                    if (message is DetourOffer) offerId = message.offerId
                    messages.emit(message)
                }
                offerId = null
                delay(2000)
            }
        }
    }

    override fun send(message: NavMessage) {
        if (message is GlassesAction && message.targetId == offerId &&
            (message.action == ActionType.ACCEPT_DETOUR || message.action == ActionType.DISMISS_DETOUR)) {
            messages.tryEmit(DetourResolved(seq.next(), message.targetId, message.action == ActionType.ACCEPT_DETOUR))
            offerId = null
        }
    }
    override fun close() { job?.cancel(); job = null }
}
