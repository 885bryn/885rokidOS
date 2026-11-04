package com.rokidos885.phone.alerts

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.rokidos885.protocol.LatLng
import com.rokidos885.protocol.PositionUpdate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

interface SabreClient {
    suspend fun fetch(center: LatLng): List<Incident>
    fun close()
}

/** One random response action per exchange; requests only target the user-selected WzSabre app. */
class AndroidSabreClient(context: Context) : SabreClient {
    private val context = context.applicationContext
    private var handshake: SabreProtocol.Handshake? = null

    override suspend fun fetch(center: LatLng): List<Incident> {
        val info = handshake ?: exchange("${SabreProtocol.PACKAGE}.HANDSHAKE", HANDSHAKE_TIMEOUT_MS,
            { SabreProtocol.handshakeRequest(it) }) { messages ->
            SabreProtocol.handshakeResponse(messages.receive())
        }.also { handshake = it }
        val requestId = UUID.randomUUID().toString()
        return exchange(info.requestAction, FETCH_TIMEOUT_MS,
            { SabreProtocol.fetchRequest(requestId, center, it) }) { messages ->
            val batches = SabreBatches(requestId)
            var result: List<Incident>?
            while (true) {
                result = batches.accept(SabreProtocol.batch(messages.receive()))
                if (result != null) break
            }
            result!!
        }
    }

    private suspend fun <T> exchange(
        action: String,
        timeoutMs: Long,
        payload: (String) -> String,
        receive: suspend (Channel<String>) -> T,
    ): T = withTimeout(timeoutMs) {
        val responseAction = "${context.packageName}.SABRE_${UUID.randomUUID()}"
        val messages = Channel<String>(64)
        var sentAt = 0L
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != responseAction) return
                val data = intent.getStringExtra("data") ?: return
                if (com.rokidos885.phone.BuildConfig.DEBUG) android.util.Log.d("885rokidOSSabre", "reply on $action after ${System.currentTimeMillis() - sentAt} ms")
                if (data.length <= 512_000) messages.trySend(data)
            }
        }
        // Exported because replies originate in the separate WzSabre application.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(responseAction), ContextCompat.RECEIVER_EXPORTED)
        try {
            if (com.rokidos885.phone.BuildConfig.DEBUG) android.util.Log.d("885rokidOSSabre", "send $action")
            sentAt = System.currentTimeMillis()
            context.sendBroadcast(Intent(action).apply {
                component = ComponentName(SabreProtocol.PACKAGE, "${SabreProtocol.PACKAGE}.MainBroadcastReceiver")
                putExtra("data", payload(responseAction))
            })
            receive(messages)
        } finally {
            context.unregisterReceiver(receiver)
            messages.close()
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        /** WzSabre answers only after it has queried Waze (live map, through a WebView). */
        const val FETCH_TIMEOUT_MS = 75_000L
    }

    override fun close() {
        val action = handshake?.shutdownAction
        handshake = null
        if (action != null) context.sendBroadcast(Intent(action).apply {
            component = ComponentName(SabreProtocol.PACKAGE, "${SabreProtocol.PACKAGE}.MainBroadcastReceiver")
        })
    }
}

/** Poll only while the coordinator collects an active trip, centred on the latest road-snapped fix. */
class WzSabrePoliceProvider(
    private val client: SabreClient,
    private val positions: Flow<PositionUpdate>,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : IncidentProvider {
    private val mutableStatus=kotlinx.coroutines.flow.MutableStateFlow("Waiting for navigation")
    val status:kotlinx.coroutines.flow.StateFlow<String> = mutableStatus
    override val name = "WzSabre reports"
    override fun incidentsAlong(routePolyline: String): Flow<List<Incident>> = flow {
        var previous = emptyList<Incident>()
        try {
            positions.conflate().collect { position ->
                try {
                    previous = client.fetch(LatLng(position.lat, position.lng))
                    mutableStatus.value="WzSabre connected · ${previous.size} nearby reports"
                } catch (cancelled: CancellationException) {
                    // Timeouts are handled below; a trip cancellation must release the receiver immediately.
                    if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
                    mutableStatus.value="WzSabre is not answering. Keep it installed and exempt it from battery optimization."
                } catch (_: Exception) {
                    // A plugin error must not stop navigation. Retain only unexpired reports.
                    mutableStatus.value="WzSabre reports unavailable. Retrying."
                }
                val now = nowMs()
                previous = previous.filter { it.expiresAtMs?.let { expiry -> expiry > now } != false }
                emit(previous)
                // WzSabre itself takes ~30 s per answer (measured 2026-10-19), so a short pause suffices.
                delay(10_000)
            }
        } finally {
            client.close()
            mutableStatus.value="Waiting for navigation"
        }
    }
}
