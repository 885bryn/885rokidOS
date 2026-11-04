package com.rokidos885.phone.alerts

import com.rokidos885.protocol.AlertKind
import com.rokidos885.protocol.LatLng
import com.rokidos885.protocol.Geo
import com.rokidos885.protocol.PolylineCodec
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/**
 * Source of road alerts (police, cameras, crashes, closures...). Google's public APIs do NOT expose
 * police reports. WzSabre is the user-selected police/camera source (see docs/DATA_SOURCES.md).
 * Each provider returns incidents
 * near the route; the coordinator turns them into protocol [com.rokidos885.protocol.Alert]s with
 * distance-ahead computed against the active route.
 */
interface IncidentProvider {
    val name: String

    /** Called when the route changes; providers may poll a corridor around it. */
    fun incidentsAlong(routePolyline: String): Flow<List<Incident>>
}

data class Incident(
    val id: String,
    val kind: AlertKind,
    val location: LatLng,
    val description: String,
    val reportedAtMs: Long,
    val source: String = "",
    val headings: List<Double> = emptyList(),
    val expiresAtMs: Long? = null,
)

class CompositeIncidentProvider(private val providers: List<IncidentProvider>) : IncidentProvider {
    override val name = "composite"
    override fun incidentsAlong(routePolyline: String): Flow<List<Incident>> =
        if (providers.isEmpty()) flowOf(emptyList()) else combine(
            providers.map { provider -> provider.incidentsAlong(routePolyline).onStart { emit(emptyList()) } },
        ) { snapshots -> snapshots.flatMap { it }.distinctBy { it.id } }
}

/** Debug-only: drops a fake police alert ~1 km ahead so the glasses UI can be exercised. */
class MockIncidentProvider(private val enabled: Boolean) : IncidentProvider {
    override val name = "mock"
    override fun incidentsAlong(routePolyline: String): Flow<List<Incident>> {
        if (!enabled) return emptyFlow()
        return flow {
            val points = PolylineCodec.decode(routePolyline)
            val lengths = points.zipWithNext { a, b -> Geo.distanceMeters(a, b) }
            var remaining = minOf(1000.0, lengths.sum() * 0.75)
            val index = lengths.indexOfFirst { length ->
                if (remaining <= length) true else { remaining -= length; false }
            }
            if (index < 0 || lengths[index] == 0.0) { emit(emptyList()); return@flow }
            val a = points[index]
            val b = points[index + 1]
            val fraction = remaining / lengths[index]
            val deltaLng = (b.lng - a.lng + 540) % 360 - 180
            emit(listOf(Incident("mock:police", AlertKind.POLICE,
                LatLng(a.lat + (b.lat - a.lat) * fraction, (a.lng + deltaLng * fraction + 540) % 360 - 180),
                "Police reported", System.currentTimeMillis(), "Demo")))
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfiguredIncidentProvider(private val provider:IncidentProvider,
    private val settings:Flow<com.rokidos885.phone.settings.UserSettings>,
    private val enabled:(com.rokidos885.phone.settings.UserSettings)->Boolean):IncidentProvider {
    override val name=provider.name
    override fun incidentsAlong(routePolyline:String)=settings.map(enabled).distinctUntilChanged().flatMapLatest {
        if(it)provider.incidentsAlong(routePolyline) else flowOf(emptyList())
    }
}
