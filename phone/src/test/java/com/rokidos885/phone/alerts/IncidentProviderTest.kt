package com.rokidos885.phone.alerts

import com.rokidos885.protocol.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncidentProviderTest {
    @Test fun compositeKeepsCameraSnapshotWhenPoliceChanges() = runTest {
        val camera = Incident("camera", AlertKind.SPEED_CAMERA, LatLng(0.0, 0.01), "Speed camera", 0)
        val police = camera.copy(id = "police", kind = AlertKind.POLICE)
        val cameraFlow = MutableStateFlow(listOf(camera))
        val policeFlow = MutableStateFlow(listOf(police))
        fun provider(input: Flow<List<Incident>>) = object : IncidentProvider {
            override val name = "fake"
            override fun incidentsAlong(routePolyline: String) = input
        }
        var latest = emptyList<Incident>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            CompositeIncidentProvider(listOf(provider(cameraFlow), provider(policeFlow)))
                .incidentsAlong("").collect { latest = it }
        }
        runCurrent()
        assertEquals(setOf("camera", "police"), latest.map { it.id }.toSet())
        policeFlow.value = emptyList()
        runCurrent()
        assertEquals(listOf(camera), latest)
    }

    @Test fun policePollUsesLatestPositionAndClosesOnCancellation() = runTest {
        val centers = mutableListOf<LatLng>()
        var closed = false
        val client = object : SabreClient {
            override suspend fun fetch(center: LatLng): List<Incident> { centers += center; return emptyList() }
            override fun close() { closed = true }
        }
        val positions = MutableSharedFlow<PositionUpdate>(replay = 1)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            WzSabrePoliceProvider(client, positions).incidentsAlong("").collect()
        }
        positions.emit(PositionUpdate(1, 0.0, 0.0, 90f, 10f, timestampMs = 0))
        runCurrent()
        positions.emit(PositionUpdate(2, 0.0, 0.01, 90f, 10f, timestampMs = 1))
        positions.emit(PositionUpdate(3, 0.0, 0.02, 90f, 10f, timestampMs = 2))
        runCurrent()
        assertEquals(1, centers.size)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.02)), centers)
        job.cancel()
        runCurrent()
        assertTrue(closed)
    }

    @Test fun mockPoliceCountsDownAndClearsOnTheRealAlertPath() = runTest {
        val points = listOf(LatLng(0.0, 0.0), LatLng(0.0, 0.05))
        val incidents = MockIncidentProvider(true).incidentsAlong(PolylineCodec.encode(points)).first()
        val tracker = AlertTracker(points)
        var seq = 0L
        fun fix(lon: Double) = PositionUpdate(0, 0.0, lon, 90f, 10f, timestampMs = System.currentTimeMillis())
        val start = tracker.update(incidents, fix(0.0), System.currentTimeMillis()) { ++seq }.single() as Alert
        assertTrue(tracker.update(incidents, fix(0.005), System.currentTimeMillis()) { ++seq }.isEmpty())
        val closer = tracker.current.single()
        assertEquals(1000, start.distanceAheadMeters)
        assertTrue(closer.distanceAheadMeters < start.distanceAheadMeters)
        assertTrue(tracker.update(incidents, fix(0.02), System.currentTimeMillis()) { ++seq }.single() is AlertCleared)
        assertTrue(MockIncidentProvider(false).incidentsAlong("").toList().isEmpty())
    }
}
