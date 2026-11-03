package com.rokidos885.phone.map

import com.rokidos885.protocol.*
import org.junit.Assert.*
import org.junit.Test

class RoadContextProviderTest {
    private fun road(name:String,major:Boolean,vararg p:Pair<Double,Double>)=TileRoad(p.map {LatLng(it.first,it.second)},name,major)

    @Test fun keepsStreetNamesHierarchyAndOnlyValidGeometry() {
        val roads=RoadContextProvider.select(listOf(
            road("Main Street",true,0.0 to 0.0,0.0 to 0.002),
            road("Side Street",false,-0.001 to 0.001,0.001 to 0.001),
            road("Bad",true,91.0 to 0.0,92.0 to 0.0)),LatLng(0.0,0.0))
        assertEquals(listOf("Main Street","Side Street"),roads.map {it.name})
        assertTrue(roads.first().major)
    }
    @Test fun snapshotFitsTheBudgetAndEveryFrameFitsTheLink() {
        val roads=RoadContextProvider.select((1..2000).map {road("Street $it",false,0.0 to 0.0,0.001 to 0.001)},LatLng(0.0,0.0))
        assertTrue(roads.size in 2..400)
        val frames=RoadContextChunker.split("r".repeat(64),"c123",roads,{Long.MAX_VALUE})
        assertTrue(frames.sumOf {ProtocolCodec.encode(it).second.toByteArray().size}<=RoadContextProvider.SNAPSHOT_BUDGET_BYTES+frames.size*200)
        frames.forEach {assertTrue(ProtocolCodec.encode(it).second.toByteArray().size<=6000)}
    }
    @Test fun streetsAlongTheRouteAheadOutrankCloserStreetsBehind() {
        // Behind is 220 m from the driver; Ahead is 1.1 km away but crosses the route ahead.
        val roads=listOf(road("Behind",false,-0.002 to -0.002,-0.002 to 0.002),road("Ahead",false,0.01 to -0.002,0.01 to 0.002))
        val ahead=listOf(LatLng(0.0,0.0),LatLng(0.02,0.0))
        assertEquals(listOf("Ahead","Behind"),RoadContextProvider.select(roads,LatLng(0.0,0.0),ahead).map {it.name})
    }
    @Test fun simplifyDropsPointsWithinFiveMeters() {
        val line=(0..100).map {LatLng(0.0,it*1e-5)}   // straight, ~1.1 m spacing
        assertEquals(2,RoadContextProvider.simplify(line).size)
    }
}
