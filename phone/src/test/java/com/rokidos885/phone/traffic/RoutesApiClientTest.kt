package com.rokidos885.phone.traffic

import com.rokidos885.phone.nav.*
import com.rokidos885.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RoutesApiClientTest {
    @Test fun parsesFractionalDurationsAndOmittedStartIndex() {
        // Routes REST response shape; synthetic traffic/token extensions, not a recorded live route.
        val routes=RoutesApiClient.parse("""{"routes":[{"distanceMeters":772,"duration":"165.5s","staticDuration":"140s","polyline":{"encodedPolyline":"??"},"routeToken":"opaque","description":"via Main","travelAdvisory":{"speedReadingIntervals":[{"endPolylinePointIndex":2,"speed":"SLOW"}]}}]}""")
        val route=routes.single()
        assertEquals(165,route.durationSeconds);assertEquals(140,route.staticDurationSeconds)
        assertEquals(0,route.speedIntervals.single().startIndex);assertEquals("opaque",route.routeToken)
        assertTrue(RoutesApiClient.parse("""{}""").isEmpty())
    }
    @Test fun bodyUsesTrafficAwareRouteTokensPlaceDestinationAndHeading() {
        val body=Json.parseToJsonElement(RoutesApiClient.requestBody(LatLng(1.0,2.0),-90,LatLng(3.0,4.0),"place-id",true)).jsonObject
        assertEquals("TRAFFIC_AWARE_OPTIMAL",body["routingPreference"]!!.jsonPrimitive.content)
        assertEquals(270,body["origin"]!!.jsonObject["location"]!!.jsonObject["heading"]!!.jsonPrimitive.int)
        assertEquals("place-id",body["destination"]!!.jsonObject["placeId"]!!.jsonPrimitive.content)
    }
    @Test fun speedIntervalsProjectOntoCurrentRouteAndRejectNearbyParallelRoad() {
        val points=listOf(LatLng(0.0,0.0),LatLng(0.0,0.01),LatLng(0.0,0.02))
        val active=ActiveRoute("r",PolylineCodec.encode(points),2224)
        fun option(line:String)=RouteOption("t",line,2224,300,200,"A",listOf(SpeedInterval(0,2,"TRAFFIC_JAM")))
        val jam=TrafficMonitor.projectTraffic(active,option(active.encodedPolyline)).single()
        assertEquals(TrafficLevel.JAM,jam.level);assertEquals(0,jam.startMeters);assertEquals(2223,jam.endMeters)
        val unrelated=PolylineCodec.encode(points.map {it.copy(lat=0.01)})
        assertTrue(TrafficMonitor.projectTraffic(active,option(unrelated)).isEmpty())
    }
}
